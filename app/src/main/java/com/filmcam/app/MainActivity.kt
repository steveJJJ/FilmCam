package com.filmcam.app

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.spektralab.engine.FilmStockMapper
import app.spektralab.engine.SpektraProcessor
import com.filmcam.app.camera.Camera2Controller
import com.filmcam.app.camera.FilmExposure
import com.filmcam.app.databinding.ActivityMainBinding
import com.filmcam.app.develop.DevelopController
import com.filmcam.app.develop.DeviceGeometryReport
import com.filmcam.app.develop.DeviceGeometryReportIo
import com.filmcam.app.film.UndevelopedFilmStore
import com.filmcam.app.filter.FilmRoll
import com.filmcam.app.filter.FilmRollAdapter
import com.filmcam.app.filter.FilmRollRepository
import com.filmcam.app.flash.FlashController
import com.filmcam.app.flash.FlashMode
import com.filmcam.app.ui.AdvanceRitual
import com.filmcam.app.ui.T3LcdView
import com.filmcam.app.ui.ViewfinderFrameView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Locale
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
    /** 慢门档位（胶片曝光模型）：关 / 自然 / 偏慢 —— 决定暗光下「糊一下」的概率 */
    private var slowShutterMode = FilmExposure.Mode.OFF
    /** 慢门触发机制：纯概率 / 纯真实 / 真实+轻随机（工程师模式可切） */
    private var slowTrigger = FilmExposure.Trigger.PROBABILITY
    /** 本次拍摄的参数快照 JSON（闪光/白平衡/EV/快门/失焦），拍照线程写入、落盘线程读取 */
    @Volatile private var lastShotJson = ""
    private var filmRollMode = false  // 胶卷模式开关（旧设置保留兼容，计数改由 UndevelopedFilmStore 权威）
    /** 触感强度档位：0=低 1=中 2=强（用户可在设置里切，见 vibrate() 的缩放系数） */
    private var hapticLevel = 1
    private var debugInstantDevelop = false  // 调试：拍完立即后台冲洗出片
    /** P3 RAW 负片（工程师控制台，默认关）：开着的快门同时收 RAW，DNG 作底片进引擎 RAW 会话 */
    private var rawNegativeEnabled = false
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

    /**
     * v63 内测诊断：进程启动时记一次设备 / 窗口基线。
     * 只读系统信息，不碰任何布局、曝光或渲染参数；失败静默。
     * 内测用户可在「关于 → 问题反馈」里一键复制 / 分享这份报告。
     */
    private fun recordDeviceBasics() {
        val dm = resources.displayMetrics
        val cfg = resources.configuration
        val pi = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val verCode = pi?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode
            else @Suppress("DEPRECATION") it.versionCode.toLong()
        } ?: -1L
        val rotation = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: -1
            else @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        }.getOrDefault(-1)
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val wm = getSystemService(android.view.WindowManager::class.java)
                val b = wm.currentWindowMetrics.bounds
                "${b.width()}x${b.height()}"
            }.getOrDefault("?")
        } else "n/a(api<30)"
        DeviceGeometryReport.putAll(
            "device.manufacturer" to Build.MANUFACTURER,
            "device.model" to Build.MODEL,
            "device.device" to Build.DEVICE,
            "device.brand" to Build.BRAND,
            "device.android" to Build.VERSION.RELEASE,
            "device.sdk" to Build.VERSION.SDK_INT,
            "app.versionCode" to verCode,
            "app.versionName" to (pi?.versionName ?: "?"),
            "window.boundsPx" to bounds,
            "window.displayPixels" to "${dm.widthPixels}x${dm.heightPixels}",
            "window.density" to "%.3f".format(Locale.US, dm.density),
            "window.densityDpi" to dm.densityDpi,
            "window.widthDp" to cfg.screenWidthDp,
            "window.heightDp" to cfg.screenHeightDp,
            "window.smallestWidthDp" to cfg.smallestScreenWidthDp,
            "window.orientation" to if (cfg.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            ) "landscape" else "portrait",
            "window.rotation" to rotation,
        )
        DeviceGeometryReportIo.flush(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UndevelopedFilmStore.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        runCatching { recordDeviceBasics() }

        // 边到边：顶栏/底栏推出状态栏与导航栏（相机预览保持全屏）。
        // 竖屏铭牌与底栏同为 bottom gravity，必须一起补同样的底部 inset，
        // 否则它会相对下移一个导航栏高度（v48 回归，v54 修复）。
        // 横屏里铭牌是 LinearLayout 子项，EdgeToEdge 会自动跳过。
        // v58：机身控件按「预览留白」自适应定位。布局里的 150dp / 48dp / 16dp
        // 是在 S25U 上调出来的固定值，短屏 / 大状态栏 / 三键导航 / 大显示尺寸
        // 都会把它们顶进预览画面。ChromeSafeArea 只在越界时收，基准机型逐像素等价。
        var sbTopPx = 0
        var sbBottomPx = 0
        var chromeDiagWritten = false
        val refitChrome = {
            val changed = com.filmcam.app.ui.ChromeSafeArea.apply(
                binding.root, binding.topBar, binding.bottomBar, binding.brandLogo,
                binding.lcdDisplay, binding.evKey, binding.settingsKey,
                binding.viewfinderFrame.contentRect(), sbTopPx, sbBottomPx
            )
            // v63：ChromeSafeArea 已把几何记进聚合器，这里补一次落盘（字段没变不会写）
            runCatching {
                DeviceGeometryReport.put("chrome.changed", changed)
                DeviceGeometryReportIo.flush(this)
            }
            // 一次性布局快照，写到外部 filesDir 便于 adb pull / 文件管理器导出：
            //   adb pull /sdcard/Android/data/com.filmcam.app/files/filmcam_layout.txt
            if (!chromeDiagWritten && sbTopPx > 0 && sbBottomPx > 0 &&
                binding.viewfinderFrame.width > 0
            ) {
                chromeDiagWritten = true
                runCatching {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    val logoM = (binding.brandLogo.layoutParams as? android.widget.FrameLayout.LayoutParams)?.bottomMargin
                    val barM = (binding.bottomBar.layoutParams as? android.widget.FrameLayout.LayoutParams)?.bottomMargin
                    java.io.File(dir, "filmcam_layout.txt").appendText(
                        "chrome changed=$changed " +
                            "view=${binding.root.width}x${binding.root.height} " +
                            "density=${resources.displayMetrics.density} " +
                            "sb=$sbTopPx/$sbBottomPx " +
                            "content=${binding.viewfinderFrame.contentRect()} " +
                            "topPad=${binding.topBar.paddingTop} logoMargin=$logoM barMargin=$barM\n"
                    )
                }
            }
            Unit
        }
        com.filmcam.app.ui.EdgeToEdge.setup(
            this, binding.topBar, binding.bottomBar, bottomAsMargin = true,
            extraBottomMarginViews = listOf(binding.brandLogo),
            onSystemBarInsets = { t, b, vt, vb ->
                sbTopPx = t
                sbBottomPx = b
                runCatching {
                    DeviceGeometryReport.putAll(
                        // v68：t/b 是 getInsetsIgnoringVisibility 的最大 inset（隐藏栏后不跳），
                        // vt/vb 是当前可见 inset；对比历史报告请认准这两个口径。
                        "insets.topPx" to t,
                        "insets.bottomPx" to b,
                        "insets.visibleTopPx" to vt,
                        "insets.visibleBottomPx" to vb,
                        "insets.immersive" to true,
                    )
                    DeviceGeometryReportIo.flush(this)
                }
                binding.root.post { refitChrome() }
            },
            // 只在取景器页隐藏系统栏：拍摄更沉浸，其余 4 页保留时间与通知
            immersive = true,
        )
        // 旋转 / 分辨率 / 显示大小变化后重新量一次（值不变时不写，不会自激循环）
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refitChrome() }

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
                    binding.tvFlashStatus.text = getString(R.string.main_external_status, status)
                    // 逆向轮转：每按一次快门换一条候选指令，编号从机顶 LCD 出来。
                    // 用户盯着外接灯，哪次亮了报编号，就能定位 0xFFE1 的触发字节。
                    if (status.startsWith("TRIG ")) {
                        showLcdNotice(status.removePrefix("TRIG "))
                    }
                }
            }
        }

        // 先恢复胶卷排序再读选中的胶卷索引：index 是位置语义，
        // 顺序不一样时同一个 index 指向的是另一支胶卷。
        FilmRollRepository.restore(this)
        // 恢复上次退出时保存的设置
        restoreSettings()
        // 机身配色（黑钛 / 钛银）要在所有控件建好之后再刷一次，
        // 这样从别的页面回来、或横竖屏重建时都能拿到当前档位。
        applyBodyTheme()
        // 语言档位以 AppCompat 的 LocaleManager 为准（框架会自动应用并重建）；
        // 这里再补一次是为了让「自己存的档位」与框架存储保持一致（例如旧版升级上来）。
        com.filmcam.app.ui.AppLocale.applySaved(this)
        // 刚切过语言：Activity 是重建出来的，取景器有一小段无归属窗口，继续压住触摸，
        // 免得重建瞬间的抬手事件被当成点屏对焦。
        if (SystemClock.uptimeMillis() - com.filmcam.app.ui.AppLocale.lastChangeMs < 2000) {
            suppressTouchUntil = SystemClock.uptimeMillis() + 800
        }

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

                    // 横滑手势：调白平衡（上下滑仍是 EV，竖直方向优先）。
                    // 以前只有闪光开启时才能调，但白平衡本来就是「换一片校色滤镜」，
                    // 与闪不闪光无关 —— 现在任何模式都能横滑换片。
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!wbDragging && Math.abs(dx) > 32f && Math.abs(dx) > Math.abs(dy) * 1.4f) {
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
        putDev("exposure_ev", EXTRA_SAME_EXPOSURE)
        intent.getStringExtra(EXTRA_SAME_PAPER)?.takeIf { it.isNotBlank() }?.let {
            editor.putString("filmcam_dev_paper_$stockId", it)
        }
        editor.apply()

        // 拍摄参数：闪光 / 白平衡 / 曝光补偿 ——「同款」不只是冲洗，还包括这张的光。
        // 以前只带冲洗参数，闪光+白平衡拍的片子点进来就变成一台没开闪、AUTO 白平衡的机子。
        intent.getStringExtra(EXTRA_SAME_FLASH)?.let { name ->
            runCatching { FlashMode.valueOf(name) }.getOrNull()?.let { m ->
                flash.mode = m
                binding.tvFlashStatus.text = when (m) {
                    FlashMode.OFF -> getString(R.string.main_flash_off)
                    FlashMode.BUILT_IN -> getString(R.string.main_flash_internal)
                    FlashMode.EXTERNAL -> getString(R.string.main_flash_external)
                }
                if (m == FlashMode.EXTERNAL) flash.startScan()
                flash.setBuiltInEnabled(m == FlashMode.BUILT_IN)
                applyExposure()
                updateLcd()
            }
        }
        intent.getIntExtra(EXTRA_SAME_WB, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
            ?.let { awb ->
                val idx = wbPresets.indexOfFirst { it.awbMode == awb }
                if (idx >= 0) {
                    wbIndex = idx
                    applyWbIndex()
                }
            }
        intent.getIntExtra(EXTRA_SAME_EV, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
            ?.let { setEv(it, persist = false) }
        // 输出风格：整卷统一的口味，同款要一并还原（印相 / Frontier / Noritsu）
        intent.getIntExtra(EXTRA_SAME_STYLE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
            ?.let { FilmStockMapper.saveOutputStyle(this, FilmStockMapper.OutputStyle.fromPrefs(it)) }
        // 套用的结果也写回 prefs：同款不是"只这一张"，用完仍保持这套设置
        saveSettings()
        lastIndicatorKey = ""
        updateViewfinderIndicators(null, lastExposureSec, lastAeIso)

        val roll = FilmRollRepository.rolls.firstOrNull {
            runCatching { FilmStockMapper.filmStockIdFor(it.name) }.getOrNull() == stockId
        }
        val rollName = runCatching { FilmStockMapper.stockDisplayName(stockId) }.getOrDefault(stockId)
        if (roll != null && roll != currentRoll) {
            if (currentShotCount() > 0) {
                if (announce) showLcdNotice(
                    getString(R.string.lcd_notice_roll_after_develop, rollName)
                )
            } else {
                selectFilmRoll(roll, fromUser = false)
            }
        }
        if (announce) showLcdNotice(getString(R.string.lcd_notice_same_params))

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
        hapticLevel = prefs.getInt("hapticLevel", 1).coerceIn(0, 2)
        slowShutterMode = FilmExposure.Mode.fromPrefs(prefs.getInt("slowShutter", 0))
        slowTrigger = FilmExposure.Trigger.fromPrefs(
            prefs.getInt("slowTrigger", FilmExposure.Trigger.PROBABILITY.prefs)
        )
        com.filmcam.app.ui.BodyTheme.load(this)
        com.filmcam.app.ui.AppLocale.load(this)
        // 旧 filmCount 键仅作迁移保留读取，不再作为权威（张数一律读 UndevelopedFilmStore.shotCount）
        @Suppress("UNUSED_VARIABLE") val legacyFilmCount = prefs.getInt("filmCount", 0)
        debugInstantDevelop = prefs.getBoolean("debug_instant_develop", false)
        rawNegativeEnabled = prefs.getBoolean("filmcam_dev_raw", false)
        flash.mode = try {
            FlashMode.valueOf(prefs.getString("flashMode", FlashMode.OFF.name) ?: FlashMode.OFF.name)
        } catch (_: Exception) { FlashMode.OFF }
        wbIndex = prefs.getInt("wbIndex", 0).coerceIn(0, wbPresets.size - 1)
        // 白平衡是常驻设置，与闪不闪光无关：恢复时一律下发，成片与预览一致
        cameraController?.setAwbMode(wbPresets[wbIndex].awbMode)
        if (flash.mode == FlashMode.BUILT_IN) {
            // 上次退出时开着内置闪光：相机建好会话后补挂（会话未建立时由 startCamera 兜底）
            cameraController?.setFlashEnabled(true)
        }
        // 上次退出时停在外置闪光档：以前只有「切档」和「拍摄同款」会去连灯，
        // 冷启动恢复的 EXTERNAL 只是 LCD 上写着 EXT，bleFlash 根本没建，
        // flash.trigger() 全程空转。startScan 已做幂等，这里补一次即可。
        if (flash.mode == FlashMode.EXTERNAL) {
            android.util.Log.i("FlashDiag", "restoreSettings 恢复 EXTERNAL，补建连")
            runCatching { flash.startScan() }
                .onFailure { android.util.Log.w("FlashDiag", "恢复建连失败", it) }
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
        prefs.putInt("hapticLevel", hapticLevel)
        prefs.putInt("slowShutter", slowShutterMode.prefs)
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

    /**
     * 机身配色落地：换的是「机身外壳」，不是按键和取景画面。
     * 真机银色版同样是黑色蓝宝石快门钮 + 黑色机身键 + 深色液晶窗口，
     * 所以这里只刷外壳底色、机身上蚀刻的文字/铭牌色、过卷遮罩与系统栏。
     */
    private fun applyBodyTheme() {
        val theme = com.filmcam.app.ui.BodyTheme
        // 先按底色把整棵树里的文字/面板刷一遍，最后再盖机身外壳
        // （外壳在钛银档是拉丝纹理，不是纯色，所以要在重映射之后单独设）
        theme.applyToViewTree(binding.root)
        binding.root.background = theme.bodyBackground()
        // 取景画面之外的留白是 GL 画的（GLSurfaceView 铺满全屏，会盖住 root 的底），
        // 机身配色必须同步给 GL，否则切到钛银只有系统栏变色、主页还是一片黑。
        binding.glSurfaceView.bodyTitan = theme.isTitan
        binding.developingOverlay.setBackgroundColor(theme.overlayColor)
        binding.tvDevelopingText.setTextColor(theme.onBodyText)
        binding.brandLogo.setImageResource(
            if (theme.isTitan) R.drawable.ic_t3_logo_ink else R.drawable.ic_t3_logo
        )
        theme.applySystemBars(window)
        // 桌面图标固定为 Contax T3 银色实拍图（AndroidManifest 单一静态入口），不跟机身配色联动。
        // 试过 activity-alias 动态切换：三星桌面在 setComponentEnabledSetting 后
        // 不重建图标，覆盖安装又常保留旧图缓存，真机上表现为「图标没变」。
    }

    /**
     * 设置面板（dialog_settings.xml）：做成机身上的一块铭牌 / 说明书内页，
     * 一行一项「名称 + 说明 + 当前读数」，而不是系统 AlertDialog 的条目列表。
     * 真机机顶的模式窗上，当前值是一眼就能读到的，不需要点进去才知道 ——
     * 这也是为什么"机身/日期/震动"用就地翻转：开关过程面板一直在场，
     * 不存在"弹窗消失的那几帧"，触摸不会漏到取景器被当成点屏对焦。
     */
    private fun showSettings() {
        suppressTouchUntil = SystemClock.uptimeMillis() + DIALOG_TOUCH_GUARD_MS
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val labels = resources.getStringArray(R.array.set_labels)
        val hints = resources.getStringArray(R.array.set_hints)
        val rows = intArrayOf(
            R.id.rowSetBody, R.id.rowSetDatestamp, R.id.rowSetHaptic,
            R.id.rowSetWb, R.id.rowSetLanguage, R.id.rowSetQuality,
            R.id.rowSetFrame, R.id.rowSetAbout
        )
        val theme = com.filmcam.app.ui.BodyTheme
        fun row(id: Int): android.view.View = view.findViewById(id)
        fun value(id: Int): android.widget.TextView = row(id).findViewById(R.id.tvSetValue)

        rows.forEachIndexed { i, id ->
            row(id).findViewById<android.widget.TextView>(R.id.tvSetLabel).text = labels[i]
            row(id).findViewById<android.widget.TextView>(R.id.tvSetHint).text = hints[i]
        }
        // 机身行右侧是「色版」—— 一块当前外壳色的圆点，比单看文字更直白。
        // 这两块装饰常驻 XML 只在这里点亮：运行时 addView 出来的控件会漏掉主题重映射。
        val swatch = row(R.id.rowSetBody).findViewById<android.view.View>(R.id.viewSetSwatch)
        swatch.visibility = android.view.View.VISIBLE
        val ticks = row(R.id.rowSetHaptic).findViewById<android.view.View>(R.id.setTicks)
        ticks.visibility = android.view.View.VISIBLE
        val tvClose = view.findViewById<android.widget.TextView>(R.id.tvSetClose)

        /**
         * 把面板刷成当前机身档位。重映射是幂等的（表里没有反向 key），
         * 所以切换机身色时可以连着刷好几次 —— 面板跟着机身当场变色，不需要重建弹窗。
         */
        fun paintPanel() {
            theme.applyToViewTree(view)
            rows.forEach { id ->
                row(id).findViewById<android.widget.ImageView>(R.id.ivSetChevron)
                    .setColorFilter(theme.onBodyTextSecondary)
            }
            view.findViewById<android.widget.ImageView>(R.id.ivSetLogo).setImageResource(
                if (theme.isTitan) R.drawable.ic_t3_logo_ink else R.drawable.ic_t3_logo
            )
            // 关闭 chip 是透明底 + 刻线框，字色得跟着面板底而不是机身底
            tvClose.setTextColor(theme.panelText)
            tvClose.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                cornerRadius = 2f * resources.displayMetrics.density
                setStroke(1, theme.cardStroke)
            }
            swatch.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(theme.bodyColor)
                setStroke(1, theme.cardStroke)
            }
            val level = hapticLevel.coerceIn(0, 2)
            val corner = 1.5f * resources.displayMetrics.density
            listOf(R.id.tick1, R.id.tick2, R.id.tick3).forEachIndexed { i, tid ->
                ticks.findViewById<android.view.View>(tid).background =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (i <= level) theme.accent else theme.trackBg)
                        cornerRadius = corner
                    }
            }
        }

        fun refreshValues() {
            value(R.id.rowSetBody).text = theme.name(this@MainActivity)
            value(R.id.rowSetDatestamp).text =
                if (dateStampEnabled) getString(R.string.common_on) else getString(R.string.common_off)
            value(R.id.rowSetHaptic).text = hapticLevelName()
            value(R.id.rowSetWb).text = wbName()
            value(R.id.rowSetLanguage).text = languageName()
            value(R.id.rowSetQuality).text = qualityName()
            value(R.id.rowSetFrame).text = frameBorderName()
            value(R.id.rowSetAbout).text = versionLabel()
            paintPanel()
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, theme.dialogTheme)
            .setView(view)
            .create()
        dialog.guardDialog()
        dialog.setOnShowListener {
            // 面板宽度：横屏时不至于撑满，竖屏时留出两侧余量；高度交回 wrap_content
            val dm = resources.displayMetrics
            val w = (dm.widthPixels * 0.86f).toInt().coerceAtMost((400 * dm.density).toInt())
            dialog.window?.setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            // Material 默认给 dialog 的是大圆角卡片，换成机加工面板的外框
            dialog.window?.setBackgroundDrawable(theme.panelDrawable())
        }

        refreshValues()

        row(R.id.rowSetBody).hapticClick {
            // 就地换外壳：面板不关，跟着机身一起翻色
            theme.cycle(this@MainActivity)
            applyBodyTheme()
            refreshValues()
            dialog.window?.setBackgroundDrawable(theme.panelDrawable())
        }
        row(R.id.rowSetDatestamp).hapticClick {
            dateStampEnabled = !dateStampEnabled
            saveSettings()
            refreshValues()
        }
        row(R.id.rowSetHaptic).hapticClick {
            hapticLevel = (hapticLevel + 1) % HAPTIC_LEVEL_SCALE.size
            saveSettings()
            refreshValues()
            // 按键感之后隔一下，再按「新档位」实震一回，让用户当场比出差别
            row(R.id.rowSetHaptic).postDelayed({ vibrate(30, 200) }, 180)
        }
        row(R.id.rowSetWb).hapticClick {
            // 白平衡：相当于换一片校色滤镜，AUTO → 钨丝灯 → 荧光灯 → 日光 → 阴天 → 阴影 → 黄昏。
            // 选好就一直是这一片，拍完不回弹（之前只在闪光下的横滑手势里能改，一松手就看不见了）
            wbIndex = (wbIndex + 1) % wbPresets.size
            applyWbIndex()
            saveSettings()
            refreshValues()
            lastIndicatorKey = ""   // 档位变了，取景器里的 WB 标记要立刻重画
            updateViewfinderIndicators(lastAfState, lastExposureSec, lastAeIso)
        }
        row(R.id.rowSetLanguage).hapticClick {
            // 切档会重建 Activity，弹窗必须走正常 dismiss（守卫计数归位）
            dialog.dismiss()
            com.filmcam.app.ui.AppLocale.cycle(this@MainActivity)
        }
        row(R.id.rowSetQuality).hapticClick {
            // 冲洗画质：就地翻转（完整 ↔ 标准），面板不关。
            // 只影响下次冲洗的成片分辨率，当前正在跑的冲洗不受影响。
            com.filmcam.app.develop.DevQuality.cycle(this@MainActivity)
            refreshValues()
        }
        row(R.id.rowSetFrame).hapticClick {
            // v69 三档直选：之前点一下就地循环，白边相框很容易被连点切走，
            // 用户只看到「白框没了」却不知道档位变了。现在弹出列表、当前档打勾，
            // 点哪档是哪档；选完立刻刷新面板读数并给一条即时反馈。
            // 白边相框就是列表里明确的一项，不会再被点走。
            val choices = FilmStockMapper.FrameFormat.choices
            val labels = choices.map { frameBorderName(it) }.toTypedArray()
            val current = FilmStockMapper.readFrameFormat(this@MainActivity)
            val checked = choices.indexOf(current).coerceAtLeast(0)
            val picker = androidx.appcompat.app.AlertDialog.Builder(this, theme.dialogTheme)
                .setTitle(R.string.set_frame)
                .setSingleChoiceItems(labels, checked) { d, which ->
                    val chosen = choices[which]
                    d.dismiss()
                    if (chosen != current) {
                        FilmStockMapper.saveFrameFormat(this@MainActivity, chosen)
                        refreshValues()
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            getString(R.string.set_frame) + "：" + frameBorderName(chosen),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        // 只有全画幅档走引擎片门自检：白边是 App 侧画的、关是纯画面。
                        // 正在冲洗时不插队：两边共用同一个引擎实例。
                        if (chosen.isOn && !developingNow()) probeFrameGate(chosen)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            picker.guardDialog()
            picker.setOnShowListener {
                val dm = resources.displayMetrics
                val w = (dm.widthPixels * 0.86f).toInt().coerceAtMost((400 * dm.density).toInt())
                picker.window?.setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                picker.window?.setBackgroundDrawable(theme.panelDrawable())
            }
            picker.show()
        }
        row(R.id.rowSetAbout).hapticClick {
            dialog.dismiss()
            startActivity(android.content.Intent(this, AboutActivity::class.java))
        }
        tvClose.hapticClick { dialog.dismiss() }

        dialog.show()
    }

    /** 设置面板「关于」行右侧的刻字版本号（v1.2.3） */
    private fun versionLabel(): String = runCatching {
        "v${packageManager.getPackageInfo(packageName, 0).versionName}"
    }.getOrDefault("")

    /** 震动强度当前档位名（低/中/强） */
    private fun hapticLevelName(): String = when (hapticLevel.coerceIn(0, 2)) {
        0 -> getString(R.string.haptic_low)
        1 -> getString(R.string.haptic_mid)
        else -> getString(R.string.haptic_strong)
    }

    /** 白平衡档位读数：AUTO / 钨丝灯 / 荧光灯 / 日光 / 阴天 / 阴影 / 黄昏 */
    private fun wbName(): String = wbPresets.getOrNull(wbIndex)?.label ?: "AUTO"

    /** 冲洗画质当前档位：完整（原始分辨率）/ 标准（约 1200 万像素，快约 3 倍） */
    private fun qualityName(): String = when (com.filmcam.app.develop.DevQuality.load(this)) {
        com.filmcam.app.develop.DevQuality.STANDARD -> getString(R.string.quality_standard)
        else -> getString(R.string.quality_full)
    }

    /** 设置面板「胶片相框」当前档位：关 / 全画幅 3:2 / 白边 */
    private fun frameBorderName(): String =
        frameBorderName(FilmStockMapper.readFrameFormat(this))

    /** 直选列表用：按指定档位取名，与面板读数同一套文案。 */
    private fun frameBorderName(format: FilmStockMapper.FrameFormat): String = when (format) {
        FilmStockMapper.FrameFormat.FULL -> getString(R.string.frame_full)
        FilmStockMapper.FrameFormat.WHITE -> getString(R.string.frame_white)
        else -> getString(R.string.common_off)
    }

    /**
     * 有没有一卷正在冲。抄的是"服务已经接过一卷、而且还没冲完"这个状态
     * （`phase` 的默认值就是 developing，光看 phase 会永远以为在冲）。
     */
    private fun developingNow(): Boolean {
        val st = com.filmcam.app.develop.DevelopmentState
        return st.rollId != null &&
            st.phase != com.filmcam.app.develop.DevelopmentState.PHASE_DONE &&
            st.phase != com.filmcam.app.develop.DevelopmentState.PHASE_ERROR
    }

    @Volatile
    private var frameGateProbing = false

    /**
     * 切到某个片框档位时，拿该档位片门比例的小图**真跑一遍**引擎，问清楚这机器认不认。
     *
     * 片门匹配是渲染期校验：参数会被爽快收下，冲到 solve 才抛
     * `overscan: a 1440 x 1080 frame is not the 135 gate's shape (36 x 24)`。
     * 冲洗前还有一次同样的自检（会退回另一格），这里只是让用户当场就知道结果。
     * 引擎初始化偏慢，放后台线程，结果落盘（spektra_frame_probe.txt）便于拉回来查。
     */
    private fun probeFrameGate(format: FilmStockMapper.FrameFormat) {
        if (frameGateProbing) return
        frameGateProbing = true
        Thread {
            try {
                val overscanFormat = format.overscanFormat ?: return@Thread
                val json = FilmStockMapper.buildParamsJson(
                    "kodak_portra_400", FilmStockMapper.DEFAULT_PRINT_FALLBACK,
                    FilmStockMapper.DevSettings(frameFormat = format),
                    includeStyleMark = false,
                    extraEntries = FilmStockMapper.frameBorderEntries(
                        "kodak_portra_400", 1, overscanFormat
                    ),
                )
                // 横竖两个朝向各问一遍：竖拍走的是竖置片门，引擎未必认
                val report = StringBuilder()
                var anyOk = false
                for (portrait in listOf(false, true)) {
                    val aspect = format.gateAspectFor(portrait)
                    val long = 96
                    val w = if (portrait) (long * aspect).toInt() else long
                    val h = if (portrait) long else (long / aspect).toInt()
                    val err = SpektraProcessor.probeRenderOk(
                        applicationContext, json, w.coerceAtLeast(1), h.coerceAtLeast(1)
                    )
                    report.append(if (portrait) "竖 ${w}x$h" else "横 ${w}x$h")
                        .append(" -> ").append(err ?: "OK").append('\n')
                    if (err == null) anyOk = true
                }
                // 同样是「给开发看的东西」：工程师模式关着就不落盘，日志照常
                if (DevDump.enabled(this)) runCatching {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    java.io.File(dir, "spektra_frame_probe.txt").writeText(
                        "gate=${format.key}\n$report"
                    )
                }
                Log.d("MainActivity", "片框自检 ${format.key}:\n$report")
                if (!anyOk) {
                    runOnUiThread {
                        Toast.makeText(
                            this, getString(R.string.set_frame_probe_failed), Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } finally {
                frameGateProbing = false
            }
        }.start()
    }

    /** 语言当前档位名：跟随系统 / 简体中文 / English */
    private fun languageName(): String = when (com.filmcam.app.ui.AppLocale.mode) {
        com.filmcam.app.ui.AppLocale.ZH -> getString(R.string.lang_zh)
        com.filmcam.app.ui.AppLocale.EN -> getString(R.string.lang_en)
        else -> getString(R.string.lang_system)
    }

    /** 胶卷拍满：引导去暗房冲洗 */
    private fun promptDevelopFullRoll() {
        androidx.appcompat.app.AlertDialog.Builder(this, com.filmcam.app.ui.BodyTheme.dialogTheme)
            .setTitle(getString(R.string.main_roll_finished_title))
            .setMessage(getString(R.string.main_roll_finished_body))
            .setPositiveButton(getString(R.string.main_roll_finished_develop)) { _, _ ->
                activeRollId()?.let { DevelopController.startDevelopment(this, it) }
            }
            .setNegativeButton(getString(R.string.main_roll_finished_later), null)
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
        captureSeq++              // 作废上一张残留的过卷回调
        captureStartMs = SystemClock.elapsedRealtime()
        binding.developingOverlay.visibility = android.view.View.VISIBLE
        binding.ivDeveloping.setIcon(currentRoll.iconResId)
        binding.ivDeveloping.visibility = android.view.View.VISIBLE
        binding.tvDevelopingText.text = "REWIND FILM..."
        binding.tvDevelopingText.visibility = android.view.View.VISIBLE

        playAdvanceSound()
        // 手动回卷：同样是齿孔一格一格退回去的手感，全程 1.4s 连续轻震
        startGearHaptics(captureSeq, fromMs = 0, toMs = 1400, stepMs = 58)

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
        androidx.appcompat.app.AlertDialog.Builder(this, com.filmcam.app.ui.BodyTheme.dialogTheme)
            .setTitle("FILMCAM")
            .setMessage(getString(R.string.main_guide_body))
            .setPositiveButton(getString(R.string.main_guide_start), null)
            .create()
            .guardDialog()
            .show()
    }


    /** 打开胶卷选择器（底部弹层） */
    /** 换卷弹层只负责「选哪支、排序、触感」，实现已挪到 ui/FilmRollPicker。 */
    private fun showFilmRollPicker() {
        com.filmcam.app.ui.FilmRollPicker.show(
            activity = this,
            onPick = { roll -> selectFilmRoll(roll) },
            onVibrate = { ms, amp -> vibrate(ms, amp) },
            guard = { d -> d.guardDialog() },
        )
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

    // lazy：label 要读本地化资源，必须在 Activity attach 之后才能取 getString
    private val wbPresets by lazy {
        listOf(
            WbPreset("AUTO", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO, WbIcon.AUTO),
            WbPreset(getString(R.string.main_wb_incandescent), android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, WbIcon.BULB),
            WbPreset(getString(R.string.main_wb_fluorescent), android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, WbIcon.TUBE),
            WbPreset(getString(R.string.main_wb_daylight), android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, WbIcon.SUN),
            WbPreset(getString(R.string.main_wb_cloudy), android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, WbIcon.CLOUD_SUN),
            WbPreset(getString(R.string.main_wb_shade), android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_SHADE, WbIcon.SHADE),
            WbPreset(getString(R.string.main_wb_twilight), android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_TWILIGHT, WbIcon.TWILIGHT)
        )
    }
    private var wbIndex = 0

    /** 白平衡面板是否在取景器里显示（横滑期间显示，抬手后收起） */
    private var wbVisible = false

    /** 这个时刻之前忽略取景器触摸：弹窗/设置项关闭后短暂的防误触窗口 */
    private var suppressTouchUntil = 0L

    /** 当前在场弹窗层数（>0 时取景器一律不响应触摸）。用计数不用布尔：
     *  工程师控制台里还会再开「冲洗参数」这种子窗，子窗关闭不该撤掉父窗的防护。 */
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
                showLcdNotice(getString(R.string.lcd_notice_slide_no_print))
                vibrate(70, 200)
                return
            }
            // 换卷拦截：当前卷有未冲洗帧时禁止换胶卷（对应真机拍完必须回卷才能开盖）
            val active = UndevelopedFilmStore.currentRoll()
            val pending = active?.let { UndevelopedFilmStore.shotCount(it.rollId) } ?: 0
            if (active != null && pending > 0) {
                showLcdNotice(getString(R.string.lcd_notice_develop_before_reload))
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
     * 应用曝光设置（2026-11-06 修订：外置闪光不再钉死手动曝光）。
     *
     * 旧逻辑把 EXTERNAL 档锁成 ISO200 / 1/60，前提是「灯闪那一瞬间快门正好开着」。
     * 但 FotorGear 的灯是灯身物理按键触发 + BLE HID 遥控快门：手机从收到
     * Volume Down 到真正开曝光还要约 270–330ms（真机 00:29 日志：09.931 按键 →
     * 10.270 拍照完成），16ms 的窗口根本套不住闪光脉冲，暗场里这档必然出黑片。
     *
     * 更糟的是这套手动参数因为相机重启丢设置（见 startCamera）从未真正生效过：
     * 三张原始 DNG 的 EXIF 是 ISO12/1/55、ISO90/1/32、ISO239/1/100，
     * 没有一张是 ISO200/1/60。00:29 那张黑片就是旋转重启后 AE 还在 SEARCHING
     * 时按了外接灯按键，AE 交出一组没收敛的欠曝参数。
     *
     * 现在三个档位统一走 AE + EV 补偿：不管外接灯有没有同步上，环境光那份曝光
     * 都由相机测光兜住，不会再出「AE 没收敛就硬拍」的黑片。
     *
     * EXTERNAL 档再叠一层：快门帧交给 [Camera2Controller.setExternalFlashSync] 锁到
     * 「闪光前的环境光基线」。真机 RAW 归一化证实灯的光**确实进了画面**，是 AE 在
     * 闪光峰值重新测光把曝光砍了 14.6 倍（场景只亮 2.88 倍）才压成黑片的 ——
     * 锁住测光后，闪光就是画面里那一记高光，而不是把整帧拖黑。
     * 冲洗侧仍按 ShotMeta.flash=EXTERNAL 走直闪变体（辉光/预闪），味道不丢。
     */
    private fun applyExposure() {
        try {
            cameraController?.setManualIso(null)
            cameraController?.setManualExposure(null)
            cameraController?.setExposureComp(exposureComp)
            cameraController?.setExternalFlashSync(flash.mode == FlashMode.EXTERNAL)
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
            Toast.makeText(this, getString(R.string.main_toast_develop_first), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.main_toast_no_roll_to_rewind), Toast.LENGTH_SHORT).show()
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
    private var lastAeIso = 0
    private var lastAfState: Int? = null

    private fun updateViewfinderIndicators(afState: Int?, exposureSec: Float, isoAe: Int = 0) {
        if (exposureSec > 0f) lastExposureSec = exposureSec
        if (isoAe > 0) lastAeIso = isoAe
        if (afState != null) lastAfState = afState
        val focus = when (afState) {
            2, 5 -> if (cameraController?.isAfLocked() == true)
                ViewfinderFrameView.LedMode.BLINK_SLOW else ViewfinderFrameView.LedMode.ON
            3, 6 -> ViewfinderFrameView.LedMode.BLINK_FAST
            else -> ViewfinderFrameView.LedMode.OFF
        }
        val lit = shutterTagsFor(lastExposureSec)
        val blink = lastExposureSec > 0f && lastExposureSec <= 1f / 1200f
        // 慢门警告（T3 手册里快门档位闪烁 = 当心手抖）：按胶卷算出来的快门已经慢到会抖
        val slowSec = filmShutterPreviewSec()
        val slowWarn = slowShutterMode != FilmExposure.Mode.OFF &&
            slowSec != null && slowSec >= 1f / 45f
        // WB 状态必须进 key：否则横滑换档时因为其它字段没变，被去重直接跳过不重画
        val key = "${focus.ordinal}|$exposureComp|${lit.joinToString(",")}|$blink|$slowWarn|" +
            "${flash.mode}|$wbVisible|$wbIndex"
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
                shutterSlow = slowWarn,
                // 白平衡标记：非 AUTO 时常驻（设置完就一直是这一片），横滑时也会亮出来
                wbOn = wbVisible || wbIndex != 0,
                wbIndex = wbIndex,
                wbCount = wbPresets.size
            )
        )
    }

    /** 胶卷档位（用户手动选了 ISO 就用用户的，否则按卷名里的 DX 感光度） */
    private fun effectiveFilmIso(): Int =
        isoValues[isoValue] ?: FilmExposure.isoFromRollName(currentRoll.name)

    /** 按胶卷算出来的这张「真机快门」（秒）；取景器用它决定要不要闪慢门警告 */
    private fun filmShutterPreviewSec(): Float? {
        val c = cameraController ?: return null
        val meter = c.meteringSnapshot() ?: return null
        val ev = FilmExposure.ev100(meter.first, meter.second, c.aperture()) ?: return null
        return FilmExposure.filmShutterSec(ev, effectiveFilmIso(), exposureComp)
    }

    /**
     * 生成本次拍摄的慢门方案。闪光开启时一律不慢门 ——
     * 真机闪光同步最快也就 1/60，闪光 + 长曝光只会拍成一团糊光。
     */
    private fun planSlowShutter(): FilmExposure.Plan? {
        if (slowShutterMode == FilmExposure.Mode.OFF) return null
        if (flash.mode != FlashMode.OFF) return null
        val c = cameraController ?: return null
        if (!c.supportsSlowShutter()) return null
        val meter = c.meteringSnapshot() ?: return null
        val (minSec, maxSec) = c.deviceExposureRangeSec()
        return FilmExposure.plan(
            tAeSec = meter.first,
            isoAe = meter.second,
            aperture = c.aperture(),
            filmIso = effectiveFilmIso(),
            mode = slowShutterMode,
            evCompSteps = exposureComp,
            deviceMinSec = minSec,
            deviceMaxSec = maxSec,
            deviceIsoRange = c.deviceIsoRange(),
            trigger = slowTrigger
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
            binding.filmInfoText.text = getString(
                R.string.main_film_info,
                currentRoll.name,
                FILM_MAX - currentShotCount()
            )
        } catch (_: Exception) {}
    }

    /** 循环切换闪光灯模式：OFF → BUILT_IN → EXTERNAL → OFF */
    private fun cycleFlashMode() {
        val from = flash.mode
        flash.mode = when (flash.mode) {
            FlashMode.OFF -> FlashMode.BUILT_IN
            FlashMode.BUILT_IN -> FlashMode.EXTERNAL
            FlashMode.EXTERNAL -> FlashMode.OFF
        }
        // v59：档位切换全程留痕。灯身物理键走 HID 音量键，排查时靠这条
        // 与 FlashDiag 的 keyDown / shutterDown 对齐，才能分清是「没切档」
        // 还是「切了档但灯没响」。
        android.util.Log.i("FlashDiag", "cycleFlashMode $from -> ${flash.mode}")
        binding.tvFlashStatus.text = when (flash.mode) {
            FlashMode.OFF -> getString(R.string.main_flash_off)
            FlashMode.BUILT_IN -> getString(R.string.main_flash_internal)
            FlashMode.EXTERNAL -> getString(R.string.main_flash_external)
        }
        if (flash.mode == FlashMode.EXTERNAL) {
            // 建连失败也不能吞掉后面的相机同步 / LCD / 落盘，否则看起来像「按键没反应」
            runCatching { flash.startScan() }
                .onFailure { android.util.Log.w("FlashDiag", "切档时建连失败", it) }
        }
        // 内置闪光灯档位变化要同步给相机：切进来 = 拍照闪光模式，切走 = 关掉
        flash.setBuiltInEnabled(flash.mode == FlashMode.BUILT_IN)
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
        binding.tvProToggle.text =
            if (proMode) getString(R.string.main_external_on) else getString(R.string.main_external_off)
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
        binding.tvIso.text =
            if (iso != null) getString(R.string.main_iso_value, iso) else getString(R.string.main_iso_auto)
        binding.tvShutter.text = when (exposureNs) {
            null -> getString(R.string.main_shutter_auto)
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
            Toast.makeText(this, getString(R.string.main_toast_settings_failed, e.message), Toast.LENGTH_SHORT).show()
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
        com.filmcam.app.develop.DevelopmentState.removeListener(devStateListener)
        super.onPause()
        saveSettings()  // 兜底：退出/后台时保存设置
        // 进后台：释放相机 + 暂停 GL 渲染，避免回前台时相机会话冲突导致预览卡死
        cameraController?.stop()
        cameraController = null
        binding.glSurfaceView.onPause()
    }

    /** v68：从通知栏 / 权限框 / 分屏返回时，系统栏会被系统重新显示，重新隐藏。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) com.filmcam.app.ui.EdgeToEdge.applyImmersive(this)
    }

    override fun onResume() {
        super.onResume()
        // v68：切后台 / 下拉通知 / 弹权限框后系统会恢复系统栏，回前台补一次隐藏
        com.filmcam.app.ui.EdgeToEdge.applyImmersive(this)
        // 回前台：恢复 GL，SurfaceTexture 重建后会自动触发 onSurfaceTextureReady → 重新启动相机
        binding.glSurfaceView.onResume()
        // v59：回前台时若仍停在外置档，补一次建连（startScan 幂等；
        // 若 onDestroy 曾 release 过，新建的 FlashController 会在这里把灯连回来）。
        if (flash.mode == FlashMode.EXTERNAL) {
            runCatching { flash.startScan() }
                .onFailure { android.util.Log.w("FlashDiag", "回前台建连失败", it) }
        }
        // 冲洗是后台干的：用户这会儿可能正在暗房看进度，也可能已经回到机身。
        // 「冲洗完成」这条状态汇报该从机顶 LCD 出来，而不是弹一个 Android 式吐司。
        com.filmcam.app.develop.DevelopmentState.addListener(devStateListener)
        announceDevelopedOnLcd()

        // 工程师模式里可能刚改过慢门触发机制/档位：回前台重新对齐，不必重启 App
        val prefs = getSharedPreferences("filmcam", MODE_PRIVATE)
        // RAW 负片开关同理：控制台里刚拨的，回到取景器就要生效
        rawNegativeEnabled = prefs.getBoolean("filmcam_dev_raw", false)
        cameraController?.rawNegativeEnabled = rawNegativeEnabled
        // onResume 回前台时相机可能已经在跑：同样检查 RAW 能力并从 LCD 告知
        if (rawNegativeEnabled && cameraController?.rawCapable == false) {
            showLcdNotice(getString(R.string.lcd_notice_raw_unavailable))
        }
        val newTrigger = FilmExposure.Trigger.fromPrefs(
            prefs.getInt("slowTrigger", FilmExposure.Trigger.PROBABILITY.prefs)
        )
        val newMode = FilmExposure.Mode.fromPrefs(prefs.getInt("slowShutter", 0))
        if (newTrigger != slowTrigger || newMode != slowShutterMode) {
            slowTrigger = newTrigger
            slowShutterMode = newMode
            lastIndicatorKey = ""   // 机制变了，取景器警告灯按新档重画
            updateViewfinderIndicators(null, lastExposureSec, lastAeIso)
        }
    }

    // ===== 机顶 LCD：后台冲洗完成通报 =====

    /** 已经通报过的那一卷（同一卷只通报一次，别每次回主页都重播） */
    private var lcdNotifiedRollId: String? = null

    private val devStateListener: () -> Unit = { announceDevelopedOnLcd() }

    /**
     * 把「冲洗完成」送到机顶 LCD。
     * 之前是 Toast（屏幕中下方一块浮层），跟机身没关系；暗房那边现在也不弹了 ——
     * 这条消息属于机身状态，从 LCD 出来才对：在暗房时 LCD 看不见（机身不在前台），
     * 回到机身第一眼就能看到「冲洗完成 出片 12 张」，几秒后自动恢复常规读数。
     */
    private fun announceDevelopedOnLcd() {
        val st = com.filmcam.app.develop.DevelopmentState
        if (st.phase != com.filmcam.app.develop.DevelopmentState.PHASE_DONE) return
        val rollId = st.rollId ?: return
        if (rollId == lcdNotifiedRollId) return
        lcdNotifiedRollId = rollId
        runOnUiThread {
            showLcdNotice(getString(R.string.lcd_notice_dev_done, st.done))
        }
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
            Toast.makeText(this, getString(R.string.main_toast_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCamera(surfaceTexture: SurfaceTexture) {
        cameraController = Camera2Controller(this)
        // RAW 负片开关在这里补挂：onResume 传开关时控制器还不存在（时序是
        // onResume → surface 就绪 → startCamera），冷启动开的开关传了个空
        android.util.Log.i("FilmCam", "startCamera: rawNegativeEnabled=$rawNegativeEnabled")
        cameraController?.rawNegativeEnabled = rawNegativeEnabled
        cameraController?.start(surfaceTexture)
        // ⚠️ 相机每次重建（旋转 / 切后台回来 / GL Surface 重建）都会 new 一个
        // Camera2Controller，所有拨盘状态都活在旧对象里。restoreSettings() 早于
        // startCamera 执行，那时 controller 还是 null，里面的 setAwbMode /
        // applyExposure 全是空操作。这里必须把机身状态重新压回新控制器，否则
        // 设置悄悄退回「AE 自动 + EV0 / AUTO 白平衡」：真机 00:29 那张外置闪光
        // 黑片就是旋转后 AE 未收敛造成的（EXIF ISO239 1/100）。
        // 顺序不能反：setFlashEnabled 在后面，ALWAYS_FLASH 才不会被 AE_ON 覆盖。
        cameraController?.setAwbMode(wbPresets[wbIndex].awbMode)
        applyExposure()
        // RAW 能力检测 + UI 明示：用户开了 RAW 负片但本机不支持时，
        // 从机顶 LCD 告知「已用 JPEG」——这和 T3 机身上的状态屏一致，
        // 不是弹个 Toast，是机身自己在说话。
        if (rawNegativeEnabled && cameraController?.rawCapable == false) {
            showLcdNotice(getString(R.string.lcd_notice_raw_unavailable))
        }
        // 桥接 FlashController → Camera2Controller：内置闪光交给相机 HAL（拍照闪光模式）
        flash.onBuiltInFlash = { enabled ->
            cameraController?.setFlashEnabled(enabled)
        }
        // 相机可能已经先于闪光灯档位恢复而建好会话，这里补一次当前档位
        cameraController?.setFlashEnabled(flash.mode == FlashMode.BUILT_IN)
        // 实时元数据 → 取景器内的 T3 指示灯（合焦灯 / 快门档位）
        cameraController?.onViewfinderMeta = { afState, exposureSec, isoAe ->
            updateViewfinderIndicators(afState, exposureSec, isoAe)
        }
        // 外置闪光：传感器开始曝光的那一刻才通过 BLE 让灯闪（0xFFE1: 05 xx）。
        // 提前发会错过曝光窗口；这一回调由 Camera2Controller 在 onCaptureStarted 触发。
        cameraController?.onExposureStarted = {
            // 不 post 回主线程：BLE 写入本身是异步提交，多一次队列就多几十毫秒，
            // 而曝光窗口只有 100ms。这里只发字节，不碰任何 View。
            if (flash.mode == FlashMode.EXTERNAL) flash.trigger()
        }
        // 全分辨率拍照回调：后台把原帧 JPEG 存入未冲洗胶卷（零处理，不做 LUT/日期戳/EXIF——那些在冲洗侧做）
        cameraController?.onPhotoCaptured = { bytes, rotation, rawDng ->
            saveExecutor.execute {
                var savedRollId: String? = null
                try {
                    // 字节直传：相机 ISP 输出的 JPEG 原样入卷，不解码、不旋转、不全尺寸重编码。
                    // 方向由 rotation 随帧记录，冲洗侧统一旋转（90° 整数倍，无损）。
                    // rawDng（有则）由 saveFrame 移进底片目录 negatives/<rollId>/ 作「底片」。
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
                        roll.rollId, bytes, System.currentTimeMillis(), iso, rotation,
                        shotJson = lastShotJson, rawDng = rawDng
                    )
                    runOnUiThread {
                        if (count >= FILM_MAX) {
                            promptDevelopFullRoll()
                        }
                        updateLcd()
                        updateFilmInfo()
                        // 拍完把白平衡再下发一次：HAL 在 STILL_CAPTURE 之后偶尔会把 AWB
                        // 拨回 AUTO，表现为「设置好的校色片拍一张就没了」。
                        applyWbIndex()
                        // 调试即时出片：拍完立即后台冲洗
                        if (debugInstantDevelop) {
                            savedRollId?.let { DevelopController.developRollInBackground(this, it) }
                        }
                    }
                } catch (e: Exception) {
                    // 底片没落上卷就不能留在缓存目录里（一张几十 MB，漏一张占一块地）
                    rawDng?.let { runCatching { it.delete() } }
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.main_toast_save_failed, e.message), Toast.LENGTH_LONG).show()
                    }
                } finally {
                    val seq = captureSeq
                    runOnUiThread {
                        // 只记账、不收尾：过卷仪式有自己的时长，走完才由 finishCapture 复位。
                        // 以前这里直接收尾，写入快的帧会把过卷声和动画一起吞掉。
                        scheduleFinishAfterRitual(seq)
                    }
                }
            }
        }
            binding.tvFlashStatus.text = getString(R.string.main_flash_off)
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
            android.widget.Toast.makeText(
                this,
                getString(R.string.main_toast_preview_map_test, next),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            android.util.Log.i("RotateTest", "override=$next")
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            // v59：灯身物理键 = HID Volume Down。这条日志能区分「灯键没送到」
            // 与「灯键送到了但拍照被守卫拦下」（卷满 / isCapturing 未复位）。
            android.util.Log.i(
                "FlashDiag",
                "keyDown=$keyCode mode=${flash.mode} capturing=$isCapturing " +
                    "shots=${currentShotCount()} -> triggerShutter"
            )
            triggerShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private var isCapturing = false

    /**
     * 过卷时序的「这一次拍摄」序号。
     *
     * 之前所有延时回调都以 `isCapturing` 为条件，而它在照片写完的瞬间就被置回 false ——
     * 于是出现「声音/动画被保存速度掐断」：写入快的帧，过卷声根本来不及播，遮罩也一闪
     * 而过，用户以为没拍上就再按一次，听起来就是「两声快门、没有过卷」。
     *
     * 真机不是这样：马达走完一格是固定的机械动作，跟底片多快曝光完无关。所以时序一律
     * 挂在序号上（序号一变，上一张的回调全部作废），而 `isCapturing` 只负责「能不能再按」。
     */
    private var captureSeq = 0
    /** 本次拍摄的起点（SystemClock.elapsedRealtime），所有时序都相对它排 */
    private var captureStartMs = 0L
    /** 本次过卷仪式的最短时长：照片写得再快，也要等它走完才收尾 */
    private var captureRitualMs = 0L
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 相对本次拍摄起点的定时（挂在序号上，不用 View.post —— 退到后台 View 会 detach，
     * 回调就再也不来了）。序号不匹配说明这张已经翻篇，回调直接作废。
     */
    private fun postAt(seq: Int, atMs: Long, action: () -> Unit) {
        val delay = (atMs - (SystemClock.elapsedRealtime() - captureStartMs)).coerceAtLeast(0L)
        mainHandler.postDelayed({ if (captureSeq == seq) action() }, delay)
    }

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
        captureSeq++              // 作废上一张残留的过卷回调
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
                val base = if (amp > 0) amp else HAPTIC_AMP_DEFAULT
                // 再乘用户在设置里选的强度档（低 0.55 / 中 1.0 / 强 1.45），
                // 最后夹到 [24, 255]：太低马达不响应，255 是 API 上限。
                val level = (base * HAPTIC_LEVEL_SCALE[hapticLevel.coerceIn(0, 2)])
                    .roundToInt().coerceIn(24, 255)
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
        val seq = ++captureSeq
        captureStartMs = SystemClock.elapsedRealtime()
        // 外置闪光时序排查：灯是物理按键触发，HID 事件到真正开曝光还有几百毫秒。
        // 把按键时刻打出来，才能和 FlashNotify / 拍照完成对齐，判断灯落在窗口内外。
        android.util.Log.i(
            "FlashDiag",
            "shutterDown seq=$seq mode=${flash.mode} ev=$exposureComp raw=$rawNegativeEnabled " +
                "at=$captureStartMs"
        )

        // 慢门决策：按胶卷曝光模型掷骰，命中才真的把这张拍糊
        val slow = planSlowShutter()
        val slowNs = if (slow != null && slow.applied) slow.exposureNs else 0L
        val slowIso = if (slow != null && slowNs > 0L) slow.iso else 0

        // 先把整条过卷时序排出来：真机上这是马达的机械动作，时长固定，
        // 不随照片多快写完而变。慢门要等曝光走完（闭帘）马达才过片。
        val timeline = AdvanceRitual.plan(slowNs)
        val closeAt = timeline.closeAtMs
        val advanceAt = timeline.advanceAtMs
        captureRitualMs = timeline.ritualMs

        playShutterFeedback()  // 开帘：快门声音 + 振动
        // 外置灯的 BLE 触发不再放在这里：灯会在 t+0 闪完，而 HAL 要 ~240ms
        // 后才真正开曝光，闪光根本进不了画面。改由 Camera2Controller 的
        // onExposureStarted 在传感器开始积分那一刻发指令（见 startCamera）。
        binding.btnShutter.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80)
            .withEndAction {
                binding.btnShutter.animate().scaleX(1f).scaleY(1f).duration = 80
            }

        if (slowNs > 0L) {
            // 慢门：快门还开着，得等曝光走完才有「闭帘」和过卷声。
            // 真机也是曝光结束马达才过片，这段等待本身就是慢门的仪式感。
            postAt(seq, closeAt) { playShutterCloseFeedback() }
            postAt(seq, closeAt + 40) { showDevelopingAnimation(seq, advanceAt) }
        } else {
            // 常规：快门 + 过卷一体声，过卷动画立即同步启动
            showDevelopingAnimation(seq, advanceAt)
        }

        // 闪光灯按键触发，立即拍照
        binding.glSurfaceView.post {
            try {
                // 模拟失焦：工程师模式设定的概率触发对焦不准（0-100%，0=关闭）。
                // 慢门本身已经是「废片」的一个来源，同一张再叠失焦就太狠了，概率减半。
                val prob = if (slowNs > 0L) softFocusProb / 2 else softFocusProb
                val defocus = softFocusEnabled && java.util.Random().nextInt(100) < prob
                // 这一张的拍摄参数随帧落盘（冲洗时写进 EXIF），拍摄同款要靠它复现
                lastShotJson = com.filmcam.app.film.ShotMeta(
                    flash = flash.mode.name,
                    awbMode = wbPresets[wbIndex].awbMode,
                    ev = exposureComp,
                    shutterSec = if (slowNs > 0L) (slow?.filmSec ?: 0f) else 0f,
                    slow = slowNs > 0L,
                    defocus = defocus,
                    // 这一张实际用的 35mm 视角裁切比：冲洗端按帧取它补裁 RAW，
                    // 不依赖「相机是否刚在这台机上初始化过」
                    zoom = cameraController?.currentZoomRatio ?: 0f
                ).toJson()
                cameraController?.takePicture(defocus, slowNs, slowIso)
                android.util.Log.i(
                    "SlowShutter",
                    "capture ev=${slow?.ev100} film=${slow?.let { FilmExposure.shutterLabel(it.filmSec) }}" +
                        " iso=${slow?.iso} applied=${slowNs > 0} exposureMs=${slowNs / 1_000_000} " +
                        "mode=$slowShutterMode trigger=$slowTrigger"
                )
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.main_toast_capture_error, e.message), Toast.LENGTH_LONG).show()
                finishCapture(seq)
            }
        }
    }

    /**
     * 「闭帘」反馈：慢门曝光结束时把快门声再补一记。
     * 音高略压、音量略低 —— 第一声是叶片打开，第二声是合上，不该和开帘一样响。
     */
    private fun playShutterCloseFeedback() {
        try {
            if (shutterSoundId != 0) {
                shutterSoundPool?.play(shutterSoundId, 0.82f, 0.82f, 1, 0, 0.94f)
            }
        } catch (_: Exception) {}
        vibrate(26, 190)
    }

    /**
     * 自动过卷动画：拍照后模拟 T3 自动过卷（胶卷条滚动动画 + 过卷声 + 微弱震动），照片在后台处理。
     * @param seq 本次拍摄序号（序号不对说明这张已经翻篇，整个过卷作废）
     * @param advanceAtMs 马达进场的时刻，相对本次拍摄起点
     */
    private fun showDevelopingAnimation(seq: Int, advanceAtMs: Long) {
        if (captureSeq != seq) return   // 慢门会把这段延迟到曝光结束，期间这一张可能已经翻篇
        binding.developingOverlay.visibility = android.view.View.VISIBLE

        // 显示当前胶卷条，模拟胶片被马达拖过片门
        binding.ivDeveloping.setIcon(currentRoll.iconResId)
        binding.ivDeveloping.visibility = android.view.View.VISIBLE
        binding.tvDevelopingText.text = "FILM ADVANCING..."
        binding.tvDevelopingText.visibility = android.view.View.VISIBLE

        // 过卷等待动画：胶卷条匀速滚动，持续到过卷走完（胶片相机拍完需等冲洗，动画连贯不跳变）
        startDevelopingAnimation()

        // 过卷声音：真实 T3 的顺序是「叶片先喀一声曝光」→ 停顿一下 →「马达拖动齿轮过卷」，
        // 两段声音之间有明显间隔。快门素材本身只有 82ms，但人耳需要约 300ms 的静默
        // 才能把前后两个声音感知成"两件事"——之前 200ms 起播只留 118ms 间隙，
        // 用户反馈"没有停顿、不好听"。这里延后 420ms：快门结束后留出约 340ms 的
        // 明确静默，再让马达进场；0.75 速率把素材拉长到约 450ms、音调略沉
        // （更接近马达带负载的质感）。
        // 慢门时 advanceAtMs 是「闭帘后 200ms」，节奏一样是「先静一下再让马达进场」。
        postAt(seq, advanceAtMs) { playAdvanceSound() }

        // 过卷触感：马达带动胶卷时齿孔被齿轮一格一格啃过去——用连续的短促轻震
        // 模拟"卷在转"的颗粒感，与马达声同步起，收尾再补一记顿挫。
        startGearHaptics(seq, fromMs = advanceAtMs, toMs = advanceAtMs + AdvanceRitual.GEAR_HAPTIC_SPAN_MS)

        // 文案切到 PROCESSING...：等马达进场之后再说（慢门时 750ms 还在曝光，别提前剧透）
        postAt(seq, maxOf(750L, advanceAtMs + 120)) {
            binding.tvDevelopingText.text = "PROCESSING..."
        }

        // 看门狗：相机回调迟迟不来（>20s）时兜底复位，避免卡死在过卷界面。
        // isCapturing 只在 finishCapture 里落回 false —— 这一张已经收过尾就别再弹「拍照超时」，
        // 否则每拍一张、20 秒后都会冒出一条凭空的超时提示（照片其实早就存好了）。
        postAt(seq, AdvanceRitual.WATCHDOG_MS) {
            if (!isCapturing) return@postAt
            // 真超时才走到这里：留一条日志，方便区分「相机回调真没回来」和旧版的凭空提示
            Log.w("Capture", "拍照超时：${AdvanceRitual.WATCHDOG_MS}ms 内没等到相机回调")
            finishCapture(seq)
            Toast.makeText(this, getString(R.string.main_toast_capture_timeout), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 「齿轮过片」触感：在 [fromMs, toMs] 区间里按 stepMs 连续给短促轻震，
     * 模拟胶片齿孔被齿轮一格一格啃过去的手感（不是单次"嗡"一下），
     * 末尾再给一记稍明显的顿挫表示这一格走完了。
     */
    private fun startGearHaptics(
        seq: Int, fromMs: Long, toMs: Long, stepMs: Long = AdvanceRitual.GEAR_HAPTIC_STEP_MS
    ) {
        var t = fromMs
        while (t <= toMs) {
            postAt(seq, t) { vibrate(18, 140) }
            t += stepMs
        }
        postAt(seq, toMs + 60) { vibrate(32, 210) }
    }

    /**
     * 收尾一次拍摄：停动画、收遮罩、放开快门。
     * 只在「还是这一次拍摄」时生效 —— 用户中途切走或又按了一次都不会误收。
     */
    private fun finishCapture(seq: Int) {
        if (captureSeq != seq) return
        stopDevelopingAnimation()
        binding.developingOverlay.visibility = android.view.View.GONE
        isCapturing = false
    }

    /**
     * 照片写完后调用：真机上马达走完一格才允许拍下一张，所以这里**不立刻收尾**，
     * 而是补上过卷仪式剩下的时间（写入快的帧以前就是在这里把过卷声整个吞掉的）。
     */
    private fun scheduleFinishAfterRitual(seq: Int) {
        val elapsed = SystemClock.elapsedRealtime() - captureStartMs
        val wait = AdvanceRitual.finishDelayMs(elapsed, captureRitualMs)
        mainHandler.postDelayed({ finishCapture(seq) }, wait)
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

        /** 过卷时序的常数都在 com.filmcam.app.ui.AdvanceRitual（那边有单测兜着） */

        /**
         * 触感强度基准（1-255）。三星这类线性马达对低振幅短脉冲几乎不响应，
         * 低于约 1/3 强度基本无感，所以整机触感统一抬到这个档位再按场景微调。
         * - 轻触（滑动刻度/档位）：约 0.6 倍
         * - 常规按键：基准值
         * - 快门/拦截等重反馈：0.9 倍以上
         */
        const val HAPTIC_AMP_DEFAULT = 170

        /** 设置里「震动强度」三档的振幅系数：低 / 中 / 强 */
        val HAPTIC_LEVEL_SCALE = floatArrayOf(0.55f, 1.0f, 1.45f)
        /** 震动强度档位名（已改用 hapticLevelName() 取本地化文案，这里仅作旧引用保留） */
        @Suppress("unused") val HAPTIC_LEVEL_NAMES = arrayOf("低", "中", "强")

        // 「拍摄同款」Intent extras（写入方见 PhotoViewerActivity.shootSame）
        const val EXTRA_SAME_FILM = "same_film_stock"
        const val EXTRA_SAME_PAPER = "same_print_stock"
        const val EXTRA_SAME_GRAIN = "same_grain"
        const val EXTRA_SAME_HALATION = "same_halation"
        const val EXTRA_SAME_GLARE = "same_glare"
        const val EXTRA_SAME_EXPOSURE = "same_exposure"
        /** 闪光模式（OFF / BUILT_IN / EXTERNAL） */
        const val EXTRA_SAME_FLASH = "same_flash"
        /** 白平衡的 CONTROL_AWB_MODE_* 常量 */
        const val EXTRA_SAME_WB = "same_wb"
        /** 曝光补偿档位 */
        const val EXTRA_SAME_EV = "same_ev"
        /** 成片出口档位（OutputStyle.prefs；缺省 = 印相） */
        const val EXTRA_SAME_STYLE = "same_output_style"

        /**
         * 弹窗关闭后的取景器触摸屏蔽时长（ms）。
         * 弹窗是独立 Window，但关闭那一刻手指余下的事件会落到下面的取景器被当成点屏对焦。
         */
        const val DIALOG_TOUCH_GUARD_MS = 400L

        /** 套用完后要摘掉的 extras（避免横竖屏重建时重复套用 + 重复提示） */
        val EXTRA_SAME_KEYS = arrayOf(
            EXTRA_SAME_FILM, EXTRA_SAME_PAPER, EXTRA_SAME_GRAIN,
            EXTRA_SAME_HALATION, EXTRA_SAME_GLARE, EXTRA_SAME_EXPOSURE,
            EXTRA_SAME_FLASH, EXTRA_SAME_WB, EXTRA_SAME_EV, EXTRA_SAME_STYLE
        )
    }
}
