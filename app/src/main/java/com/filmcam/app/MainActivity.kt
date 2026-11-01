package com.filmcam.app

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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

        // 屏幕快门按钮（快门键行程更长、更实，给一记比功能键更重的触感）
        binding.btnShutter.hapticClick(ms = 32, amp = 235) { triggerShutter() }

        // 点击胶卷名 → 打开胶卷选择器
        binding.tvFilmName.hapticClick { showFilmRollPicker() }

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
        binding.tvFlashStatus.hapticClick { cycleFlashMode() }

        // 相册回看
        binding.btnGallery.hapticClick {
            startActivity(android.content.Intent(this, GalleryActivity::class.java))
        }

        // 设置按钮
        binding.settingsKey.hapticClick { showSettings() }

        // 顶部功能键：闪光灯 / 曝光补偿
        binding.flashKey.hapticClick { cycleFlashMode() }
        binding.evKey.hapticClick { cycleEvValue() }
        binding.filmInfoText.hapticClick { showFilmRollPicker() }
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
        // 本次手势是不是从取景器自己的 DOWN 开始的。
        // 弹窗/菜单上的手指抬起时，事件会连着飘到下面的取景器——那是没有 DOWN 的孤立
        // UP/MOVE，带着上一次手势的陈旧状态（touchMoved=false、EV 没变），
        // 正好满足"点按对焦"的全部条件，于是隔着菜单就对焦了。
        // 真机不存在这种操作，所以没有自己 DOWN 的手势一律不认。
        touchActive = false
        lastDownTime = -1L

        // 点按对焦判定交给 GestureDetector：它只在「自己收到过 DOWN 的完整序列」上回调
        // onSingleTapUp。弹窗飘下来的孤立 UP 没有配对 DOWN，从结构上不可能触发对焦——
        // 这比任何时间窗都可靠（时间窗会过期，序列完整性不会）。
        val tapDetector = android.view.GestureDetector(
            this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean = true
                override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                    if (dialogDepth > 0) return true
                    if (SystemClock.uptimeMillis() < suppressTouchUntil) return true
                    if (touchMoved) return true          // 滑动调过 EV/白平衡就不算点按
                    val fx = e.x
                    val fy = e.y
                    binding.viewfinderFrame.notifyActivity()
                    // 只有落在预览内容矩形内才对焦：letterbox 黑边、预览窗之外一律忽略
                    if (!binding.viewfinderFrame.contentRect().contains(fx, fy)) return true
                    // 点屏对焦：不做任何视觉标记，合焦后仅给一次短震动
                    cameraController?.focusAt(
                        fx, fy,
                        binding.glSurfaceView.width, binding.glSurfaceView.height
                    ) { vibrate(24, 185) }
                    return true
                }
            }
        )

        binding.glSurfaceView.setOnTouchListener { _, event ->
            // 弹窗在场时取景器本就不该收到事件，收到就是菜单飘下来的残余
            if (dialogDepth > 0) return@setOnTouchListener true
            // 同一手势序列的 downTime 恒定；downTime 对不上说明是别的序列的残余事件
            if (event.action != android.view.MotionEvent.ACTION_DOWN &&
                event.downTime != lastDownTime
            ) return@setOnTouchListener true
            tapDetector.onTouchEvent(event)
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // 弹窗/设置项刚关闭的一瞬间，抬手事件会落到取景器上被当成点屏对焦。
                    // 真机不存在这种"隔着菜单对焦"，所以这段时间窗内一律不响应触摸。
                    if (SystemClock.uptimeMillis() < suppressTouchUntil) return@setOnTouchListener true
                    touchActive = true
                    lastDownTime = event.downTime
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    touchStartEv = exposureComp
                    touchMoved = false
                    wbDragging = false
                    touchStartWbIndex = wbIndex
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!touchActive) return@setOnTouchListener true
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
                        wbVisible = true
                        binding.viewfinderFrame.notifyActivity()
                        vibrate(20, 140)     // 进入 WB 手势给一记轻触，确认「抓住了」
                        applyWbIndex()
                    }
                    if (wbDragging) {
                        val idx = (touchStartWbIndex + (dx / 90f).toInt())
                            .coerceIn(0, wbPresets.size - 1)
                        if (idx != wbIndex) {
                            wbIndex = idx
                            applyWbIndex()
                            vibrate(16, 120)
                        }
                        return@setOnTouchListener true
                    }

                    val delta = (touchStartY - event.rawY) / 100f
                    val newEv = (touchStartEv + delta.toInt()).coerceIn(-3, 3)
                    if (newEv != exposureComp) {
                        // 统一入口：原先这里只调 setExposureComp + notifyActivity，
                        // 漏了 applyExposure（外置闪光开关会失效）与 updateLcd（LCD 读数不更新）
                        setEv(newEv, persist = false)   // 抬手时统一 saveSettings
                        // 每过一档给一记轻震：曝光补偿是「档位」机械感，
                        // 手指要能感觉到自己拨过了一格（与胶卷列表滑动触感同一套语言）
                        vibrate(14, 115)
                    }
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    // 没有自己 DOWN 的孤立抬手（弹窗点击飘过来的）直接丢弃
                    if (!touchActive) return@setOnTouchListener true
                    touchActive = false
                    if (wbDragging) {
                        if (wbIndex != touchStartWbIndex) saveSettings()
                        wbDragging = false
                        // 抬手不立刻擦掉：缓 900ms 让人看清落在哪一档，再淡出
                        binding.viewfinderFrame.postDelayed({
                            wbVisible = false
                            lastIndicatorKey = ""   // 强制下一次去重放过，允许重画
                            updateViewfinderIndicators(lastAfState, lastExposureSec)
                        }, 900)
                        return@setOnTouchListener true
                    }
                    if (exposureComp != touchStartEv) saveSettings()
                    // 点按对焦已交给 tapDetector（只在完整手势序列上触发）
                }
            }
            true
        }

        // 曝光补偿按钮
        binding.btnEvPlus.hapticClick {
            setEv(if (exposureComp >= 3) -3 else exposureComp + 1)
        }
        binding.btnEvMinus.hapticClick {
            setEv(if (exposureComp <= -3) 3 else exposureComp - 1)
        }

        // 专业模式
        binding.tvProToggle.hapticClick { toggleProMode() }
        binding.tvIso.hapticClick { cycleIso() }
        binding.tvShutter.hapticClick { cycleShutter() }
        binding.tvExposure.hapticClick { cycleEv() }

        // 「拍摄同款」从暗房带参数跳回（进程被杀后冷启动也走这里）
        // 参数每次都照常套用（幂等），但机身提示只在「真正新的一次跳转」给：
        // 横竖屏切换会重建 Activity，此时 savedInstanceState != null，
        // 再提示一遍就成了骚扰。
        // Activity 重建时旧弹窗已随实例销毁，这里必须复位，否则取景器会永久失联
        dialogDepth = 0
        applySameRecipe(intent, announce = savedInstanceState == null)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applySameRecipe(intent, announce = true)
    }

    /**
     * 弹窗关闭后的防误触总闸。
     * 弹窗本身是独立 Window（它的触摸不走这里），所以这只影响弹窗关闭后那一小段
     * 时间里落到 Activity 上的事件——正是从菜单飘下来、会被误判成点屏对焦的那些。
     * 放在这一层比只在取景器的 onTouch 里拦更彻底：任何子 View 都收不到。
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (SystemClock.uptimeMillis() < suppressTouchUntil) {
            // 只吃掉落在取景器上的事件：快门/胶卷/设置这类机身按键不受影响，
            // 否则关掉弹窗后立刻按快门会有 400ms 没反应，反而像卡了。
            val r = android.graphics.Rect()
            if (binding.glSurfaceView.getGlobalVisibleRect(r) &&
                r.contains(ev.rawX.toInt(), ev.rawY.toInt())
            ) return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 「拍摄同款」：PhotoViewer 把成片 EXIF 里的胶卷 stockId 与冲洗参数快照
     * 通过 extras 带回来，这里一次性套用——
     *  · 冲洗参数写入 filmcam_dev_<param>_<stockId>（与冲洗参数弹窗同一套键）；
     *  · 胶卷：找到 stockId 对应的 FilmRoll 直接切换（fromUser=false，绕开换卷
     *    拦截——这是用户显式要求的换卷）；当前卷还有未冲洗帧时不强行换卷，
     *    参数先行生效，LCD 提示等这卷冲洗后再拍才是同款。
     */
    private fun applySameRecipe(intent: android.content.Intent?, announce: Boolean) {
        if (intent == null) return
        val stockId = intent.getStringExtra(EXTRA_SAME_FILM)?.trim().orEmpty()
        if (stockId.isEmpty()) return

        val prefs = getSharedPreferences("filmcam", MODE_PRIVATE)
        val editor = prefs.edit()
        fun putDev(param: String, key: String) {
            if (intent.hasExtra(key)) {
                editor.putFloat("filmcam_dev_${param}_$stockId", intent.getFloatExtra(key, 1f))
            }
        }
        putDev("grain", EXTRA_SAME_GRAIN)
        putDev("halation", EXTRA_SAME_HALATION)
        putDev("glare", EXTRA_SAME_GLARE)
        putDev("print_exposure", EXTRA_SAME_EXPOSURE)
        intent.getStringExtra(EXTRA_SAME_PAPER)?.takeIf { it.isNotBlank() }?.let {
            editor.putString("filmcam_dev_paper_$stockId", it)
        }
        editor.apply()

        val roll = FilmRollRepository.rolls.firstOrNull {
            runCatching { FilmStockMapper.filmStockIdFor(it.name) }.getOrNull() == stockId
        }
        val rollName = runCatching { FilmStockMapper.stockDisplayName(stockId) }.getOrDefault(stockId)
        if (roll != null && roll != currentRoll) {
            if (currentShotCount() > 0) {
                if (announce) showLcdNotice("本卷拍完冲洗后换$rollName")
            } else {
                selectFilmRoll(roll, fromUser = false)
            }
        }
        if (announce) showLcdNotice("已套用同款参数")

        // 用完就把 extras 摘掉：同一个 Intent 对象在配置变更（横竖屏）后会被复用，
        // 留着会在重建时再套一次、再提示一次。
        EXTRA_SAME_KEYS.forEach { intent.removeExtra(it) }
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

    /**
     * 弹窗关闭守卫：无论怎么关的，dismiss 后都补一段取景器触摸屏蔽。
     * 只在点击回调里设不够——点弹窗外部、按返回键关闭时同样会有事件甩给取景器。
     */
    private fun <T : android.app.Dialog> T.guardDialog(): T {
        dialogDepth++
        setOnDismissListener {
            dialogDepth = (dialogDepth - 1).coerceAtLeast(0)
            suppressTouchUntil = SystemClock.uptimeMillis() + DIALOG_TOUCH_GUARD_MS
            // 弹窗一关就把手势状态清零：漏进来的事件不会续上一次手势
            touchActive = false
            lastDownTime = -1L
        }
        return this
    }

    /** 设置弹窗：只留普通用户项；调试与模拟失焦收进「关于页 → 版本号连点 10 次」的工程师模式 */
    private fun showSettings() {
        val items = arrayOf(
            "日期水印: ${if (dateStampEnabled) "开" else "关"}",
            "冲洗参数: 颗粒/辉光/眩光",
            "关于 FILMCAM"
        )
        // 弹窗刚关闭的一瞬间，抬手事件会穿透到取景器被当成点屏对焦 → 短暂屏蔽触摸
        suppressTouchUntil = SystemClock.uptimeMillis() + DIALOG_TOUCH_GUARD_MS
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("设置")
            .setItems(items) { _, which -> onSettingsItem(which) }
            .setNegativeButton("关闭", null)
            .create()
        dialog.guardDialog()
        dialog.setOnShowListener {
            // 日期水印是「开关」不是「动作」：就地翻转文案、弹窗不关。
            // 原先是点了就 dismiss 再 show 一个新弹窗——弹窗消失与重建之间的那几帧
            // 正是触摸事件最容易漏到取景器、被误判成点屏对焦的窗口。现在没有这个
            // 窗口了：整个开关过程弹窗一直在场，取景器根本收不到事件。
            dialog.listView?.setOnItemClickListener { _, _, which, _ ->
                if (which == 0) {
                    dateStampEnabled = !dateStampEnabled
                    saveSettings()
                    items[0] = "日期水印: ${if (dateStampEnabled) "开" else "关"}"
                    (dialog.listView?.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
                } else {
                    dialog.dismiss()
                    onSettingsItem(which)
                }
            }
        }
        dialog.show()
    }

    private fun onSettingsItem(which: Int) {
        suppressTouchUntil = SystemClock.uptimeMillis() + DIALOG_TOUCH_GUARD_MS
        when (which) {
            1 -> showDevParamsDialog()
            2 -> startActivity(android.content.Intent(this, AboutActivity::class.java))
        }
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
            .create()
            .guardDialog()
            .show()
    }

    /**
     * 回卷动画：胶卷拍满后模拟手动回卷——胶卷条快速回卷
     * + 过卷声，营造"这卷拍完了，卷回去送冲"的仪式感。结束后回调。
     */
    private fun showRewindAnimation(onEnd: () -> Unit) {
        if (isCapturing) return
        isCapturing = true
        binding.developingOverlay.visibility = android.view.View.VISIBLE
        binding.ivDeveloping.setIcon(currentRoll.iconResId)
        binding.ivDeveloping.visibility = android.view.View.VISIBLE
        binding.tvDevelopingText.text = "REWIND FILM..."
        binding.tvDevelopingText.visibility = android.view.View.VISIBLE

        playAdvanceSound()
        // 手动回卷：同样是齿孔一格一格退回去的手感，全程 1.4s 连续轻震
        startGearHaptics(fromMs = 0, toMs = 1400, stepMs = 58)

        // 快速回卷：胶片带高速倒着被卷回暗盒（速度越快拖影越长），1.6s 后收尾
        binding.ivDeveloping.startSpinning(5.0f)
        android.animation.ObjectAnimator.ofFloat(binding.ivDeveloping, "alpha", 1f, 1f).apply {
            duration = 1600
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    binding.ivDeveloping.stopSpinning()
                    binding.developingOverlay.visibility = android.view.View.GONE
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
            .create()
            .guardDialog()
            .show()
    }

    /** 冲洗参数弹窗：颗粒/辉光/眩光强度（键量纲与 FilmStockMapper 冻结契约一致） */
    private fun showDevParamsDialog() {
        suppressTouchUntil = SystemClock.uptimeMillis() + DIALOG_TOUCH_GUARD_MS
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
                .create()
                .also { it.guardDialog() }
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
        rowPaper.hapticClick {
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
                .create()
                .also { it.guardDialog() }
                .show()
        }

        // 恢复该款默认：移除当前 stockId 对应的 5 个后缀键（=回落冻结默认）
        btnReset.hapticClick {
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
            .create()
            .also { it.guardDialog() }
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
            vibrate(22, 175)         // 选中一支胶卷也有一记触感，和滑动/长按拖拽保持一致
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
                // 长按抓起：给一记稍重的触感，等价于「这一项被拿起来了」
                override fun onSelectedChanged(
                    viewHolder: RecyclerView.ViewHolder?,
                    actionState: Int
                ) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                        vibrate(34, 200)
                    }
                }

                // 每越过一个相邻位置给一记极轻触感，让「调位置」这件事在手上能被数出来
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    adapter.moveItem(
                        viewHolder.bindingAdapterPosition,
                        target.bindingAdapterPosition
                    )
                    vibrate(18, 130)
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

        // 滑动选胶卷时的"齿轮感"：列表每滚过一格（约 1/4 个条目高）给一记极轻触感，
        // 像拨动过片齿轮的齿——让"往上滑换卷"这件事在手上能被数出来。
        // 只用 dy 的绝对值累加，方向无关的连续刻度感；惯性滑行时也会持续给到，
        // 但太密的震动会变成嗡嗡声，故阈值取条目高的约 1/4 并做 40ms 节流。
        var tickAccum = 0
        var lastTickMs = 0L
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                tickAccum += kotlin.math.abs(dy)
                val threshold = (recyclerView.height / 8).coerceAtLeast(40)
                val now = android.os.SystemClock.elapsedRealtime()
                if (tickAccum >= threshold && now - lastTickMs > 40) {
                    tickAccum = 0
                    lastTickMs = now
                    vibrate(12, 95)
                }
            }
        })

        dialog.guardDialog().show()
        // 横屏下 BottomSheet 默认只展开到 peek 高度，25 支胶卷的大半被压在屏幕外，
        // 也看不出"往上还能滑"——直接强制全展开，让滚动发生在列表内部。
        dialog.behavior.state =
            com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    }

    // ===== 机身提示 =====

    /**
     * 机身提示：走机顶 LCD（T3LcdView.showMessage）。
     *
     * 之前做成挂在机身外侧的一块琥珀铭牌，用户反馈颜色不好看——问题在于它引入了
     * 一套 LCD 之外的颜色，等于凭空加了第二个状态输出口。LCD 本来就是机身的状态屏，
     * 互锁、不支持的胶卷这类信息从它这里走是唯一正确的位置，配色也天然一致。
     */
    private fun showLcdNotice(text: String) {
        binding.lcdDisplay.showMessage(text)
    }

    // ===== 白平衡（闪光开启后，预览上左右拖动切换） =====

    /** 白平衡档位：语义对应胶片机的校色滤镜片，顺序即横滑从左到右的档位 */
    private enum class WbIcon { AUTO, BULB, TUBE, SUN, CLOUD_SUN, SHADE, TWILIGHT }

    private data class WbPreset(val label: String, val awbMode: Int, val icon: WbIcon)

    private val wbPresets = listOf(
        WbPreset("AUTO", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO, WbIcon.AUTO),
        WbPreset("白炽灯", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, WbIcon.BULB),
        WbPreset("荧光灯", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, WbIcon.TUBE),
        WbPreset("日光", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, WbIcon.SUN),
        WbPreset("阴天", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, WbIcon.CLOUD_SUN),
        WbPreset("阴影", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_SHADE, WbIcon.SHADE),
        WbPreset("暮光", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_TWILIGHT, WbIcon.TWILIGHT)
    )
    private var wbIndex = 0

    /** 白平衡面板是否在取景器里显示（横滑期间显示，抬手后收起） */
    private var wbVisible = false

    /** 这个时刻之前忽略取景器触摸：弹窗/设置项关闭后短暂的防误触窗口 */
    private var suppressTouchUntil = 0L

    /** 当前在场弹窗层数（>0 时取景器一律不响应触摸）。用计数不用布尔：
     *  冲洗参数弹窗里还会再开子选择弹窗，子窗关闭不该撤掉父窗的防护。 */
    private var dialogDepth = 0

    /** 取景器手势状态（提为字段，弹窗关闭时可强制复位，避免残余状态被新事件续上） */
    private var touchActive = false
    private var lastDownTime = -1L

    private fun applyWbIndex() {
        cameraController?.setAwbMode(wbPresets[wbIndex].awbMode)
        updateViewfinderIndicators(lastAfState, lastExposureSec)
    }

    /** 选中胶卷：切换 LUT + 更新图标。fromUser=false 用于启动时恢复设置（不做拦截）。 */
    private fun selectFilmRoll(roll: FilmRoll, fromUser: Boolean = true) {
        if (fromUser) {
            // 反转片拦截：与原项目一致，本版只支持负片印相纸
            if (!roll.available) {
                showLcdNotice("反转片不支持印相")
                vibrate(70, 200)
                return
            }
            // 换卷拦截：当前卷有未冲洗帧时禁止换胶卷（对应真机拍完必须回卷才能开盖）
            val active = UndevelopedFilmStore.currentRoll()
            val pending = active?.let { UndevelopedFilmStore.shotCount(it.rollId) } ?: 0
            if (active != null && pending > 0) {
                showLcdNotice("胶卷冲洗后才能换卷")
                // 被拦下来要有「机身顶住了」的手感：两连震，比单纯给提示更直接。
                // 强度给到接近满档——早先用系统默认强度在真机上偏软，顶不住的感觉出不来。
                vibrate(95, 235)
                binding.viewfinderFrame.postDelayed({ vibrate(95, 235) }, 140)
                return
            }
        }
        currentRoll = roll
        binding.tvFilmName.setImageResource(roll.iconResId)
        updateFilmInfo()
        saveSettings()
        // 选中一支新胶卷：给一记轻触确认，替换 LUT 的同时手上要有响应。
        // 时长比按键略长，像「卷芯卡到位」的一记闷响。
        if (fromUser) vibrate(50, 190)
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
    private var lastAfState: Int? = null

    private fun updateViewfinderIndicators(afState: Int?, exposureSec: Float) {
        if (exposureSec > 0f) lastExposureSec = exposureSec
        if (afState != null) lastAfState = afState
        val focus = when (afState) {
            2, 5 -> if (cameraController?.isAfLocked() == true)
                ViewfinderFrameView.LedMode.BLINK_SLOW else ViewfinderFrameView.LedMode.ON
            3, 6 -> ViewfinderFrameView.LedMode.BLINK_FAST
            else -> ViewfinderFrameView.LedMode.OFF
        }
        val lit = shutterTagsFor(lastExposureSec)
        val blink = lastExposureSec > 0f && lastExposureSec <= 1f / 1200f
        // WB 状态必须进 key：否则横滑换档时因为其它字段没变，被去重直接跳过不重画
        val key = "${focus.ordinal}|$exposureComp|${lit.joinToString(",")}|$blink|${flash.mode}|$wbVisible|$wbIndex"
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
                shutterBlink = blink,
                wbOn = wbVisible && flash.mode != FlashMode.OFF,
                wbIndex = wbIndex,
                wbCount = wbPresets.size
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
        // 调试用：连按 3 次音量上键循环切换横屏预览采样映射（0=原始 1=180 2=顺时针 3=逆时针）
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && DEBUG_ROTATE_TEST) {
            val next = (binding.glSurfaceView.debugRotateOverride + 1) % 4
            binding.glSurfaceView.debugRotateOverride = next
            android.widget.Toast.makeText(this, "预览映射测试: $next", android.widget.Toast.LENGTH_SHORT).show()
            android.util.Log.i("RotateTest", "override=$next")
            return true
        }
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

    /**
     * 触感反馈（统一入口，兼容 API 26/31+）。
     * amp<=0 时用 HAPTIC_AMP_DEFAULT。注意：早先这里走的是系统默认振幅（-1），
     * 在三星这类线性马达上表现为「若有若无」，整机的按键手感因此偏轻；
     * 现在默认档直接给到 170/255，敲击感才明显。
     */
    private fun vibrate(ms: Long, amp: Int = HAPTIC_AMP_DEFAULT) {
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                    as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val level = if (amp > 0) amp.coerceAtMost(255) else HAPTIC_AMP_DEFAULT
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(ms, level))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        } catch (_: Exception) {}
    }

    /**
     * 机身按键的点击触感：按下先给一记短震，再执行动作。
     * 真机上一个键按下去手指是有回馈的，纯视觉变化会让人不确定有没有按到；
     * 这里统一包一层，避免以后新增按键漏掉触感。
     * @param ms 震动时长；@param amp 强度（1-255，越大越实）
     */
    private fun android.view.View.hapticClick(ms: Long = 24, amp: Int = 175, action: () -> Unit) {
        setOnClickListener { vibrate(ms, amp); action() }
    }

    /**
     * 播放自动过卷声（res/raw/t3_advance.wav：真实 35mm 相机马达过卷录音，
     * 700ms / 44.1kHz / 16bit / 单声道，已淡入淡出并归一到 peak 0.85）。
     * 素材本身是真实录音，原先的合成音在手机上显得又尖又响，这里用较低的
     * 播放音量（ADVANCE_VOLUME）把"马达在机壳里闷响"的分寸压回来。
     * @param rate 播放速率。小于 1 会拉长时长并压低音调。
     */
    private fun playAdvanceSound(rate: Float = 1f, volume: Float = ADVANCE_VOLUME) {
        try {
            if (advanceSoundId != 0) {
                advanceSoundPool?.play(advanceSoundId, volume, volume, 1, 0, rate.coerceIn(0.5f, 2f))
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
        // 快门振动：快门键的「段落感」要比功能键更实，给满强度
        vibrate(38, 230)
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

    /** 自动过卷动画：拍照后模拟 T3 自动过卷（胶卷条滚动动画 + 过卷声 + 微弱震动），照片在后台处理 */
    private fun showDevelopingAnimation() {
        binding.developingOverlay.visibility = android.view.View.VISIBLE

        // 显示当前胶卷条，模拟胶片被马达拖过片门
        binding.ivDeveloping.setIcon(currentRoll.iconResId)
        binding.ivDeveloping.visibility = android.view.View.VISIBLE
        binding.tvDevelopingText.text = "FILM ADVANCING..."
        binding.tvDevelopingText.visibility = android.view.View.VISIBLE

        // 过卷等待动画：胶卷条匀速滚动，持续到处理+保存完成（胶片相机拍完需等冲洗，动画连贯不跳变）
        startDevelopingAnimation()

        // 过卷声音：真实 T3 的顺序是「叶片先喀一声曝光」→ 停顿一下 →「马达拖动齿轮过卷」，
        // 两段声音之间有明显间隔。快门素材本身只有 82ms，但人耳需要约 300ms 的静默
        // 才能把前后两个声音感知成"两件事"——之前 200ms 起播只留 118ms 间隙，
        // 用户反馈"没有停顿、不好听"。这里延后 420ms：快门结束后留出约 340ms 的
        // 明确静默，再让马达进场；0.75 速率把素材拉长到约 450ms、音调略沉
        // （更接近马达带负载的质感）。
        binding.ivDeveloping.postDelayed({
            if (isCapturing) playAdvanceSound()
        }, 420)

        // 过卷触感：马达带动胶卷时齿孔被齿轮一格一格啃过去——用连续的短促轻震
        // 模拟"卷在转"的颗粒感，与马达声同步起（420ms），收尾再补一记顿挫。
        startGearHaptics(fromMs = 420, toMs = 1060, stepMs = 58)

        // 750ms 后文案切到 PROCESSING...；真正的完成收尾由 onPhotoCaptured 回调 finally 里做
        binding.ivDeveloping.postDelayed({
            binding.tvDevelopingText.text = "PROCESSING..."
        }, 750)

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

    /**
     * 「齿轮过片」触感：在 [fromMs, toMs] 区间里按 stepMs 连续给短促轻震，
     * 模拟胶片齿孔被齿轮一格一格啃过去的手感（不是单次"嗡"一下），
     * 末尾再给一记稍明显的顿挫表示这一格走完了。
     */
    private fun startGearHaptics(fromMs: Long, toMs: Long, stepMs: Long) {
        var t = fromMs
        while (t <= toMs) {
            binding.ivDeveloping.postDelayed({ if (isCapturing) vibrate(18, 140) }, t)
            t += stepMs
        }
        binding.ivDeveloping.postDelayed({ if (isCapturing) vibrate(32, 210) }, toMs + 60)
    }

    /** 过卷等待动画：胶片带匀速被拖过片门，持续到后台处理+保存完成（连贯不跳变） */
    private fun startDevelopingAnimation() {
        stopDevelopingAnimation()
        // 900px/s：卷轴约 0.7s 转一圈，齿孔快速掠过——够快才看得出"卷在转"
        binding.ivDeveloping.startSpinning(3.2f)
    }

    private fun stopDevelopingAnimation() {
        binding.ivDeveloping.stopSpinning()
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
        /** 临时调试：允许音量上键循环切换横屏预览采样映射（默认关闭，避免抢快门） */
        private const val DEBUG_ROTATE_TEST = false

        /** 过卷声播放音量：真实录音 + 0.45 音量，避免像合成音那样"炸耳朵" */
        const val ADVANCE_VOLUME = 0.45f

        /**
         * 触感强度基准（1-255）。三星这类线性马达对低振幅短脉冲几乎不响应，
         * 低于约 1/3 强度基本无感，所以整机触感统一抬到这个档位再按场景微调。
         * - 轻触（滑动刻度/档位）：约 0.6 倍
         * - 常规按键：基准值
         * - 快门/拦截等重反馈：0.9 倍以上
         */
        const val HAPTIC_AMP_DEFAULT = 170

        // 「拍摄同款」Intent extras（写入方见 PhotoViewerActivity.shootSame）
        const val EXTRA_SAME_FILM = "same_film_stock"
        const val EXTRA_SAME_PAPER = "same_print_stock"
        const val EXTRA_SAME_GRAIN = "same_grain"
        const val EXTRA_SAME_HALATION = "same_halation"
        const val EXTRA_SAME_GLARE = "same_glare"
        const val EXTRA_SAME_EXPOSURE = "same_exposure"

        /**
         * 弹窗关闭后的取景器触摸屏蔽时长（ms）。
         * 弹窗是独立 Window，但关闭那一刻手指余下的事件会落到下面的取景器被当成点屏对焦。
         */
        const val DIALOG_TOUCH_GUARD_MS = 400L

        /** 套用完后要摘掉的 extras（避免横竖屏重建时重复套用 + 重复提示） */
        val EXTRA_SAME_KEYS = arrayOf(
            EXTRA_SAME_FILM, EXTRA_SAME_PAPER, EXTRA_SAME_GRAIN,
            EXTRA_SAME_HALATION, EXTRA_SAME_GLARE, EXTRA_SAME_EXPOSURE
        )
    }
}
