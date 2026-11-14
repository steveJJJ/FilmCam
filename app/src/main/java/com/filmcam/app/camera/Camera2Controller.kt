package com.filmcam.app.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.graphics.SurfaceTexture
import android.view.Surface
import android.widget.Toast
import app.spektralab.engine.FilmStockMapper
import java.io.File
import java.lang.Exception

/**
 * Camera2 控制器
 * 支持手动 ISO / 快门 + 全分辨率拍照
 */
class Camera2Controller(private val context: Context) {

    /** internal 供单测访问（pickRawSize 是纯函数） */
    internal companion object {
        const val FLASH_PRECAPTURE_TIMEOUT_MS = 700L

        /**
         * 外置闪光的**固定**曝光量（以 100ms 同步快门下的 ISO 表示）。
         *
         * 闪光是独立光源，强度不随环境光变化，所以绝不能跟着 AE 基线缩放：
         * 那样换个稍亮的房间 ISO 会被算到设备下限，闪光立刻欠曝成黑片。
         * 真机闪光灯也是固定光圈/ISO/同步快门，只靠闪光补偿拨盘调。
         *
         * 真机逐档扫描（暗场、灯距固定）：ISO 57 @100ms → RAW 净信号 284、
         * p90=562、p99=966、饱和 1.68%（不过曝的直闪片）；ISO 289 时 68% 饱和。
         * `flash_sync_gain.txt` 是倍率（**默认 4.0**，与常量一致），EV 拨盘按 2^EV 调；
         * 实际下发 ISO = 57 × gain × 2^EV（默认 228）。
         * ⚠️ 这是 S25U 单台标定值，跨厂商不可移植；P0 只保证「出图 + 按本机
         * ISO 刻度守恒」，绝对曝光量的机型差异留给 P1 的 ISP 引导定标。
         */
        private const val FLASH_SYNC_ISO_AT_100MS = 57f
        private const val FLASH_SYNC_GAIN_DEFAULT = 4.0f

        /** 闪光同步窗口物理下限：灯脉冲几十毫秒，窗口再窄会漏闪。 */
        private const val MIN_SYNC_SHUTTER_NS = 8_000_000L
        /** 闪光同步窗口上限（S25U 设备上限 176ms，留 26ms 余量）。 */
        private const val MAX_SYNC_SHUTTER_NS = 150_000_000L

        /**
         * 灯的固件固有延迟：从收到 05 xx 到 COB 真正出光约 200ms（官方所谓
         * 「预闪 → 同步触发」序列）。真机逐档扫描（RAW 净信号）证实：
         *   提前 250/200/150ms → 953/957/956（闪光进画面）
         *   曝光开始 / +50 / +100ms → ~11（完全没进）
         * 所以必须相对曝光开始提前 200ms 发指令。
         */
        private const val DEFAULT_FLASH_SYNC_OFFSET_MS = -200L
        /**
         * 外置闪光的曝光窗口。BLE 写入要等连接间隔（约 20–50ms），COB 灯脉冲
         * 又是几十到上百毫秒，窗口太窄会漏掉闪光。100ms 足够兜住，同时按
         * 同一曝光量把 ISO 折低，环境光不会因此过曝 —— 出来的就是慢速同步直闪。
         * 设备上限 0.176s（S25U 实测），100ms 留了余量。
         */
        private const val FLASH_SYNC_SHUTTER_NS = 100_000_000L

        /**
         * 三星 HAL 从提交 capture 到真正开曝光的延迟，**按通路分开**：
         *  - JPG 直提交（EXTERNAL 档 AE_OFF）：11-07 18:44 七帧实测
         *    39 / 42 / 41 / 26 / 37 / 31 / 14ms，中位 ~35ms；
         *  - RAW 多挂一路 RAW_SENSOR surface：v30 实测 265~274ms。
         * 两套样本混在一个中位数里会把触发时机整体带偏，所以首次兜底
         * 值按当前通路选（见 [flashLatencyFallbackMs]）。
         */
        private const val FLASH_HAL_LATENCY_MS_JPEG = 40L
        private const val FLASH_HAL_LATENCY_MS_RAW = 270L

        /**
         * 触发调度（纯函数，供单测）：相对「提交拍照请求」的延迟 =
         * 实测的 HAL 延迟 + 灯的起辉补偿。offset 为负表示提前发；
         * 结果下限 0 —— 不可能比提交时刻更早发指令。
         *
         * 只有 halLatency ≥ |offset|（RAW 通路）才走这条：提交后延迟
         * 几十毫秒发指令。JPG 通路 halLatency 只有 30 几毫秒，
         * 这里恒等于 0，改由 [flashPreRollMs] 反向延后快门。
         */
        fun flashTriggerDelayMs(halLatencyMs: Long, offsetMs: Long): Long =
            (halLatencyMs + offsetMs).coerceAtLeast(0L)

        /**
         * 快门前置量（纯函数，供单测）：先点灯，再把拍照请求延后这么多毫秒提交。
         *
         * 灯的固件从收到 05 00 到 COB 出光约 |offset| 毫秒（默认 200），而 JPG
         * 通路提交后只要 ~35ms 就开曝光 —— 「曝光开始前 200ms 发指令」物理上
         * 做不到（那时快门还没按）。反着来才成立：
         *   t=0 发 BLE 指令 → t=(|offset| − halLatency) 提交 → t=|offset| 曝光开始
         * 正好与灯出光对齐。代价是约 160ms 快门时滞，与真实机身闪光的观感一致。
         *
         * 返回 0 表示无需前置（halLatency ≥ |offset|，RAW 通路），走原路径。
         */
        fun flashPreRollMs(halLatencyMs: Long, offsetMs: Long): Long =
            (-offsetMs - halLatencyMs).coerceAtLeast(0L)

        /**
         * 最近 N 次实测延迟的中位数，抵消单次抖动；空样本回落到首帧兜底值。
         * 奇数个取正中，偶数个取偏大那个（宁可稍晚，别提前太多）。
         */
        fun medianLatencyMs(samples: List<Long>, fallbackMs: Long): Long {
            if (samples.isEmpty()) return fallbackMs
            val s = samples.sorted()
            return s[s.size / 2]
        }

        /**
         * RAW 图 / 快门结果到达的最大等待。JPEG 与 RAW 出自同一次快门，
         * 正常先后脚到；超时说明这帧 RAW 拿不到了（HAL 没出图/结果丢失），
         * 按纯 JPEG 帧落卷，帧不丢。
         */
        const val RAW_WAIT_MS = 1500L

        /** RAW 选尺寸的面积上限（像素）。三星 Ultra 主摄可能把全分辨率 Bayer 列出来，
         *  一张 200MP DNG 400MB 起步，写盘和冲洗都扛不住 —— 选「不超过 16MP 的最大档」，
         *  正好覆盖 12MP 合并档；全超就取最小档。 */
        const val RAW_MAX_PIXELS = 16_000_000

        /**
         * 从 HAL 报的 RAW_SENSOR 档位里选一个：取面积不超过 [RAW_MAX_PIXELS] 的最大档
         * （12MP 合并档优先），全都超限则取最小档（聊胜于无）；空表返回 null（不支持）。
         * 纯函数（宽高对进出），可单测。
         */
        fun pickRawSize(sizes: List<Pair<Int, Int>>): Pair<Int, Int>? {
            if (sizes.isEmpty()) return null
            val area = { s: Pair<Int, Int> -> s.first.toLong() * s.second }
            val capped = sizes.filter { area(it) <= RAW_MAX_PIXELS }
            return if (capped.isNotEmpty()) capped.maxByOrNull(area)
            else sizes.minByOrNull(area)   // 全超限：取最小档，别让一张 DNG 几百 MB
        }

        /**
         * 直闪帧的曝光偏置（EV，负 = 更暗）。
         *
         * ⚠️ v81（11-14 用户拍板）：从 -2/3 归零。真机同场景 A/B 定量——
         * `ALWAYS_FLASH` 本身就把环境光砍了约 4.5 EV（iso 202/1:23 → 23/1:60），
         * 成片均值 89.8→27.0、暗像素 46%→83%、四角 49→10（背景几乎纯黑）；
         * 再叠 -0.7 EV 属于过冲，主体虽正常但整幅观感太黑。
         * 归零后保留 HAL 重测光 + 线性 tonemap + AWB 钉日光，直闪味不丢。
         *
         * 下发逻辑（按设备 AE 补偿步长换算整档）原样留着，以后要微调只改这个常量。
         */
        const val FLASH_EV_BIAS = 0f

        /**
         * vc85 慢同步 AE 正补偿实验 —— **已证无效并归零（11-14 17:2x）**。
         *
         * 真机：`FlashTuning evBiasSteps=10` / `slowSyncEv=1.0` 确实下发，但
         * `拍照完成 flashState=FIRED iso=23 expNs=16667400` 纹丝不动，五张成片
         * mean 63~69 / p50 13~28 与基线一字不差 → 三星 HAL 在 ALWAYS_FLASH 下
         * 忽略/钉死 AE 补偿。换算代码与预闪帧下发能力保留（别的机型可能有效），
         * 但常量必须保持 0f，否则无 MANUAL_SENSOR 的回退路径会带一个未验证的 +1EV。
         */
        const val FLASH_SLOW_SYNC_EV = 0f

        /**
         * vc86：直闪帧手动锁定到「闪光前预览 AE 的实测曝光」。
         *
         * FIMO 运行时取证（tmp/flash_lag_1114/dumpsys_fimo_live.txt）：
         * FIMO 预览请求 sensor.sensitivity=250 / exposureTime=1/60s，
         * 我们 ALWAYS_FLASH 拍照结果 iso=23 / 1/60s —— 差 ≈3.4EV，
         * 就是 FIMO 背景有环境光、我们背景死黑的全部差异。
         * AE 补偿（vc85）被这台 HAL 忽略；这里改为 AE_OFF + 手写 SENSOR_*，
         * 绕开 HAL 重测光。曝光值取预览回调实测，不硬编码 250，机型自适应。
         * 置 false 即一键回退到 vc82 的 ALWAYS_FLASH 口径。
         */
        const val FLASH_LOCK_PREVIEW_AE = true

        /**
         * vc87：手写 AE 曝光的整体 EV 微调 —— 专治「主体高光被压成死白平板」。
         *
         * 取证（tmp/flash_lag_1114/vc86/，原图全分辨率、裁掉白边）：
         * 9 张里最亮的 1/16 格 std 只有 0.7~8.8（几乎零纹理），RGB 最大值
         * 只有 162~175、>=245 像素为 0 → **不是数值削顶，是 HAL JPEG 输入过曝后
         * 被引擎胶片曲线肩部压成恒定平台**；FIMO 同口径亮格 std 14~22、
         * max 255，主体有层次。目视：手掌一整片死白、皮肤纹理与明暗过渡全丢。
         *
         * vc86 手写 ISO 291~449 / 快门 10~30ms，比 FIMO 实测 250 / 1:60 高且
         * 每帧大幅波动；AE_OFF 下曝光终于由 App 控制（vc85 的 AE 补偿被 HAL 忽略，
         * 手写 SENSOR_* 有效），所以这里**只缩放 ISO**：把 291~449 拉到 179~276，
         * 落到 FIMO 基准附近，让主体退出曲线肩部恢复层次；快门保持预览 AE 实测值
         * （保留用户 EV 拨盘与暗光慢门策略），不硬编码任何机型常数。
         *
         * vc83 定稿值 -0.7EV（ISO ×0.615）：经 vc87 13 张 / vc88 5 张 /
         * vc89 7 张真机复标后保留，定稿样张主体平台占比稳定 <2.5%、
         * 亮格 std 23.7（FIMO 16.5）、目视无死白剪影。
         * 置 0f 即回到 vc86 口径；置 FLASH_LOCK_PREVIEW_AE=false 回到 vc82。
         */
        const val FLASH_LOCK_EV_BIAS = -0.7f

        /**
         * vc88：直闪帧的**总曝光量上限**（ISO·ms）—— 主体过曝的真正判据。
         *
         * vc87 真机 13 张定量（tmp/flash_lag_1114/vc87/，裁白边、原图全分辨率）：
         * 只缩 ISO 仍过曝，因为决定主体是否进引擎肩部的是 ISO×快门：
         *   不过曝 9 张（>=150 平台占比 0%、有皮肤纹理） 1369~2108 ISO·ms
         *   过曝   4 张（平台 35~45%、手掌成灰白剪影） 5609~30624 ISO·ms
         *   FIMO 基准 250 × 1/60s = 4167（它的闪光功率按自身 AE 测定、配套）
         * 我们闪光功率由 precapture 在 HAL 的 ISO23 基准下测定，功率偏大，
         * 安全阈值必须低于 FIMO。vc88 先取 2500（平台降到 1.7%，但背景
         * p50 只有 12、死黑），vc89 放宽到 **3600**（+0.53EV）：7 张真机
         * 目视全部无主体过曝、平台 2.2%、亮格 std 23.7、p50 抬到 26。
         * ⚠️ 不是机型常数：值由闪光功率基准决定，仍按 deviceIsoRange/
         * 设备曝光范围钳制；别的机型若功率不同按同口径复标即可。
         */
        const val FLASH_MAX_EXPOSURE_PRODUCT = 3600f

        /**
         * vc89：直闪帧的**手写 ISO 上限** —— 单独管住主体。
         *
         * vc88 真机：ISO 246~253 配 10ms 快门的帧平台占比只有 0.2~0.5%
         * （不过曝、纹理完整）；vc87 里 ISO 187 配 30ms 却过曝 35.6%。
         * 说明主体受光由 ISO 与快门共同决定，但 ISO 是可以直接钉住的那一项。
         * 取 vc88 已验证不过曝的上界 250，机型自适应仍靠 deviceIsoRange 夹。
         */
        const val FLASH_MAX_FLASH_ISO = 250

        /**
         * vc89：快门上限 1/30s（33.3ms）—— 手持慢同步的安全线。
         * vc88 用 1/60s 把背景压得太黑（p50 12 vs FIMO 86）；这里放开上限，
         * 快门由 目标曝光量 / 实际 ISO 反算。
         * ⚠️ vc89 真机日志：实际只用到 14.4~16.8ms —— product 上限 3600
         * 加 ISO 钳 250 已决定这个量级，「把增益移给快门」并未真正发生，
         * 本质只是 +0.53EV 放宽。上限留着供后续换机制时不必再改代码。
         */
        const val FLASH_MAX_SHUTTER_SEC = 0.03333334f
        const val TAG = "Camera2Controller"
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var cameraId: String = "0"
    private var imageReader: ImageReader? = null

    // ---------------- P3 RAW 负片 ----------------

    /** RAW 第二路输出（RAW_SENSOR 16bit Bayer）；null = 设备不支持 */
    private var rawImageReader: ImageReader? = null

    /** RAW 早到寄存槽：HAL 的 RAW 图可能先于快门结果回调到货（三星实测），先存这等 JPEG 锚点来取 */
    @Volatile private var bufferedRaw: Image? = null

    /** DngCreator 要用的 Characteristics（start 时存一份） */
    private var cameraChars: CameraCharacteristics? = null

    /** DNG 写盘线程：DngCreator.writeImage 是重 IO，不能占相机线程 */
    private val rawWriterExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 一次快门的三个碎片（JPEG 字节 / RAW 图 / 快门结果）汇合处，全在相机线程上访问 */
    private var pendingShot: PendingShot? = null

    /**
     * RAW 负片开关（工程师控制台，默认关）。开了之后每次快门同时收 JPEG + RAW，
     * DNG 落卷作「底片」，冲洗时走引擎 RAW 会话（绕过 ISP 的 tone mapping / AWB / 多帧合成）。
     * 设备不支持 RAW_SENSOR 时开了也没用（rawImageReader 为 null，按纯 JPEG 帧）。
     */
    @Volatile var rawNegativeEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // JPG 直提交延迟 ~35ms、RAW 多挂一路 surface 要 265~274ms。两套样本混在
            // 一个中位数里会让下一帧的触发时机整体跑偏（JPG 变「晚到」），切通路即清零。
            val reset = Runnable {
                flashHalLatencySamples.clear()
                flashHalLatencyMs = flashLatencyFallbackMs()
            }
            (backgroundHandler?.post(reset) ?: false).let { if (!it) reset.run() }
        }

