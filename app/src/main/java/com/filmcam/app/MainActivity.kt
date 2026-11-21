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
import com.filmcam.app.ui.attachEdgeHaptics
import com.filmcam.app.ui.Motion
import com.filmcam.app.ui.PermissionNameplate
import com.filmcam.app.ui.attachPressFeedback
import com.filmcam.app.ui.animatePanelIn
import com.filmcam.app.ui.fadeInChrome
import com.filmcam.app.ui.dismissPanel
import com.filmcam.app.ui.attachOutsideDismiss
import com.filmcam.app.ui.attachRowPress
import com.filmcam.app.ui.setTextWithRoll
import com.filmcam.app.ui.T3LcdView
import com.filmcam.app.ui.ViewfinderFrameView
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
    /**
     * vc76 B：Manifest 声明 configChanges 后旋转不再重建 Activity，但系统也**不会**
     * 重选 layout-land。机身 chrome（顶栏/铭牌/底栏）由 onConfigurationChanged 手动换，
     * 这个标记记录当前 root 上挂的是哪一套，避免同朝向 180° 翻转也去换一遍。
     */
    private var chromeLandscape = false
    /** 系统栏 inset（忽略可见性口径），由 setupEdgeToEdge 回写、refitChrome 消费。 */
    private var sbTopPx = 0
    private var sbBottomPx = 0
    private var chromeDiagWritten = false
    private var cameraController: Camera2Controller? = null
    private lateinit var flash: FlashController
    private var shutterSoundPool: android.media.SoundPool? = null
    private var shutterSoundId = 0
    private var advanceSoundPool: android.media.SoundPool? = null
    private var advanceSoundId = 0
    private var currentRoll: FilmRoll = FilmRollRepository.rolls[0]
    /** 外置氙气灯回电提示时长（实测 2.6s 间隔仍偏弱，故给 4s）。 */
    private val FLASH_RECHARGE_HINT_MS = 4000L
    /** 拍照保存单线程池：保证帧写入顺序，避免连拍时多个裸线程并发写 */
    private val saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** 日期水印：11-13 起新装默认关；老用户已存过的档位照旧。 */
    private var dateStampEnabled = DefaultSettings.DATE_STAMP_ENABLED
    private var exposureComp = 0  // 曝光补偿 -3 ~ +3（OFF / INT 档的整数 AE 补偿）
    // EXT 档专用：切到外闪后机顶 EV 拨盘就地变成闪光灯细档补偿（0.5 EV/档）。
    // 两套值分开存，切档互不覆盖；默认 0 = 不改变原有出片。
    private var flashCompEv = 0f
    // 腰平取景（折叠屏彩蛋）：总闸在工程模式（off / auto / simulate，默认 auto）。
    // waistApplied 表示当前确实处于半屏分区，refitChrome 在此期间不能按整屏尺寸重算。
    private var waistEgg = "auto"
    private var waistApplied = false
    /**
     * 本次半开已被用户收起：直到重新展平才允许再次自动进入。
     * 否则 CLOSE / 返回键刚退出，下一帧 FoldingFeature 又会把它拉回来。
     */
    private var waistDismissed = false
    /** 最近一次窗口布局里的折痕（窗口坐标）；展平 / 直板机 = 全部复位。 */
    private var foldHalfOpened = false
    private var foldSeparating = false
    private var foldHinge = com.filmcam.app.ui.FoldPosture.Hinge.NONE
    private var foldBounds: android.graphics.Rect? = null
    /**
     * 腰平画幅档：AUTO 按取景窗几何自动判定（方窗→6×6、宽窗→3:2、竖窗→2:3），
     * SQUARE/STANDARD 手动覆盖（误判兜底）。画幅跟取景窗走，成片形状 = 取景窗形状。
     */
    private var waistGateStyle = com.filmcam.app.ui.FoldPosture.GateStyle.AUTO
    /** 当前生效的腰平画幅（w/h，来自 posture.gateAspect）；0 = 未在腰平态。 */
    private var waistGateAspect = 0f
    /** 腰平态才启用的返回键回调：按下=收起腰平，不退 App（退出通道②）。 */
    private var waistBackCallback: androidx.activity.OnBackPressedCallback? = null
    private var softFocusEnabled = DefaultSettings.SOFT_FOCUS_ENABLED  // 模拟失焦：2% 默认开
    private var softFocusProb = DefaultSettings.SOFT_FOCUS_PROB         // 0-100（工程师可调；0=关）
    /** 慢门档位（胶片曝光模型）：关 / 自然 / 偏慢 —— 决定暗光下「糊一下」的概率 */
    private var slowShutterMode = DefaultSettings.slowMode
    /** 慢门触发机制：纯概率 / 纯真实 / 真实+轻随机（工程师模式可切） */
    private var slowTrigger = DefaultSettings.slowTrigger
    /** 本次拍摄的参数快照 JSON（闪光/白平衡/EV/快门/失焦），拍照线程写入、落盘线程读取 */
    @Volatile private var lastShotJson = ""
    private var filmRollMode = false  // 胶卷模式开关（旧设置保留兼容，计数改由 UndevelopedFilmStore 权威）
    /** 触感强度档位：0=低 1=中 2=强（用户可在设置里切，见 vibrate() 的缩放系数） */
    private var hapticLevel = 1
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

    /**
     * 被拒一次就不再反复拉系统权限框：铭牌已经把「去哪开」说清楚了，
     * 再弹只会形成「拒绝→重启→再弹」的死循环。相机仍是必需项，
     * 这个标记只挡重复申请，不放行无权限拍照。
     */
    private var permissionDeniedHandled = false

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
        chromeLandscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        runCatching { recordDeviceBasics() }

        // 边到边：顶栏/底栏推出状态栏与导航栏（相机预览保持全屏）。
        // 竖屏铭牌与底栏同为 bottom gravity，必须一起补同样的底部 inset，
        // 否则它会相对下移一个导航栏高度（v48 回归，v54 修复）。
        // 横屏里铭牌是 LinearLayout 子项，EdgeToEdge 会自动跳过。
        // v58：机身控件按「预览留白」自适应定位。布局里的 150dp / 48dp / 16dp
        // 是在 S25U 上调出来的固定值，短屏 / 大状态栏 / 三键导航 / 大显示尺寸
        // 都会把它们顶进预览画面。ChromeSafeArea 只在越界时收，基准机型逐像素等价。
        setupEdgeToEdge()
        // 旋转 / 分辨率 / 显示大小变化后重新量一次（值不变时不写，不会自激循环）。
        // 只挂在 root 上：root 及其上的 GL Surface 在换 chrome 时始终不换实例。
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refitChrome() }

        // 等待 GL 线程就绪，拿到 SurfaceTexture 后启动 Camera2。
        // vc76 B：这个回调只在 GL Surface 首次创建 / 重建时触发；旋转不重建 Activity，
        // GL Surface 也就不重建，相机会话全程不关。
        binding.glSurfaceView.onSurfaceTextureReady = { surfaceTexture ->
            runOnUiThread { startCamera(surfaceTexture) }
        }

        // 机身控件监听：冷启动、以及朝向切换换新 chrome 后都走同一套（幂等）
        wireChrome()

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
            // 外闪状态串进诊断报告：TRIGGER_SENT / TRIGGER_FAIL / TRIGGER_SKIP NOT_READY
            // 三类结果直接决定「是没连上」还是「连上了但写不进去」。
            runCatching { com.filmcam.app.develop.DeviceGeometryReport.put("flash.extStatus", status) }
            runOnUiThread {
                // 只在外置闪光模式时显示 BLE 状态
                if (flash.mode == FlashMode.EXTERNAL) {
                    // vc86：有已学档案时把灯名带进状态字，用户一眼知道连的是哪盏灯，
                    // 而不是泛泛的 READY / STATE: 2。
                    val pairedName = runCatching {
                        com.filmcam.app.flash.FlashProfileStore(this@MainActivity)
                            .list().firstOrNull()?.displayName
                    }.getOrNull()
                    binding.tvFlashStatus.text = if (pairedName.isNullOrBlank()) {
                        getString(R.string.main_external_status, status)
                    } else {
                        getString(R.string.main_external_status_named, pairedName, status)
                    }
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

        updateLcd()
        updateFilmInfo()
        // 退出通道②：返回键只在腰平分区时启用，交给 applyWaistLevel 按 waistApplied 开关。
        waistBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                setWaistLevel(false)
            }
        }.also { onBackPressedDispatcher.addCallback(this, it) }
        // 恢复腰平彩蛋总闸（prefs 里的工程模式三态），再开始监听折痕
        applyWaistLevel()
        observeFolding()

        // 点屏对焦 + 上下滑调曝光（GCam 风格）
        // 滑动过程只在 EV 整数档位真正变化时才下发相机参数（最多 6 次），
        // 落盘统一放到抬手时做一次，避免每移动一像素就重建请求 + 写 SharedPreferences。
        // 位移小于阈值视为点按 → 触发点屏对焦（对焦框动画 + 成功震动）。
        var touchStartX = 0f
        var touchStartY = 0f
        var touchStartEv = 0
        var touchStartFlashEv = 0f
        var touchMoved = false
        var wbDragging = false          // 本次手势是否已进入横滑调白平衡
        var touchStartWbIndex = 0
        // 本次手势是否从「机身铭牌那一行」起手（竖屏 ISO 横带）。
        var gestureInIsoBand = false
        var isoDragging = false         // 本次手势是否已进入横滑调 ISO
        var isoDragAcc = 0f
        var touchStartLocalX = 0f
        // 本次手势是否从「取景框」（预览内容矩形）里起手。
        // 顶栏铭牌 / 机顶 LCD / 底栏快门条 / letterbox 黑边上的上下滑不该动 EV，
        // 横滑也不该换白平衡 —— 之前手势挂在全屏 glSurfaceView 上，全屏都能拨档。
        // 口径与点屏对焦一致：viewfinderFrame.contentRect()。
        var gestureInContent = false
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
                    // 点屏对焦：不做任何视觉标记，合焦后仅给一次短震动。
                    // 腰平态传画幅比：预览是裁切填充（不是 letterbox），对焦映射走同一套裁切公式。
                    cameraController?.focusAt(
                        fx, fy,
                        binding.glSurfaceView.width, binding.glSurfaceView.height,
                        onFocused = {
                            vibrate(24, 185)
                            // v82 段 2 检测点：新手第一次对焦成功 → LCD 接力下一条
                            if (OnboardingGuide.onFocusDone(this@MainActivity)) showOnboardingHint()
                        },
                        waistGateAspect = if (waistApplied) waistGateAspect else 0f,
                    )
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
                    touchMoved = false
                    wbDragging = false
                    // 起手点不在取景框内 → 整条序列都不拨 EV / 白平衡。
                    // tapDetector 自己还有 contentRect 判定，隔着顶栏也不会对焦。
                    gestureInContent = binding.viewfinderFrame.contentRect()
                        .contains(event.x, event.y)
                    // 🔴 11-20 用户：快门刻度/横屏 ISO 竖带上滑动误触 EV。
                    // 这些控制区在内容矩形右缘，点稍偏出命中区就穿透到这里被当成上下滑。
                    // Pro 模式下起手点落在控制区附近就让路给 viewfinderFrame，不认 EV/WB。
                    if (gestureInContent && proMode &&
                        binding.viewfinderFrame.isProControlZone(event.x, event.y)
                    ) {
                        gestureInContent = false
                    }
                    if (!gestureInContent) return@setOnTouchListener true
                    touchActive = true
                    lastDownTime = event.downTime
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    touchStartEv = exposureComp
                    touchStartFlashEv = flashCompEv
                    touchStartWbIndex = wbIndex
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!touchActive || !gestureInContent) return@setOnTouchListener true
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
                    // 🔴 11-20 定稿：只有快门刻度那一列（ViewfinderFrameView 命中区 +
                    // isProControlZone 让路）单独控制快门；取景窗其它区域的上下滑仍是
                    // 曝光补偿 / 闪光补偿。不要再把手势扩散到整个取景窗——那会把 EV 抹掉。
                    if (flash.mode == FlashMode.EXTERNAL) {
                        // EXT 档：同一个拨盘每 100px 走一档（0.3 EV）
                        val next = quantizeFlashEv(touchStartFlashEv + delta.toInt() * 0.3f)
                        if (next != flashCompEv) {
                            flashCompEv = next
                            applyFlashCompUi(persist = false)  // 抬手时统一 saveSettings
                            vibrate(14, 115)
                            if (OnboardingGuide.onEvChanged(this)) showOnboardingHint()
                        }
                    } else {
                        val newEv = (touchStartEv + delta.toInt()).coerceIn(-3, 3)
                        if (newEv != exposureComp) {
                            // 统一入口：值 → 按钮数字 → LCD → 取景器 → 相机参数全同步
                            setEv(newEv, persist = false)
                            vibrate(14, 115)
                            if (OnboardingGuide.onEvChanged(this)) showOnboardingHint()
                        }
                    }
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    // 没有自己 DOWN 的孤立抬手（弹窗点击飘过来的）直接丢弃；
                    // 起手点在取景框外的手势同样什么都不做（不落盘、不擦 WB 提示）
                    if (!touchActive || !gestureInContent) return@setOnTouchListener true
                    touchActive = false
                    if (wbDragging) {
                        if (wbIndex != touchStartWbIndex) saveSettings()
                        wbDragging = false
                        // 抬手不立刻擦掉：缓 900ms 让人看清落在哪一档，再淡出
                        binding.viewfinderFrame.postDelayed({
                            wbVisible = false
                            lastIndicatorKey = ""   // 强制下一次去重放过，允许重画
                            updateViewfinderIndicators(lastAfState, lastExposureSec)
                            updateWaistWbReadout()   // 腰平取景窗读数同步收起
                        }, 900)
                        return@setOnTouchListener true
                    }
                    if (flash.mode == FlashMode.EXTERNAL) {
                        if (flashCompEv != touchStartFlashEv) saveSettings()
                    } else if (exposureComp != touchStartEv) saveSettings()
                    // 点按对焦已交给 tapDetector（只在完整手势序列上触发）
                }
            }
            true
        }

        // 机身按键监听统一在 wireChrome() 里绑（朝向切换换新 chrome 时会重绑）。

        // 「拍摄同款」从暗房带参数跳回（进程被杀后冷启动也走这里）
        // 参数每次都照常套用（幂等），但机身提示只在「真正新的一次跳转」给：
        // vc76 起横竖屏切换不再重建 Activity，savedInstanceState != null 只剩
        // 进程被杀后恢复 / 语言·显示大小变化重建这几种情况，仍不该重复提示。
        // Activity 重建时旧弹窗已随实例销毁，这里必须复位，否则取景器会永久失联
        dialogDepth = 0
        applySameRecipe(intent, announce = savedInstanceState == null)
    }

    /**
     * vc76 B：Manifest 给 MainActivity 声明了 configChanges，旋转不再销毁重建，
     * 相机 / GL Surface / 拨盘状态全程保留。但系统**不会**替已 inflate 的 View
     * 重新选 `layout-land` —— 横竖两套机身 chrome 必须在这里手动换。
     *
     * 只换三块（topBar / brandLogo / bottomBar）：glSurfaceView、viewfinderFrame、
     * 隐藏控件与过卷遮罩在两套 XML 里完全一致，原样留在 root 上，因此相机会话、
     * SurfaceTexture、触摸监听、过卷动画引用都不动。
     *
     * 成片方向 / 点屏对焦 / GL uMode 都是实时读 display.rotation 的
     * （Camera2Controller.jpegRotationDegrees、focusAt、LutGLSurfaceView.onDrawFrame），
     * 不依赖 Activity 重建，所以 B 不动片门口径、不改 EXIF、不废横屏布局。
     * 同一朝向内的 180° 翻转、键盘、折叠屏尺寸变化不换 chrome，只重算几何。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val landscape = newConfig.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (landscape == chromeLandscape) {
            // 同族方向变化：layout 资源不变，只重采诊断 + 重算安全区
            runCatching { recordDeviceBasics() }
            binding.root.post { applyWaistLevel() }
            return
        }
        chromeLandscape = landscape
        val swapped = runCatching { swapChromeForOrientation() }
            .onFailure { android.util.Log.w("FilmCam", "swapChrome failed", it) }
            .isSuccess
        if (!swapped) {
            // 换 chrome 失败就保持现状（相机仍不关），只重采诊断，别把半套状态再刷一遍
            runCatching { recordDeviceBasics() }
            binding.root.post { applyWaistLevel() }
            return
        }
        // 新 chrome 是全新 View 实例：inset 补偿基准、机身配色、读数都要重新压回去
        setupEdgeToEdge()
        applyBodyTheme()
        updateLcd()
        updateFilmInfo()
        runCatching { recordDeviceBasics() }
        binding.root.post { applyWaistLevel() }
    }

    /**
     * 按当前 resources.configuration 重新解析 activity_main（自动落到 layout / layout-land），
     * 只把机身 chrome 三块搬进正在显示的 root。绝不 touch glSurfaceView。
     */
    private fun swapChromeForOrientation() {
        val root = binding.root
        val frame = binding.viewfinderFrame
        val oldTop = binding.topBar
        val oldBar = binding.bottomBar
        val oldLogo = root.findViewById<android.view.View>(R.id.brandLogo)
        // 🔴 竖屏时铭牌（proBrandRow）是 root 的直接子，换横屏不会被 topBar/bottomBar 带走，
        // 会留在屏幕上 —— 它的 gravity 是 bottom|center_horizontal，横屏下就跑到
        // **取景窗正中间**，看着像预览里浮着一个 CONTAX T3（11-19 真机）。
        // 所以要跟其它 chrome 一起摘掉。
        val oldBrandRow = root.findViewById<android.view.View>(R.id.proBrandRow)

        val fresh = layoutInflater.inflate(R.layout.activity_main, null) as android.widget.FrameLayout
        val newTop = fresh.findViewById<android.view.View>(R.id.topBar)
        val newBar = fresh.findViewById<android.view.View>(R.id.bottomBar)
        val newLogo = fresh.findViewById<android.view.View>(R.id.brandLogo)
        // 铭牌：竖屏时它是 root 的直接子（要单独搬），横屏时它在 topBar 内（随 topBar 走）。
        // 少了这一步，「竖屏→横屏→竖屏」之后竖屏铭牌就永远回不来了。
        val newBrandRow = fresh.findViewById<android.view.View>(R.id.proBrandRow)

        // 旧 chrome 先摘掉：横屏铭牌是 topBar 的子项，会随 topBar 一起走
        if (oldLogo.parent === root) root.removeView(oldLogo)
        root.removeView(oldTop)
        root.removeView(oldBar)
        // 竖屏铭牌是 root 的直接子，只能单独摘（横屏时它在 oldTop 里，parent 不是 root）
        if (oldBrandRow != null && oldBrandRow.parent === root) {
            root.removeView(oldBrandRow)
        }

        // 新 chrome 也要从临时 root 摘下来，才能挂进真正显示的 root
        val moving = mutableListOf<android.view.View>()
        fresh.removeView(newTop); moving += newTop
        if (newLogo.parent === fresh) { fresh.removeView(newLogo); moving += newLogo }
        // 竖屏铭牌是 fresh 的直接子，搬进来；横屏时它在 topBar 内，不搬
        if (newBrandRow != null && newBrandRow.parent === fresh) {
            fresh.removeView(newBrandRow); moving += newBrandRow
        }
        fresh.removeView(newBar); moving += newBar

        // 插在取景框之后、隐藏控件 / 过卷遮罩之前，保证遮罩仍在最上层
        var index = root.indexOfChild(frame) + 1
        moving.forEach { root.addView(it, index++) }
        // P2-1：新 chrome 干净淡入（120ms），消掉旋转时的硬切。旧 chrome 必须同步摘掉，
        // 否则 binding / 触摸分发会指向旧布局，破坏旋转契约；reduced-motion 下直接可见。
        moving.forEach { it.fadeInChrome() }

        binding = ActivityMainBinding.bind(root)
        wireChrome()
        // 🔴 换完 chrome 必须把 Pro 的可见状态重新压回去：新 chrome 的 PRO 徽标
        // 按布局默认是 GONE，而 proMode 字段还留着 true —— 不同步的话旋转后
        // 徽标消失、模式却还在（11-19 真机复现）。
        syncProModeUi()
        if (proMode) {
            // 手动档也要重新推一次：换 chrome 期间相机可能重放过 AE
            runCatching { applyProSettings() }
        }
        // 真机验证用：topBar 宽度 -1(match_parent)=竖版 / -2(wrap_content)=横版。
        // 若是 Resources 配置没跟上导致选错 layout，反馈邮件里一眼能看出来。
        runCatching {
            DeviceGeometryReport.putAll(
                "rotation.swapTo" to if (chromeLandscape) "landscape" else "portrait",
                "rotation.topBarWidth" to newTop.layoutParams.width,
                "rotation.recreated" to false,
            )
            DeviceGeometryReportIo.flush(this)
        }
    }

    /** 机身控件监听；冷启动与换 chrome 后各调一次，全是 setOnClickListener 覆盖，幂等。 */
    private fun wireChrome() {
        // 胶卷图标（预览不做 LUT 调色：光学取景器看不到成片效果，是产品设计）
        binding.tvFilmName.setImageResource(currentRoll.iconResId)
        // vc77：把方形胶卷图裁成与 DARKROOM 同款 14dp 圆角方牌（横竖统一）。
        // ⚠️ 换朝向会重新 inflate bottomBar / tvFilmName，必须在这里幂等重设。
        com.filmcam.app.ui.FilmIconBadge.apply(binding.tvFilmName)
        // 11-21：液晶屏本身可点——真机按一下 EL 窗也会亮起 self-test 背光，
        // 亮 4s 后自动淡出（T3LcdView.pulseBacklight 内部已有 600ms 淡出）。
        binding.lcdDisplay.isClickable = true
        binding.lcdDisplay.setOnClickListener {
            com.filmcam.app.ui.Haptics.vibrate(this, 14, 130)
            binding.lcdDisplay.pulseBacklight()
        }
        binding.btnShutter.hapticClick(ms = 32, amp = 235) { triggerShutter() }
        binding.btnShutter.attachPressFeedback(0.94f)   // 机械按压：按下沉、抬手弹（11-21）
        binding.tvFilmName.hapticClick { showFilmRollPicker() }
        binding.tvFilmName.attachPressFeedback(0.96f)
        binding.tvFlashStatus.hapticClick { cycleFlashMode() }
        binding.tvFlashStatus.attachPressFeedback(0.96f)
        // vc86：长按「闪」字进外闪自学习配对向导（单击仍切档，交互不变）。
        // 手动入口：取消不退档；保存了档案则返回时立即重建 BLE 会话。
        binding.tvFlashStatus.setOnLongClickListener {
            com.filmcam.app.ui.Haptics.vibrate(this, 24)
            runCatching {
                flashPairLauncher.launch(
                    android.content.Intent(this, com.filmcam.app.flash.FlashPairActivity::class.java)
                )
            }.onFailure { android.util.Log.w("FlashDiag", "打开配对向导失败", it) }
            true
        }
        binding.btnGallery.hapticClick {
            startActivity(android.content.Intent(this, GalleryActivity::class.java))
        }
        binding.btnGallery.attachPressFeedback(0.92f)   // 暗房字牌（11-21 用户反馈漏挂）
        binding.settingsKey.hapticClick { showSettings() }
        binding.settingsKey.attachPressFeedback(0.92f)
        // 腰平控件是 root 的常驻 child，不随换朝向重建；setOnClickListener 覆盖即幂等
        binding.waistShutter.hapticClick { triggerShutter() }
        binding.waistShutter.attachPressFeedback(0.94f)
        // 退出通道①：折叠前盖 CLOSE
        binding.waistClose.hapticClick { setWaistLevel(false) }
        binding.waistClose.attachPressFeedback(0.94f)
        // 画幅档循环：AUTO → 1:1（6×6）→ 3:2（135）→ AUTO
        binding.waistRotate.hapticClick {
            setWaistGateStyle(
                when (waistGateStyle) {
                    com.filmcam.app.ui.FoldPosture.GateStyle.AUTO ->
                        com.filmcam.app.ui.FoldPosture.GateStyle.SQUARE
                    com.filmcam.app.ui.FoldPosture.GateStyle.SQUARE ->
                        com.filmcam.app.ui.FoldPosture.GateStyle.STANDARD
                    com.filmcam.app.ui.FoldPosture.GateStyle.STANDARD ->
                        com.filmcam.app.ui.FoldPosture.GateStyle.AUTO
                }
            )
        }
        binding.waistRotate.attachPressFeedback(0.92f)
        // 退出通道③：长按摄像头那半屏（黑屏），手不用摸回底部
        binding.waistMask.setOnLongClickListener {
            com.filmcam.app.ui.Haptics.vibrate(this, 18)
            setWaistLevel(false)
            true
        }
        binding.flashKey.hapticClick { cycleFlashMode() }
        binding.flashKey.attachPressFeedback(0.92f)
        binding.evKey.hapticClick { cycleEvValue() }
        binding.evKey.attachPressFeedback(0.92f)
        binding.filmInfoText.hapticClick { showFilmRollPicker() }
        binding.filmInfoText.attachPressFeedback(0.96f)
        binding.btnEvPlus.hapticClick { stepEv(1) }
        binding.btnEvPlus.attachPressFeedback(0.92f)
        binding.btnEvMinus.hapticClick { stepEv(-1) }
        binding.btnEvMinus.attachPressFeedback(0.92f)
        // Pro 模式入口：整块机身铭牌可点，进出 Pro；铭牌右侧浮出 PRO 徽标。
        // 铭牌本来就是界面里最像「机身按钮」的一块，点它比另加浮层按钮更自然。
        // Pro 模式入口：机身铭牌可点。**单击进出 Pro，铭牌上左右滑调 ISO**；
        // 快门则在取景窗右侧那列快门数字上推拉（见 ViewfinderFrameView 的快门刻度命中区）。
        binding.proBrandRow.setOnTouchListener { _, event -> onBrandTouch(event) }
        // 铭牌与底栏抢同一条底部带，兜底提到最层，否则触摸被兄弟 View 吃掉（11-19 真机）。
        binding.proBrandRow.bringToFront()
        // 快门刻度列的推拉 → 调快门。刻度区之外的触摸照旧穿透给取景器（对焦/白平衡/EV）。
        binding.viewfinderFrame.onShutterDrag = { dy -> onShutterDrag(dy) }
        binding.viewfinderFrame.onShutterDragStart = {
            shutterDragAcc = 0f
            shutterStepped = false       // 新手势：允许再调一档
            binding.viewfinderFrame.notifyActivity()
        }
        binding.viewfinderFrame.onShutterDragEnd = { }
        // 横屏 ISO 手势带：取景窗右缘与快门键之间那条缝，上下推拉调 ISO。
        binding.viewfinderFrame.onIsoDrag = { dy -> onIsoDrag(dy) }
        binding.viewfinderFrame.onIsoDragStart = {
            isoDragAcc = 0f
            isoStepped = false           // 新手势：允许再调一档
        }
        binding.viewfinderFrame.onIsoDragEnd = { }
        // 几何要等**真正布局完**（快门键左边界、预览内容矩形）才知道。
        // 🔴 不能用 post：post 只保证"下一帧"，那时可能还没 measure/layout，
        // btnShutter.left 还是 26，算出来只有 1px 宽的窄缝，手指永远碰不到（11-19 真机）。
        // 用全局布局回调：所有子 View 都摆好之后才来，这时几何才是真的。
        binding.viewfinderFrame.viewTreeObserver.addOnGlobalLayoutListener {
            updateIsoGestureZone()
        }
    }

    /**
     * 边到边 + 安全区自适应：冷启动一次，朝向切换换新 chrome 后再来一次。
     * 每次都读新控件的 padding / margin 基准，避免把竖屏的 48dp / 150dp
     * 错误带进横屏（layout-land 里这两项分别是 0 / 不适用）。
     */
    private fun setupEdgeToEdge() {
        // vc77：竖屏 bottomBar 是贴底栏，必须补底部 inset 避开导航栏；
        // 但横屏 bottomBar 是 `end|center_vertical` 的**垂直居中侧栏**，给它加
        // bottomMargin 会让 FrameLayout 的 center_vertical 把整列上推 margin/2
        // （S25U 横屏实测 40px），快门就偏离屏幕中心。横屏系统导航栏在右侧
        // （sb.right），sb.bottom 对这个侧栏没有意义，所以横屏干脆不补偿它。
        // 换朝向时 swapChrome 已经重新 inflate 过 bottomBar，基准仍从 0 起，不叠加。
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        com.filmcam.app.ui.EdgeToEdge.setup(
            this, binding.topBar,
            if (landscape) null else binding.bottomBar,
            bottomAsMargin = true,
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
                binding.root.post { applyWaistLevel() }
            },
            // 只在取景器页隐藏系统栏：拍摄更沉浸，其余 4 页保留时间与通知
            immersive = true,
        )
    }

    /** 旋转 / 分辨率 / 显示大小变化后重新量一次（值不变时不写，不会自激循环）。 */
    private fun refitChrome() {
        // 腰平模式下 viewfinderFrame 只剩半屏，contentRect() 是半屏内的局部坐标；
        // 再拿它和整屏 root 尺寸混算会污染安全区与诊断报告（控件此时也已隐藏）。
        if (waistApplied) return
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
    }

    /**
     * 腰平取景（P0）：设置面板的可见开关 → 纯函数求分区 → 幂等应用。
     * 只改「取景显示在哪一半」，相机请求 / 冲洗 / EXIF / 成片方向一律不动。
     * P1 接 androidx.window 后把 halfOpened / hinge / bounds 填进 Input 即可。
     */
    private fun applyWaistLevel() {
        val root = binding.root
        root.post {
            if (isFinishing || isDestroyed) return@post
            val realFold = waistEgg == com.filmcam.app.ui.FoldPosture.EGG_AUTO &&
                foldHalfOpened && foldSeparating
            val simulate = waistEgg == com.filmcam.app.ui.FoldPosture.EGG_SIMULATE
            // 彩蛋语义的唯一判定：关＝永不；自动＝真折痕半开+分离；模拟＝直板机调试
            val allowed = com.filmcam.app.ui.FoldPosture.allowsWaist(
                waistEgg, foldHalfOpened, foldSeparating, waistDismissed
            )
            val b = foldBounds
            val posture = com.filmcam.app.ui.FoldPosture.solve(
                com.filmcam.app.ui.FoldPosture.Input(
                    windowW = root.width,
                    windowH = root.height,
                    displayW = displayWidthPx(),
                    displayH = displayHeightPx(),
                    // 只有真实折痕才带 hinge；模拟档沿用平分（直板机没有折痕）
                    hinge = if (allowed && realFold) foldHinge
                    else com.filmcam.app.ui.FoldPosture.Hinge.NONE,
                    halfOpened = allowed && realFold,
                    boundsLeft = b?.left ?: 0,
                    boundsTop = b?.top ?: 0,
                    boundsRight = b?.right ?: 0,
                    boundsBottom = b?.bottom ?: 0,
                    manual = allowed && simulate,
                    gateStyle = waistGateStyle,
                )
            )
            waistApplied = posture.mode == com.filmcam.app.ui.FoldPosture.Mode.WAIST
            com.filmcam.app.ui.WaistLevelChrome.apply(
                root, binding.glSurfaceView, binding.viewfinderFrame,
                binding.waistMask, binding.waistTopBar, binding.waistFinderOverlay,
                binding.waistBar, binding.developingOverlay, posture
            )
            // 腰平态取景屏只保留 MF-2 那套：T3 标记整体不画；预览改为裁切填充：
            // 表面即画幅，4:3 内容按拍摄朝向裁切铺满（零拉伸，所见即成片）。
            binding.viewfinderFrame.waistSuppress = waistApplied
            binding.viewfinderFrame.waistGate = waistApplied
            binding.glSurfaceView.waistFill = waistApplied
            waistGateAspect = if (waistApplied) posture.gateAspect else 0f
            applyWaistGateStyle()
            // 腰平态接管系统返回键：返回=收起腰平，不退 App；退出后立即交还。
            waistBackCallback?.isEnabled = waistApplied
            if (waistApplied) updateWaistReadout()
            // 进/出腰平都重算一次：退出时清空短时读数，避免下次进入残留上一档刻字
            updateWaistWbReadout()
            // 退出腰平后等布局重算完，再按整屏尺寸重跑安全区，避免残留半屏 margin。
            if (!waistApplied) root.post { refitChrome() }
        }
    }

    /**
     * 腰平模式的唯一入口。CLOSE / 返回键 / 长按黑屏三条退出通道都走这里。
     *
     * enabled=false = 用户主动收起：记为「本次半开已收起」，直到重新展平才允许
     * 再次自动进入（否则下一帧 FoldingFeature 会立刻把它拉回来）。
     * 总闸在工程模式（waistEgg），这里不写 prefs。
     */
    private fun setWaistLevel(enabled: Boolean) {
        if (enabled) {
            waistDismissed = false
        } else {
            if (!waistApplied) return
            waistDismissed = true
        }
        applyWaistLevel()
        updateWaistReadout()
        showLcdNotice(
            getString(if (enabled) R.string.lcd_notice_waist else R.string.lcd_notice_waist_off)
        )
    }

    /**
     * 腰平彩蛋的折痕监听：FoldingFeature 只区分展平 / 半开，没有角度读数，
     * 「折到 90°」在这里就是 `HALF_OPENED + isSeparating`（折痕真的把窗口分成两半）。
     * 展平时复位 [waistDismissed]，下次折起还能自动进。
     */
    private fun observeFolding() {
        lifecycleScope.launch {
            runCatching {
                androidx.window.layout.WindowInfoTracker.getOrCreate(this@MainActivity)
                    .windowLayoutInfo(this@MainActivity)
                    .collect { info ->
                        val f = info.displayFeatures
                            .filterIsInstance<androidx.window.layout.FoldingFeature>()
                            .firstOrNull()
                        val wasHalf = foldHalfOpened
                        if (f == null) {
                            foldHalfOpened = false
                            foldSeparating = false
                            foldHinge = com.filmcam.app.ui.FoldPosture.Hinge.NONE
                            foldBounds = null
                        } else {
                            foldHalfOpened = f.state ==
                                androidx.window.layout.FoldingFeature.State.HALF_OPENED
                            foldSeparating = f.isSeparating
                            foldHinge = when (f.orientation) {
                                androidx.window.layout.FoldingFeature.Orientation.HORIZONTAL ->
                                    com.filmcam.app.ui.FoldPosture.Hinge.HORIZONTAL
                                else -> com.filmcam.app.ui.FoldPosture.Hinge.VERTICAL
                            }
                            foldBounds = f.bounds
                        }
                        // 展平了 → 解除「本次已收起」，下次折起重新自动进入
                        if (wasHalf && !foldHalfOpened) waistDismissed = false
                        applyWaistLevel()
                    }
            }.onFailure {
                android.util.Log.w("WaistFold", "折痕监听不可用，按直板机处理", it)
            }
        }
    }

    /** 整块屏幕宽（不是窗口宽）：判「系统是否已经缩窗」要用它。 */
    private fun displayWidthPx(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 30)
            windowManager.maximumWindowMetrics.bounds.width()
        else resources.displayMetrics.widthPixels

    private fun displayHeightPx(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 30)
            windowManager.maximumWindowMetrics.bounds.height()
        else resources.displayMetrics.heightPixels

    /**
     * 腰平画幅档切换（AUTO / 1:1 6×6 / 3:2 135）。真机 MF-2 的磨砂屏就是固定片窗，
     * 画幅随取景窗几何走；手动档只是误判兑底。改档重算分区（画幅矩形 / 预览裁切 /
     * 成片画幅全部跟着 posture.gateAspect 变）。
     */
    private fun setWaistGateStyle(style: com.filmcam.app.ui.FoldPosture.GateStyle) {
        if (waistGateStyle == style) return
        waistGateStyle = style
        saveSettings()
        applyWaistLevel()
    }

    /** 把当前画幅档落到前盖按钮文字（幂等，可反复调）。 */
    private fun applyWaistGateStyle() {
        try {
            binding.waistRotate.text = when (waistGateStyle) {
                com.filmcam.app.ui.FoldPosture.GateStyle.AUTO -> "AUTO"
                com.filmcam.app.ui.FoldPosture.GateStyle.SQUARE -> "1:1"
                com.filmcam.app.ui.FoldPosture.GateStyle.STANDARD -> "3:2"
            }
        } catch (_: Exception) {}
    }

    /** 折叠前盖中间那行读数：EV（EXT 用细档）· 闪光档位 · 卷内帧数 · 当前画幅。 */
    private fun updateWaistReadout() {
        try {
            val ev = if (flash.mode == FlashMode.EXTERNAL) formatEv(flashCompEv)
            else formatEv(exposureComp.toFloat())
            val flashLabel = when (flash.mode) {
                FlashMode.OFF -> "OFF"
                FlashMode.BUILT_IN -> "INT"
                FlashMode.EXTERNAL -> "EXT"
            }
            val gateLabel = when {
                waistGateAspect <= 0f -> ""
                kotlin.math.abs(waistGateAspect - 1f) < 0.01f -> " · 6×6"
                waistGateAspect > 1f -> " · 3:2"
                else -> " · 2:3"
            }
            binding.waistReadout.text =
                "EV $ev · $flashLabel · ${currentShotCount()}/$FILM_MAX$gateLabel"
        } catch (_: Exception) {}
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
        // EXT 档的细档补偿也随「拍摄同款」还原：只回到整数 EV 的话，
        // 外闪固定曝光量对不上，同款成片亮度会与样张不一致。
        intent.getFloatExtra(EXTRA_SAME_FLASH_EV, Float.NaN)
            .takeIf { !it.isNaN() }
            ?.let {
                flashCompEv = it.coerceIn(-3f, 3f)
                applyFlashCompUi(persist = false)
            }
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
        dateStampEnabled = prefs.getBoolean("dateStamp", DefaultSettings.DATE_STAMP_ENABLED)
        exposureComp = prefs.getInt("ev", 0)
        // 0.3EV 网格规整：老 prefs 里可能是 0.5 档的旧值，落回最近的 0.3
        flashCompEv = quantizeFlashEv(prefs.getFloat("flashEv", 0f))
        // 腰平彩蛋总闸：工程模式三态（off / auto / simulate），默认自动
        waistEgg = prefs.getString("waistEgg", "auto") ?: "auto"
        waistGateStyle = when (prefs.getString("waistGateStyle", "auto")) {
            "square" -> com.filmcam.app.ui.FoldPosture.GateStyle.SQUARE
            "standard" -> com.filmcam.app.ui.FoldPosture.GateStyle.STANDARD
            else -> com.filmcam.app.ui.FoldPosture.GateStyle.AUTO
        }
        softFocusEnabled = prefs.getBoolean("softFocus", DefaultSettings.SOFT_FOCUS_ENABLED)
        // 11-13 起新装缺省 2%（工程师滑杆文案「0% = 关闭」）；已存过的键不覆盖
        softFocusProb = prefs.getInt("softFocus_prob", DefaultSettings.SOFT_FOCUS_PROB)
        filmRollMode = prefs.getBoolean("filmRollMode", false)
        hapticLevel = prefs.getInt("hapticLevel", 1).coerceIn(0, 2)
        slowShutterMode = FilmExposure.Mode.fromPrefs(
            prefs.getInt("slowShutter", DefaultSettings.slowMode.prefs)
        )
        slowTrigger = FilmExposure.Trigger.fromPrefs(
            prefs.getInt("slowTrigger", DefaultSettings.slowTrigger.prefs)
        )
        com.filmcam.app.ui.BodyTheme.load(this)
        com.filmcam.app.ui.AppLocale.load(this)
        // 旧 filmCount 键仅作迁移保留读取，不再作为权威（张数一律读 UndevelopedFilmStore.shotCount）
        @Suppress("UNUSED_VARIABLE") val legacyFilmCount = prefs.getInt("filmCount", 0)
        rawNegativeEnabled = prefs.getBoolean("filmcam_dev_raw", false)
        flash.mode = try {
            FlashMode.valueOf(prefs.getString("flashMode", FlashMode.OFF.name) ?: FlashMode.OFF.name)
        } catch (_: Exception) { FlashMode.OFF }
        wbIndex = prefs.getInt("wbIndex", 0).coerceIn(0, wbPresets.size - 1)
        // 白平衡是常驻设置，与闪不闪光无关：恢复时一律下发，成片与预览一致
        cameraController?.setAwbMode(wbPresets[wbIndex].awbMode)
        // v74 P2：冷启动也把 UI 档位写进诊断报告
        runCatching { DeviceGeometryReport.put("flash.uiMode", flash.mode.name) }
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
        // 启动恢复时若该卷已下架（available=false），回退到第一支可用胶卷，避免带着禁用卷启动
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
        prefs.putFloat("flashEv", flashCompEv)
        // 腰平总闸在工程模式（waistEgg），不在这里写；这里只持久化画幅档
        prefs.putString("waistGateStyle", when (waistGateStyle) {
            com.filmcam.app.ui.FoldPosture.GateStyle.SQUARE -> "square"
            com.filmcam.app.ui.FoldPosture.GateStyle.STANDARD -> "standard"
            else -> "auto"
        })
        prefs.putBoolean("softFocus", softFocusEnabled)
        prefs.putBoolean("filmRollMode", filmRollMode)
        prefs.putInt("hapticLevel", hapticLevel)
        prefs.putInt("slowShutter", slowShutterMode.prefs)
        // 旧 filmCount 键不再写入；权威张数来自 UndevelopedFilmStore
        prefs.putString("flashMode", flash.mode.name)
        prefs.putInt("wbIndex", wbIndex)
        prefs.putInt("rollIndex", FilmRollRepository.rolls.indexOf(currentRoll))
        prefs.apply()
    }

    /**
     * 弹窗关闭守卫：无论怎么关的，dismiss 后都补一段取景器触摸屏蔽。
     * 只在点击回调里设不够——点弹窗外部、按返回键关闭时同样会有事件甩给取景器。
     * [onDismissed]：需要监听 dismiss 的弹窗（快速上手卡）把回调织进来，
     * 因为 setOnDismissListener 是单槽，guardDialog 和调用方各自挂会互相覆盖。
     */
    private fun <T : android.app.Dialog> T.guardDialog(onDismissed: () -> Unit = {}): T {
        dialogDepth++
        setOnDismissListener {
            dialogDepth = (dialogDepth - 1).coerceAtLeast(0)
            suppressTouchUntil = SystemClock.uptimeMillis() + DIALOG_TOUCH_GUARD_MS
            // 弹窗一关就把手势状态清零：漏进来的事件不会续上一次手势
            touchActive = false
            lastDownTime = -1L
            onDismissed()
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
            R.id.rowSetBody, R.id.rowSetHaptic,
            R.id.rowSetWb, R.id.rowSetLanguage, R.id.rowSetQuality,
            R.id.rowSetColor, R.id.rowSetRaw,
            R.id.rowSetFlash, R.id.rowSetAbout
        )
        val theme = com.filmcam.app.ui.BodyTheme
        fun row(id: Int): android.view.View = view.findViewById(id)
        fun value(id: Int): android.widget.TextView = row(id).findViewById(R.id.tvSetValue)

        rows.forEachIndexed { i, id ->
            row(id).findViewById<android.widget.TextView>(R.id.tvSetLabel).text = labels[i]
            row(id).findViewById<android.widget.TextView>(R.id.tvSetHint).text = hints[i]
            row(id).attachRowPress()   // 11-21：行的机械按压高亮（此前只有 haptic，无视觉选中感）
        }
        // v87 限高滚动：底沿渐隐与刻线窗。都常驻 XML，只在这里取引用，
        // 运行时 addView 出来的控件会漏掉主题重映射（历史上踩过一次）。
        val setScroll = view.findViewById<android.widget.ScrollView>(R.id.setScroll)
        val setFade = view.findViewById<android.view.View>(R.id.setFade)
        val setMoreRule = view.findViewById<android.view.View>(R.id.setMoreRule)
        val setMoreTick = view.findViewById<android.view.View>(R.id.setMoreTick)

        // 机身行右侧是「色版」—— 一块当前外壳色的圆点，比单看文字更直白。
        // 这两块装饰常驻 XML 只在这里点亮：运行时 addView 出来的控件会漏掉主题重映射。
        val swatch = row(R.id.rowSetBody).findViewById<android.view.View>(R.id.viewSetSwatch)
        swatch.visibility = android.view.View.VISIBLE
        val ticks = row(R.id.rowSetHaptic).findViewById<android.view.View>(R.id.setTicks)
        ticks.visibility = android.view.View.VISIBLE
        val tvClose = view.findViewById<android.widget.TextView>(R.id.tvSetClose)

        /**
         * 底沿渐隐按面板底色现画：黑钛是 #1A1A1A、钛银是浅钛面，
         * 系统 fadingEdge 的黑色压在浅钛面板上会像一道脏边，只能自己画。
         */
        fun paintFade() {
            setFade.background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(theme.panelBg, android.graphics.Color.TRANSPARENT)
            )
            setMoreRule.setBackgroundColor(theme.cardStroke)
        }

        /** 亮刻线 + 渐隐 = 「下面还有行」；滚到底就收起。始终占位，不会改高度 */
        fun updateMoreHint(canScrollDown: Boolean = setScroll.canScrollVertically(1)) {
            setFade.visibility = if (canScrollDown) android.view.View.VISIBLE
            else android.view.View.INVISIBLE
            setMoreTick.setBackgroundColor(if (canScrollDown) theme.accent else theme.trackBg)
        }

        /**
         * 把面板刷成当前机身档位。重映射是幂等的（表里没有反向 key），
         * 所以切换机身色时可以连着刷好几次 —— 面板跟着机身当场变色，不需要重建弹窗。
         */
        fun paintPanel() {
            theme.applyToViewTree(view)
            paintFade()
            updateMoreHint()
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
            value(R.id.rowSetBody).setTextWithRoll(theme.name(this@MainActivity))
            value(R.id.rowSetHaptic).setTextWithRoll(hapticLevelName())
            value(R.id.rowSetWb).setTextWithRoll(wbName())
            value(R.id.rowSetLanguage).setTextWithRoll(languageName())
            value(R.id.rowSetQuality).setTextWithRoll(qualityName())
            value(R.id.rowSetColor).setTextWithRoll(colorStrengthName())
            value(R.id.rowSetRaw).setTextWithRoll(rawSettingName())
            // v86：外闪灯管理行的读数 = 已配对档案状态（灯名 / 台数 / 未配对），
            // 和面板其它行一样「不点进去就能读到当前值」。
            value(R.id.rowSetFlash).setTextWithRoll(runCatching {
                val profiles = com.filmcam.app.flash.FlashProfileStore(this).list()
                when {
                    profiles.isEmpty() -> getString(R.string.set_flash_none)
                    profiles.size == 1 -> profiles[0].displayName.take(14)
                    else -> getString(R.string.set_flash_multi, profiles.size)
                }
            }.getOrDefault(getString(R.string.set_flash_none)))
            value(R.id.rowSetAbout).setTextWithRoll(versionLabel())
            paintPanel()
        }

        // ⚠️ 宽度必须「先触发 installDecor，再 setLayout」（11-14 S25U 窗口 dump 实证）：
        // Dialog 主题 windowIsFloating=true，show() 时 PhoneWindow.generateLayout()
        // 会 setLayout(WRAP_CONTENT, WRAP_CONTENT)，把 show() 前设的宽度静默重置
        //（实测 mAttrs=(wrapxwrap)、Requested w=1024px）。create() 后先取一次
        // decorView 触发 installDecor/generateLayout，再 setLayout(w, ...) 就不会
        // 再被覆盖，且仍在 show() 之前 → 首帧即 842px、零跳变。
        // ❌ 否决：把内容包进固定宽 FrameLayout —— 窗口仍 1024、内容 842 左对齐，
        //   右边空 182px（tmp/ui_dump_2031.xml：rowSetBody=[28,557][870,693]）。
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.78f).toInt().coerceAtMost((400 * dm.density).toInt())

        // ⚠️ v87 限高（11-16）：11 行「名称 + 说明 + 读数」自然高 ≈1800px，
        // S25U 可用高约 2100px → 面板几乎顶满整屏，像一条又窄又长的铭牌。
        // 这里在 show() 前用 UNSPECIFIED 量出自然高，再把 setScroll 的高度减掉
        // 超出量，ScrollView 才真正拿到高度上限、滚动才会生效。窗口仍是
        // WRAP_CONTENT（不碰 78% 宽度契约），首帧即最终形态、零跳变。
        val maxPanelH = (dm.heightPixels * 0.58f).toInt()
        val widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(
            w, android.view.View.MeasureSpec.EXACTLY)
        val heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(
            0, android.view.View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        val naturalH = view.measuredHeight
        if (naturalH > maxPanelH) {
            val lp = setScroll.layoutParams
            lp.height = (setScroll.measuredHeight - (naturalH - maxPanelH))
                .coerceAtLeast((120 * dm.density).toInt())
            setScroll.layoutParams = lp
            view.measure(widthSpec, heightSpec)   // 同步 measure 结果，滚动判定才准
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, theme.dialogTheme)
            .setView(view)
            .create()
        dialog.guardDialog()
        val win = dialog.window
        win?.decorView   // 提前 installDecor()，此后 setLayout 不再被重置
        win?.setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        // 背景在 show() 之前定死（Material 大圆角卡片 → 机加工铭牌），
        // 首帧即最终形态，零跳变。78% 屏宽在 S25U 是 842px≈321dp、
        // 每侧约 45dp 留白盖住侧滑起手区；横屏仍由 400dp 上限兜底。
        win?.setBackgroundDrawable(theme.panelDrawable())

        refreshValues()

        // 滚动触感：约每滚过一行给一记 8ms 轻 tick（55ms 节流），到顶/到底
        // 再给一记机械限位震；强度跟随设置里的低/中/强（Haptics 读 hapticLevel）。
        // 只挂一条 OnScrollChangeListener，避免二次 set 把限位震冲掉。
        val detents = com.filmcam.app.ui.ScrollDetents(this, (44 * dm.density).toInt())
        setScroll.attachEdgeHaptics(detents = detents) { canScrollDown ->
            updateMoreHint(canScrollDown)
        }

        row(R.id.rowSetBody).hapticClick {
            // 就地换外壳：面板不关，跟着机身一起翻色
            theme.cycle(this@MainActivity)
            applyBodyTheme()
            refreshValues()
            dialog.window?.setBackgroundDrawable(theme.panelDrawable())
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
        row(R.id.rowSetColor).hapticClick {
            // v73 色彩浓度：标准 → 淡 → 原始 → 标准。就地循环、面板不关，
            // 只影响下一次冲洗；与工程师控制台那根 0–100% 滑杆共用同一个 prefs 键，
            // 在那里拖过任意中间值的话，这一行会显示「自定义 xx%」而不是吸附回档位。
            com.filmcam.app.develop.ColorStrength.cycle(this@MainActivity)
            refreshValues()
        }
        row(R.id.rowSetRaw).hapticClick {
            // v81 RAW 底片对外开放：从工程师控制台搬到设置面板，两边共用同一个
            // prefs 键（filmcam_dev_raw），哪里拨都在下次 startCamera / onResume 生效，
            // 这里直接立即下发，所见即所得。不支持的机型不翻转，从 LCD 告知。
            if (!rawSensorAvailable()) {
                showLcdNotice(getString(R.string.lcd_notice_raw_unavailable))
                return@hapticClick
            }
            rawNegativeEnabled = !rawNegativeEnabled
            getSharedPreferences("filmcam", MODE_PRIVATE)
                .edit().putBoolean("filmcam_dev_raw", rawNegativeEnabled).apply()
            cameraController?.rawNegativeEnabled = rawNegativeEnabled
            refreshValues()
        }
        row(R.id.rowSetFlash).hapticClick {
            // 可见入口（长按「闪」字太隐蔽）：从设置进向导的管理模式，
            // 第一屏就是已配对的灯。这不是切 EXT 自动弹出的流程，取消不退档。
            dialog.dismiss()
            flashPairAutoFromExt = false
            runCatching {
                flashPairLauncher.launch(
                    android.content.Intent(this, com.filmcam.app.flash.FlashPairActivity::class.java)
                        .putExtra(com.filmcam.app.flash.FlashPairActivity.EXTRA_MANAGE, true)
                )
            }.onFailure { android.util.Log.w("FlashDiag", "打开外闪灯管理失败", it) }
        }
        row(R.id.rowSetAbout).hapticClick {
            dialog.dismiss()
            startActivity(android.content.Intent(this, AboutActivity::class.java))
        }
        // 只有「主动关闭字牌」走退场动画；下面三行跳转型关闭（语言重建 / 外闪向导 /
        // 关于页）必须立刻 dismiss —— 它们紧接着 startActivity，180ms 动画会被切屏打断。
        tvClose.hapticClick { dialog.dismissPanel(view) }
        tvClose.attachPressFeedback(0.96f)   // 面板关闭字牌（11-21 补齐）

        dialog.show()
        // 兜底：个别 ROM 在 addView 阶段可能改写 attrs.width；值相同不产生可见跳变
        dialog.window?.setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        // 机身面板入场：只动内容根 alpha/位移，不碰窗口尺寸/内层 ScrollView 限高契约
        view.animatePanelIn()
        // 11-21：点外部 / 返回键也走退场动画（此前只有关闭字牌有，用户能明显感到不一致）。
        // 必须在 show() 之后挂：decorView 与内容坐标都要窗口 attach 后才有效。
        dialog.attachOutsideDismiss(view)
        // 首帧后按实际 layout 结果再校正一次「下面还有行」（measure 期判定可能偏早）
        view.post { updateMoreHint() }
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
    private fun frameBorderName(): String = when (FilmStockMapper.readFrameFormat(this)) {
        FilmStockMapper.FrameFormat.FULL -> getString(R.string.frame_full)
        FilmStockMapper.FrameFormat.WHITE -> getString(R.string.frame_white)
        else -> getString(R.string.common_off)
    }

    /** 设置面板「色彩浓度」当前档位：标准 / 淡 / 原始 / 自定义（工程师滑杆细调过） */
    private fun colorStrengthName(): String {
        val cs = com.filmcam.app.develop.ColorStrength
        val v = cs.load(this)
        return when (cs.levelOf(v)) {
            com.filmcam.app.develop.ColorStrength.Level.STANDARD ->
                getString(R.string.color_strength_standard)
            com.filmcam.app.develop.ColorStrength.Level.SOFT ->
                getString(R.string.color_strength_soft)
            com.filmcam.app.develop.ColorStrength.Level.ORIGINAL ->
                getString(R.string.color_strength_original)
            com.filmcam.app.develop.ColorStrength.Level.CUSTOM ->
                getString(R.string.color_strength_custom, (v * 100f).roundToInt())
        }
    }

    /** 设置面板「RAW 底片」当前读数：不支持机型如实刻「不支持」（探测口径与工程师控制台一致） */
    private fun rawSettingName(): String =
        if (!rawSensorAvailable()) getString(R.string.raw_unavailable)
        else if (rawNegativeEnabled) getString(R.string.common_on)
        else getString(R.string.common_off)

    /**
     * 本机后摄能不能出 RAW_SENSOR 流。与工程师控制台（AboutActivity）同一套探测，
     * 探一次缓存到成员：CameraCharacteristics 有系统级缓存，再探也轻，但设置面板
     * 每次 refreshValues 都要读，没必要求反。
     */
    private var rawSensorAvailableCache: Boolean? = null
    private fun rawSensorAvailable(): Boolean {
        rawSensorAvailableCache?.let { return it }
        val ok = runCatching {
            val cm = getSystemService(android.hardware.camera2.CameraManager::class.java)
            val sizes = cm?.getCameraCharacteristics("0")
                ?.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
            !sizes.isNullOrEmpty()
        }.getOrDefault(false)
        rawSensorAvailableCache = ok
        return ok
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
        setDevelopingOverlayVisible(true)
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
                    setDevelopingOverlayVisible(false)
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

    /**
     * 首次进入引导（v82 三段式）：段 1 说明书卡「快速上手」三屏翻页，
     * 播完 dismiss 后机顶 LCD 接力段 2 实操引导（见 [showOnboardingHint]）。
     * 老用户（guide_shown 已置）不重播；「关于」页有重看入口。
     */
    private fun maybeShowFirstRunGuide() {
        val prefs = getSharedPreferences("filmcam", MODE_PRIVATE)
        if (prefs.getBoolean("guide_shown", false)) return
        prefs.edit().putBoolean("guide_shown", true).apply()
        com.filmcam.app.ui.QuickStartCard.show(
            activity = this,
            guard = { d, afterDismiss -> d.guardDialog(afterDismiss) },
            onDismissed = {
                // 段 1 结束 → 段 2 启动：说明书讲完，LCD 接力第一条实操提示
                if (OnboardingGuide.stage(this) == 0) OnboardingGuide.setStage(this, 1)
                showOnboardingHint()
            },
        )
    }

    // ===== v82 段 2：机顶 LCD 实操引导（做对自动前进，15s 超时跳过） =====

    private val guideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var guideTimeoutRunnable: Runnable? = null

    /** 当前段位该出哪条 LCD 提示；重新挂超时（超时自动前进不卡人） */
    private fun showOnboardingHint() {
        val s = OnboardingGuide.stage(this)
        if (s !in 1 until OnboardingGuide.STAGE_DONE) return
        showLcdNotice(
            getString(
                when (s) {
                    1 -> R.string.guide_focus
                    2 -> R.string.guide_ev
                    else -> R.string.guide_shoot
                }
            )
        )
        guideTimeoutRunnable?.let { guideHandler.removeCallbacks(it) }
        val r = Runnable {
            val cur = OnboardingGuide.stage(this)
            if (cur in 1 until OnboardingGuide.STAGE_DONE) {
                // 超时跳过：做不做对都前进，引导永远不拦着人拍照
                OnboardingGuide.setStage(this, cur + 1)
                showOnboardingHint()
            }
        }
        guideTimeoutRunnable = r
        guideHandler.postDelayed(r, OnboardingGuide.HINT_TIMEOUT_MS)
    }

    /** 段 2 完成时停掉超时轮转（第三步「拍第一张」的提示交给段 3 的 1/36 提示接力） */
    private fun stopOnboardingTimeout() {
        guideTimeoutRunnable?.let { guideHandler.removeCallbacks(it) }
        guideTimeoutRunnable = null
    }


    /** 打开胶卷选择器（底部弹层） */
    /** 换卷弹层只负责「选哪支、排序、触感」，实现已挪到 ui/FilmRollPicker。 */
    private fun showFilmRollPicker() {
        com.filmcam.app.ui.FilmRollPicker.show(
            activity = this,
            currentName = currentRoll.name,
            canPick = { roll -> filmRejectReason(roll) == null },
            onPick = { roll -> selectFilmRoll(roll) },
            onReject = { roll ->
                val reason = filmRejectReason(roll) ?: return@show
                showLcdNotice(reason)
                // 被拦要有「顶住了」的手感：双震，强度接近满档
                vibrate(95, 235)
                binding.viewfinderFrame.postDelayed({ vibrate(95, 235) }, 140)
            },
            onVibrate = { ms, amp -> vibrate(ms, amp) },
            guard = { d -> d.guardDialog() },
        )
    }

    /**
     * 换卷拦截的单一口径：null = 可以换，非 null = 拒绝原因文案。
     * 唯一真实约束＝当前卷还有未冲洗帧时禁止换卷（真机拍完必须回卷才能开盖）。
     * 🔴反转片可选、可拍摄（引擎 scan_film 直扫正片），不在这里拦截。
     * 弹层的 canPick / onReject 与 selectFilmRoll 都走这里，避免两处判断漂移。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun filmRejectReason(roll: com.filmcam.app.filter.FilmRoll): String? {
        val active = UndevelopedFilmStore.currentRoll()
        val pending = active?.let { UndevelopedFilmStore.shotCount(it.rollId) } ?: 0
        if (active != null && pending > 0) {
            return getString(R.string.lcd_notice_develop_before_reload)
        }
        return null
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

    // ===== 白平衡（预览上左右拖动切换，任何闪光档都能拨） =====

    private enum class WbIcon { AUTO, BULB, TUBE, SUN, CLOUD_SUN, SHADE, TWILIGHT }

    /**
     * 白平衡档位：语义对应胶片机的校色滤镜片，顺序即横滑从左到右的档位。
     *
     * kelvin 是 **AOSP 3A 文档给每档 AWB_MODE 的标称适用光源近似值**
     * （source.android.com/devices/camera/camera3_3Amodes.html：
     *  INCANDESCENT 2700K / FLUORESCENT 5000K / DAYLIGHT 5500K /
     *  CLOUDY_DAYLIGHT 6500K / SHADE 7500K / TWILIGHT 15000K）。
     * Android 只承诺「approximately match CIE standard illuminant」，各厂商 HAL
     * 实现有差异 —— 所以这是**档位标称值，不是传感器实测色温**，UI 上带 ≈ 号，
     * 也绝不拿它去调引擎参数。AUTO 档由 3A 动态决定，没有固定 K，只显示 AUTO。
     */
    private data class WbPreset(
        val label: String,
        val awbMode: Int,
        val icon: WbIcon,
        val kelvin: Int?,
        val code: String,
    )

    // lazy：label 要读本地化资源，必须在 Activity attach 之后才能取 getString
    private val wbPresets by lazy {
        listOf(
            WbPreset("AUTO", android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO, WbIcon.AUTO, null, "AUTO"),
            WbPreset(getString(R.string.main_wb_incandescent), android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, WbIcon.BULB, 2700, "INCAND"),
            WbPreset(getString(R.string.main_wb_fluorescent), android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, WbIcon.TUBE, 5000, "FLUOR"),
            WbPreset(getString(R.string.main_wb_daylight), android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, WbIcon.SUN, 5500, "DAYLIGHT"),
            WbPreset(getString(R.string.main_wb_cloudy), android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, WbIcon.CLOUD_SUN, 6500, "CLOUDY"),
            WbPreset(getString(R.string.main_wb_shade), android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_SHADE, WbIcon.SHADE, 7500, "SHADE"),
            WbPreset(getString(R.string.main_wb_twilight), android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_TWILIGHT, WbIcon.TWILIGHT, 15000, "TWILIGHT")
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
        updateWaistWbReadout()
    }

    /**
     * 腰平取景窗里的**短时**白平衡读数。腰平态抑制了 T3 整层标记（含原 WB 面板），
     * 横滑换片时屏幕上什么都没有，只剩震动 —— 这里把当前档位的标称色温写进
     * MF-2 取景格。仅 `waistApplied && wbVisible`（横滑起手置位、抬手 900ms 后清）
     * 显示；整屏模式、静止态、退出腰平后一律为空。**不改机顶 LCD、不改整屏 T3 面板、不常驻**。
     */
    private fun updateWaistWbReadout() {
        try {
            val preset = wbPresets.getOrNull(wbIndex)
            val label = when {
                preset == null -> ""
                preset.kelvin == null -> "WB AUTO"
                else -> "WB ≈${preset.kelvin}K · ${preset.code}"
            }
            binding.waistFinderOverlay.wbReadout =
                if (waistApplied && wbVisible) label else ""
        } catch (_: Exception) {}
    }

    /** 选中胶卷：切换 LUT + 更新图标。fromUser=false 用于启动时恢复设置（不做拦截）。 */
    private fun selectFilmRoll(roll: FilmRoll, fromUser: Boolean = true) {
        if (fromUser) {
            // 拦截口径与弹层 canPick / onReject 共用 filmRejectReason（单一来源）
            val reason = filmRejectReason(roll)
            if (reason != null) {
                showLcdNotice(reason)
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
        if (fromUser) {
            vibrate(50, 190)
            // P1 换卷翻牌：底部胶卷图标短促下沉回弹 + 亮度一闪，像卷芯咔哒到位。
            // 纯 View 层动画，不动相机/LUT 时序；和 haptic 同帧。
            binding.tvFilmName.animate().cancel()
            binding.tvFilmName.animate()
                .scaleX(0.88f).scaleY(0.88f)
                .setDuration(Motion.duration(this, Motion.PRESS_DOWN))
                .setInterpolator(Motion.accelerate)
                .withEndAction {
                    binding.tvFilmName.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(Motion.duration(this, Motion.PRESS_UP))
                        .setInterpolator(Motion.decelerate)
                        .start()
                }
                .start()
        }
    }

    // ===== T3 机身 UI 交互 =====

    /**
     * 落卷时把相机层实测的这一帧曝光参数并进拍摄快照。
     * 只在这里补：拍照 request 构造 / TotalCaptureResult 都晚于 shutterDown
     * 里生成 lastShotJson 的时刻，落盘是唯一能拿到真值的点。
     */
    private fun shotJsonWithCapture(): String {
        val base = com.filmcam.app.film.ShotMeta.fromJson(lastShotJson)
            ?: com.filmcam.app.film.ShotMeta()
        val iso = cameraController?.lastCaptureIso ?: 0
        val ns = cameraController?.lastCaptureShutterNs ?: 0L
        return base.copy(
            sensorIso = iso,
            sensorShutterSec = if (ns > 0L) ns / 1_000_000_000f else 0f,
            aperture = cameraController?.lastCaptureAperture ?: 0f,
            focalMm = cameraController?.lastCaptureFocalMm ?: 0f,
        ).toJson()
    }

    /**
     * 当前外置灯的档案快照（灯名 / MAC / 通道 / 触发指令）。
     * 在 shutterDown 的主线程读一次，落盘线程不再碰 filesDir；
     * 注册表条目给出「实际连的是哪盏」，档案给出协议细节。
     */
    private fun currentExternalFlashInfo(): ExtFlashInfo? {
        val entry = runCatching {
            com.filmcam.app.flash.ExternalFlashRegistry.preferredEntry()
        }.getOrNull() ?: return null
        val profiles = runCatching {
            com.filmcam.app.flash.FlashProfileStore(this).list()
        }.getOrDefault(emptyList())
        val p = profiles.firstOrNull { it.address in entry.knownAddresses }
            ?: profiles.maxByOrNull { it.pairedAt }
        return ExtFlashInfo(
            displayName = p?.displayName?.takeIf { it.isNotBlank() } ?: entry.displayName,
            address = p?.address ?: entry.knownAddresses.firstOrNull().orEmpty(),
            channel = p?.writeCharUuid.orEmpty(),
            triggerHex = p?.triggerHex.orEmpty(),
        )
    }

    private data class ExtFlashInfo(
        val displayName: String, val address: String, val channel: String, val triggerHex: String
    )

    /** 机顶 EV 键：到顶回卷。EXT 档下同一颗键改拨 0.5 EV/档的闪光灯细档。 */
    private fun cycleEvValue() {
        if (flash.mode == FlashMode.EXTERNAL) {
            // EXT 档按 0.3EV 走（用户 11-19 定），到顶回卷到 -3
            flashCompEv = if (flashCompEv >= 2.85f) -3f
            else quantizeFlashEv(flashCompEv + 0.3f)
            applyFlashCompUi()
        } else {
            setEv(if (exposureComp >= 3) -3 else exposureComp + 1)
        }
    }

    /** EV± 按钮与上下滑手势的统一分流：steps>0 加档，<0 减档。 */
    private fun stepEv(steps: Int, persist: Boolean = true) {
        if (flash.mode == FlashMode.EXTERNAL) {
            val next = quantizeFlashEv(flashCompEv + steps * 0.3f)
            if (next == flashCompEv) return
            flashCompEv = next
            applyFlashCompUi(persist)
        } else {
            val next = (exposureComp + steps).let {
                if (it > 3) -3 else if (it < -3) 3 else it
            }
            setEv(next, persist)
        }
    }

    /** 细档补偿落盘 + 下发相机 + 刷新全部读数（与 setEv 的同步纪律一致）。 */
    private fun applyFlashCompUi(persist: Boolean = true) {
        binding.tvEvValue.text = formatEv(flashCompEv)
        // 闪光补偿落到 TTL **目标亮度** 上（不是曝光增益，增益会被闭环抵消）
        cameraController?.externalTtlTargetEv = flashCompEv
        applyExposure()
        updateLcd()
        binding.viewfinderFrame.notifyActivity()
        if (persist) saveSettings()
    }

    /**
     * 把闪光补偿对齐到 **0.3EV** 网格（2026-11-19 用户定的步进）。
     *
     * 浮点累加会漂（0.3*3 = 0.8999999），所以所有改值的地方都过这一道收口，
     * 保证落盘值和界面读数永远是同一个数。
     */
    private fun quantizeFlashEv(v: Float): Float =
        (Math.round(v / 0.3f) * 0.3f).coerceIn(-3f, 3f)

    /** 读数格式：整数不带小数、半档显示 .5、正数带 +、0 显示 0。 */
    private fun formatEv(v: Float): String {
        val s = if (v == v.toInt().toFloat()) v.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", v)
        return if (v > 0f) "+$s" else s
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
            cameraController?.setExternalFlashComp(flashCompEv)
            // 按灯取同步偏移：氙气灯(A2 -80) 与 COB 灯(-200) 起辉不同；
            // 热调文件 flash_sync_offset.txt 仍可覆盖它（adb 扫参用）。
            cameraController?.setExternalFlashSync(
                flash.mode == FlashMode.EXTERNAL,
                flash.currentSyncOffsetMs(),
            )
            // A2 是三段式（LED → 氙气预闪 → 氙气主闪）：一张照片发 3 次触发，
            // 第 3 发才是主闪。其他灯给 1，行为完全不变。
            cameraController?.setExternalFlashStages(flash.triggerStages())
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
            // Pro 模式下手动 ISO 才是真正生效值；外置闪光档 ISO 由 TTL 反算，如实显示 A
            val iso = if (proMode && !isExternalFlash) {
                isoSteps[isoIdx]?.toString() ?: "A"
            } else {
                isoValues[isoValue]?.toString() ?: "A"
            }
            val count = "${currentShotCount()}/$FILM_MAX"
            val ev = if (flash.mode == FlashMode.EXTERNAL) formatEv(flashCompEv)
            else if (exposureComp > 0) "+$exposureComp" else "$exposureComp"
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
                    evSteps = if (flash.mode == FlashMode.EXTERNAL) flashCompEv
                    else exposureComp.toFloat(),
                    flashLabel = flashLabel,
                    battery = batteryIconLevel(),
                    // 快门：Pro 模式且非外置闪光时显示手动档；
                    // 外置闪光被同步模型锁死在 100ms 窗口，不显示免得误读成 1/100
                    shutterLabel = if (proMode && !isExternalFlash)
                        (if (shutterSteps[shutterIdx] != null) shutterLabels[shutterIdx] else "")
                    else ""
                )
            )
        } catch (_: Exception) {}
        // 腰平前盖读数跟随机顶 LCD 一起刷新（EV / 闪光 / 帧数同源同刻）
        updateWaistReadout()
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
        // Pro 模式手动快门：取景器右侧 500/125/30/LT 刻度必须跟着手动档走，
        // 否则「转盘转了、刻度不动」，用户会以为没生效
        // 🔴 单位：shutterSteps 里是**纳秒**，必须除 1e9 才是「秒」。
        // 之前写成 1e9/ns（算出来是 1/秒 的倒数，1/125 秒会变成 125），
        // shutterTagsFor 于是永远落在 LT 档，刻度一个都不亮（11-19 真机复现）。
        val shownSec = if (proMode && !isExternalFlash) {
            shutterSteps[shutterIdx]?.let { it / 1_000_000_000f } ?: lastExposureSec
        } else {
            lastExposureSec
        }
        // Pro 模式：按**手动档**映射到取景窗那列刻度（500/125/30/LT）。
        // 手动档有 7 档（1/8~1/500），刻度只有 4 个，所以速度落在两档之间时
        // 两档都亮（真机语义）。液晶屏另按实际速度显示，两边不冲突。
        val lit = if (proMode && !isExternalFlash) {
            proShutterLit.getOrElse(shutterIdx) { setOf("125") }
        } else {
            shutterTagsFor(shownSec)
        }
        val blink = shownSec > 0f && shownSec <= 1f / 1200f
        // 慢门警告（T3 手册里快门档位闪烁 = 当心手抖）：按胶卷算出来的快门已经慢到会抖
        // 🔴 Pro 模式下必须以**手动快门**为准：手动选了 1/125 还拿胶卷的慢门档闪"当心手抖"，
        // 那是吓唬人，而且刻度每 350ms 一明一灭，根本看不明白当前在哪一档（11-19 真机）。
        val slowWarn = if (proMode && !isExternalFlash) {
            shownSec >= 1f / 45f
        } else {
            val slowSec = filmShutterPreviewSec()
            slowShutterMode != FilmExposure.Mode.OFF &&
                slowSec != null && slowSec >= 1f / 45f
        }
        // WB 状态必须进 key：否则横滑换档时因为其它字段没变，被去重直接跳过不重画
        val currentEv = if (flash.mode == FlashMode.EXTERNAL) flashCompEv else exposureComp.toFloat()
        // shownSec 也要进 key：Pro 手动档与 AE 档可能落在同一组刻度标签上，
        // 只看 lit 会误判成「没变化」而不重画（11-19 真机：转盘拧了刻度不亮）。
        val key = "${focus.ordinal}|$currentEv|${lit.joinToString(",")}|$blink|$slowWarn|" +
            "${flash.mode}|$wbVisible|$wbIndex|$shownSec"
        if (key == lastIndicatorKey) return
        lastIndicatorKey = key
        binding.viewfinderFrame.setIndicators(
            ViewfinderFrameView.Indicators(
                focus = focus,
                evCompOn = currentEv != 0f,
                evSteps = currentEv,
                flash = if (flash.mode != FlashMode.OFF) ViewfinderFrameView.LedMode.ON
                        else ViewfinderFrameView.LedMode.OFF,
                shutterLit = lit,
                shutterBlink = blink,
                shutterSlow = slowWarn,
                // 白平衡标记：非 AUTO 时常驻（设置完就一直是这一片），横滑时也会亮出来
                wbOn = wbVisible || wbIndex != 0,
                wbIndex = wbIndex,
                wbCount = wbPresets.size,
                // Pro 模式：取景窗内直接显示当前 ISO，手指在铭牌上左右滑时眼睛不用离开取景器
                isoText = if (proMode && !isExternalFlash) {
                    val v = isoLabels[isoIdx]
                    if (v == "A") "ISO AUTO" else "ISO $v"
                } else null
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
    /**
     * 快门刻度点亮哪几档。
     *
     * 真机 T3 的取景器右侧只有四档：**500 / 125 / 30 / LT**，每一档是一个区间：
     * 1/500~1/250 算 500 档、1/125~1/60 算 125 档、1/30~1/15 算 30 档、更慢算 LT。
     * Pro 模式下的手动档与这四档一一对应（[shutterSteps]），所以推拉刻度时
     * 点亮的数字和液晶屏上的 TV 值永远是同一档。
     */
    private fun shutterTagsFor(sec: Float): Set<String> {
        if (sec <= 0f) return emptySet()
        return when {
            sec <= 1f / 250f -> setOf("500")
            sec <= 1f / 60f -> setOf("125")
            sec <= 1f / 15f -> setOf("30")
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

    // vc86：外闪配对向导结果通道。向导保存档案后回传 RESULT_OK，
    // 这里立即按新档案重建 BLE 会话（startScan 内部会 syncLearned），
    // 用户回到取景器即可按快门，无需重启 App。
    private val flashPairLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ok = result.resultCode == android.app.Activity.RESULT_OK
        if (flashPairAutoFromExt && !ok) {
            // 自动弹出的向导没配成：EXT 档没有可用灯，退回 OFF
            flashPairAutoFromExt = false
            flash.mode = FlashMode.OFF
            flash.setBuiltInEnabled(false)
            binding.tvFlashStatus.text = getString(R.string.main_flash_off)
            applyExposure()
            updateLcd()
            binding.viewfinderFrame.notifyActivity()
            saveSettings()
            Toast.makeText(this, getString(R.string.main_flash_pair_cancel), Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        flashPairAutoFromExt = false
        if (ok && flash.mode == FlashMode.EXTERNAL) {
            // 🔴11-18 真机 bug：原来这里调 startScan()，但 startScan 的「已有会话」
            // 幂等判断会直接跳过 —— 存档 A2 后仍连着存档前建的 FG-COB 旧会话，
            // 拍照触发帧写错灯（日志里 FG-COB 空转重连 277 次）。必须显式丢弃旧
            // 会话按最新档案重建，新灯才真正生效。
            runCatching { flash.rebuildSession() }
                .onFailure { android.util.Log.w("FlashDiag", "配对返回后建连失败", it) }
            // offset 随当前灯切换（氙气灯 -80 / COB -200），立即同步给相机时序层
            applyExposure()
            // 配对成功回到取景器：机顶 LCD 明确报一次 PAIRED，别让用户怀疑没存上
            showLcdNotice(getString(R.string.flash_pair_lcd_paired))
        }
    }
    /** 本次向导是不是「切到 EXT 自动弹出」——决定取消时是否退回 OFF。 */
    private var flashPairAutoFromExt = false

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
        // v74 P2：诊断报告记录 UI 档位，便于区分「没切档」与「切了档 HAL 没关灯」
        runCatching { DeviceGeometryReport.put("flash.uiMode", flash.mode.name) }
        binding.tvFlashStatus.text = when (flash.mode) {
            FlashMode.OFF -> getString(R.string.main_flash_off)
            FlashMode.BUILT_IN -> getString(R.string.main_flash_internal)
            FlashMode.EXTERNAL -> getString(R.string.main_flash_external)
        }
        if (flash.mode == FlashMode.EXTERNAL) {
            // vc86：切到 EXT 时若一个外灯都没配过，自动进配对向导（用户拍板的产品流程：
            // 切档 → 弹配对 → 配完即用；下次切 EXT 直接用档案连灯，不再打扰）。
            // 已有档案（含内置 FotorGear 条目可匹配的灯）则照旧直接建连。
            if (runCatching {
                    com.filmcam.app.flash.FlashProfileStore(this).list().isEmpty()
                }.getOrDefault(true)) {
                flashPairAutoFromExt = true
                Toast.makeText(this, getString(R.string.main_flash_pair_needed), Toast.LENGTH_SHORT).show()
                runCatching {
                    flashPairLauncher.launch(
                        android.content.Intent(this, com.filmcam.app.flash.FlashPairActivity::class.java)
                    )
                }.onFailure {
                    flashPairAutoFromExt = false
                    android.util.Log.w("FlashDiag", "自动打开配对向导失败", it)
                }
            } else {
                // 已有档案：状态字直接带出灯名，不干等下一次 BLE 状态回调
                val name = runCatching {
                    com.filmcam.app.flash.FlashProfileStore(this).list().firstOrNull()?.displayName
                }.getOrNull()
                if (!name.isNullOrBlank()) {
                    binding.tvFlashStatus.text = getString(
                        R.string.main_external_status_named, name, "READY"
                    )
                }
                // 建连失败也不能吞掉后面的相机同步 / LCD / 落盘，否则看起来像「按键没反应」
                runCatching { flash.startScan() }
                    .onFailure { android.util.Log.w("FlashDiag", "切档时建连失败", it) }
            }
        }
        // 内置闪光灯档位变化要同步给相机：切进来 = 拍照闪光模式，切走 = 关掉
        flash.setBuiltInEnabled(flash.mode == FlashMode.BUILT_IN)
        applyExposure()
        updateLcd()
        // 切到 EXT：明确告诉用户机顶拨盘已换语义（0.5 EV/档的闪光灯补偿）
        if (flash.mode == FlashMode.EXTERNAL) {
            showLcdNotice(getString(R.string.lcd_notice_flash_ev_fine))
        }
        // 点亮取景器内的闪光指示
        binding.viewfinderFrame.notifyActivity()
        saveSettings()
    }

    // ===== Pro 模式（2026-11-19 按用户方案重做）=====
    // 入口：点机身铭牌「CONTAX T3」，铭牌右侧浮出 PRO 徽标。
    // 🔴 第二版按用户意见**去掉了两枚滚花转盘**（太丑，横屏也塞不下）：
    //   - 快门：手指在**取景窗右侧那列快门数字**（500/125/30/LT）上推拉；
    //   - ISO：手指在**取景窗下方的铭牌那条**左右滑。
    // 进 Pro 后取景窗指示灯常亮（alwaysOn），ISO 与快门在取景窗内直接读数，
    // 机顶液晶屏同步显示 ISO / TV。
    //
    // 🔴 外置闪光档下这两处都锁着（同步模型接管曝光，氙气只闪约 1ms、光圈固定 f/1.7，
    // 同步模型把曝光固定成 100ms 窗口、ISO 由 TTL 反算；氙气只闪约 1ms、光圈固定 f/1.7，
    // 这台机器在闪光曝光上没有任何可拧的机械量。给一个「拧了画面不变」的旋钮，
    // 比灰着更糟。闪光亮度的唯一手动杠杆是机顶 EV 键（±3EV，落到 TTL 目标偏移上）。
    private var proMode = false

    // 🔴 快门档位：T3 全 7 档（1/8 ~ 1/500），比取景窗上那 4 个刻度细。
    // 取景窗刻度只有 500 / 125 / 30 / LT 四档，**不代表可选档位只有 4 个**：
    // 手动档给的是 7 档，液晶屏如实显示当前速度（125 / 250 / 60 …），
    // 取景窗那列刻度则按"当前速度落在哪两档之间"点亮（11-19 用户要求档位分细点）。
    // 顺序：index 越小 = 越快（1/500 最快）。
    private val shutterSteps = listOf<Long?>(
        2_000_000L,       // 1/500
        4_000_000L,       // 1/250
        8_000_000L,       // 1/125
        16_666_667L,      // 1/60
        33_333_333L,      // 1/30
        66_666_667L,      // 1/15
        125_000_000L,     // 1/8
    )
    private val shutterLabels = listOf("500", "250", "125", "60", "30", "15", "8")
    /** 每个手动档在取景窗那列刻度上该点亮哪几个（真机语义：速度落在两档之间就两档都亮） */
    private val proShutterLit = listOf(
        setOf("500"),           // 1/500
        setOf("500", "125"),    // 1/250
        setOf("125"),           // 1/125
        setOf("125", "30"),     // 1/60
        setOf("30"),            // 1/30
        setOf("30", "LT"),      // 1/15
        setOf("30", "LT"),      // 1/8
    )
    private val isoSteps = listOf<Int?>(null, 100, 200, 400, 800, 1600, 3200)
    private val isoLabels = listOf("A", "100", "200", "400", "800", "1600", "3200")
    private var isoIdx = 3        // 默认 ISO 400
    private var shutterIdx = 2    // 默认 1/125（取景窗上正好点亮 125）

    private val isExternalFlash: Boolean get() = flash.mode == FlashMode.EXTERNAL

    /**
     * Pro 模式下的手动档（11-19 用户拍板：**不要转盘**）。
     *
     * 转盘那版太丑，横屏还塞不下（右侧栏是竖排的）。改成两处直接上手：
     *  - **快门**：手指在取景窗右侧那列快门数字（500/125/30/LT）上推拉；
     *  - **ISO**：手指在取景窗下方 CONTAX T3 铭牌那条左右滑。
     *
     * 进 Pro 后取景窗指示灯**常亮**（`alwaysOn`），两个数值实时可见，机顶液晶屏同步。
     *
     * 🔴 外置闪光档下这两处都锁着：同步模型把曝光固定成 100ms 窗口、ISO 由 TTL 反算，
     * 氙气只闪约 1ms、光圈固定 f/1.7，这台机器在闪光曝光上没有任何可拧的机械量。
     * 给一个「拧了画面不变」的控件比锁着更糟。
     */
    private var shutterDragAcc = 0f
    // 🔴 一次手势（按下→抬起）只调一档：达到阈值调一次后即置位，本次余下 MOVE 不再动。
    // 11-20 用户：「一次滑动改变一次参数，这样能精细调整」——原先按距离连续累加，
    // 手一滑就跳好几档，数值根本收不住。
    private var shutterStepped = false
    private var brandDownX = 0f
    private var brandDownY = 0f
    private var brandDragging = false
    private var brandIsoAcc = 0f
    private var brandIsoStepped = false

    /** 一次手势调一档所需的滑动距离：拉长到 72dp，滑一小段不跳档（11-20 用户）。 */
    private val knobStepPx: Float get() = 72f * resources.displayMetrics.density

    /**
     * 快门刻度列拖动：上推 = 更快的快门（索引变小）。
     * 一次手势只走一档，方向由本次手势累计位移的符号决定。
     */
    private fun onShutterDrag(dy: Float) {
        if (!proMode) return
        if (isExternalFlash) {
            // 锁着：给一记短震表示「这个拨盘现在不动」
            if (kotlin.math.abs(dy) > 6f) vibrate(12, 70)
            return
        }
        if (shutterStepped) return
        shutterDragAcc += dy
        val step = knobStepPx
        // index 越小 = 越快；上推（dy 为负）往前一档，下拉往后一档。
        if (shutterDragAcc <= -step) {
            shutterStepped = true
            stepShutter(-1)
        } else if (shutterDragAcc >= step) {
            shutterStepped = true
            stepShutter(+1)
        }
    }

    /**
     * 快门**不循环**：推到 500（最快）或 1/8（最慢）就停住。
     *
     * 🔴 11-19 用户明确：快门要按取景窗显示的逻辑走 —— 往上是 1/500，
     * 往下是 1/30，撞到两端就停，不许绕回另一头（绕回会让"再往上推"变成变慢，
     * 手上完全没有方向感）。
     */
    private fun stepShutter(delta: Int) {
        val next = (shutterIdx + delta).coerceIn(0, shutterSteps.size - 1)
        if (next == shutterIdx) return
        shutterIdx = next
        applyProSettings()
        vibrate(10, 90)
    }

    /** ISO：右滑 = 变大，撞到两端同样停住（不循环）。 */
    private fun stepIso(delta: Int) {
        if (!proMode) return
        if (isExternalFlash) {
            if (delta != 0) vibrate(12, 70)
            return
        }
        val next = (isoIdx + delta).coerceIn(0, isoSteps.size - 1)
        if (next == isoIdx) return
        isoIdx = next
        applyProSettings()
        vibrate(10, 90)
    }

    private var isoDragAcc = 0f
    private var isoStepped = false

    /**
     * 横屏 ISO 手势带：上滑 = ISO 变大。
     *
     * 🔴 11-20 用户：一次手势只调一档（精细），滑动距离拉长到 72dp ——
     * 原先按距离连续累加，一滑就跳好几档。方向由本次手势累计位移符号决定。
     */
    private fun onIsoDrag(dy: Float) {
        if (!proMode) return
        if (isExternalFlash) {
            if (kotlin.math.abs(dy) > 6f) vibrate(12, 70)
            return
        }
        if (isoStepped) return
        isoDragAcc += dy
        val step = knobStepPx
        if (isoDragAcc <= -step) {
            isoStepped = true
            stepIso(+1)
        } else if (isoDragAcc >= step) {
            isoStepped = true
            stepIso(-1)
        }
    }

    /**
     * 横屏：把「取景窗右缘 ↔ 快门键」那条缝设成 ISO 手势带。
     *
     * 横屏右侧栏是竖排的（暗房 / 快门 / 胶卷），铭牌在左边栏根本够不着；
     * 取景窗和快门键之间正好是一条空带，在那儿上下推拉最顺手（11-19 用户指定）。
     * 竖屏不用这条 —— 竖屏在铭牌那一行左右滑。
     */
    private fun updateIsoGestureZone() {
        val frame = binding.viewfinderFrame
        val density = resources.displayMetrics.density
        if (!chromeLandscape) {
            // 竖屏 ISO 走铭牌那一整条横带（onBrandTouch），不需要取景窗里的竖缝
            if (frame.isoGestureZone != null) frame.isoGestureZone = null
            return
        }
        val rect = frame.contentRect()
        // 布局还没完成时（预览矩形为空）就等下一次布局回调
        if (rect.isEmpty || frame.width == 0) return
        val pad = 6f * density
        val left = rect.right + pad
        // 🔴 btnShutter.left 是**相对父容器 bottomBar** 的坐标（横屏栏有 10dp paddingStart，
        // 所以读出来是 26），不是屏幕坐标 —— 直接拿来算会得到 1px 宽的窄缝。
        // 要加上下栏自身的 left 才是屏幕位置。
        val bar = binding.bottomBar
        val shutterScreenLeft = (bar.left + binding.btnShutter.left).toFloat()
        val right = (left + 42f * density)
            .coerceAtMost(shutterScreenLeft - pad)
            .coerceAtLeast(left + 1f)
        val zone = android.graphics.RectF(left, 0f, right, frame.height.toFloat())
        if (frame.isoGestureZone != zone) frame.isoGestureZone = zone
    }

    /**
     * 铭牌（CONTAX T3）上的手势：**单击 = 进出 Pro**，左右滑 = 调 ISO（仅竖屏）。
     *
     * 🔴 横屏时铭牌在左边栏，ISO 走「取景窗 ↔ 快门键」那条竖缝（上下推拉），
     * 铭牌上左右滑**不**调 ISO（11-19 用户明确：横屏不要在 logo 处调）。
     *
     * 滑动优先于单击：位移超过阈值就认定为调 ISO，不再切换 Pro 模式。
     *
     * 🔴 这里用 `event.x/event.y` 而不是 `rawX/rawY`：部分 ROM（这台 S25U 实测）
     * 注入/合成的触摸事件里 rawX 会是 0，于是 dx 恒为负，滑动方向整个反过来，
     * 表现为「怎么滑都调不动 / 越滑越小」。相对坐标跟 View 自身对齐，最稳。
     */
    private fun onBrandTouch(event: android.view.MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                brandDownX = event.x
                brandDownY = event.y
                brandDragging = false
                brandIsoAcc = 0f
                brandIsoStepped = false
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dx = event.x - brandDownX
                val dy = event.y - brandDownY
                if (!brandDragging &&
                    kotlin.math.abs(dx) > 18f * density &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f
                ) {
                    brandDragging = true
                    vibrate(18, 130)   // 进入滑档给一记轻触，确认「抓住了」
                }
                // 🔴 11-20 用户：一次手势只调一档，滑动距离拉长（72dp）。
                // 左滑/右滑方向由本次手势累计位移定，达到阈值调一档即锁定，
                // 本次余下的 MOVE 不再累加 —— 想调第二档就抬手再滑一次。
                if (brandDragging && !chromeLandscape && !brandIsoStepped) {
                    brandIsoAcc += dx
                    val step = knobStepPx
                    if (brandIsoAcc >= step) {
                        brandIsoStepped = true
                        stepIso(+1)
                    } else if (brandIsoAcc <= -step) {
                        brandIsoStepped = true
                        stepIso(-1)
                    }
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (!brandDragging) {
                    vibrate(24, 185)
                    toggleProMode()
                }
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    private fun toggleProMode() {
        proMode = !proMode
        syncProModeUi()
        if (proMode) {
            applyProSettings()
        } else {
            isoIdx = 3
            shutterIdx = 2
            cameraController?.externalTtlEnabled = false
            applyAutoExposure()
        }
        updateLcd()
        // 进出 Pro 都要让取景器刻度重画一次：进 Pro 走手动档、出 Pro 回到 AE 档
        runCatching { updateViewfinderIndicators(lastAfState, lastExposureSec, lastAeIso) }
    }

    /**
     * 把 Pro 模式的**可见状态**压到当前 chrome 上（徽标 + 取景器常亮）。
     *
     * 🔴 11-19 真机 bug：横竖屏旋转后 PRO 徽标不见了，但模式其实还在（手动档仍生效）。
     * 根因：旋转走 [swapChromeForOrientation]，那里重新 inflate 了一套 chrome，
     * 新 chrome 里的 `tvProTag` 按布局默认值是 GONE；而 `proMode` 是 Activity 的字段，
     * 旋转不会重建 Activity，所以它还留着 true —— 两边对不上，徽标就没了。
     * 修法：换完 chrome 后立刻重新同步一次可见状态。
     */
    private fun syncProModeUi() {
        binding.tvProTag.visibility =
            if (proMode) android.view.View.VISIBLE else android.view.View.GONE
        // 过卷 / 自拍倒数 / 回卷期间遮罩盖住预览，铭牌也要一起收掉。
        // 旋转重建后新铭牌默认 VISIBLE，若不重新压回去会浮在黑幕上（11-20 用户实测）。
        binding.proBrandRow.visibility =
            if (binding.developingOverlay.visibility == android.view.View.VISIBLE)
                android.view.View.INVISIBLE else android.view.View.VISIBLE
        // Pro 模式常亮：指示灯不再 8 秒自动熄灭，参数一直看得见
        binding.viewfinderFrame.alwaysOn = proMode
    }

    private fun applyProSettings() {
        try {
            val c = cameraController ?: return
            if (isExternalFlash) {
                // 外置闪光：曝光由同步模型接管，转盘锁着；这里只保证 TTL 与手动值一致
                c.setManualIso(isoSteps[isoIdx])
                c.setManualExposure(shutterSteps[shutterIdx])
                c.externalTtlEnabled = true
            } else {
                // 任一轴 AUTO ⇒ 整体交回 AE，杜绝「半锁」
                // （AE 一旦被关掉，另一轴留空会取相机默认，常常是极短曝光）
                c.setManualIso(isoSteps[isoIdx])
                c.setManualExposure(shutterSteps[shutterIdx])
                c.externalTtlEnabled = false
            }
            updateLcd()
            // 手动档一改，取景器右侧的快门刻度必须立刻跟上；
            // 等相机下一次元数据回调会有延迟，而且 AE 被接管后回调可能干脆不来。
            runCatching { updateViewfinderIndicators(lastAfState, lastExposureSec, lastAeIso) }
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.main_toast_settings_failed, e.message),
                Toast.LENGTH_SHORT
            ).show()
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
        if (!hasPermissions() && !permissionDeniedHandled) {
            ActivityCompat.requestPermissions(this, permissions, REQ_CODE)
        }
    }

    override fun onPause() {
        com.filmcam.app.develop.DevelopmentState.removeListener(devStateListener)
        super.onPause()
        saveSettings()  // 兜底：退出/后台时保存设置
        // 进后台：释放相机 + 暂停 GL 渲染，避免回前台时相机会话冲突导致预览卡死
        // v74 P0：会话还活着时先显式取消预闪/灭灯，不能只靠 stop() 的 close 断电
        cameraController?.setFlashEnabled(false)
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
        // 腰平彩蛋总闸同理：工程模式里刚改的 off / auto / simulate，回前台立刻生效
        val egg = prefs.getString("waistEgg", "auto") ?: "auto"
        if (egg != waistEgg) {
            waistEgg = egg
            waistDismissed = false
            applyWaistLevel()
        }
        // RAW 负片开关同理：控制台里刚拨的，回到取景器就要生效
        rawNegativeEnabled = prefs.getBoolean("filmcam_dev_raw", false)
        cameraController?.rawNegativeEnabled = rawNegativeEnabled
        // onResume 回前台时相机可能已经在跑：同样检查 RAW 能力并从 LCD 告知
        if (rawNegativeEnabled && cameraController?.rawCapable == false) {
            showLcdNotice(getString(R.string.lcd_notice_raw_unavailable))
        }
        val newTrigger = FilmExposure.Trigger.fromPrefs(
            prefs.getInt("slowTrigger", DefaultSettings.slowTrigger.prefs)
        )
        val newMode = FilmExposure.Mode.fromPrefs(
            prefs.getInt("slowShutter", DefaultSettings.slowMode.prefs)
        )
        if (newTrigger != slowTrigger || newMode != slowShutterMode) {
            slowTrigger = newTrigger
            slowShutterMode = newMode
            lastIndicatorKey = ""   // 机制变了，取景器警告灯按新档重画
            updateViewfinderIndicators(null, lastExposureSec, lastAeIso)
        }
        // v82 段 2 进行中回前台：LCD 提示早被常规读数冲掉，按当前段位重新出一条
        if (OnboardingGuide.stage(this) in 1 until OnboardingGuide.STAGE_DONE) {
            showOnboardingHint()
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
        // v82 段 3 收口：首卷冲洗完成出贺词并归档（此后不再提醒）
        val firstRoll = !OnboardingGuide.firstRollDone(this)
        runOnUiThread {
            if (firstRoll) OnboardingGuide.markFirstRollDone(this)
            showLcdNotice(
                getString(
                    if (firstRoll) R.string.lcd_notice_first_dev_done
                    else R.string.lcd_notice_dev_done,
                    st.done
                )
            )
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
        if (requestCode != REQ_CODE) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            // 权限通过后，等 onSurfaceTextureReady 回调自动启动相机
            return
        }
        permissionDeniedHandled = true
        // 多组被拒时只弹一张铭牌，按「没它就无法拍照」的相关度取最要紧的一组。
        // grantResults 可能短于 permissions（用户中途取消），缺项按被拒处理。
        val denied = permissions.filterIndexed { i, _ ->
            i >= grantResults.size || grantResults[i] != PackageManager.PERMISSION_GRANTED
        }
        val kind = when {
            denied.any { it == Manifest.permission.CAMERA } ->
                PermissionNameplate.Kind.CAMERA
            denied.any {
                it == Manifest.permission.BLUETOOTH_SCAN ||
                    it == Manifest.permission.BLUETOOTH_CONNECT ||
                    it == Manifest.permission.ACCESS_FINE_LOCATION
            } -> PermissionNameplate.Kind.BLUETOOTH
            denied.any { it == Manifest.permission.WRITE_EXTERNAL_STORAGE } ->
                PermissionNameplate.Kind.STORAGE
            else -> PermissionNameplate.Kind.CAMERA
        }
        PermissionNameplate.show(this, kind)
    }

    private fun startCamera(surfaceTexture: SurfaceTexture) {
        cameraController = Camera2Controller(this)
        // 外置闪光相机侧 TTL 的测光结果：LCD 上给一行短反馈（测光 ISO → 正式 ISO）。
        // 真正的曝光修正已经由控制器写回 manualIso，这里只是让人知道它做了什么。
        cameraController?.onExternalTtlResult = { meterIso, finalIso, _, _ ->
            runOnUiThread {
                binding.lcdDisplay.showMessage("TTL $meterIso→$finalIso", 1800L)
            }
        }
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
        // v75 P1：setFlashEnabled 仍在最后补挂当前档位，但现在预览只下发
        // AE_ON + FLASH_MODE_OFF（ALWAYS_FLASH 已收进 STILL_CAPTURE 帧），
        // 不会再出现「预览常驻点灯」的状态。
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
            // 外置氙气灯：刚放过一发，电容需要数秒才能充满，期间的成片会明显偏弱。
            // 实测（11-19，A2）：间隔 2.6s 的第二张比首张暗 29%，间隔 1.9s 的第三张
            // 与之持平 —— 所以提示给 4s（3s 不够）。只提示、不拦快门，保住机械手感。
            if (flash.mode == FlashMode.EXTERNAL && flash.needsRecycleHint()) {
                runOnUiThread {
                    binding.lcdDisplay.showMessage(
                        getString(R.string.main_flash_recharging),
                        FLASH_RECHARGE_HINT_MS
                    )
                }
            }
            saveExecutor.execute {
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
                    val iso = isoValues[isoValue]
                    val count = UndevelopedFilmStore.saveFrame(
                        roll.rollId, bytes, System.currentTimeMillis(), iso, rotation,
                        shotJson = shotJsonWithCapture(), rawDng = rawDng
                    )
                    runOnUiThread {
                        // v82 段 2 检测点：首张落卷 = 三步实操走完，停掉超时轮转，
                        // 「拍第一张」提示由段 3 的 1/36 提醒接力（不两条连着刷屏）
                        if (OnboardingGuide.onShotTaken(this)) stopOnboardingTimeout()
                        // v82 段 3 首卷护栏：第 1 / 12 张各提醒一次去暗房
                        if (OnboardingGuide.guardFrameHit(this, count)) {
                            showLcdNotice(
                                getString(
                                    if (count == OnboardingGuide.GUARD_FRAME_FIRST)
                                        R.string.guide_first_frame
                                    else R.string.guide_third_frame
                                )
                            )
                        }
                        if (count >= FILM_MAX) {
                            promptDevelopFullRoll()
                        }
                        updateLcd()
                        updateFilmInfo()
                        // 拍完把白平衡再下发一次：HAL 在 STILL_CAPTURE 之后偶尔会把 AWB
                        // 拨回 AUTO，表现为「设置好的校色片拍一张就没了」。
                        applyWbIndex()
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
        setDevelopingOverlayVisible(true)
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
                    setDevelopingOverlayVisible(false)
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
        // 11-21：机身按键＝机械动作，EL 背光跟着亮一次（4s 后 600ms 淡出）。
        // 放在 hapticClick 里一次覆盖所有机顶/底部键，不必逐个挂；
        // 快门/换卷另有 showMessage 自己 pulse，这里是普通功能键的那一记。
        setOnClickListener {
            vibrate(ms, amp)
            runCatching { binding.lcdDisplay.pulseBacklight() }
            action()
        }
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
            "shutterDown seq=$seq mode=${flash.mode} ev=$exposureComp " +
                "flashEv=$flashCompEv raw=$rawNegativeEnabled " +
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
        // 11-21 叶片闭合视觉：开帘同帧把取景窗压暗一下再放开（纯 View，不改成像/时序）。
        // 只做这一下（慢门闭帘不再补，避免重现 v15 用户反感的"双反馈"）。
        binding.viewfinderFrame.shutterVeilPulse()
        // 外置灯的 BLE 触发不再放在这里：灯会在 t+0 闪完，而 HAL 要 ~240ms
        // 后才真正开曝光，闪光根本进不了画面。改由 Camera2Controller 的
        // onExposureStarted 在传感器开始积分那一刻发指令（见 startCamera）。
        // 11-21：快门的按压缩放已移到 btnShutter 的 OnTouchListener（attachPressFeedback），
        // 按下即沉、抬手弹回，覆盖「按下→成像」的空窗；这里不再重复触发缩放（会打架）。

        if (slowNs > 0L) {
            // v15（11-18）：慢门原本在闭帘时再补一记快门声 + 一记振动（两声、抖一下），
            // 用户反馈困扰。现在整条链路只保留开帘那一记反馈（见上 playShutterFeedback），
            // 闭帘不再补声/补振 —— 曝光/过卷时序不变，只是少一次重复反馈。
            postAt(seq, closeAt + 40) { showDevelopingAnimation(seq, advanceAt) }
        } else {
            // 常规：快门 + 过卷一体声。黑幕延后 260ms 让叶片压暗完整演完（60+180=240ms，11-21），
            // 马达声/齿轮触感/收尾的绝对时刻不变（showDevelopingAnimation 内仍按 advanceAtMs）。
            showDevelopingAnimation(seq, advanceAt, overlayDelayMs = 260L)
        }

        // 闪光灯按键触发，立即拍照
        binding.glSurfaceView.post {
            try {
                // 模拟失焦：工程师模式设定的概率触发对焦不准（0-100%，0=关闭）。
                // 慢门本身已经是「废片」的一个来源，同一张再叠失焦就太狠了，概率减半。
                val prob = if (slowNs > 0L) softFocusProb / 2 else softFocusProb
                val defocus = softFocusEnabled && java.util.Random().nextInt(100) < prob
                // 外闪档顺手把「这一帧用的是哪盏灯、哪个通道、哪条指令」刻进快照：
                // 过曝复盘时只看 ISO/快门还不够，灯与触发帧同样是配方的一部分。
                val extInfo = if (flash.mode == FlashMode.EXTERNAL) currentExternalFlashInfo() else null
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
                    zoom = cameraController?.currentZoomRatio ?: 0f,
                    // 外闪细档补偿随帧落盘，冲洗时进 EXIF UserComment，便于复盘 / 拍摄同款
                    flashCompEv = if (flash.mode == FlashMode.EXTERNAL) this.flashCompEv else 0f,
                    flashDevice = extInfo?.displayName.orEmpty(),
                    flashAddress = extInfo?.address.orEmpty(),
                    flashChannel = extInfo?.channel.orEmpty(),
                    flashTriggerHex = extInfo?.triggerHex.orEmpty(),
                    // 腰平方窗拍的是 120 6×6（1:1）；整屏 / 非方窗一律 135（3:2）。
                    // 冲洗端按帧选片门与片框版式，老帧没这个字段回落 135。
                    gate = if (waistApplied && kotlin.math.abs(waistGateAspect - 1f) < 0.01f) {
                        app.spektralab.engine.FilmStockMapper.FilmGate.MF6x6.key
                    } else {
                        app.spektralab.engine.FilmStockMapper.FilmGate.F135.key
                    },
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
     * 过卷 / 自拍倒数 / 回卷的黑色遮罩统一显隐。
     *
     * 🔴 11-20 用户实测：竖屏按快门后过卷时 CONTAX T3 铭牌还浮在黑幕上。
     * 根因不是遮罩没盖住，而是 wireChrome() 给 proBrandRow 调了 bringToFront()
     * （原本是为了铭牌触摸不被底栏抢走），导致它 z 序在遮罩之上。
     * 遮罩显示时把铭牌一并隐藏，收尾再恢复；用 INVISIBLE 保留占位，
     * 不改变取景窗几何 / 底栏布局。
     */
    private fun setDevelopingOverlayVisible(show: Boolean) {
        binding.developingOverlay.visibility =
            if (show) android.view.View.VISIBLE else android.view.View.GONE
        binding.proBrandRow.visibility =
            if (show) android.view.View.INVISIBLE else android.view.View.VISIBLE
    }

    /**
     * 自动过卷动画：拍照后模拟 T3 自动过卷（胶卷条滚动动画 + 过卷声 + 微弱震动），照片在后台处理。
     * @param seq 本次拍摄序号（序号不对说明这张已经翻篇，整个过卷作废）
     * @param advanceAtMs 马达进场的时刻，相对本次拍摄起点
     */
    private fun showDevelopingAnimation(
        seq: Int, advanceAtMs: Long, overlayDelayMs: Long = 0L
    ) {
        if (captureSeq != seq) return   // 慢门会把这段延迟到曝光结束，期间这一张可能已经翻篇

        // 11-21 叶片闭合视觉：常规档黑幕延后 VEIL_LEAD_MS 显示，让取景窗压暗先演完；
        // 只延迟黑幕这一层视觉，马达声/齿轮触感/收尾全部仍按 advanceAtMs 的绝对时刻走。
        if (overlayDelayMs > 0L) {
            postAt(seq, overlayDelayMs) {
                if (captureSeq != seq) return@postAt
                setDevelopingOverlayVisible(true)
                binding.ivDeveloping.setIcon(currentRoll.iconResId)
                binding.ivDeveloping.visibility = android.view.View.VISIBLE
                binding.tvDevelopingText.text = "FILM ADVANCING..."
                binding.tvDevelopingText.visibility = android.view.View.VISIBLE
                startDevelopingAnimation()
            }
        } else {
            setDevelopingOverlayVisible(true)

            // 显示当前胶卷条，模拟胶片被马达拖过片门
            binding.ivDeveloping.setIcon(currentRoll.iconResId)
            binding.ivDeveloping.visibility = android.view.View.VISIBLE
            binding.tvDevelopingText.text = "FILM ADVANCING..."
            binding.tvDevelopingText.visibility = android.view.View.VISIBLE

            // 过卷等待动画：胶卷条匀速滚动，持续到过卷走完（胶片相机拍完需等冲洗，动画连贯不跳变）
            startDevelopingAnimation()
        }

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
        setDevelopingOverlayVisible(false)
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
        /** EXT 档闪光灯细档补偿（EV，0.5 步进） */
        const val EXTRA_SAME_FLASH_EV = "same_flash_ev"
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
            EXTRA_SAME_FLASH, EXTRA_SAME_WB, EXTRA_SAME_EV,
            EXTRA_SAME_FLASH_EV, EXTRA_SAME_STYLE
        )
    }
}
