package com.filmcam.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
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

        // 加载默认胶卷 LUT
        binding.glSurfaceView.loadLutFromRaw(currentRoll.lutResId)
        binding.tvFilmName.setImageResource(currentRoll.iconResId)

        // 颗粒先关掉，拍照时再加
        binding.glSurfaceView.setGrain(0f)

        // 等待 GL 线程就绪，拿到 SurfaceTexture 后启动 CameraX
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

        // 恢复上次退出时保存的设置
        restoreSettings()

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
        var touchStartY = 0f
        var touchStartEv = 0
        binding.glSurfaceView.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchStartY = event.rawY
                    touchStartEv = exposureComp
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val delta = (touchStartY - event.rawY) / 100f
                    exposureComp = (touchStartEv + delta.toInt()).coerceIn(-3, 3)
                    binding.tvEvValue.text = exposureComp.toString()
                    cameraController?.setExposureComp(exposureComp)
                    saveSettings()
                }
            }
            true
        }

        // 曝光补偿按钮
        binding.btnEvPlus.setOnClickListener {
            exposureComp += 1
            if (exposureComp > 3) exposureComp = -3
            binding.tvEvValue.text = exposureComp.toString()
            cameraController?.setExposureComp(exposureComp)
            saveSettings()
        }
        binding.btnEvMinus.setOnClickListener {
            exposureComp -= 1
            if (exposureComp < -3) exposureComp = 3
            binding.tvEvValue.text = exposureComp.toString()
            cameraController?.setExposureComp(exposureComp)
            saveSettings()
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
        filmRollMode = prefs.getBoolean("filmRollMode", false)
        // 旧 filmCount 键仅作迁移保留读取，不再作为权威（张数一律读 UndevelopedFilmStore.shotCount）
        @Suppress("UNUSED_VARIABLE") val legacyFilmCount = prefs.getInt("filmCount", 0)
        debugInstantDevelop = prefs.getBoolean("debug_instant_develop", false)
        binding.glSurfaceView.photoGrainEnabled = prefs.getBoolean("grain", true)
        flash.mode = try {
            FlashMode.valueOf(prefs.getString("flashMode", FlashMode.OFF.name) ?: FlashMode.OFF.name)
        } catch (_: Exception) { FlashMode.OFF }
        val rollIndex = prefs.getInt("rollIndex", 0).coerceIn(0, FilmRollRepository.rolls.size - 1)
        selectFilmRoll(FilmRollRepository.rolls[rollIndex], fromUser = false)
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
        prefs.putBoolean("grain", binding.glSurfaceView.photoGrainEnabled)
        prefs.putString("flashMode", flash.mode.name)
        prefs.putInt("rollIndex", FilmRollRepository.rolls.indexOf(currentRoll))
        prefs.apply()
    }

    /** 设置弹窗 */
    private fun showSettings() {
        val items = arrayOf(
            "日期水印: ${if (dateStampEnabled) "开" else "关"}",
            "模拟失焦: ${if (softFocusEnabled) "开" else "关"}",
            "胶片颗粒: ${if (binding.glSurfaceView.photoGrainEnabled) "开" else "关"}",
            "调试: 跳过冲洗立即出片: ${if (debugInstantDevelop) "开" else "关"}",
            "冲洗参数: 颗粒/辉光/眩光"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { dateStampEnabled = !dateStampEnabled; saveSettings() }
                    1 -> { softFocusEnabled = !softFocusEnabled; saveSettings() }
                    2 -> { binding.glSurfaceView.photoGrainEnabled = !binding.glSurfaceView.photoGrainEnabled; saveSettings() }
                    3 -> { debugInstantDevelop = !debugInstantDevelop; saveSettings() }
                    4 -> showDevParamsDialog()
                }
                if (which != 4) showSettings()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 胶卷拍满：引导去暗房冲洗 */
    private fun promptDevelopFullRoll() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("胶卷已拍完")
            .setMessage("这一卷 36 张已拍完，去暗房冲洗出片")
            .setPositiveButton("去冲洗") { _, _ ->
                activeRollId()?.let { DevelopController.startDevelopment(this, it) }
            }
            .setNegativeButton("稍后", null)
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
            androidx.appcompat.app.AlertDialog.Builder(this)
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
            androidx.appcompat.app.AlertDialog.Builder(this)
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

        androidx.appcompat.app.AlertDialog.Builder(this)
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
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = FilmRollAdapter(FilmRollRepository.rolls) { roll ->
            selectFilmRoll(roll)
            dialog.dismiss()
        }

        dialog.show()
    }

    /** 选中胶卷：切换 LUT + 更新图标。fromUser=false 用于启动时恢复设置（不做拦截）。 */
    private fun selectFilmRoll(roll: FilmRoll, fromUser: Boolean = true) {
        if (fromUser) {
            // 换卷拦截：当前卷有未冲洗帧时禁止换胶卷
            val active = UndevelopedFilmStore.currentRoll()
            if (active != null && UndevelopedFilmStore.shotCount(active.rollId) > 0) {
                Toast.makeText(this, "请先冲洗当前胶卷", Toast.LENGTH_SHORT).show()
                return
            }
        }
        currentRoll = roll
        binding.tvFilmName.setImageResource(roll.iconResId)
        binding.glSurfaceView.loadLutFromRaw(roll.lutResId)
        updateFilmInfo()
        saveSettings()
    }

    // ===== T3 机身 UI 交互 =====

    /** 曝光补偿键：-3 → +3 循环 */
    private fun cycleEvValue() {
        exposureComp += 1
        if (exposureComp > 3) exposureComp = -3
        applyExposure()
        updateLcd()
        saveSettings()
    }

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
            binding.lcdDisplay.text = "$count $currentMode\nISO$iso  EV$ev\nFLASH $flashLabel"
        } catch (_: Exception) {}
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
        // 全分辨率拍照回调：后台把原帧 JPEG 存入未冲洗胶卷（零处理，不做 LUT/日期戳/EXIF——那些在冲洗侧做）
        cameraController?.onPhotoCaptured = { bmp ->
            saveExecutor.execute {
                var savedRollId: String? = null
                try {
                    val bytes = java.io.ByteArrayOutputStream().use { baos ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 100, baos)
                        baos.toByteArray()
                    }
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
                        roll.rollId, bytes, System.currentTimeMillis(), iso
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
        // 拍满 36 张：拒绝拍照，弹窗引导冲洗
        if (currentShotCount() >= FILM_MAX) {
            promptDevelopFullRoll()
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

    /** 快门反馈：播放真实 T3 快门声 + 振动（过卷声暂不播放，待找到素材后加入） */
    private fun playShutterFeedback() {
        // 真实 T3 快门声（res/raw/t3_shutter.wav）
        try {
            if (shutterSoundId != 0) {
                shutterSoundPool?.play(shutterSoundId, 1f, 1f, 1, 0, 1f)
            }
        } catch (_: Exception) {}
        // 快门振动（30ms 轻微触感）
        try {
            val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(30)
            }
        } catch (_: Exception) {}
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
                // 模拟失焦：开启时随机概率触发对焦不准（约40%）
                val defocus = softFocusEnabled && java.util.Random().nextFloat() < 0.4f
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

        // 过卷声音暂不播放（待找到真实 T3 过卷素材后在此加入，advance 声源已预留）

        // 微弱震动（过卷期间低幅触感，模拟机械过卷）
        try {
            val vibrator = getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(60, 40))
                binding.ivDeveloping.postDelayed({
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(50, 30))
                }, 160)
                binding.ivDeveloping.postDelayed({
                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(50, 30))
                }, 260)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(40)
            }
        } catch (_: Exception) {}

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