    /** 设备是否支持 RAW_SENSOR 输出（拍照前就探好） */
    val rawCapable: Boolean get() = rawImageReader != null

    // 手动参数
    private var manualIso: Int? = null
    private var manualExposure: Long? = null
    private var exposureComp: Int = 0

    /**
     * 设备实际支持的 AE 补偿范围。
     * CONTROL_AE_EXPOSURE_COMPENSATION 的名义范围是 [-4,4]，但多数机型只支持 [-2,2]，
     * 越界 set 会抛 IllegalArgumentException。故启动时从 Characteristics 读取真实范围，
     * 下发前统一钳制。
     */
    private var aeCompRange: IntRange = -2..2

    // 模拟失焦：手动对焦到失焦距离
    private var minFocusDistance = 0f   // 最近对焦距离（最大屈光度），在 start 时读取
    private var defocusDistance: Float? = null  // null=自动对焦

    // 点屏对焦状态：afRegion 为点屏测光/对焦区，afLocked 表示扫焦完成已锁定
    private var afRegion: android.hardware.camera2.params.MeteringRectangle? = null
    private var afLocked = false
    private var pendingFocusCb: (() -> Unit)? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val focusTimeout = Runnable {
        if (!afLocked) { afLocked = true; fireFocusCallback() }
    }

    /** 预览 Surface：闪光预闪（AE precapture）要往这里发一次请求 */
    private var previewSurface: Surface? = null

