package com.filmcam.app

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.spektralab.engine.FilmStockMapper
import com.filmcam.app.camera.Camera2Controller
import com.filmcam.app.databinding.ActivityMainBinding
import com.filmcam.app.develop.DevelopController
import com.filmcam.app.film.UndevelopedFilmStore
import com.filmcam.app.filter.FilmRoll
import com.filmcam.app.filter.FilmRollAdapter
import com.filmcam.app.filter.FilmRollRepository
import com.filmcam.app.flash.FlashController
import com.filmcam.app.flash.FlashMode
import com.filmcam.app.ui.T3LcdView
import com.filmcam.app.ui.ViewfinderFrameView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.roundToInt

/**
 * FilmCam 主 Activity
 *
 * MVP 核心链路：
 *   LutGLSurfaceView 创建 SurfaceTexture → CameraX 取景
 *   CameraX 帧 → OES 纹理 → GL 片元着色器（3D LUT 查表）→ 屏幕
 *
 * 闪光灯三种模式：
 *   - OFF：不闪
 *   - BUILT_IN：手机自带 LED（默认，开箱即用）
 *   - EXTERNAL：FotorGear BLE 外接灯（可选配件）
 *
 * 遥控拍摄：按灯上物理键 → HID 模拟 Volume Down → onKeyDown → 快门
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var cameraController: Camera2Controller? = null
    private lateinit var flash: FlashController
    private var shutterSoundPool: android.media.SoundPool? = null
    private var shutterSoundId = 0
    private var advanceSoundPool: android.media.SoundPool? = null
    private var advanceSoundId = 0
    private var developingAnimator: android.animation.ObjectAnimator? = null
    private var currentRoll: FilmRoll = FilmRollRepository.rolls[0]
    /** 拍照保存单线程池：保证帧写入顺序，避免连拍时多个裸线程并发写 */
    private val saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var dateStampEnabled = true
    private var exposureComp = 0  // 曝光补偿 -3 ~ +3
    private var softFocusEnabled = false  // 模拟失焦：拍摄时对焦故意不准
    private var softFocusProb = 0         // 模拟失焦概率 0-100（工程师模式可调；0=关）
    private var filmRollMode = false  // 胶卷模式开关（旧设置保留兼容，计数改由 UndevelopedFilmStore 权威）
    private var debugInstantDevelop = false  // 调试：拍完立即后台冲洗出片
    private val FILM_MAX = 36  // 一卷36张

    // T3 机身 UI 状态
    private var currentMode = "P"  // P / A / SELF
    private var isoValue = 0  // isoValues 索引
    private val isoValues = listOf<Int?>(null, 100, 200, 400, 800)  // null = 自动
    private var gridEnabled = true  // 取景网格

    /** 当前活动卷 id（无活动卷返回 null） */
    private fun activeRollId(): String? = UndevelopedFilmStore.currentRoll()?.rollId

    /** 当前卷已拍张数（权威来源：UndevelopedFilmStore，不再自存 filmCount） */
    private fun currentShotCount(): Int {
        val id = activeRollId() ?: return 0
        return UndevelopedFilmStore.shotCount(id)
    }

    private val permissions = mutableListOf(Manifest.permission.CAMERA).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // API 26..28：未冲洗帧/成片走公共 DCIM 文件系统，需要运行时存储权限
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UndevelopedFilmStore.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 胶卷图标（预览不做 LUT 调色：光学取景器看不到成片效果，是产品设计）
        binding.tvFilmName.setImageResource(currentRoll.iconResId)

        // 等待 GL 线程就绪，拿到 SurfaceTexture 后启动 Camera2
        binding.glSurfaceView.onSurfaceTextureReady = { surfaceTexture ->
            runOnUiThread { startCamera(surfaceTexture) }
        }

        // 屏幕快门按钮
        binding.btnShutter.setOnClickListener { triggerShutter() }

        // 点击胶卷名 → 打开胶卷选择器
        binding.tvFilmName.setOnClickListener { showFilmRollPicker() }

        // 闪光灯控制器
        flash = FlashController(this)

        // 加载 T3 快门声（真实 Contax T 机械快门音，替换系统快门音）
        if (Build.VERSION.SDK_INT >= 21) {
            shutterSoundPool = android.media.SoundPool.Builder().setMaxStreams(4).build()
            shutterSoundId = shutterSoundPool?.load(this, R.raw.t3_shutter, 1) ?: 0
        }
        // 加载 T3 自动过卷声（预留：过卷声暂不播放，找到真实素材后再启用）
        if (Build.VERSION.SDK_INT >= 21) {
            advanceSoundPool = android.media.SoundPool.Builder().setMaxStreams(4).build()
            advanceSoundId = advanceSoundPool?.load(this, R.raw.t3_advance, 1) ?: 0
        }
        flash.onStatusChange = { status ->
            runOnUiThread {
                // 只在外置闪光模式时显示 BLE 状态
                if (flash.mode == FlashMode.EXTERNAL) {
                    binding.tvFlashStatus.text = "外置闪光: $status"
                }
            }
        }

        // 先恢复胶卷排序再读选中的胶卷索引：index 是位置语义，
        // 顺序不一样时同一个 index 指向的是另一支胶卷。
        FilmRollRepository.restore(this)
        // 恢复上次退出时保存的设置
        restoreSettings()

        // 首次进入引导（只弹一次）
        binding.glSurfaceView.postDelayed({ maybeShowFirstRunGuide() }, 500)

        // 闪光灯模式切换
        binding.tvFlashStatus.setOnClickListener { cycleFlashMode() }

        // 相册回看
        binding.btnGallery.setOnClickListener {
            startActivity(android.content.Intent(this, GalleryActivity::class.java))
        }

        // 设置按钮
        binding.settingsKey.setOnClickListener { showSettings() }

        // 顶部功能键：闪光灯 / 曝光补偿
        binding.flashKey.setOnClickListener { cycleFlashMode() }
        binding.evKey.setOnClickListener { cycleEvValue() }
        binding.filmInfoText.setOnClickListener { showFilmRollPicker() }
        updateLcd()
        updateFilmInfo()

        // 点屏对焦 + 上下滑调曝光（GCam 风格）
        // 滑动过程只在 EV 整数档位真正变化时才下发相机参数（最多 6 次），
        // 落盘统一放到抬手时做一次，避免每移动一像素就重建请求 + 写 SharedPreferences。
        // 位移小于阈值视为点按 → 触发点屏对焦（对焦框动画 + 成功震动）。
        var touchStartX = 0f
        var touchStartY = 0f
        var touchStartEv = 0
        var touchMoved = false
        var wbDragging = false          // 本次手势是否已进入横滑调白平衡
        var touchStartWbIndex = 0
        binding.glSurfaceView.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    touchStartEv = exposureComp
                    touchMoved = false
                    wbDragging = false
                    touchStartWbIndex = wbIndex
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    binding.viewfinderFrame.notifyActivity()
                    if (Math.abs(event.rawX - touchStartX) > 24f ||
                        Math.abs(event.rawY - touchStartY) > 24f
                    ) touchMoved = true

                    // 闪光开启后的横滑手势：调白平衡（上下滑仍是 EV，竖直方向优先）
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!wbDragging && flash.mode != FlashMode.OFF &&
                        Math.abs(dx) > 32f && Math.abs(dx) > Math.abs(dy) * 1.4f
                    ) {
                        wbDragging = true
                        showWbStrip()
                    }
                    if (wbDragging) {
                        val idx = (touchStartWbIndex + (dx / 90f).toInt())
                            .coerceIn(0, wbPresets.size - 1)
                        if (idx != wbIndex) {
                            wbIndex = idx
                            applyWbIndex()
                            vibrate(12)
                        }
                        return@setOnTouchListener true
                    }

                    val delta = (touchStartY - event.rawY) / 100f
                    val newEv = (touchStartEv + delta.toInt()).coerceIn(-3, 3)
                    if (newEv != exposureComp) {
                        // 统一入口：原先这里只调 setExposureComp + notifyActivity，
                        // 漏了 applyExposure（外置闪光开关会失效）与 updateLcd（LCD 读数不更新）
                        setEv(newEv, persist = false)   // 抬手时统一 saveSettings
                    }
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (wbDragging) {
                        if (wbIndex != touchStartWbIndex) saveSettings()
                        wbDragging = false
                        hideWbStripDelayed()
                        return@setOnTouchListener true
                    }
                    if (exposureComp != touchStartEv) saveSettings()
                    if (event.action == android.view.MotionEvent.ACTION_UP &&
                        !touchMoved && exposureComp == touchStartEv
                    ) {
                        // 点按：点屏对焦（event.x/y 已是相对取景器的坐标，
                        // 比	rawX 减 getLocationOnScreen 更可靠——小窗/窗口偏移时后者会错位）
                        val fx = event.x
                        val fy = event.y
                        binding.viewfinderFrame.notifyActivity()
                        // 只有落在预览内容矩形内才对焦：letterbox 黑边、预览窗之外的
                        // 点按一律忽略（真机 T3 的可视面积就是这块，点外面不该有反应）
                        if (!binding.viewfinderFrame.contentRect().contains(fx, fy)) {
                            return@setOnTouchListener true
                        }
                        // 点屏对焦：不做任何视觉标记，合焦后仅给一次短震动
                        cameraController?.focusAt(
                            fx, fy,
                            binding.glSurfaceView.width, binding.glSurfaceView.height
                        ) { vibrate(15) }
                    }
                }
            }
            true
        }

        // 曝光补偿按钮
        binding.btnEvPlus.setOnClickListener {
            setEv(if (exposureComp >= 3) -3 else exposureComp + 1)
        }
        binding.btnEvMinus.setOnClickListener {
            setEv(if (exposureComp <= -3) 3 else exposureComp - 1)
        }

        // 专业模式
        binding.tvProToggle.setOnClickListener { toggleProMode() }
        binding.tvIso.setOnClickListener { cycleIso() }
        binding.tvShutter.setOnClickListener { cycleShutter() }
        binding.tvExposure.setOnClickListener { cycleEv() }
    }

    /** 恢复上次退出的设置 */
    private fun restoreSettings() {
        val prefs = getSharedPreferences("filmcam", MODE_PRIVATE)
        dateStampEnabled = prefs.getBoolean("dateStamp", true)
        exposureComp = prefs.getInt("ev", 0)
        softFocusEnabled = prefs.getBoolean("softFocus", false)
        // 失焦概率缺省：开过模拟失焦的老用户 → 40%（旧版硬编码值）；否则 0
        softFocusProb = prefs.getInt("softFocus_prob", if (softFocusEnabled) 40 else 0)
        filmRollMode = prefs.getBoolean("filmRollMode", false)
        // 旧 filmCount 键仅作迁移保留读取，不再作为权威（张数一律读 UndevelopedFilmStore.shotCount）
        @Suppress("UNUSED_VARIABLE") val legacyFilmCount = prefs.getInt("filmCount", 0)
        debugInstantDevelop = prefs.getBoolean("debug_instant_develop", false)
        flash.mode = try {
            FlashMode.valueOf(prefs.getString("flashMode", FlashMode.OFF.name) ?: FlashMode.OFF.name)
        } catch (_: Exception) { FlashMode.OFF }
        wbIndex = prefs.getInt("wbIndex", 0).coerceIn(0, wbPresets.size - 1)
        if (flash.mode != FlashMode.OFF) {
            // 白平衡预设只在闪光开着时有意义；恢复时同步下发，成片与预览一致
            cameraController?.setAwbMode(wbPresets[wbIndex].awbMode)
        }
        val rollIndex = prefs.getInt("rollIndex", 0).coerceIn(0, FilmRollRepository.rolls.size - 1)
        // 上次选中的若是反转片（本版不支持），回退到第一支可用胶卷，避免带着禁用卷启动
        val restored = FilmRollRepository.rolls[rollIndex].takeIf { it.available }
            ?: FilmRollRepository.rolls.first()
        selectFilmRoll(restored, fromUser = false)
        applyExposure()
        updateLcd()
        updateFilmInfo()
    }

    /** 保存当前设置到本地 */
    private fun saveSettings() {
        val prefs = getSharedPreferences("filmcam", MODE_PRIVATE).edit()
        prefs.putBoolean("dateStamp", dateStampEnabled)
        prefs.putInt("ev", exposureComp)
        prefs.putBoolean("softFocus", softFocusEnabled)
        prefs.putBoolean("filmRollMode", filmRollMode)
        // 旧 filmCount 键不再写入；权威张数来自 UndevelopedFilmStore
        prefs.putBoolean("debug_instant_develop", debugInstantDevelop)
        prefs.putString("flashMode", flash.mode.name)
        prefs.putInt("wbIndex", wbIndex)
        prefs.putInt("rollIndex", FilmRollRepository.rolls.indexOf(currentRoll))
        prefs.apply()
    }

    /** 设置弹窗：只留普通用户项；调试与模拟失焦收进「关于页 → 版本号连点 10 次」的工程师模式 */
    private fun showSettings() {
        val items = arrayOf(
            "日期水印: ${if (dateStampEnabled) "开" else "关"}",
            "冲洗参数: 颗粒/辉光/眩光",
            "关于 FILMCAM"
        )
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { dateStampEnabled = !dateStampEnabled; saveSettings() }
                    1 -> showDevParamsDialog()
                    2 -> startActivity(android.content.Intent(this, AboutActivity::class.java))
                }
                if (which == 0) showSettings()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 胶卷拍满：引导去暗房冲洗 */
    private fun promptDevelopFullRoll() {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("胶卷已拍完")
            .setMessage("这一卷 36 张已拍完，去暗房冲洗出片")
            .setPositiveButton("去冲洗") { _, _ ->
                activeRollId()?.let { DevelopController.startDevelopment(this, it) }
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    /**
     * 回卷动画：胶卷拍满后模拟手动回卷——胶卷图标逆时针快速旋转
     * + 过卷声，营造"这卷拍完了，卷回去送冲"的仪式感。结束后回调。
     */
    private fun showRewindAnimation(onEnd: () -> Unit) {
        if (isCapturing) return
        isCapturing = true
        binding.developingOverlay.visibility = android.view.View.VISIBLE
        binding.ivDeveloping.setImageResource(currentRoll.iconResId)
        binding.ivDeveloping.visibility = android.view.View.VISIBLE
        binding.tvDevelopingText.text = "REWIND FILM..."
        binding.tvDevelopingText.visibility = android.view.View.VISIBLE

        playAdvanceSound()
        vibrate(40, 30)

        android.animation.ObjectAnimator.ofFloat(binding.ivDeveloping, "rotation", 0f, -720f).apply {
            duration = 1600
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    binding.developingOverlay.visibility = android.view.View.GONE
                    binding.ivDeveloping.rotation = 0f
                    isCapturing = false
                    onEnd()
                }
                override fun onAnimationStart(a: android.animation.Animator) {}
                override fun onAnimationCancel(a: android.animation.Animator) {}
                override fun onAnimationRepeat(a: android.animation.Animator) {}
            })
            start()
        }
    }

    /** 首次进入引导：一句话讲清胶片机的核心玩法 */
    private fun maybeShowFirstRunGuide() {
        val prefs = getSharedPreferences("filmcam", MODE_PRIVATE)
        if (prefs.getBoolean("guide_shown", false)) return
        prefs.edit().putBoolean("guide_shown", true).apply()
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("FILMCAM")
            .setMessage(
                "像使用真实胶卷一样拍照：\n\n" +
                    "· 点按屏幕对焦，上下滑动调整曝光\n" +
                    "· 一卷 36 张，拍完去暗房冲洗\n" +
                    "· 冲洗前看不到成片——胶片的惊喜就在这里"
            )
            .setPositiveButton("开拍", null)
            .show()
    }

    /** 冲洗参数弹窗：颗粒/辉光/眩光强度（键量纲与 FilmStockMapper 冻结契约一致） */
    private fun showDevParamsDialog() {
        val prefs = getSharedPreferences("filmcam", MODE_PRIVATE)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_dev_params, null)

        val sbGrain = view.findViewById<android.widget.SeekBar>(R.id.sbDevGrain)
        val sbHalation = view.findViewById<android.widget.SeekBar>(R.id.sbDevHalation)
        val sbGlare = view.findViewById<android.widget.SeekBar>(R.id.sbDevGlare)
        val tvGrain = view.findViewById<android.widget.TextView>(R.id.tvDevGrainValue)
        val tvHalation = view.findViewById<android.widget.TextView>(R.id.tvDevHalationValue)
        val tvGlare = view.findViewById<android.widget.TextView>(R.id.tvDevGlareValue)
        val sbExposure = view.findViewById<android.widget.SeekBar>(R.id.sbDevExposure)
        val tvExposure = view.findViewById<android.widget.TextView>(R.id.tvDevExposureValue)
        val rowPaper = view.findViewById<android.view.View>(R.id.rowDevPaper)
        val tvPaper = view.findViewById<android.widget.TextView>(R.id.tvDevPaperValue)
        val rowRoll = view.findViewById<android.view.View>(R.id.rowDevRoll)
        val tvRoll = view.findViewById<android.widget.TextView>(R.id.tvDevRollValue)
        val btnReset = view.findViewById<android.widget.Button>(R.id.btnDevReset)
        btnReset.text = "恢复该款默认"

        // 相纸选项：第一项自动（""），其后 9 款 catalog prints
        val paperOptions = try {
            FilmStockMapper.printStockOptions(this)
        } catch (_: Throwable) {
            emptyList()
        }
        fun paperDisplayName(id: String): String =
            if (id.isEmpty()) "自动（按胶卷默认）"
            else paperOptions.firstOrNull { it.id == id }?.name ?: id

        // 当前选中的胶卷 stock id（默认 = 当前胶卷；不在 9 款映射时回退第一款）
        fun stockIdOf(roll: FilmRoll): String? =
            try { FilmStockMapper.filmStockIdFor(roll.name) } catch (_: Throwable) { null }
        var selRollId: String = stockIdOf(currentRoll)
            ?: stockIdOf(FilmRollRepository.rolls.firstOrNull() ?: currentRoll)
            ?: ""
        // 后缀键：filmcam_dev_<param>_<stockId>
        fun devKey(param: String) = "filmcam_dev_${param}_$selRollId"

        // SeekBar progress = 值*100；grain/halation 0..2.00，glare 0..6.00；exposure 0.50..2.00（progress=(v-0.5)*100）
        fun syncUi() {
            tvRoll.text = try { FilmStockMapper.stockDisplayName(selRollId) } catch (_: Throwable) { selRollId }
            sbGrain.progress = (prefs.getFloat(devKey("grain"), 1.0f) * 100).toInt()
            sbHalation.progress = (prefs.getFloat(devKey("halation"), 1.0f) * 100).toInt()
            sbGlare.progress = (prefs.getFloat(devKey("glare"), 3.0f) * 100).toInt()
            tvGrain.text = String.format("%.2f", sbGrain.progress / 100f)
            tvHalation.text = String.format("%.2f", sbHalation.progress / 100f)
            tvGlare.text = String.format("%.2f", sbGlare.progress / 100f)
            val exp = prefs.getFloat(devKey("print_exposure"), 1.0f)
            sbExposure.progress = ((exp - 0.5f) * 100f).roundToInt()
            tvExposure.text = String.format("%.2f", exp)
            tvPaper.text = paperDisplayName(prefs.getString(devKey("paper"), "") ?: "")
            // 反转片没有印相纸环节（引擎 scan_film 直扫正片）：相纸行置灰
            val positiveStock = FilmStockMapper.isPositive(selRollId)
            rowPaper.isEnabled = !positiveStock
            rowPaper.alpha = if (positiveStock) 0.45f else 1f
            if (positiveStock) tvPaper.text = "—（反转片直接显影，无相纸）"
        }
        syncUi()

        // 切胶卷：单选 9 款 FilmRoll
        rowRoll.setOnClickListener {
            val rolls = FilmRollRepository.rolls
            val items = arrayOfNulls<String>(rolls.size)
            val ids = arrayOfNulls<String>(rolls.size)
            rolls.forEachIndexed { i, r ->
                val id = stockIdOf(r) ?: ""
                ids[i] = id
                val disp = try { FilmStockMapper.stockDisplayName(id) } catch (_: Throwable) { id }
                items[i] = "$disp ($id)"
            }
            val checked = ids.indexOf(selRollId).coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("选择胶卷")
                .setSingleChoiceItems(items, checked) { d, which ->
                    selRollId = ids[which] ?: ""
                    d.dismiss()
                    syncUi()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        sbGrain.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                prefs.edit().putFloat(devKey("grain"), v).apply()
                tvGrain.text = String.format("%.2f", v)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        sbHalation.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                prefs.edit().putFloat(devKey("halation"), v).apply()
                tvHalation.text = String.format("%.2f", v)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        sbGlare.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress / 100f
                prefs.edit().putFloat(devKey("glare"), v).apply()
                tvGlare.text = String.format("%.2f", v)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        sbExposure.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val v = 0.5f + progress / 100f
                prefs.edit().putFloat(devKey("print_exposure"), v).apply()
                tvExposure.text = String.format("%.2f", v)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        // 相纸选择：单选弹窗（自动 + 9 款）
        rowPaper.setOnClickListener {
            val current = prefs.getString(devKey("paper"), "") ?: ""
            val items = arrayOfNulls<String>(paperOptions.size + 1)
            items[0] = "自动（按胶卷默认）"
            paperOptions.forEachIndexed { i, opt -> items[i + 1] = opt.name }
            val checked = if (current.isEmpty()) 0 else (paperOptions.indexOfFirst { it.id == current } + 1).coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("相纸选择")
                .setSingleChoiceItems(items, checked) { d, which ->
                    val id = if (which == 0) "" else paperOptions[which - 1].id
                    prefs.edit().putString(devKey("paper"), id).apply()
                    tvPaper.text = paperDisplayName(id)
                    d.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 恢复该款默认：移除当前 stockId 对应的 5 个后缀键（=回落冻结默认）
        btnReset.setOnClickListener {
            prefs.edit()
                .remove(devKey("grain"))
                .remove(devKey("halation"))
                .remove(devKey("glare"))
                .remove(devKey("print_exposure"))
                .remove(devKey("paper"))
                .apply()
            syncUi()
        }

        androidx.appcompat.app.AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("冲洗参数")
            .setView(view)
            .setPositiveButton("确定") { d, _ ->
                d.dismiss()
                showSettings()
            }
            .show()
    }

    /** 打开胶卷选择器（底部弹层） */
    private fun showFilmRollPicker() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_film_roll, null)
        dialog.setContentView(view)

        val rv = view.findViewById<RecyclerView>(R.id.rvFilmRolls)
        // RecyclerView 是 wrap_content，25 支胶卷的内容高度远超一屏时弹层会被
        // 容器裁掉底部——最后一两项永远被导航区/屏幕底边盖住。这里给列表
        // 设定明确高度上限（屏高 72%），让它在弹层内部滚动，底部 padding 生效。
        rv.layoutParams?.height = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        dialog.behavior.skipCollapsed = true   // 直接全展开，避免半开状态看不到底部
        rv.layoutManager = LinearLayoutManager(this)
        val adapter = FilmRollAdapter(FilmRollRepository.rolls) { roll ->
            selectFilmRoll(roll)
            dialog.dismiss()
        }
        rv.adapter = adapter

        // 长按拖拽排序：把常用的胶卷拖到列表顶部，方便下次切换。
        // 只开上下方向（拖拽标记 dragFlags=UP|DOWN，swipeFlags=0 即不支持滑动删除），
        // 松手时统一落盘一次，避免拖动过程中反复写 SharedPreferences。
        val touchHelper = androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or
                    androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    adapter.moveItem(
                        viewHolder.bindingAdapterPosition,
                        target.bindingAdapterPosition
                    )
                    return true
                }

                override fun onSwiped(   viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    // 不支持滑动
                }

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ) {
                    super.clearView(recyclerView, viewHolder)
                    FilmRollRepository.persist(this@MainActivity)
                }
            }
        )
        touchHelper.attachToRecyclerView(rv)

        dialog.show()
    }

    // ===== 机身提示铭牌 =====

    private val noticeHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var noticeHideRunnable: Runnable? = null

    /**
     * 机身提示铭牌：替代原生 Toast。
     *
     * 系统 Toast 浮在屏幕最底部，样式是一坨圆角灰底——既不处视线自然停留处，也完全脱离
     * 机身质感的语境。这里换成机顶下方的一块蚀刻铭牌：深玻璃底 + 琥珀发丝边框 +
     * 左侧琥珀竖条，标题用等宽大写（与 FILM ADVANCING... 等机身状态文案同一套语汇），
     * 正文用中文补一句为什么。下滑浮现、到时自动收起。
     */
    private fun showBodyNotice(title: String, body: String, durationMs: Long = 2800L) {
        val plate = binding.bodyNotice
        binding.tvNoticeTitle.text = title
        binding.tvNoticeBody.text = body

        // 连续触发时重置计时，避免上一条的收起动画把新一条抹掉
        noticeHideRunnable?.let { noticeHandler.removeCallbacks(it) }

        plate.animate().cancel()
        plate.visibility = android.view.View.VISIBLE
        plate.translationY = -14f
        plate.alpha = 0f
        plate.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        noticeHideRunnable = Runnable { hideBodyNotice() }
        noticeHandler.postDelayed(noticeHideRunnable!!, durationMs)
    }

    private fun hideBodyNotice() {
        val plate = binding.bodyNotice
        plate.animate().cancel()
        plate.animate()
            .translationY(-14f)
            .alpha(0f)
            .setDuration(180)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { plate.visibility = android.view.View.GONE }
            .start()
    }

    // ===== 白平衡（闪光开启后，预览上左右拖动浮现选择条） =====

    private data class WbPreset(val label: String, val awbMode: Int)

    /** 白平衡预设：语义对应胶片机的校色滤镜片，顺序即横滑从左到右的档位 */
    private val wbPresets = listOf(
        WbPreset("AUTO", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO),
        WbPreset("白炽灯", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
        WbPreset("荧光灯", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
        WbPreset("日光", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
        WbPreset("阴天", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
        WbPreset("阴影", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_SHADE),
        WbPreset("暮光", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_TWILIGHT)
    )
    private var wbIndex = 0

    /** 选择条生成：琥珀等宽小字，选中档加白描边框——与 LCD/铭牌同一套暗房色板 */
    private fun setupWbStrip() {
        val strip = binding.wbStrip
        strip.removeAllViews()
        wbPresets.forEachIndexed { i, preset ->
            val tv = TextView(this).apply {
                text = preset.label
                setTextColor(0xFFE8E4DA.toInt())
                textSize = 11f
                letterSpacing = 0.1f
                setPadding(18, 7, 18, 7)
                if (i == wbIndex) {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0x33FFC100)
                        setStroke(1, 0xFFFFC100.toInt())
                        cornerRadius = 2f
                    }
                }
            }
            strip.addView(tv)
        }
    }

    private fun applyWbIndex() {
        cameraController?.setAwbMode(wbPresets[wbIndex].awbMode)
        setupWbStrip()   // 重画选中框
    }

    private fun showWbStrip() {
        setupWbStrip()
        val strip = binding.wbStrip
        strip.animate().cancel()
        strip.visibility = android.view.View.VISIBLE
        strip.alpha = 0f
        strip.animate().alpha(1f).setDuration(140).start()
    }

    private fun hideWbStripDelayed() {
        noticeHandler.postDelayed({
            binding.wbStrip.animate().cancel()
            binding.wbStrip.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction { binding.wbStrip.visibility = android.view.View.GONE }
                .start()
        }, 700)
    }

    /** 选中胶卷：切换 LUT + 更新图标。fromUser=false 用于启动时恢复设置（不做拦截）。 */
    private fun selectFilmRoll(roll: FilmRoll, fromUser: Boolean = true) {
        if (fromUser) {
            // 反转片拦截：与原项目一致，本版只支持负片印相纸
            if (!roll.available) {
                showBodyNotice(
                    "SLIDE FILM NOT SUPPORTED",
                    "反转片是供幻灯放映的，本版只能把负片印到相纸上"
                )
                return
            }
            // 换卷拦截：当前卷有未冲洗帧时禁止换胶卷（对应真机拍完必须回卷才能开盖）
            val active = UndevelopedFilmStore.currentRoll()
            val pending = active?.let { UndevelopedFilmStore.shotCount(it.rollId) } ?: 0
            if (active != null && pending > 0) {
                showBodyNotice(
                    "CHANGE FILM LOCKED",
                    "这卷已拍 $pending 张还没冲洗 —— 真机上不回卷也开不了后盖"
                )
                return
            }
        }
        currentRoll = roll
        binding.tvFilmName.setImageResource(roll.iconResId)
        updateFilmInfo()
        saveSettings()
    }

    // ===== T3 机身 UI 交互 =====

    /** 曝光补偿键：-3 → +3 循环 */
    private fun cycleEvValue() {
        // 统一入口：原先这里漏了 tvEvValue 的同步（按 EV 键按钮旁数字不变）
        setEv(if (exposureComp >= 3) -3 else exposureComp + 1)
    }

    // ===== 曝光补偿反馈 =====
    // 只用取景器内那颗白色 ± 指示（ViewfinderFrameView 的 ⑤），不再叠居中浮层：
    // 真机 T3 取景器里也没有浮层，多点一处反而与取景框线条打架。

    /**
     * 应用曝光设置。
     * 外置闪光灯（瞬时闪光、无常亮）：改用手动曝光 + 慢快门。
     * 好处：① 绕开 AE 自动测光的等待（AE 会等光线稳定，拖慢拍照，错过闪光）
     *       ② 慢快门窗口大（1/60≈16ms），提高覆盖到灯闪瞬间的概率。
     * 非外置闪光时，用相机 AE 曝光补偿。
     */
    private fun applyExposure() {
        try {
            if (flash.mode == FlashMode.EXTERNAL) {
                // 外置闪光：手动 ISO + 慢快门（EV0 = ISO200, 1/60）
                val (iso, ns) = when (exposureComp) {
                    3 -> 1600 to 16_666_667L   // ISO1600, 1/60
                    2 -> 800 to 16_666_667L    // ISO800, 1/60
                    1 -> 400 to 16_666_667L    // ISO400, 1/60
                    0 -> 200 to 16_666_667L    // ISO200, 1/60
                    -1 -> 200 to 8_000_000L    // ISO200, 1/125
                    -2 -> 100 to 8_000_000L    // ISO100, 1/125
                    else -> 100 to 4_000_000L  // -3: ISO100, 1/250
                }
                cameraController?.setManualIso(iso)
                cameraController?.setManualExposure(ns)
            } else {
                // 非外置：相机自动测光 + AE 曝光补偿
                cameraController?.setManualIso(null)
                cameraController?.setManualExposure(null)
                cameraController?.setExposureComp(exposureComp)
            }
        } catch (_: Exception) {}
    }

    /**
     * 曝光补偿的唯一入口。
     *
     * 历史 bug：原来有四条改 EV 的路径（上下滑 / EV+ 按钮 / EV- 按钮 / 顶部 EV 键），
     * 每条只更新一部分显示，且只有顶部 EV 键走 applyExposure()——而 applyExposure
     * 里包含「外置闪光灯改手动 ISO + 慢快门」的分支。结果是：开了外置闪光用滑动或
     * 按钮改 EV 时，LCD 数字变了、实际曝光参数却没变，表现为「EV 对不上」。
     * 现在四条路径统一走这里，保证：值 → 按钮数字 → LCD → 取景器 → 相机参数全同步。
     *
     * @param persist 滑动过程中传 false，避免每变一档就写 SharedPreferences。
     */
    private fun setEv(value: Int, persist: Boolean = true) {
        exposureComp = value.coerceIn(-3, 3)
        binding.tvEvValue.text = exposureComp.toString()
        applyExposure()                        // 内含外置闪光的 ISO/快门映射
        updateLcd()                            // 机顶 LCD 的 EV 读数
        binding.viewfinderFrame.notifyActivity()
        if (persist) saveSettings()
    }

    /** 回卷键：活动卷有未冲洗帧时拦截 */
    private fun rewindFilm() {
        val active = UndevelopedFilmStore.currentRoll()
        if (active != null && UndevelopedFilmStore.shotCount(active.rollId) > 0) {
            Toast.makeText(this, "请先冲洗当前胶卷", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "没有可回卷的胶卷", Toast.LENGTH_SHORT).show()
        updateLcd()
        updateFilmInfo()
    }

    /** 更新 LCD 显示屏（张数 / ISO / 曝光 / 闪光灯状态） */
    private fun updateLcd() {
        try {
            val iso = isoValues[isoValue]?.toString() ?: "A"
            val count = "${currentShotCount()}/$FILM_MAX"
            val ev = if (exposureComp > 0) "+$exposureComp" else "$exposureComp"
            val flashLabel = when (flash.mode) {
                FlashMode.OFF -> "OFF"
                FlashMode.BUILT_IN -> "INT"
                FlashMode.EXTERNAL -> "EXT"
            }
            binding.evKey.text = "EV$ev"
            binding.lcdDisplay.setState(
                T3LcdView.State(
                    count = currentShotCount(),
                    filmMax = FILM_MAX,
                    mode = currentMode,
                    isoLabel = iso,
                    evSteps = exposureComp.toFloat(),
                    flashLabel = flashLabel,
                    battery = batteryIconLevel()
                )
            )
        } catch (_: Exception) {}
        updateViewfinderIndicators(null, lastExposureSec)
    }

    /** 电池格数 0~3（满电 3 格） */
    private fun batteryIconLevel(): Int {
        return try {
            val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level < 0 || scale <= 0) 3 else {
                val pct = level * 100 / scale
                when { pct > 66 -> 3; pct > 33 -> 2; pct > 10 -> 1; else -> 0 }
            }
        } catch (_: Exception) { 3 }
    }

    /**
     * 把相机实时元数据翻译成 T3 取景器内的指示灯状态（T3 手册 P.4）：
     * ⑥ 合焦绿灯：常亮=合焦 / 快闪 4Hz=失焦 / 慢闪 1Hz=焦点锁定（AFL）
     * ⑧ 快门档位：500 / 125 / 30 / LT
     */
    private var lastIndicatorKey = ""
    private var lastExposureSec = 0f

    private fun updateViewfinderIndicators(afState: Int?, exposureSec: Float) {
        if (exposureSec > 0f) lastExposureSec = exposureSec
        val focus = when (afState) {
            2, 5 -> if (cameraController?.isAfLocked() == true)
                ViewfinderFrameView.LedMode.BLINK_SLOW else ViewfinderFrameView.LedMode.ON
            3, 6 -> ViewfinderFrameView.LedMode.BLINK_FAST
            else -> ViewfinderFrameView.LedMode.OFF
        }
        val lit = shutterTagsFor(lastExposureSec)
        val blink = lastExposureSec > 0f && lastExposureSec <= 1f / 1200f
        val key = "${focus.ordinal}|$exposureComp|${lit.joinToString(",")}|$blink|${flash.mode}"
        if (key == lastIndicatorKey) return
        lastIndicatorKey = key
        binding.viewfinderFrame.setIndicators(
            ViewfinderFrameView.Indicators(
                focus = focus,
                evCompOn = exposureComp != 0,
                evSteps = exposureComp,
                flash = if (flash.mode != FlashMode.OFF) ViewfinderFrameView.LedMode.ON
                        else ViewfinderFrameView.LedMode.OFF,
                shutterLit = lit,
                shutterBlink = blink
            )
        )
    }

    /** 把曝光时间映射为 T3 取景器的 500/125/30/LT 档组合（相邻两档同亮=中间值） */
    private fun shutterTagsFor(sec: Float): Set<String> {
        if (sec <= 0f) return emptySet()
        return when {
            sec <= 1f / 700f -> setOf("500")
            sec <= 1f / 240f -> setOf("500", "125")
            sec <= 1f / 60f -> setOf("125")
            sec <= 1f / 24f -> setOf("125", "30")
            sec <= 1f / 8f -> setOf("30")
            sec <= 4f -> setOf("30", "LT")
            else -> setOf("LT")
        }
    }

    /** 更新左下胶卷信息 */
    private fun updateFilmInfo() {
        try {
            binding.filmInfoText.text = "${currentRoll.name}\nCOLOR NEGATIVE\nISO500 · 剩 ${FILM_MAX - currentShotCount()} 张"
        } catch (_: Exception) {}
    }

    /** 循环切换闪光灯模式：OFF → BUILT_IN → EXTERNAL → OFF */
    private fun cycleFlashMode() {
        flash.mode = when (flash.mode) {
            FlashMode.OFF -> FlashMode.BUILT_IN
            FlashMode.BUILT_IN -> FlashMode.EXTERNAL
            FlashMode.EXTERNAL -> FlashMode.OFF
        }
        binding.tvFlashStatus.text = when (flash.mode) {
            FlashMode.OFF -> "闪: 关"
            FlashMode.BUILT_IN -> "闪: 内置"
            FlashMode.EXTERNAL -> "闪: 外置"
        }
        if (flash.mode == FlashMode.EXTERNAL) {
            flash.startScan()
        }
        applyExposure()
        updateLcd()
        // 点亮取景器内的闪光指示
        binding.viewfinderFrame.notifyActivity()
        saveSettings()
    }

    // ===== 闪光灯模式：预设曝光补偿档位 =====
    private var proMode = false
    // 闪光灯模式预设：label → (ISO, 快门纳秒)
    // LED 闪光灯过曝时，用低 ISO + 稍快快门压暗
    private val flashModes = listOf(
        Triple("FL-AUTO", null, null),                              // 自动模式
        Triple("FL-WEAK", 100, 4_000_000L),                        // ISO 100, 1/250s
        Triple("FL-MED", 100, 2_000_000L),                         // ISO 100, 1/500s
        Triple("FL-STRONG", 50, 1_000_000L)                       // ISO 50, 1/1000s
    )
    private var flashModeIndex = 0

    private fun toggleProMode() {
        proMode = !proMode
        binding.tvProToggle.text = if (proMode) "外置: 开" else "外置: 关"
        binding.tvProToggle.setTextColor(if (proMode) 0xFF00E5FF.toInt() else 0xFFFFFFFF.toInt())
        binding.proControls.visibility = if (proMode) android.view.View.VISIBLE else android.view.View.GONE
        if (!proMode) {
            flashModeIndex = 0
            updateFlashModeLabels()
            applyAutoExposure()
        }
    }

    private fun cycleIso() { cycleFlashComp() }
    private fun cycleShutter() { cycleFlashComp() }
    private fun cycleEv() { cycleFlashComp() }
    private fun cycleFlashComp() {
        flashModeIndex = (flashModeIndex + 1) % flashModes.size
        updateFlashModeLabels()
        applyProSettings()
    }

    private fun updateFlashModeLabels() {
        val (label, iso, exposureNs) = flashModes[flashModeIndex]
        binding.tvIso.text = if (iso != null) "ISO $iso" else "ISO 自动"
        binding.tvShutter.text = when (exposureNs) {
            null -> "快门 自动"
            8_000_000L -> "1/125"
            4_000_000L -> "1/250"
            2_000_000L -> "1/500"
            else -> "1/${1_000_000_000L / exposureNs}"
        }
        binding.tvExposure.text = label
    }

    private fun applyProSettings() {
        try {
            val (_, iso, exposureNs) = flashModes[flashModeIndex]
            cameraController?.setManualIso(iso)
            cameraController?.setManualExposure(exposureNs)
            Toast.makeText(this, flashModes[flashModeIndex].first, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "设置失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyAutoExposure() {
        try {
            cameraController?.setManualIso(null)
            cameraController?.setManualExposure(null)
        } catch (_: Exception) {}
    }

    override fun onStart() {
        super.onStart()
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, permissions, REQ_CODE)
        }
    }

    override fun onPause() {
        super.onPause()
        saveSettings()  // 兜底：退出/后台时保存设置
        // 进后台：释放相机 + 暂停 GL 渲染，避免回前台时相机会话冲突导致预览卡死
        cameraController?.stop()
        cameraController = null
        binding.glSurfaceView.onPause()
    }

    override fun onResume() {
        super.onResume()
        // 回前台：恢复 GL，SurfaceTexture 重建后会自动触发 onSurfaceTextureReady → 重新启动相机
        binding.glSurfaceView.onResume()
    }

    private fun hasPermissions(): Boolean =
        permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            // 权限通过后，等 onSurfaceTextureReady 回调自动启动相机
        } else {
            Toast.makeText(this, "需要相机/蓝牙权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCamera(surfaceTexture: SurfaceTexture) {
        cameraController = Camera2Controller(this)
        cameraController?.start(surfaceTexture)
        // 桥接 FlashController → Camera2Controller 控制 LED
        flash.onBuiltInFlash = { enabled ->
            cameraController?.enableFlash(enabled)
        }
        // 实时元数据 → 取景器内的 T3 指示灯（合焦灯 / 快门档位）
        cameraController?.onViewfinderMeta = { afState, exposureSec ->
            updateViewfinderIndicators(afState, exposureSec)
        }
        // 全分辨率拍照回调：后台把原帧 JPEG 存入未冲洗胶卷（零处理，不做 LUT/日期戳/EXIF——那些在冲洗侧做）
        cameraController?.onPhotoCaptured = { bytes, rotation ->
            saveExecutor.execute {
                var savedRollId: String? = null
                try {
                    // 字节直传：相机 ISP 的 JPEG 原样入卷，不解码、不旋转、不全尺寸重编码。
                    // 方向由 rotation 随帧记录，冲洗侧统一旋转（90° 整数倍，无损）。
                    // 无活动卷 → 按当前胶卷映射开新卷
                    var roll = UndevelopedFilmStore.currentRoll()
                    if (roll == null) {
                        roll = UndevelopedFilmStore.startRoll(
                            FilmStockMapper.filmStockIdFor(currentRoll.name),
                            FilmStockMapper.printStockIdFor(currentRoll.name)
                        )
                    }
                    savedRollId = roll.rollId
                    val iso = isoValues[isoValue]
                    val count = UndevelopedFilmStore.saveFrame(
                        roll.rollId, bytes, System.currentTimeMillis(), iso, rotation
                    )
                    runOnUiThread {
                        if (count >= FILM_MAX) {
                            promptDevelopFullRoll()
                        }
                        updateLcd()
                        updateFilmInfo()
                        // 调试即时出片：拍完立即后台冲洗
                        if (debugInstantDevelop) {
                            savedRollId?.let { DevelopController.developRollInBackground(this, it) }
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    runOnUiThread {
                        stopDevelopingAnimation()
                        binding.developingOverlay.visibility = android.view.View.GONE
                        isCapturing = false
                    }
                }
            }
        }
        binding.tvFlashStatus.text = "闪: 关"
    }

    /**
     * ⭐ 关键：拦截音量键。
     * 灯上物理按键通过 HID 模拟 Volume Down，会落到这里。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            triggerShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private var isCapturing = false

    private fun triggerShutter() {
        // 拍满 36 张：先播回卷动画，再弹窗引导冲洗
        if (currentShotCount() >= FILM_MAX) {
            showRewindAnimation { promptDevelopFullRoll() }
            return
        }
        // SELF 自拍模式：先 3 秒倒计时再拍
        if (currentMode == "SELF") {
            startSelfTimer()
            return
        }
        doCapture()
    }

    private fun startSelfTimer() {
        if (isCapturing) return
        isCapturing = true
        binding.developingOverlay.visibility = android.view.View.VISIBLE
        binding.ivDeveloping.visibility = android.view.View.GONE
        binding.tvDevelopingText.visibility = android.view.View.VISIBLE
        binding.tvDevelopingText.text = "SELF TIMER 3"

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var count = 3
        val runnable = object : Runnable {
            override fun run() {
                count--
                if (count > 0) {
                    binding.tvDevelopingText.text = "SELF TIMER $count"
                    handler.postDelayed(this, 1000)
                } else {
                    binding.developingOverlay.visibility = android.view.View.GONE
                    isCapturing = false
                    doCapture()
                }
            }
        }
        handler.postDelayed(runnable, 1000)
    }

    /** 触感反馈（统一入口，兼容 API 26/31+）。amp=-1 用系统默认强度 */
    private fun vibrate(ms: Long, amp: Int = -1) {
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                    as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val effect = if (amp > 0) {
                    android.os.VibrationEffect.createOneShot(ms, amp)
                } else {
                    android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        } catch (_: Exception) {}
    }

    /**
     * 播放 T3 自动过卷声（res/raw/t3_advance.wav：340ms / 48kHz / 16bit / 单声道）。
     * @param rate 播放速率。小于 1 会拉长时长并压低音调，接近马达带负载时的质感。
     */
    private fun playAdvanceSound(rate: Float = 1f) {
        try {
            if (advanceSoundId != 0) {
                advanceSoundPool?.play(advanceSoundId, 1f, 1f, 1, 0, rate.coerceIn(0.5f, 2f))
            }
        } catch (_: Exception) {}
    }


    /** 快门反馈：播放真实 T3 快门声 + 振动（过卷声暂不播放，待找到素材后加入） */
    private fun playShutterFeedback() {
        // 真实 T3 快门声（res/raw/t3_shutter.wav）
        try {
            if (shutterSoundId != 0) {
                shutterSoundPool?.play(shutterSoundId, 1f, 1f, 1, 0, 1f)
            }
        } catch (_: Exception) {}
        // 快门振动（30ms 轻微触感）
        vibrate(30)
    }

    private fun doCapture() {
        if (isCapturing) return
        isCapturing = true

        playShutterFeedback()  // 快门声音 + 振动
        flash.trigger()
        binding.btnShutter.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80)
            .withEndAction {
                binding.btnShutter.animate().scaleX(1f).scaleY(1f).duration = 80
            }

        // 拍摄后一体声（快门+过卷）由 playShutterFeedback 播放，过卷动画立即同步启动
        showDevelopingAnimation()

        // 闪光灯按键触发，立即拍照
        binding.glSurfaceView.post {
            try {
                // 模拟失焦：工程师模式设定的概率触发对焦不准（0-100%，0=关闭）
                val defocus = softFocusEnabled &&
                    java.util.Random().nextInt(100) < softFocusProb
                cameraController?.takePicture(defocus)
            } catch (e: Exception) {
                Toast.makeText(this, "拍照错误: ${e.message}", Toast.LENGTH_LONG).show()
                isCapturing = false
            }
        }
    }

    /** 自动过卷动画：拍照后模拟 T3 自动过卷（胶卷旋转动画 + 过卷声 + 微弱震动），照片在后台处理 */
    private fun showDevelopingAnimation() {
        binding.developingOverlay.visibility = android.view.View.VISIBLE

        // 显示当前胶卷图标，模拟过卷旋转
        binding.ivDeveloping.setImageResource(currentRoll.iconResId)
        binding.ivDeveloping.visibility = android.view.View.VISIBLE
        binding.ivDeveloping.rotation = 0f
        binding.tvDevelopingText.text = "FILM ADVANCING..."
        binding.tvDevelopingText.visibility = android.view.View.VISIBLE

        // 过卷等待动画：持续旋转直到处理+保存完成（胶片相机拍完需等冲洗，动画连贯不跳变）
        startDevelopingAnimation()

        // 过卷声音：真实 T3 的顺序是「叶片先喀一声曝光」→ 停顿一下 →「马达拖动齿轮过卷」，
        // 两段声音之间有明显间隔。原先它与快门声在同一帧触发，82ms 的快门声还没播完
        // 340ms 的马达声就压上来了，两个音叠成一坨，听不出层次——用户反馈"没有过片声"
        // 其实是听到了，只是被糊在一起分不出来。这里延后 200ms 起播给曝光留出交代，
        // 并用 0.75 速率把素材拉长到约 450ms、音调略沉（更接近马达带负载的质感）。
        binding.ivDeveloping.postDelayed({
            if (isCapturing) playAdvanceSound(rate = 0.75f)
        }, 200)

        // 微弱震动：同步挪到马达真正发力之后，让手感与听觉对齐
        binding.ivDeveloping.postDelayed({ if (isCapturing) vibrate(50, 30) }, 200)
        binding.ivDeveloping.postDelayed({ if (isCapturing) vibrate(45, 25) }, 380)
        binding.ivDeveloping.postDelayed({ if (isCapturing) vibrate(45, 25) }, 520)

        // 480ms 后文案切到 PROCESSING...；真正的完成收尾由 onPhotoCaptured 回调 finally 里做
        binding.ivDeveloping.postDelayed({
            binding.tvDevelopingText.text = "PROCESSING..."
        }, 480)

        // 看门狗：相机回调迟迟不来（>20s）时兜底复位，避免卡死在过卷界面
        binding.ivDeveloping.postDelayed({
            if (isCapturing) {
                stopDevelopingAnimation()
                binding.developingOverlay.visibility = android.view.View.GONE
                isCapturing = false
                Toast.makeText(this, "拍照超时", Toast.LENGTH_SHORT).show()
            }
        }, 20_000)
    }

    /** 过卷等待动画：无限旋转，持续到后台处理+保存完成（连贯不跳变） */
    private fun startDevelopingAnimation() {
        stopDevelopingAnimation()
        binding.ivDeveloping.rotation = 0f
        developingAnimator = android.animation.ObjectAnimator.ofFloat(binding.ivDeveloping, "rotation", 0f, 360f).apply {
            duration = 1200   // 慢速匀速旋转，模拟机械过卷/冲洗等待的从容节奏
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
    }

    private fun stopDevelopingAnimation() {
        developingAnimator?.cancel()
        developingAnimator = null
    }

    override fun onDestroy() {
        super.onDestroy()
        saveSettings()  // 兜底：退出时保存设置
        shutterSoundPool?.release()
        shutterSoundPool = null
        advanceSoundPool?.release()
        advanceSoundPool = null
        flash.release()
        saveExecutor.shutdownNow()
    }

    companion object {
        private const val REQ_CODE = 1001
    }
}