    /** 拍照结果的闪光状态回读：验证「到底闪没闪」，日志里能看到 */
    private val flashStateCallback =
        object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: android.hardware.camera2.CameraCaptureSession,
                request: CaptureRequest,
                result: android.hardware.camera2.TotalCaptureResult,
            ) {
                val st = result.get(android.hardware.camera2.CaptureResult.FLASH_STATE)
                val ae = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
                android.util.Log.i(
                    TAG,
                    "拍照完成 flashState=${
                        when (st) {
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_UNAVAILABLE -> "UNAVAILABLE"
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_CHARGING -> "CHARGING"
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_READY -> "READY"
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_FIRED -> "FIRED"
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_PARTIAL -> "PARTIAL"
                            else -> "$st"
                        }
                    } aeState=$ae"
                )
            }
        }

    /**
     * 快门帧结果的暂存回调：P3 RAW 负片要拿 TotalCaptureResult 喂 DngCreator
     * （DNG 头里的曝光/白平衡/方向元数据全从它来），顺手把原闪光状态日志也打了。
     */
    private val stillCaptureCallback =
        object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(
                session: android.hardware.camera2.CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long
            ) {
                // 自适应触发时机：这是 HAL 唯一明确告知「曝光已开始」的事件，
                // 用它实测「提交 → 曝光开始」的延迟，供下一帧算前置量。
                // 此处不发灯 —— 灯起辉约 200ms 远大于 100ms 曝光窗口，
                // 所以只测量，触发由 submitStillCapture 在提交前完成（见 flashPreRollMs）。
                if (externalFlashSync && flashSubmitRealtimeMs > 0L) {
                    val latency = android.os.SystemClock.elapsedRealtime() - flashSubmitRealtimeMs
                    flashSubmitRealtimeMs = 0L
                    if (latency in 1L..2000L) {
                        flashHalLatencySamples.addLast(latency)
                        while (flashHalLatencySamples.size > 3) flashHalLatencySamples.removeFirst()
                        flashHalLatencyMs = Camera2Controller.medianLatencyMs(
                            flashHalLatencySamples.toList(), flashLatencyFallbackMs()
                        )
                    }
                    android.util.Log.i(
                        "FlashSync",
                        "HAL 实测 提交→曝光开始=${latency}ms 样本=${flashHalLatencySamples.size}" +
                            " 采用=${flashHalLatencyMs}ms frame=$frameNumber"
                    )
                }
                // 负偏移走 submitStillCapture 的预触发；这里只负责 0 / 正偏移。
                if (externalFlashSync && flashTriggerOffsetMs >= 0) {
                    android.util.Log.i(
                        "FlashSync",
                        "曝光开始 frame=$frameNumber ts=$timestamp"
                    )
                    if (flashTriggerOffsetMs == 0L) {
                        fireExternalFlashOnce("曝光开始")
                    } else {
                        val off = flashTriggerOffsetMs
                        backgroundHandler?.postDelayed({
                            if (externalFlashSync) fireExternalFlashOnce("曝光开始+${off}ms")
                        }, off)
                    }
                }
            }

            override fun onCaptureCompleted(
                session: android.hardware.camera2.CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (rawNegativeEnabled && rawImageReader != null) {
                    // 到达顺序不保证（结果可能先于 JPEG/RAW）：槽不存在就建，谁先到谁开户
                    val p = pendingShot ?: PendingShot().also { pendingShot = it }
                    if (!p.completed) p.result = result
                }
                // 原闪光状态日志原样保留，补上这一帧真正用到的曝光参数：
                // 外置闪光黑片排查时不用再拉 DNG 读 EXIF，logcat 里就能看出
                // 「AE 有没有收敛 / 手动参数有没有生效 / HAL 闪光有没有开」。
                val st = result.get(android.hardware.camera2.CaptureResult.FLASH_STATE)
                val ae = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
                android.util.Log.i(
                    TAG,
                    "拍照完成 flashState=${
                        when (st) {
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_UNAVAILABLE -> "UNAVAILABLE"
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_CHARGING -> "CHARGING"
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_READY -> "READY"
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_FIRED -> "FIRED"
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_PARTIAL -> "PARTIAL"
                            else -> "$st"
                        }
                    } aeState=$ae" +
                        " iso=${result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)}" +
                        " expNs=${result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)}" +
                        " flashEnabled=$flashEnabled manualIso=$manualIso manualExp=$manualExposure ev=$exposureComp"
                )
                // v74 P0：拍照帧一结束就把 HAL 从预闪状态拉回来，否则小米 13U 等
                // HAL 会把 LED 一直点在「预闪常亮」上（切档也关不掉，只有关会话才灭）。
                resetFlashPrecapture("stillCaptureCompleted")
            }

            override fun onCaptureFailed(
                session: android.hardware.camera2.CameraCaptureSession,
                request: CaptureRequest,
                failure: android.hardware.camera2.CaptureFailure
            ) {
                // 拍照失败也不能把预闪状态留在 HAL 里
                resetFlashPrecapture("stillCaptureFailed")
            }
        }

    /** 一次快门的碎片。只在相机后台线程（JPEG/RAW 监听、结果回调、超时 Runnable）上访问，无需加锁。 */
    private class PendingShot {
        var jpeg: ByteArray? = null
        var rotation: Int = 0
        var rawImage: Image? = null
        var result: TotalCaptureResult? = null
        var completed = false
        var timeoutPosted = false
    }

    /** JPEG / RAW / 快门结果三者到齐（或超时）→ 交付落卷。相机线程上调用。 */
    private fun tryCompletePendingShot() {
        val p = pendingShot ?: return
        if (p.completed) return
        if (p.jpeg != null && p.rawImage != null && p.result != null) {
            p.completed = true
            deliverPendingShot(p)
        }
    }

    /** 超时兜底：RAW 拿不到了就按纯 JPEG 帧交付，帧不丢。相机线程上调用。 */
    private fun completePendingShotOnTimeout() {
        val p = pendingShot ?: return
        if (p.completed) return
        p.completed = true
        p.rawImage?.close()
        p.rawImage = null
        bufferedRaw?.close()
        bufferedRaw = null
        android.util.Log.w(TAG, "RAW 碎片未到齐（raw=${p.rawImage != null} result=${p.result != null}），按纯 JPEG 帧落卷")
        deliverPendingShot(p)
    }

    /** 汇合后的交付：JPEG 必须有；RAW 齐 → 相机线程外写 DNG 再回调，否则直接回调。 */
    private fun deliverPendingShot(p: PendingShot) {
        pendingShot = null
        val jpeg = p.jpeg
        val image = p.rawImage
        val result = p.result
        if (jpeg == null || image == null || result == null) {
            // 任一缺件：DNG 不写（Image 关掉），JPEG 照常落卷
            image?.close()
            if (jpeg == null) return
            onPhotoCaptured?.invoke(jpeg, p.rotation, null)
            return
        }
        val chars = cameraChars
        if (chars == null) {
            image.close()
            onPhotoCaptured?.invoke(jpeg, p.rotation, null)
            return
        }
        // DNG 写盘是重 IO：丢给专职线程，相机线程立刻回去伺候预览
        val submitted = runCatching {
            rawWriterExecutor.execute {
                val tmp = File(context.cacheDir, "raw_shot_${System.currentTimeMillis()}.dng")
                val ok = runCatching {
                    android.hardware.camera2.DngCreator(chars, result).use { creator ->
                        java.io.FileOutputStream(tmp).use { out -> creator.writeImage(out, image) }
                    }
                    tmp.length() > 0
                }.onFailure {
                    android.util.Log.e(TAG, "DNG 写盘失败（本帧按 JPEG 负片）: ${it.message}", it)
                }.getOrDefault(false)
                image.close()
                val dng = if (ok) tmp else { tmp.delete(); null }
                android.util.Log.i(
                    TAG, "RAW 底片 ${if (dng != null) "${tmp.length() / 1024}KB" else "失败"} -> ${dng?.name}"
                )
                onPhotoCaptured?.invoke(jpeg, p.rotation, dng)
            }
        }.isSuccess
        if (!submitted) {
            image.close()
            onPhotoCaptured?.invoke(jpeg, p.rotation, null)
        }
    }

    /**
     * 内置闪光灯是否开启。
     *
     * 注意语义变化：以前「闪光灯」是用常亮手电（FLASH_MODE_TORCH）在快门那一刻亮 200ms
     * 顶一下 —— torch 是降功率常亮，亮度远低于真正的拍照闪光脉冲，且拍照的 capture
     * request 压根不带闪光参数，所以成片几乎看不出闪过。现在改成相机 HAL 的拍照闪光：
     * v75 P1 起：ALWAYS_FLASH **只挂在 STILL_CAPTURE 那一帧**，预览与预闪请求保持
     * AE_ON + FLASH_MODE_OFF。理由是 Camera2 允许 HAL 在 ALWAYS_FLASH 下于预览/预闪
     * 测光阶段点亮 LED，小米 13U 点亮后不会自己收（拍完常亮、切档无效、关会话才灭）；
     * 而 v74 的一次性 CANCEL 会被随后常驻的 repeating 请求立刻覆盖，等于边关边开。
     * 拍照帧仍按「有闪光」重新测光（主体提亮、背景压暗），直闪成片味道不变。
     */
    @Volatile private var flashEnabled: Boolean = false

    /** 设备是否有闪光灯单元；没有的话只能退回 torch 常亮补光 */
    @Volatile private var hasFlashUnit: Boolean = true

    /** 无闪光灯单元时的兜底：torch 常亮的开启状态 */
    @Volatile private var torchOn: Boolean = false

    // ---- v74 P0：AE 预闪状态跟踪（只用于收尾与诊断，不改成像） ----
    /** 是否发出过 PRECAPTURE_START 却还没 CANCEL —— 小米 13U 常亮的直接诱因 */
    @Volatile private var precapturePending = false
    @Volatile private var flashPrecaptureStarts = 0
    @Volatile private var flashPrecaptureCancels = 0
    @Volatile private var lastAeState: Int? = null
    @Volatile private var lastFlashResetReason = "none"

    // ---------------- 慢门（胶片曝光模型）的能力与测光快照 ----------------

    /** 传感器曝光范围（秒），取自 SENSOR_INFO_EXPOSURE_TIME_RANGE */
    private var deviceMinExposureSec = 1f / 8000f
    private var deviceMaxExposureSec = 0.2f

    /** 传感器 ISO 范围（SENSOR_INFO_SENSITIVITY_RANGE） */
    private var deviceIsoRange = 50..3200

    /** 主摄实际光圈（f 值）：把预览 AE 的曝光量还原成场景亮度要用它 */
    private var apertureValue = 1.8f

    /** 是否支持 MANUAL_SENSOR：不支持就只能降级，慢门不可用 */
    private var hasManualSensor = false

    /** 能否关掉镜头光学防抖。T3 没有防抖，不关的话长曝光根本抖不起来 */
    private var canDisableOis = false

    /** 能否关掉降噪。多帧降噪会把抖动「修」平，等于白拍 */
    private var canDisableNr = false

    /** 降噪最低档（关不掉时的次优选择，比默认档少抹细节） */
    private var canDisableNrMinimal = false

    /** 能否关掉边缘锐化。手机的锐化会在边缘留 halo，正是「数码味」的来源之一 */
    private var canDisableEdge = false

    /** 能否关掉零快门延迟（ZSL）。不能关时 HAL 可能从缓冲里挑一张合成过的旧帧 */
    private var canDisableZsl = false

    /**
     * 能否把色调曲线钉成线性（CONTRAST_CURVE + ≥2 个曲线点）。
     * 直闪帧用它关掉厂商 tone mapping —— 不然 ISP 会把被闪光压黑的背景又提回来。
     */
    private var canLinearTonemap = false

    /** 设备 AE 补偿步长（EV/档，如 1/2 = 0.5EV）；读不到时保持 1（最保守） */
    private var aeCompStep = android.util.Rational(1, 1)

    /** 预览 AE 最近一次测光结果 —— 慢门决策的唯一输入 */
    @Volatile private var lastAeSec = 0f
    @Volatile private var lastAeIso = 0

    /**
     * 外置闪光同步用的「环境光测光基线」。
     *
     * 真机 11-06 实测：外接 COB 灯闪起来时，AE 会在闪光峰值上重新测光，把
     * ISO 从 2649 砍到 272、快门从 1/33 砍到 1/50（曝光量 -14.6 倍），但 RAW
     * 归一化后场景其实只亮了 2.88 倍 —— 净欠曝约 2.3 档，成片就是黑的。
     * 所以只在 AE 收敛（CONVERGED/LOCKED）时更新基线；闪光一来 AE 进 SEARCHING，
     * 基线自然停留在闪光前的环境光读数，不会被峰值带跑。
     */
    @Volatile private var aeBaselineSec = 0f
    @Volatile private var aeBaselineIso = 0

    /** 外置闪光档：快门帧改用锁定的环境光基线，不让 HAL 在闪光峰值重新测光 */
    @Volatile private var externalFlashSync = false

    /**
     * 传感器真正开始曝光的回调。外接灯的 BLE 触发指令必须**在这一刻**发出：
     * 按快门时发的话，灯在 t+0 就闪完，而三星 HAL 要 ~240ms 后才开曝光，
     * 闪光根本进不了画面（真机 11-06 实测）。
     */
    @Volatile var onExposureStarted: (() -> Unit)? = null

    /**
     * 外置闪光触发偏移（ms，相对「曝光开始」的灯起辉补偿）。负值 = 提前发指令，
     * 用来补偿「BLE 传输 + 灯固件预闪序列 + COB 起辉」约 200ms 的固有延迟。
     * 每次拍照从外部私有目录 `flash_sync_offset.txt` 重读，方便 adb push 热调。
     *
     * 下限放宽到 -400：短 HAL 延迟机型需要把触发提前到提交后几十毫秒，
     * 旧的 [-250, +100] 会把 delay 卡在 135ms 以上，怎么扫都够不着。
     * 正半区 [0, +100] 必然漏闪（灯 200ms 起辉 > 100ms 曝光窗口），保留只为逆向调参。
     */
    @Volatile private var flashTriggerOffsetMs: Long = DEFAULT_FLASH_SYNC_OFFSET_MS
    @Volatile private var flashSyncGain: Float = FLASH_SYNC_GAIN_DEFAULT
    private var flashTriggerPosted = false

    /** 提交拍照请求的 wall-clock；配合 onCaptureStarted 实测「提交 → 曝光开始」。 */
    @Volatile private var flashSubmitRealtimeMs = 0L
    /** 最近 3 次实测延迟（只在 backgroundHandler 线程访问），取中位数抗抖动。 */
    private val flashHalLatencySamples = java.util.ArrayDeque<Long>()
    /** 本帧采用的 HAL 延迟：首帧按当前通路兜底，之后由实测中位数替换。 */
    @Volatile private var flashHalLatencyMs = FLASH_HAL_LATENCY_MS_JPEG

    /** 当前通路的 HAL 延迟兜底值（JPG 直提交 ~40ms / RAW 多挂 surface ~270ms）。 */
    private fun flashLatencyFallbackMs(): Long =
        if (rawNegativeEnabled) FLASH_HAL_LATENCY_MS_RAW else FLASH_HAL_LATENCY_MS_JPEG

    /** 每次拍照重读两个热调文件，方便 adb push 扫参，不用重新编译。
     *  实测样本刻意不重置 —— 它要跨帧累积才能做中位数。 */
    private fun readFlashSyncConfig() {
        val dir = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        flashTriggerOffsetMs = if (dir == null) DEFAULT_FLASH_SYNC_OFFSET_MS else runCatching {
            val f = java.io.File(dir, "flash_sync_offset.txt")
            if (!f.exists()) DEFAULT_FLASH_SYNC_OFFSET_MS
            else f.readText().substringBefore("//").trim().toLongOrNull()
                ?: DEFAULT_FLASH_SYNC_OFFSET_MS
        }.getOrDefault(DEFAULT_FLASH_SYNC_OFFSET_MS).coerceIn(-400L, 100L)
        flashSyncGain = if (dir == null) FLASH_SYNC_GAIN_DEFAULT else runCatching {
            val f = java.io.File(dir, "flash_sync_gain.txt")
            if (!f.exists()) FLASH_SYNC_GAIN_DEFAULT
            else f.readText().substringBefore("//").trim().toFloatOrNull()
                ?: FLASH_SYNC_GAIN_DEFAULT
        }.getOrDefault(FLASH_SYNC_GAIN_DEFAULT).coerceIn(0.05f, 10.0f)
        flashTriggerPosted = false
    }

    /** 真正发一次 BLE 触发，并防重（预触发与 onCaptureStarted 只会走一条）。 */
    private fun fireExternalFlashOnce(where: String) {
        if (flashTriggerPosted) return
        flashTriggerPosted = true
        android.util.Log.i(
            "FlashSync",
            "触发外接灯 @ $where offset=${flashTriggerOffsetMs}ms" +
                " halLatency=${flashHalLatencyMs}ms t=${android.os.SystemClock.elapsedRealtime()}"
        )
        onExposureStarted?.invoke()
    }

    // 复刻康泰时 T3 35mm 视角：主摄等效约23mm，裁切至35mm ≈ 1.52x（常量与 RAW 冲洗端共享）
    @Volatile private var zoomRatio = app.spektralab.engine.FilmStockMapper.T3_ZOOM_RATIO

    /**
     * 当前实际下发给 HAL 的 T3 变焦比。拍照时随帧写进 `ShotMeta.zoom`，
     * 冲洗端按帧取它补裁 RAW 的 ActiveArea——不能让冲洗依赖这个进程内变量，
     * 否则冷启动直接进暗房、跨进程冲洗、换机导入时都会读到错的默认值。
     */
    val currentZoomRatio: Float get() = zoomRatio

    /** 预览流实际尺寸（start 时确定），点屏对焦坐标映射用 */
    private var previewSize: Size = Size(1920, 1440)

    fun setExposureComp(comp: Int) {
        // v81：值没变就不要重发 repeating。切闪光灯档位会连着调
        // setManualIso / setManualExposure / setExposureComp，三次都是旧值，
        // 白白往 HAL 塞三条重复请求，预览会出现一帧可感知的抖动。
        if (exposureComp == comp) return
        exposureComp = comp
        previewRequestBuilder?.let {
            it.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                comp.coerceIn(aeCompRange.first, aeCompRange.last)
            )
            captureSession?.setRepeatingRequest(it.build(), previewMetaCallback, backgroundHandler)
        }
    }

    /** 当前生效的白平衡预设（takePicture 的独立 builder 需要带上，否则成片不跟随预览） */
    private var currentAwbMode: Int? = null

    /**
     * 白平衡预设（闪光开启后横滑调 WB 用）。
     * @param awbMode CaptureRequest.CONTROL_AWB_MODE_* 常量；null = 恢复相机自动白平衡。
     * 走 ISP 级 CONTROL_AWB_MODE 而非 COLOR_CORRECTION_GAINS：前者对预览与成片
     * 同时生效且不需要机型校准，预设语义（日光/阴天…）也更贴合胶片机的滤镜片直觉。
     */
    fun setAwbMode(awbMode: Int?) {
        currentAwbMode = awbMode
        previewRequestBuilder?.let {
            it.set(
                CaptureRequest.CONTROL_AWB_MODE,
                awbMode ?: CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            captureSession?.setRepeatingRequest(it.build(), previewMetaCallback, backgroundHandler)
        }
    }

    // 拍照回调
    /**
     * 拍照回调：相机 ISP 输出的原始 JPEG 字节 + 像素需顺时针旋转的角度（0/90/180/270）
     * + RAW 底片临时文件（null = 这帧没有 DNG：RAW 关着 / 设备不支持 / 这帧 RAW 没拿到）。
     * 不做任何解码/旋转/重编码——那会把一次编码变成两次并多吃约 100MB 内存。
     * 方向只作为元数据随帧记录，由冲洗侧统一旋转。DNG 由落卷方移进底片目录，临时文件
     * 用完（或落卷失败）由落卷方负责删除。
     */
    var onPhotoCaptured: ((ByteArray, Int, File?) -> Unit)? = null

    /** 取景器元信息回调：(AF 状态, 曝光时间秒)，驱动 T3 取景器内的合焦灯与快门档显示 */
    var onViewfinderMeta: ((afState: Int?, exposureSec: Float, isoAe: Int) -> Unit)? = null
    private var lastMetaPostAt = 0L

    /** 当前设备屏幕旋转（0/90/180/270），拍照方向要用 */
    private fun deviceRotationDegrees(): Int {
        val rot = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching { context.display?.rotation }.getOrNull()
        } else null
        val r = rot ?: @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.rotation
        return when (r) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /** Camera2 标准公式：JPEG 旋转角 = (屏幕旋转映射 + 传感器方向 + 270) % 360 */
    private fun jpegRotationDegrees(sensorOrientation: Int): Int {
        val mapped = when (deviceRotationDegrees()) {
            90 -> 0
            180 -> 270
            270 -> 180
            else -> 90
        }
        return (mapped + sensorOrientation + 270) % 360
    }

    fun start(surfaceTexture: SurfaceTexture) {
        backgroundThread = HandlerThread("Camera2").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: "0"

        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        minFocusDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        // ---- T3 35mm 视角动态化：读传感器物理尺寸 + 镜头实际焦距，算出本机等效焦距 ----
        // 等效焦距 = 实际焦距 × (43.27 / 传感器对角线mm)
        // 裁切比 = 35 / 本机等效焦距
        // 读不到时保持默认 1.52（23mm），不会比之前差
        runCatching {
            val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            if (sensorSize != null && focalLengths != null && focalLengths.isNotEmpty()) {
                // 传感器对角线 = sqrt(w² + h²)
                val diag = kotlin.math.hypot(sensorSize.width.toDouble(), sensorSize.height.toDouble()).toFloat()
                // 取第一个（主摄通常只有一个焦距）
                val focal = focalLengths[0]
                if (diag > 0f && focal > 0f) {
                    FilmStockMapper.setT3ZoomRatio(diag, focal)
                    // 同步本地 zoomRatio（预览/拍照请求要用）
                    zoomRatio = FilmStockMapper.T3_ZOOM_RATIO
                    // 诊断：把 HAL 自报的 zoom 范围一起打出来。若 ratio 超出 upper，
                    // HAL 会悄悄钳制而帧记录仍写未钳值 → 采集端与冲洗端裁切比不一致，
                    // 也是「RAW 成片变小、视角对不上」的可能来源，真机日志一查便知。
                    // 这里只记录、不钳制：S25U 的 1.52 已由 HAL ROI 实证，贸然钳到某些
                    // 机型上报的 [1.0,1.0] 反而会破坏已验证的 35mm 视角。
                    val halRange = if (android.os.Build.VERSION.SDK_INT >= 30) {
                        runCatching { chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) }
                            .getOrNull()
                    } else null
                    android.util.Log.i(
                        "Camera2",
                        "T3 视角裁切比 sensorDiag=${diag}mm focal=${focal}mm " +
                            "ratio=$zoomRatio halZoomRange=$halRange"
                    )
                }
            }
        }.onFailure { android.util.Log.w("Camera2", "读取传感器物理尺寸失败，使用默认裁切比 $zoomRatio", it) }

        // 读设备真实的 AE 补偿范围（缺失时保留默认 -2..2 这个最保守区间）
        chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { r ->
            if (r.upper > r.lower) aeCompRange = r.lower..r.upper
        }

        // 设备有没有真闪光灯：没有的话 HAL 闪光模式无效，退回手电筒顶一下的老办法
        hasFlashUnit = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

        // ---- 慢门能力探测：能手动曝光多久、能关什么（OIS / 降噪 / ZSL） ----
        chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { r ->
            if (r.upper > r.lower) {
                deviceMinExposureSec = r.lower / 1_000_000_000f
                deviceMaxExposureSec = r.upper / 1_000_000_000f
            }
        }
        chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { r ->
            if (r.upper > r.lower) deviceIsoRange = r.lower..r.upper
        }
        chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?.firstOrNull { it > 0f }?.let { apertureValue = it }
        hasManualSensor = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            canDisableOis = chars
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF) == true
        }
        canDisableNr = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            ?.contains(android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_OFF) == true
        canDisableNrMinimal = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            ?.contains(android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) == true
        canDisableEdge = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            ?.contains(android.hardware.camera2.CameraMetadata.EDGE_MODE_OFF) == true
        // ZSL 开关是 API 30 才有的 key，且没有公开的能力查询接口：
        // 只能在请求里试着设，失败就静默跳过（部分 HAL 不认这个 key）。
        canDisableZsl = android.os.Build.VERSION.SDK_INT >= 30
        // 直闪帧要用线性色调曲线压住 ISP 的暗部提亮：设备得认 CONTRAST_CURVE 模式
        // 且至少给 2 个曲线点（0,0 和 1,1 就够画直线）
        runCatching {
            val modes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
            val points = chars.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
            if (points >= 2 && modes != null &&
                modes.contains(android.hardware.camera2.CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            ) canLinearTonemap = true
        }
        // AE 补偿步长：换算闪光帧 EV 偏置要用（1/2 与 1/3 步长的设备很常见）
        chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.let { s ->
            if (s.numerator > 0 && s.denominator > 0) aeCompStep = s
        }
        android.util.Log.i(
            "FlashTuning",
            "caps tonemapLinear=$canLinearTonemap aeStep=$aeCompStep"
        )
        val capsLine = "slowShutter caps manualSensor=$hasManualSensor " +
            "exp=${deviceMinExposureSec}~${deviceMaxExposureSec}s iso=$deviceIsoRange " +
            "ap=$apertureValue oisOff=$canDisableOis nrOff=$canDisableNr " +
            "nrMin=$canDisableNrMinimal edgeOff=$canDisableEdge zslOff=$canDisableZsl"
        android.util.Log.i("SlowShutter", capsLine)
        runCatching {
            java.io.File(context.applicationInfo.dataDir, "files").let { d ->
                java.io.File(d, "filmcam_diag.txt").appendText("$capsLine\n")
            }
        }

        // 预览分辨率：限制在 1920 长边以内。部分机型（三星 S25/Adreno + Android 16）
        // 把 12MP 全尺寸作为 SurfaceTexture 私有流时，GL 消费者绑定 EGLImage 会
        // 持续失败（bindTextureImage 0x502），取景器黑屏；且预览远超屏幕分辨率
        // 白白耗电。拍照走 ImageReader JPEG，仍取全尺寸，不受影响。
        //
        // v68 P0：**必须优先选 4:3**。旧逻辑只比面积，vivo V2227A / X300 Ultra 的
        // HAL 报出 1920×1920(1:1, 3.686M) > 1920×1440(4:3, 2.765M)，方形纹理被
        // shader 的常量内容比例（竖 3:4 / 横 4:3）直接拉伸 33.3% —— 用户看到
        // 「画面比实物宽大」。取景器 shader 不读 previewSize，所以只能在选尺寸时收敛。
        // S25U 最大面积候选本来就是 1920×1440，改策略后逐像素零变化。
        val allPreviewSizes = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val picked = PreviewSizePolicy.choose(
            allPreviewSizes.map { it.width to it.height }, maxLongEdge = 1920
        )
        val chosenBy = when {
            picked == null -> "fallbackMinSize"
            PreviewSizePolicy.isTargetAspect(picked.first, picked.second) -> "4x3"
            else -> "fallbackMaxArea"
        }
        val previewSize = if (picked != null) {
            Size(picked.first, picked.second)
        } else {
            // 全表都超过 1920 长边（极端机型）：退回最小档，别让私有流炸掉
            allPreviewSizes.minByOrNull { it.width * it.height }
                ?.let { Size(it.width, it.height) }
                ?: Size(1920, 1440)
        }
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        this.previewSize = previewSize
        runCatching {
            java.io.File(context.applicationInfo.dataDir, "files").let { d ->
                java.io.File(d, "filmcam_diag.txt").appendText("previewSize=$previewSize\n")
            }
        }

        // 全分辨率拍照（JPEG 最大尺寸）
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
        val maxJpeg = jpegSizes?.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
        imageReader = ImageReader.newInstance(maxJpeg.width, maxJpeg.height, ImageFormat.JPEG, 2)
        // 读取传感器方向
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        android.util.Log.i("Camera2", "previewSize=$previewSize sensorOrientation=$sensorOrientation")
        // v63 起只读取证：把预览尺寸 / 比例 / 传感器能力记进跨机型报告。
        // v68 新增失配告警 —— 没有 4:3 候选的机型仍会回退旧逻辑，靠这两个字段
        // 一眼区分「尺寸选对了」与「HAL 只给了畸形比例」，别再靠截图猜。
        runCatching {
            val asp = previewSize.width.toFloat() / previewSize.height.toFloat()
            val candidates = map?.getOutputSizes(SurfaceTexture::class.java)
                ?.filter { maxOf(it.width, it.height) <= 1920 }
                ?.sortedByDescending { it.width * it.height }
                ?.take(12)
                ?.joinToString(" ") { "${it.width}x${it.height}" } ?: "?"
            com.filmcam.app.develop.DeviceGeometryReport.putAll(
                "camera.previewSize" to "${previewSize.width}x${previewSize.height}",
                "camera.previewAspect" to "%.4f".format(java.util.Locale.US, asp),
                // 与选择策略同一口径：max/min 归一化，兼容厂商竖置上报的尺寸
                "camera.previewIs4x3" to PreviewSizePolicy.isTargetAspect(
                    previewSize.width, previewSize.height
                ),
                "camera.previewIs16x9" to (kotlin.math.abs(asp - 16f / 9f) < 0.01f),
                "camera.previewChosenBy" to chosenBy,
                "camera.previewAspectMismatch" to !PreviewSizePolicy.isTargetAspect(
                    previewSize.width, previewSize.height
                ),
                "camera.previewAspectErrorPct" to "%.1f".format(
                    java.util.Locale.US,
                    PreviewSizePolicy.aspectErrorFraction(
                        previewSize.width, previewSize.height
                    ) * 100f
                ),
                "camera.sensorOrientation" to sensorOrientation,
                "camera.displayRotationDeg" to deviceRotationDegrees(),
                "camera.hasManualSensor" to hasManualSensor,
                "camera.isoRange" to "${deviceIsoRange.first}..${deviceIsoRange.last}",
                "camera.exposureSec" to "%.6f..%.6f".format(
                    java.util.Locale.US, deviceMinExposureSec, deviceMaxExposureSec
                ),
                "camera.previewCandidates" to candidates,
                "camera.maxJpeg" to "${maxJpeg.width}x${maxJpeg.height}",
            )
            reportFlashState()
            com.filmcam.app.develop.DeviceGeometryReportIo.flush(context)
        }

        // 拍照方向：Camera2 标准公式 (屏幕旋转映射 + 传感器方向 + 270) % 360。
        // 竖屏(0°)  : (90 + 90 + 270) % 360 = 90  → 传感器横画面转 90° 成 3:4 竖构图
        // 横屏(90°) : (0 + 90 + 270) % 360 = 0   → 直接用传感器原生 4:3 横构图
        // 反向横屏  : (180 + 90 + 270) % 360 = 180
        // 每次出片时现算设备朝向：Activity 旋转重建后朝向会变，不能只在 start() 取一次。
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            val bytes = try {
                val buffer = image.planes[0].buffer
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } finally {
                image.close()
            }
            val rotation = jpegRotationDegrees(sensorOrientation).also {
                android.util.Log.i("Camera2", "capture jpegRotation=$it deviceRotation=${deviceRotationDegrees()}")
            }
            android.util.Log.i(TAG, "JPEG 到货：rawNegativeEnabled=$rawNegativeEnabled rawReader=${rawImageReader != null}")
            if (!rawNegativeEnabled || rawImageReader == null) {
                // RAW 没开 / 不支持：老路径，JPEG 即全部
                onPhotoCaptured?.invoke(bytes, rotation, null)
            } else {
                // RAW 开着：JPEG 先进汇合处，等 RAW 图 + 快门结果（或超时）一起交付
                val p = pendingShot ?: PendingShot().also { pendingShot = it }
                if (!p.completed) {
                    p.jpeg = bytes
                    p.rotation = rotation
                    // RAW 图可能早于本回调到货：从寄存槽取回（早到 ≠ 丢失）
                    bufferedRaw?.let { p.rawImage = it; bufferedRaw = null }
                    if (!p.timeoutPosted) {
                        p.timeoutPosted = true
                        backgroundHandler?.postDelayed({ completePendingShotOnTimeout() }, RAW_WAIT_MS)
                    }
                    tryCompletePendingShot()
                } else {
                    // 上一张的超时碎片混进来（极小概率）：按独立 JPEG 帧交付，不丢
                    onPhotoCaptured?.invoke(bytes, rotation, null)
                }
            }
        }, backgroundHandler)

        // P3 RAW 负片：RAW_SENSOR 第二路（16bit Bayer，没过 tone mapping / AWB / 多帧合成）。
        // 能力探测 + 选档（≤16MP 的最大档，避开 200MP 全尺寸 Bayer）。
        cameraChars = chars
        val rawSizes: List<Size> = runCatching {
            map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val rawPicked = pickRawSize(rawSizes.map { it.width to it.height })
        if (rawPicked != null) {
            val rs = Size(rawPicked.first, rawPicked.second)
            rawImageReader = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 2)
            rawImageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                val p = pendingShot
                if (p == null || p.completed) {
                    // RAW 可能比 TotalCaptureResult 还早到（三星 HAL 实测如此：
                    // AE 流程拖长时结果回调最后才来）——这时不能丢，寄存单槽等锚点。
                    // 超时/交付时会清槽；新 RAW 到货覆盖旧槽（先关旧的防泄漏）。
                    bufferedRaw?.close()
                    bufferedRaw = image
                    return@setOnImageAvailableListener
                }
                p.rawImage = image
                tryCompletePendingShot()
            }, backgroundHandler)
            android.util.Log.i(TAG, "RAW 负片可用：${rs.width}x${rs.height}（HAL 共 ${rawSizes.size} 档）")
        } else {
            android.util.Log.i(TAG, "RAW 负片不可用：HAL 无 RAW_SENSOR 输出")
        }
        runCatching {
            java.io.File(context.applicationInfo.dataDir, "files").let { d ->
                java.io.File(d, "filmcam_diag.txt").appendText(
                    "rawSensor=${rawPicked != null} rawSize=${rawPicked?.first}x${rawPicked?.second}\n"
                )
            }
        }

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(surfaceTexture)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraDevice = null
                    Toast.makeText(context, "Camera2 打开失败: $error", Toast.LENGTH_SHORT).show()
                }
            }, backgroundHandler)
        } catch (e: SecurityException) {
            Toast.makeText(context, "需要相机权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createSession(surfaceTexture: SurfaceTexture) {
        val camera = cameraDevice ?: return
        val previewSurface = Surface(surfaceTexture)
        this.previewSurface = previewSurface
        val readerSurface = imageReader!!.surface

        // RAW surface 只要设备支持就常驻会话（开关切换不重建会话——
        // 往没配置过的 surface 上 addTarget 会让整次 capture 直接抛异常）
        val outputs = mutableListOf(previewSurface, readerSurface)
        rawImageReader?.let { outputs.add(it.surface) }

        previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyManualSettings(this)
            applyPreviewFlashSettings(this)
            // 白平衡预设可能在相机开启前就被设置（restoreSettings 早于会话建立），此处补挂
            set(
                CaptureRequest.CONTROL_AWB_MODE,
                currentAwbMode ?: CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
        }

        camera.createCaptureSession(
            outputs,
            object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                    captureSession = session
                    // 快速退出相机页时设备可能已 close，HAL 的配置回调晚到一步，
                    // setRepeatingRequest 会抛 IllegalStateException 把整个进程带走（真机实锤）。
                    // 预览流没起来只是页面黑一瞬，吞掉比崩掉好。
                    runCatching {
                        session.setRepeatingRequest(previewRequestBuilder!!.build(), previewMetaCallback, backgroundHandler)
                    }.onFailure {
                        android.util.Log.w(TAG, "会话配置回调迟到，预览请求未发出: ${it.message}")
                    }
                }
                override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                    Toast.makeText(context, "会话创建失败", Toast.LENGTH_SHORT).show()
                }
            },
            backgroundHandler
        )
    }

    /**
     * 拍摄全分辨率照片。
     *
     * @param defocus true = 本次故意对焦不准（模拟偶发失焦）
     * @param slowExposureNs > 0 时，本次走「胶片慢门」：手动长曝光，不走 HAL 自动测光
     * @param slowIso 与 [slowExposureNs] 配套的感光度（守住曝光量，画面不会变亮/变黑）
     */
    fun takePicture(defocus: Boolean = false, slowExposureNs: Long = 0L, slowIso: Int = 0) {
        try {
            readFlashSyncConfig()
            val camera = cameraDevice ?: return
            val readerSurface = imageReader!!.surface
            val slow = slowExposureNs > 0L && slowIso > 0
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerSurface)
                // P3 RAW：开着的快门同时收 RAW_SENSOR（同一帧的 Bayer 原始数据）
                if (rawNegativeEnabled) {
                    rawImageReader?.surface?.let { addTarget(it) }
                }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                applyManualSettings(this)
                // 白平衡跟随预览当前预设（STILL_CAPTURE 是独立 builder，不带会回到 AUTO）
                set(
                    CaptureRequest.CONTROL_AWB_MODE,
                    currentAwbMode ?: CaptureRequest.CONTROL_AWB_MODE_AUTO
                )
                // 模拟偶发失焦：手动对焦到失焦距离（只影响本次拍照，预览不受影响）
                if (defocus && minFocusDistance > 0f) {
                    set(CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, minFocusDistance * 0.6f)
                }
                applyStillFlashSettings(this)
                // 采集端「不加工」：胶片模拟要从最生的像素开始
                applyCaptureTuning(this)
                // 直闪帧的专属调优（AWB 钉日光 / 线性色调曲线 / 负 EV），
                // 内部自带条件判断，非直闪帧直接返回
                applyFlashCaptureTuning(this, slow)
                // 外置闪光：把这一帧锁到「闪光前的环境光读数」，不让 HAL 在闪光
                // 峰值上重新测光（那样会把曝光砍掉十几倍，成片发黑）。
                if (externalFlashSync && !slow) applyExternalFlashSync(this)
                // 必须放在最后：慢门要覆盖 AE 模式，也要压过上面闪光分支的 ALWAYS_FLASH
                if (slow) applySlowShutter(this, slowExposureNs, slowIso)
            }
            submitStillCapture(captureBuilder, slow)
        } catch (_: Exception) {}
    }

    /**
     * 采集端「少加工」（真机回归修订，11-05）。
     *
     * 只关**边缘锐化**：锐化在轮廓留一圈 halo，放大看就是数码味，关掉零代价。
     *
     * **降噪 / ZSL 恢复 HAL 默认**：11-02 曾一起关掉，真机回归实锤代价 —— 三星的
     * 多帧合成同时是**自动 HDR 的载体**，NOISE_REDUCTION_MODE_OFF 把它一并关掉后，
     * 室外大光比场景单帧为保高光压低曝光，暗部明显比降噪默认基线沉一大截
     * （引擎默认 scene_latitude 已开、暗部仍救不回来：8-bit JPEG 里暗部数据已压死）。
     * 多帧抹细节的代价由 **RAW 通路**承担才是正解 —— RAW_SENSOR 永远不过 ISP，
     * JPEG 通路本来就是「让 HAL 替你拍好」的那条路。
     *
     * A/B 探针：外部私有目录存在 `capture_ab_off.txt` 时回到 11-02 的全关形态
     * （生像素模式），用于对比亮度/细节代价。
     */
    private fun applyCaptureTuning(builder: CaptureRequest.Builder) {
        val rawPixels = runCatching {
            java.io.File(context.getExternalFilesDir(null), "capture_ab_off.txt").exists()
        }.getOrDefault(false)
        if (rawPixels) {
            android.util.Log.i("CaptureTuning", "A/B 探针生效：本次回到生像素模式（全关）")
            if (canDisableNr) {
                runCatching {
                    builder.set(
                        CaptureRequest.NOISE_REDUCTION_MODE,
                        android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_OFF
                    )
                }
            }
            if (canDisableZsl) {
                runCatching { builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false) }
            }
        }
        if (canDisableEdge) {
            runCatching {
                builder.set(
                    CaptureRequest.EDGE_MODE,
                    android.hardware.camera2.CameraMetadata.EDGE_MODE_OFF
                )
            }
        }
        android.util.Log.i(
            "CaptureTuning",
            "edgeOff=$canDisableEdge nrOff=${rawPixels && canDisableNr} zslOff=${rawPixels && canDisableZsl}"
        )
    }

    /**
     * 直闪帧的采集端调优（断点①：三星 ISP 在闪光瞬间对画面的两次「加工」）。
     *
     * 真 T3 直闪的三个签名对应三件事，全部只在**内置闪光、自动曝光、非慢门**的
     * 快门请求上生效——torch 是常亮补光、外闪走手动曝光、慢门是另一套曝光模型：
     *
     *  1. 色调曲线钉线性：ISP 的 tone mapping 会把被闪光压黑的背景又提回来，
     *     平方反比衰减被削平。线性曲线 = ISP 能给的最「生」的响应，衰减原样保留，
     *     压反差的胶片曲线留给引擎去做。设备不认 CONTRAST_CURVE 就跳过。
     *  2. AWB 钉日光：氙气闪光 ≈ 5500K。AUTO 模式在闪光瞬间会把钨丝背景往中性拉，
     *     钉死后主体中性、背景留暖橙——这就是 T3 直闪的冷暖分离。
     *     用户手动选了白平衡预设时不抢（那是用户自己的口味）。
     *  3. 曝光偏置 [FLASH_EV_BIAS]：v81 起常量为 0，默认不再额外压暗（HAL 的
     *     闪光重测光已经足够沉黑，再压会过冲，见常量注释里的真机定量）。
     *     换算成整档下发的代码保留，非零时仍生效。
     *  4. 关掉零快门延迟（v81，真机实锤）：S25U 的 HAL 对 STILL_CAPTURE +
     *     ALWAYS_FLASH 照样走 CheckZslStillCapture —— LED 主闪序列确实跑了
     *     （led mode 2→0），但**输出帧取自主闪前的 ZSL 历史暗帧**：连拍 4 张的
     *     RAW 底片全部贴着黑电平（净信号 ≈30/1023，98% 像素暗于 +256），
     *     而成片参数是闪光档（iso=23、1/60s、flashState=FIRED）——参数与帧内容
     *     严重错配。关 ZSL 强制 HAL 在提交时刻现拍一帧，主闪光自然落到成像帧上。
     *     [canDisableZsl] 的口径与 [applySlowShutter] 一致：设不进去就静默跳过。
     */
    private fun applyFlashCaptureTuning(builder: CaptureRequest.Builder, slow: Boolean) {
        if (!flashEnabled || !hasFlashUnit) return
        if (slow || manualIso != null || manualExposure != null) return

        var awbDaylight = false
        if (currentAwbMode == null ||
            currentAwbMode == android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO
        ) {
            awbDaylight = runCatching {
                builder.set(
                    CaptureRequest.CONTROL_AWB_MODE,
                    android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                )
            }.isSuccess
        }

        val tonemapLinear = if (canLinearTonemap) {
            runCatching {
                // (in, out) 点对：(0,0) 与 (1,1) 两点画一条对角线 = 单位直线
                val linear = android.hardware.camera2.params.TonemapCurve(
                    floatArrayOf(0f, 0f, 1f, 1f),
                    floatArrayOf(0f, 0f, 1f, 1f),
                    floatArrayOf(0f, 0f, 1f, 1f)
                )
                builder.set(
                    CaptureRequest.TONEMAP_MODE,
                    android.hardware.camera2.CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE
                )
                builder.set(CaptureRequest.TONEMAP_CURVE, linear)
            }.isSuccess
        } else false

        // v81 断点②：关掉这一帧的 ZSL，见方法注释第 4 条。
        val zslOff = if (canDisableZsl) {
            runCatching { builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false) }.isSuccess
        } else false

        var evBiasSteps = 0
        val stepEv = aeCompStep.numerator.toFloat() / aeCompStep.denominator
        if (stepEv > 0f) {
            // 除数与被除数都是 Float：Math.round(Float) 返回 Int（步长单位）
            // 按设备 AE 补偿步长换算整档（S25U 实测 1/10 EV/档，+1EV = 10 档）
            evBiasSteps = Math.round((FLASH_EV_BIAS + FLASH_SLOW_SYNC_EV) / stepEv)
            if (evBiasSteps != 0) {
                runCatching {
                    builder.set(
                        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        (exposureComp + evBiasSteps).coerceIn(aeCompRange.first, aeCompRange.last)
                    )
                }.onFailure { evBiasSteps = 0 }
            }
        }
        // vc86：手动锁定直闪帧曝光到闪光前预览 AE 的实测 (ISO, 快门)，
        // 绕开 HAL 的 ALWAYS_FLASH 重测光（它把 ISO 从 ~250 压到 23）。
        // 放在函数最后：必须覆盖 applyStillFlashSettings 写的 ALWAYS_FLASH。
        var lockAe = false
        val lockIso = lastAeIso
        val lockExpNs = (lastAeSec * 1_000_000_000f).toLong()
        val minExpNs = (deviceMinExposureSec * 1_000_000_000f).toLong().coerceAtLeast(1L)
        val maxExpNs = (deviceMaxExposureSec * 1_000_000_000f).toLong()
        // vc89（vc83 定稿）：总曝光量目标钳制 + ISO 单独钳住主体。
        // 目标取预览 AE 想给的环境光量，夹到 3600 ISO·ms（vc88 的 2500
        // 基础上 +0.53EV，仍低于 vc87 过曝下界 5609、接近 FIMO 4167）；
        // 快门 = 目标 / 实际 ISO 反算，再夹设备真实范围。
        // ⚠️ 真机日志：快门实际 14.4~16.8ms，并未变长；主体不被重新推亮
        // 靠的是 ISO 上限与总曝光量上限，不是「增益移到快门」。
        val isoScale = Math.pow(2.0, FLASH_LOCK_EV_BIAS.toDouble()).toFloat()
        var adjIso = Math.min(Math.round(lockIso * isoScale), FLASH_MAX_FLASH_ISO)
            .coerceAtLeast(1)
        val targetProduct = Math.min(
            lockIso.toFloat() * lastAeSec * 1000f,
            FLASH_MAX_EXPOSURE_PRODUCT
        )
        val adjExpSec = (targetProduct / (adjIso * 1000f))
            .coerceIn(deviceMinExposureSec, Math.min(FLASH_MAX_SHUTTER_SEC, deviceMaxExposureSec))
        adjIso = adjIso.coerceIn(deviceIsoRange.first, deviceIsoRange.last)
        val adjExpNs = (adjExpSec * 1_000_000_000f).toLong()
            .coerceIn(minExpNs, maxExpNs)
        // 日志打钳制后的真实总曝光量（vc88 打的是钳制前值，会误导复标）
        val expProduct = adjIso.toFloat() * adjExpNs / 1_000_000f
        if (FLASH_LOCK_PREVIEW_AE && hasManualSensor && lockIso > 0 && lockExpNs > 0) {
            runCatching {
                builder.set(
                    CaptureRequest.CONTROL_MODE,
                    android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO
                )
                builder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
                )
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, adjExpNs)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, adjIso)
                // AE_OFF 下 flash.* 由 app 控制，SINGLE 让这一帧点火（不是 vc81 那次
                // ALWAYS_FLASH→SINGLE 的同义替换：那时 AE 仍由 HAL 重测光）。
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    android.hardware.camera2.CameraMetadata.FLASH_MODE_SINGLE
                )
                lockAe = true
            }.onFailure {
                android.util.Log.w(TAG, "直闪手动锁定曝光失败，回落 HAL 自动: ${it.message}")
            }
        }
        android.util.Log.i(
            "FlashTuning",
            "capture awbDaylight=$awbDaylight tonemapLinear=$tonemapLinear " +
                "evBiasSteps=$evBiasSteps zslOff=$zslOff " +
                "lockAe=$lockAe lockIso=$lockIso lockExpNs=$lockExpNs " +
                "lockEvBias=$FLASH_LOCK_EV_BIAS isoScale=$isoScale lockIsoAdj=$adjIso " +
                "adjExpNs=$adjExpNs expSec=$adjExpSec expProduct=$expProduct"
        )
    }

    /** 切进/切出外置闪光档：基线只在档位变化时重置，避免每次拨 EV 都清零。 */
    fun setExternalFlashSync(enabled: Boolean) {
        if (externalFlashSync != enabled) {
            externalFlashSync = enabled
            aeBaselineIso = 0
            aeBaselineSec = 0f
        }
    }

    /**
     * 外置闪光档的快门帧：**固定曝光量**，不看环境光测光。
     *
     * 为什么不能跟 AE 基线走：闪光是独立光源，强度只取决于灯距，与环境光无关。
     * 之前按「环境光 ISO × t × 系数」折算，换个稍亮的房间 ISO 就被压到设备下限，
     * 闪光直接欠曝成黑片（用户 01:48 那张净信号只有 13.6）。真机闪光灯也是
     * 固定光圈/ISO/同步快门，靠闪光补偿拨盘调 —— 这里同理。
     *
     * 固定 [FLASH_SYNC_ISO_AT_100MS]（57）@ 100ms 同步快门，由真机逐档扫描标定；
     * EV 拨盘按标准档位 2^EV 调整，[flashSyncGain] 是热调倍率。
     * 只关 AE、不动 CONTROL_MODE —— AF / AWB 仍然自动（和慢门那套同理）。
     */
    private fun applyExternalFlashSync(builder: CaptureRequest.Builder) {
        // v62 P0：快门必须按设备真实上限收敛，且被截断后要反算 ISO 补回曝光量。
        // 旧实现写死 100ms，在最大曝光只有 33~66ms 的机型上会被 HAL 截断，
        // 曝光量凭空少 1~2 EV，闪光帧直接黑一片。
        val maxShutterNs = (deviceMaxExposureSec * 1_000_000_000f)
            .coerceIn(MIN_SYNC_SHUTTER_NS.toFloat(), MAX_SYNC_SHUTTER_NS.toFloat())
            .toLong()
        val minShutterNs = maxOf(
            MIN_SYNC_SHUTTER_NS,
            (deviceMinExposureSec * 1_000_000_000f).toLong().coerceAtLeast(0L)
        )
        val caps = FlashExposure.Caps(
            hasManualSensor, deviceIsoRange.first, deviceIsoRange.last,
            minShutterNs, maxShutterNs
        )
        if (!caps.canLockExposure()) {
            // LEGACY / LIMITED 机型不支持 MANUAL_SENSOR：手写 AE_OFF + SENSOR_* key
            // 会被 HAL 忽略甚至让整帧失败。降级为「BLE 点灯 + HAL 自动曝光」——
            // 代价是 AE 可能在闪光峰值重新测光把成片压暗，但至少保证出图。
            android.util.Log.w(
                "FlashSync",
                "设备不支持手动曝光（manualSensor=$hasManualSensor " +
                    "快门=$minShutterNs~$maxShutterNs ns ISO=$deviceIsoRange），" +
                    "外置闪光降级为 BLE 点灯 + HAL 自动曝光"
            )
            return
        }
        val evGain = Math.pow(2.0, exposureComp.toDouble()).toFloat()
        val gain = flashSyncGain * evGain
        val solved = FlashExposure.solve(
            baseIso = FLASH_SYNC_ISO_AT_100MS * gain,
            baseShutterNs = FLASH_SYNC_SHUTTER_NS,
            minIso = deviceIsoRange.first,
            maxIso = deviceIsoRange.last,
            minShutterNs = minShutterNs,
            maxShutterNs = maxShutterNs,
        )
        builder.set(
            CaptureRequest.CONTROL_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO
        )
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
        )
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, solved.shutterNs)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, solved.iso)
        builder.set(
            CaptureRequest.SENSOR_FRAME_DURATION,
            maxOf(solved.shutterNs, 33_000_000L)
        )
        android.util.Log.i(
            "FlashSync",
            "闪光固定曝光 iso=${solved.iso} t=${solved.shutterNs / 1_000_000}ms " +
                "gain=$gain ev=$exposureComp 快门上限=${maxShutterNs / 1_000_000}ms" +
                (if (solved.clamped) " ⚠️ ${solved.note}" else "") +
                " (环境基线 iso=$aeBaselineIso t=${(aeBaselineSec * 1000).toInt()}ms)"
        )
    }

    /**
     * 本次拍照强制一段长曝光（胶片慢门）。
     *
     * 只关 AE，不动 CONTROL_MODE —— 这样 AF / AWB 仍然自动（沿用上面 applyManualSettings
     * 设好的焦点与白平衡）。不能用 applyManualSettings 的手动分支：它会把 CONTROL_MODE
     * 设成 OFF，等于把白平衡也一起冻在上一次的值上。
     *
     * 另外三件事决定这一张到底糊不糊：
     *   · 帧时长必须 ≥ 曝光时间，否则 HAL 会把长曝光截断回 1/30；
     *   · 关掉 OIS —— T3 没有防抖，不关的话 1/8 秒也能被稳回来；
     *   · 关掉降噪/ZSL —— 多帧合成会把抖动「修」平，等于白拍一张长曝光。
     */
    private fun applySlowShutter(builder: CaptureRequest.Builder, exposureNs: Long, iso: Int) {
        builder.set(
            CaptureRequest.CONTROL_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO
        )
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
        )
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(deviceIsoRange.first, deviceIsoRange.last))
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposureNs, 33_000_000L))
        if (canDisableOis && android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching {
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                )
            }
        }
        if (canDisableNr) {
            builder.set(
                CaptureRequest.NOISE_REDUCTION_MODE,
                android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_OFF
            )
        }
        // 关掉零快门延迟：ZSL 会在按下前就把帧塞进缓冲，HAL 可能挑一张「旧的」交上来，
        // 长曝光等于没发生。这个 key 不是所有 HAL 都认，设不进去就静默跳过。
        val zslOff = runCatching {
            builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
        }.isSuccess
        android.util.Log.i(
            "SlowShutter",
            "apply exposure=${exposureNs / 1_000_000.0}ms iso=$iso " +
                "oisOff=$canDisableOis nrOff=$canDisableNr zslOff=$zslOff"
        )
    }

    /**
     * 按**实测** HAL 延迟调度一次提前触发（负偏移专用）。
     * 只在 `halLatency ≥ |offset|` 的通路生效（RAW 265~274ms）；JPG 通路
     * 已由 [submitStillCapture] 的「先点灯、延后快门」处理，这里直接跳过。
     */
    private fun scheduleFlashPreTrigger(handler: Handler) {
        // 已在「快门前置」阶段发过指令（JPG 通路），这里不再补发
        if (!externalFlashSync || flashTriggerOffsetMs >= 0 || flashTriggerPosted) return
        val delay = flashTriggerDelayMs(flashHalLatencyMs, flashTriggerOffsetMs)
        android.util.Log.i(
            "FlashSync",
            "预触发调度 delay=${delay}ms = halLatency ${flashHalLatencyMs}ms + offset ${flashTriggerOffsetMs}ms"
        )
        handler.postDelayed({
            if (externalFlashSync) {
                fireExternalFlashOnce("预触发 ${flashTriggerOffsetMs}ms")
            }
        }, delay)
    }

    /**
     * 外置闪光：**先点灯，再把快门延后提交**。
     *
     * 11-07 18:44 七帧真机日志坐实了时序方向是反的：
     *  - 灯收到 `05 00` 后约 200ms 才出光（`flash_sync_offset` 的绝对值就是这个起辉量）；
     *  - JPG 直提交路径 HAL 只要 26~42ms 就开曝光，曝光窗口只有 100ms。
     *
     * 所以「曝光开始前 200ms 发指令」物理上做不到 —— 那时快门还没按。旧实现把
     * `halLatency + offset` 钳到 0，等于「按下快门的同时发指令」，灯永远晚 60~90ms
     * 出光，七帧里六帧全黑、一帧只蹭到一点。
     *
     * 反过来才成立：按下快门立刻发 BLE 指令，把拍照请求延后
     * `|offset| − halLatency`（默认 200 − 35 ≈ 165ms）再提交，让「灯出光」
     * 与「曝光开始」对齐。代价是约 165ms 快门时滞，与真实机身闪光同步的观感一致。
     *
     * RAW 通路实测 halLatency 265~274ms ≥ |offset|，前置量为 0，退回原路径
     * （提交后按实测延迟提前触发），行为不变。
     */
    private fun submitStillCapture(captureBuilder: CaptureRequest.Builder, slowShutter: Boolean = false) {
        val handler = backgroundHandler
        if (externalFlashSync && !slowShutter && handler != null) {
            val preRoll = flashPreRollMs(flashHalLatencyMs, flashTriggerOffsetMs)
            if (preRoll > 0L && !flashTriggerPosted) {
                android.util.Log.i(
                    "FlashSync",
                    "外置闪光：先点灯，快门延后 ${preRoll}ms 提交" +
                        "（起辉 ${-flashTriggerOffsetMs}ms − HAL ${flashHalLatencyMs}ms）"
                )
                fireExternalFlashOnce("快门前置")
                handler.postDelayed({ submitStillCaptureNow(captureBuilder, slowShutter) }, preRoll)
                return
            }
        }
        submitStillCaptureNow(captureBuilder, slowShutter)
    }

    /**
     * 把拍照请求真正下发（不含外置闪光的前置延后，见 [submitStillCapture]）。
     *
     * 开闪光灯时先跑一次 AE 预闪（precapture）：HAL 借这一步完成闪光测光与充电，
     * 等 AE 收敛（或超时兜底）再开快门，否则第一张常常曝光过亮/过暗、甚至错过闪。
     * 关闪光时走原路径，不加任何等待。
     */
    private fun submitStillCaptureNow(captureBuilder: CaptureRequest.Builder, slowShutter: Boolean = false) {
        val session = captureSession
        val camera = cameraDevice
        val surface = previewSurface
        val handler = backgroundHandler
        if (session == null || camera == null || handler == null) {
            session?.capture(captureBuilder.build(), null, handler)
            return
        }
        // 慢门已把 AE 关掉，HAL 不会闪，也不需要预闪测光 —— 直接开快门
        if (!flashEnabled || slowShutter || manualIso != null || manualExposure != null || surface == null) {
            // 未开闪光 / 手动曝光（HAL 不会闪）→ 直接拍
            flashSubmitRealtimeMs = android.os.SystemClock.elapsedRealtime()
            session.capture(captureBuilder.build(), stillCaptureCallback, handler)
            scheduleFlashPreTrigger(handler)
            return
        }

        var fired = false
        val fire = {
            if (!fired) {
                fired = true
                runCatching {
                    flashSubmitRealtimeMs = android.os.SystemClock.elapsedRealtime()
                    session.capture(captureBuilder.build(), stillCaptureCallback, handler)
                    // 带 precapture 的路径也要以「真正提交拍照」为 t=0，不能漏掉提前触发
                    scheduleFlashPreTrigger(handler)
                }
            }
        }
        // 超时兜底：任何情况下都不能把快门卡住（最多等 700ms，够 HAL 完成测光充电）
        val timeout = Runnable { fire() }
        handler.postDelayed(timeout, FLASH_PRECAPTURE_TIMEOUT_MS)

        val preBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            applyManualSettings(this)
            // v81 回退（11-14 用户拍板）：恢复 vc80 的 ALWAYS_FLASH one-shot
            // precapture。v81 SINGLE 试验轮真机定量：S25U 在 SINGLE 下 aeState
            // 正常收敛（不再 700ms 超时），但出图口径与 ALWAYS_FLASH 完全相同
            //（iso=23/1/60s、背景物理死黑）、无预闪（用户明确要预闪手感）、
            // 亮度观感无改善——SINGLE 在这台 HAL 上没有解锁任何厂商闪光管线，
            // 反而丢了 ALWAYS_FLASH 已验证可用的预闪→主闪序列。
            applyPrecaptureFlashSettings(this)
            set(
                CaptureRequest.CONTROL_AWB_MODE,
                currentAwbMode ?: CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            // vc85 保留的预闪帧 AE 补偿换算能力（两帧同值才有意义）。
            // 当前 FLASH_SLOW_SYNC_EV=0f（vc85 已证这台 HAL 忽略 AE 补偿），
            // 不产生实际补偿；别的机型若 HAL 支持可复用这条通路。
            val preStepEv = aeCompStep.numerator.toFloat() / aeCompStep.denominator
            if (preStepEv > 0f) {
                val preEvSteps = Math.round((FLASH_EV_BIAS + FLASH_SLOW_SYNC_EV) / preStepEv)
                set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    (exposureComp + preEvSteps).coerceIn(aeCompRange.first, aeCompRange.last)
                )
            }
            set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
        }
        // v74 P0：打点 + 置 pending，拍照结果回调必须配对发 CANCEL（见 resetFlashPrecapture）
        precapturePending = true
        flashPrecaptureStarts++
        android.util.Log.i("FlashDiag", "PRECAPTURE START count=$flashPrecaptureStarts ae=ALWAYS_FLASH(one-shot) slowSyncEv=$FLASH_SLOW_SYNC_EV")
        reportFlashState()
        val preCb = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: android.hardware.camera2.CameraCaptureSession,
                request: CaptureRequest,
                result: android.hardware.camera2.TotalCaptureResult,
            ) {
                val ae = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
                // CONVERGED / FLASH_REQUIRED = 测光完成，可以开快门；PRECAPTURE = 还在跑，继续等
                if (ae == null ||
                    ae == android.hardware.camera2.CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                    ae == android.hardware.camera2.CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED ||
                    ae == android.hardware.camera2.CameraMetadata.CONTROL_AE_STATE_LOCKED
                ) {
                    handler.removeCallbacks(timeout)
                    fire()
                }
            }
        }
        runCatching { session.capture(preBuilder.build(), preCb, handler) }
            .onFailure { handler.removeCallbacks(timeout); fire() }
    }

    /**
     * v74 P0：把 HAL 从 AE 预闪状态拉回来，熄灭内置闪光 LED。
     *
     * Camera2 规范要求 precapture 序列结束或放弃时显式发 CANCEL；vc73 只发过 START，
     * 全库没有 CANCEL，小米 13U 等 HAL 就把 LED 保持在预闪常亮，切档时只把
     * CONTROL_AE_MODE 拨回 ON 也终止不了这个状态机。
     *
     * 两段式（与 Camera2Basic 官方 sample 同构）：
     *  1) 一次性 capture 发 CANCEL + FLASH_MODE_OFF + AE_ON，让 HAL 立刻收灯；
     *  2) 预览 repeating 回到 trigger=IDLE、FLASH_MODE=OFF、AE_ON。
     *
     * ⚠️ v75 P1：第 2 步**绝不再按当前档挂回 ALWAYS_FLASH** —— v74 在这里
     * 发完 CANCEL 又把预览挂回 ALWAYS_FLASH，一次性 CANCEL 被常驻 repeating
     * 立刻覆盖，是「拍完第一张常亮」在 v74 上仍会复现的直接原因。
     *
     * ⚠️ 只动预览请求，不动 TEMPLATE_STILL_CAPTURE 的成像参数：闪光成片曝光 /
     * AWB 钉日光 / 线性 tonemap / FLASH_EV_BIAS 全部不受影响，也不碰外置闪光
     * （EXTERNAL）与慢门通路。
     */
    private fun resetFlashPrecapture(reason: String, force: Boolean = false) {
        if (!force && !precapturePending) return
        val session = captureSession ?: return
        if (previewSurface == null) return
        val builder = previewRequestBuilder ?: return
        val handler = backgroundHandler
        runCatching {
            // v81：CANCEL 这一帧必须复用常驻预览 builder 的**全部参数**。
            // 旧写法用 TEMPLATE_PREVIEW 新建 builder、只塞 3 个 key 就 addTarget(预览
            // surface)，这一帧会真的投到取景器，却缺 EV / AWB / AF / zoom 等成像参数，
            // 画面瞬间跳变 —— 就是「切换闪光灯档位时预览闪一下」的直接来源。
            // 现在这一帧与前后预览帧参数完全一致，只有 trigger 不同。
            builder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL
            )
            builder.set(
                CaptureRequest.FLASH_MODE,
                android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF
            )
            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON
            )
            try {
                session.capture(builder.build(), null, handler)
            } finally {
                // 无论 capture 成败都把 trigger 放回 IDLE：repeating 不允许带 CANCEL，
                // 否则会话已关闭时下一次 setRepeatingRequest 会被 HAL 整条拒绝。
                builder.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    android.hardware.camera2.CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                )
            }
            builder.set(
                CaptureRequest.FLASH_MODE,
                android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF
            )
            // v75 P1：恒为 AE_ON。只要这里再挂 ALWAYS_FLASH，上面的 CANCEL 就白发了。
            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON
            )
            session.setRepeatingRequest(builder.build(), previewMetaCallback, handler)
            precapturePending = false
            flashPrecaptureCancels++
            lastFlashResetReason = reason
            android.util.Log.i(
                "FlashDiag",
                "PRECAPTURE CANCEL ($reason) starts=$flashPrecaptureStarts cancels=$flashPrecaptureCancels"
            )
            reportFlashState()
        }.onFailure {
            android.util.Log.w("FlashDiag", "PRECAPTURE CANCEL 失败 ($reason): ${it.message}")
        }
    }

    /** v74 P2：把闪光灯状态写进诊断报告（状态变化时才落盘，随反馈邮件正文发出） */
    private fun reportFlashState() {
        runCatching {
            val b = previewRequestBuilder
            com.filmcam.app.develop.DeviceGeometryReport.putAll(
                "flash.unitAvailable" to hasFlashUnit,
                "flash.builtInEnabled" to flashEnabled,
                "flash.previewAeMode" to (b?.get(CaptureRequest.CONTROL_AE_MODE)?.toString() ?: "?"),
                "flash.previewFlashMode" to (b?.get(CaptureRequest.FLASH_MODE)?.toString() ?: "?"),
                "flash.aeStateLast" to (lastAeState?.toString() ?: "?"),
                "flash.precapturePending" to precapturePending,
                "flash.precaptureStarts" to flashPrecaptureStarts,
                "flash.precaptureCancels" to flashPrecaptureCancels,
                "flash.resetLastReason" to lastFlashResetReason,
            )
            // 有变化才落盘（flush 内部用 snapshotIfDirty）；反馈邮件走 read() 拿内存全文，
            // 这里保证文件导出也能看到最新的 flash 状态。
            com.filmcam.app.develop.DeviceGeometryReportIo.flush(context)
        }
    }

    /**
     * v75 P1：预览 / 预闪请求专用的闪光参数。
     *
     * Camera2 允许 HAL 在 CONTROL_AE_MODE_ON_ALWAYS_FLASH 下于预览、预闪测光阶段
     * 点亮 LED；小米 13U 点亮后不自己收 —— 这就是「INT 档拍完第一张常亮、切档无效、
     * 进暗房（close 会话断电）才灭」的根因。把 ALWAYS_FLASH 从预览链路上彻底拿掉，
     * HAL 就没有点灯理由，v74 P0 的一次性 CANCEL 也不会再被 repeating 覆盖。
     *
     * ⚠️ 手动曝光（EXTERNAL / 慢门）时 CONTROL_AE_MODE 必须由 applyManualSettings
     * 保持 OFF，这里只写 FLASH_MODE_OFF，绝不覆写 AE 模式。
     */
    private fun applyPreviewFlashSettings(builder: CaptureRequest.Builder) {
        builder.set(
            CaptureRequest.FLASH_MODE,
            android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF
        )
        if (manualIso == null && manualExposure == null) {
            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON
            )
        }
    }

    /**
     * one-shot precapture 的**一次性**请求（AE_PRECAPTURE_TRIGGER_START）专用。
     *
     * vc80 真机取证（S25U）：P1 把这一路也改成 AE_ON 后，HAL 不做闪光测光与
     * 充电，700ms 超时才放快门；HAL 只把 LED 点 34ms 做测光，拍照结果
     * flashState=READY（不是 FIRED）且走 ZSL 历史帧 —— 用户看到「不闪」。
     * Camera2 要求 precapture 请求本身就是 ON_ALWAYS_FLASH，HAL 才会跑完整的
     * 预闪→测光→退出 ZSL→主闪序列。
     *
     * v81 SINGLE 试验已回退（11-14 用户拍板：要预闪手感、SINGLE 出图口径与
     * ALWAYS_FLASH 相同无收益）：这一路恢复 ALWAYS_FLASH，是用户实机验证过
     * 「预闪→主闪→FIRED」全序列可用的方案。
     *
     * 这里**只允许用于 submitStillCaptureNow 里那一个 one-shot 请求**；常驻预览
     * repeating 仍走 applyPreviewFlashSettings（AE_ON + FLASH_MODE_OFF），收尾仍由
     * resetFlashPrecapture 的两段式 CANCEL 复位 —— P1 治好的「拍完常亮」不动。
     * 不写 FLASH_MODE：AE_MODE_ON_ALWAYS_FLASH 下由 HAL 按 AE 状态控制预闪/主闪。
     */
    private fun applyPrecaptureFlashSettings(builder: CaptureRequest.Builder) {
        if (!flashEnabled) return
        if (!hasFlashUnit) return
        if (manualIso != null || manualExposure != null) return
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        )
    }

    /**
     * 闪光请求参数：⚠️ v75 P1 起 **只允许用于 TEMPLATE_STILL_CAPTURE 的拍照帧**。
     * 必须在 applyManualSettings 之后调用，否则会被自动测光分支覆盖。
     * 只在自动曝光下生效 —— 手动 ISO/快门（外置闪光灯那条路）AE 是 OFF，HAL 不闪。
     *
     * v81 回退（11-14 用户拍板）：恢复 vc80 的 ALWAYS_FLASH——用户实机验证过
     * 「预闪→主闪→flashState=FIRED」全序列可用，且**要预闪手感**。v81 SINGLE
     * 试验轮真机定量：aeState 正常收敛（不再 700ms 超时）但出图口径与 ALWAYS_FLASH
     * 完全相同（iso=23/1/60s、背景按平方反比死黑）、isFlashCapture 仍 0、无预闪、
     * 亮度观感无改善——SINGLE 在 S25U HAL 上没有解锁任何厂商闪光管线，收益为零
     * 代价为负。SINGLE 方案见 git 历史 v81（若未来某机型 ALWAYS_FLASH 表现异常可
     * 作为候选 A/B）。
     * 这里挂 ALWAYS_FLASH 是成片直闪效果的来源，一字不改。
     */
    private fun applyStillFlashSettings(builder: CaptureRequest.Builder) {
        if (!flashEnabled) return
        if (!hasFlashUnit) return          // 没有闪光灯单元：走 torch，见 setFlashEnabled
        if (manualIso != null || manualExposure != null) return
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        )
    }

    /**
     * 内置闪光灯开关。v74 P0：
     * - 关灯不再只把 AE 拨回 ON，而是走 [resetFlashPrecapture] 显式发
     *   PRECAPTURE_CANCEL + FLASH_MODE_OFF，否则小米 13U 等 HAL 的预闪 LED 关不掉；
     * - 去掉 vc73 的「同值就不下发」早退判断：同值也下发一次，杜绝「切档无效」；
     * - 只改预览请求，STILL_CAPTURE 的成像参数一个不动。
     */
    fun setFlashEnabled(enabled: Boolean) {
        val changed = flashEnabled != enabled
        flashEnabled = enabled
        android.util.Log.i(
            "FlashDiag",
            "setFlashEnabled($enabled) changed=$changed hasFlashUnit=$hasFlashUnit"
        )
        if (!hasFlashUnit) {
            // 设备没有闪光灯单元：HAL 闪光模式无效，退回常亮手电补光。
            // 这条路径本来就会写 FLASH_MODE_OFF，能正常关灯；关灯时强制再下发一次。
            if (torchOn != enabled || !enabled) {
                torchOn = enabled
                enableFlash(enabled)
            }
            reportFlashState()
            return
        }
        if (!enabled) {
            // v81：本次会话从未起过 precapture → 没有预闪状态机要取消，
            // 一次一次性 CANCEL 帧都不该发（那帧会投到预览 surface）。
            // 判据只看 precapture 状态，不看 changed —— BUILT_IN→EXTERNAL 虽然
            // changed=true，但从未拍过闪光照片时同样没有灯要收。
            // 预览 builder 的 AE_ON + FLASH_MODE_OFF 在切进档位时已经设好，早退不改它。
            // ⚠️ 只要拍过照 / 有 pending，仍强制走两段式 CANCEL —— v74 P0 治好的
            // 小米 13U 常亮、v75 P1 的复位一个都不能少。
            if (!precapturePending && flashPrecaptureStarts == 0) {
                reportFlashState()
                return
            }
            // 关闪光：显式取消预闪 + 灭灯（reset 内部已下发 repeating request）
            resetFlashPrecapture("setFlashEnabled(false)", force = true)
        } else {
            previewRequestBuilder?.let {
                applyPreviewFlashSettings(it)
                runCatching {
                    captureSession?.setRepeatingRequest(it.build(), previewMetaCallback, backgroundHandler)
                }
            }
        }
        reportFlashState()
    }

    private fun applyManualSettings(builder: CaptureRequest.Builder) {
        if (manualIso != null || manualExposure != null) {
            builder.set(CaptureRequest.CONTROL_MODE, android.hardware.camera2.CameraMetadata.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF)
            manualIso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            manualExposure?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        } else {
            // 自动模式：相机自动测光 + 曝光补偿（下发前按设备真实范围钳制）
            builder.set(CaptureRequest.CONTROL_MODE, android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                exposureComp.coerceIn(aeCompRange.first, aeCompRange.last)
            )
        }
        // 对焦：模拟失焦用手动固定对焦距离；点屏后切 AUTO 并锁定焦点（T3 半按锁定手感）；
        // 未点屏时才用连续对焦
        val d = defocusDistance
        if (d != null) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, d)
        } else {
            val region = afRegion
            if (region != null) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
        }
        // 复刻 T3 35mm 视角：主摄裁切（需 Android 11+，S25 Ultra 支持）
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        }
    }

    /**
     * 模拟失焦：故意对焦不准，让主体轻微处于焦外。
     * 实时生效于预览和拍照。focusRatio 0~1，越小失焦越轻。
     */
    fun setDefocus(enabled: Boolean, focusRatio: Float = 0.6f) {
        if (!enabled) {
            afRegion = null
            afLocked = false
        }
        defocusDistance = if (enabled && minFocusDistance > 0f) minFocusDistance * focusRatio else null
        previewRequestBuilder?.let {
            applyManualSettings(it)
            captureSession?.setRepeatingRequest(it.build(), previewMetaCallback, backgroundHandler)
        }
    }

    /**
     * 点屏对焦：把预览视图坐标映射到传感器 active array 坐标，
     * 设置 AF/AE 测光区并触发一次 AF 扫焦。
     *
     * 坐标映射：屏幕归一化 (u,v) 先按 contentRectFor 去掉 letterbox 黑边，
     * 再按「传感器图像顺时针旋转 R 度才是当前屏幕画面」的逆变换折算回传感器坐标
     * （R = (sensorOrientation - displayRotation + 360) % 360，与 JPEG_ORIENTATION 同源）。
     * 之前只用「是否横屏」决定要不要互换两轴，漏了竖屏/反向横屏该做的取反 ——
     * 表现就是测光区与手指点的位置左右对不上。
     *
     * @param onFocused 对焦流程触发后约 700ms 回调（近似扫焦完成，用于震动反馈）
     * @return false 表示无法点屏对焦（会话未就绪 / 模拟失焦开启 / 设备不支持）
     */
    fun focusAt(
        viewX: Float, viewY: Float, viewW: Int, viewH: Int,
        onFocused: (() -> Unit)? = null,
    ): Boolean {
        if (defocusDistance != null) return false   // 模拟失焦优先，不响应点屏
        val session = captureSession ?: return false
        val builder = previewRequestBuilder ?: return false
        if (viewW <= 0 || viewH <= 0) return false
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = manager.getCameraCharacteristics(cameraId)
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?: return false

            // zoom 裁切区域（API 30+ 用 CONTROL_ZOOM_RATIO，等价于 active 中心裁切 1/Z）
            val z = if (android.os.Build.VERSION.SDK_INT >= 30) zoomRatio else 1f
            val cropW = active.width() / z
            val cropH = active.height() / z
            val cropX = active.left + (active.width() - cropW) / 2f
            val cropY = active.top + (active.height() - cropH) / 2f

            // 预览画面 letterbox 几何：唯一来源 ViewfinderFrameView.contentRectFor
            // （与 shader、取景框标记同一套公式，横屏满高 / 竖屏满宽都在这里统一）。
            // 先把 view 坐标换算到画面内容坐标，再映射传感器，点哪对哪。
            val rect = com.filmcam.app.ui.ViewfinderFrameView
                .contentRectFor(viewW.toFloat(), viewH.toFloat())
            val u = ((viewX - rect.left) / rect.width().coerceAtLeast(1f)).coerceIn(0f, 1f)
            val v = ((viewY - rect.top) / rect.height().coerceAtLeast(1f)).coerceIn(0f, 1f)
            // 屏幕画面 → 传感器坐标系的总旋转量 R（顺时针）。
            // 传感器图像要顺时针转 R 度，才是当前设备方向下正立的画面；
            // 反过来查表就是屏幕归一化坐标 (u,v) 到传感器 (nx,ny) 的映射。
            // R = (sensorOrientation - displayRotation + 360) % 360 —— 与 JPEG_ORIENTATION
            // 同一套公式（官方 Camera2 sample 的 (ORIENTATIONS[rot] + sensor + 270) % 360 等价）。
            val sensorOrientation =
                chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val displayRotation = when (currentDisplayRotationDeg()) {
                90 -> 90
                180 -> 180
                270 -> 270
                else -> 0
            }
            val r = (sensorOrientation - displayRotation + 360) % 360
            val (nx, ny) = when (r) {
                90 -> v to (1f - u)      // 竖屏：轴互换，且横轴取反（画面左=传感器下）
                180 -> (1f - u) to (1f - v)
                270 -> (1f - v) to u
                else -> u to v           // 横屏（rotation=90）：画面即传感器原生方向
            }
            val sx = (cropX + nx * cropW).toInt()
            val sy = (cropY + ny * cropH).toInt()
            val half = (minOf(cropW, cropH) * 0.1f).toInt().coerceAtLeast(16)
            val region = android.hardware.camera2.params.MeteringRectangle(
                (sx - half).coerceAtLeast(active.left),
                (sy - half).coerceAtLeast(active.top),
                half * 2, half * 2, 1000
            )

            // 关键：CONTROL_AF_MODE_CONTINUOUS_PICTURE 下相机框架会忽略 AF_TRIGGER，
            // 点屏只影响测光→看起来"只有测光变了没对上焦"。这里切换到 AUTO 模式并触发一次扫焦，
            // 完成锁定后保持（T3 半按锁定手感），再次点屏才重新对焦。
            afRegion = region
            afLocked = false
            pendingFocusCb = onFocused
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.setRepeatingRequest(builder.build(), previewMetaCallback, backgroundHandler)

            // 兜底：部分设备不回调 LOCKED 状态，2.5s 后仍给一次反馈
            backgroundHandler?.removeCallbacks(focusTimeout)
            backgroundHandler?.postDelayed(focusTimeout, 2500)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 当前屏幕旋转角度（0/90/180/270），用于把触摸坐标折算到传感器方向 */
    private fun currentDisplayRotationDeg(): Int {
        val activity = context as? android.app.Activity
        val rot: Int = if (android.os.Build.VERSION.SDK_INT >= 30 && activity != null) {
            activity.display?.rotation ?: Surface.ROTATION_0
        } else if (activity != null) {
            @Suppress("DEPRECATION")
            activity.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        } else {
            Surface.ROTATION_0
        }
        return when (rot) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /**
     * 预览帧回调：既监听 AF 状态（点屏扫焦是否完成），也持续把 对焦状态/曝光时间
     * 上报给 UI 层的取景器指示灯（T3 的绿色合焦灯 / 快门速度档位）。
     */
    private val previewMetaCallback = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: android.hardware.camera2.CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult
        ) {
            val state = result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)
            val exposureSec = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                ?.let { it / 1_000_000_000f } ?: 0f

            if (state == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                state == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            ) {
                backgroundHandler?.removeCallbacks(focusTimeout)
                afLocked = true
                // 触发位回到 IDLE，避免重复扫焦；AF 仍停在锁定位置
                previewRequestBuilder?.set(
                    CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                )
                runCatching {
                    previewRequestBuilder?.let { session.setRepeatingRequest(it.build(), this, backgroundHandler) }
                }
                fireFocusCallback()
            }

            // 慢门决策要用同一对 (t, ISO)：只存曝光时间不够，亮度是两者的乘积
            val isoAe = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val aeState = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
            // v74 P2：AE 状态只在变化时上报，避免每帧置脏；闪光时它会在
            // SEARCHING / PRECAPTURE / FLASH_REQUIRED / CONVERGED 之间跳，是判异常的关键。
            if (aeState != null && aeState != lastAeState) {
                lastAeState = aeState
                reportFlashState()
            }
            if (exposureSec > 0f && isoAe > 0) {
                lastAeSec = exposureSec
                lastAeIso = isoAe
                // 外置闪光的「环境光基线」只在 AE 收敛时更新。灯一闪 AE 会立刻
                // 进 SEARCHING（真机 01:15 那张 aeState=1），基线就停在闪光前的
                // 读数，不会被闪光峰值污染 —— 这是黑片修复的关键。
                if (aeState == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_LOCKED
                ) {
                    if (aeBaselineIso <= 0) {
                        aeBaselineSec = exposureSec
                        aeBaselineIso = isoAe
                    } else {
                        aeBaselineSec = aeBaselineSec * 0.9f + exposureSec * 0.1f
                        aeBaselineIso = (aeBaselineIso * 0.9f + isoAe * 0.1f).toInt()
                    }
                }
            }

            // 指标上报（限流 150ms，避免每帧刷 UI）
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastMetaPostAt > 150) {
                lastMetaPostAt = now
                val af = state
                mainHandler.post { onViewfinderMeta?.invoke(af, exposureSec, isoAe) }
            }
        }
    }

    /** 点屏后是否已锁定焦点（供 UI 显示「慢闪」状态，对应 AFL 锁定语义） */
    fun isAfLocked(): Boolean = afLocked

    private fun fireFocusCallback() {
        val cb = pendingFocusCb ?: return
        pendingFocusCb = null
        mainHandler.post { runCatching { cb.invoke() } }
    }

    fun enableFlash(enabled: Boolean) {
        try {
            previewRequestBuilder?.apply {
                set(CaptureRequest.FLASH_MODE,
                    if (enabled) android.hardware.camera2.CameraMetadata.FLASH_MODE_TORCH
                    else android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF)
            }
            captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), previewMetaCallback, backgroundHandler)
        } catch (_: Exception) {}
    }

    fun setManualIso(iso: Int?) {
        if (manualIso == iso) return   // v81：同值不重发预览请求
        manualIso = iso
        previewRequestBuilder?.let {
            applyManualSettings(it)
            captureSession?.setRepeatingRequest(it.build(), previewMetaCallback, backgroundHandler)
        }
    }

    fun setManualExposure(exposureNs: Long?) {
        if (manualExposure == exposureNs) return   // v81：同值不重发预览请求
        manualExposure = exposureNs
        previewRequestBuilder?.let {
            applyManualSettings(it)
            captureSession?.setRepeatingRequest(it.build(), previewMetaCallback, backgroundHandler)
        }
    }

    // ---------------- 慢门：给上层（胶片曝光模型）的接口 ----------------

    /** 预览 AE 最近一次测光（曝光秒, ISO）。慢门决策的唯一输入，没有就返回 null */
    fun meteringSnapshot(): Pair<Float, Int>? =
        if (lastAeSec > 0f && lastAeIso > 0) lastAeSec to lastAeIso else null

    /** 主摄光圈（f 值）：把 AE 的曝光量还原成场景亮度要用它 */
    fun aperture(): Float = apertureValue

    /** 传感器曝光范围（秒） */
    fun deviceExposureRangeSec(): Pair<Float, Float> = deviceMinExposureSec to deviceMaxExposureSec

    /** 传感器 ISO 范围 */
    fun deviceIsoRange(): IntRange = deviceIsoRange

    /** 设备支持手动曝光才有慢门（否则只能走 AE 补偿，拍不出长曝光） */
    fun supportsSlowShutter(): Boolean = hasManualSensor

    fun stop() {
        // v74 P0：关会话之前先显式收灯，别只依赖 HAL 断电（不同 HAL 时序不一致）
        resetFlashPrecapture("stop", force = true)
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        rawImageReader?.close()
        rawImageReader = null
        // 汇合中的碎片一并丢弃（Image 必须关，否则 HAL 缓冲泄漏）
        pendingShot?.rawImage?.close()
        pendingShot = null
        // DNG 写盘线程常驻不 shutdown：stop 之后还可能 start（暂停/恢复），
        // 空闲单线程的开销为零，关了反而要在 start 里重建
        backgroundThread?.quitSafely()
        captureSession = null
        cameraDevice = null
    }
}
