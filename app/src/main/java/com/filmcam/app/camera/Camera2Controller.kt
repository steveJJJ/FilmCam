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
         * 直闪帧的曝光偏置（EV，负 = 更暗）。ALWAYS_FLASH 的测光只保「主体正常」，
         * 再压 2/3 档让被闪光够不着的背景进一步沉黑——负片肩部宽，主体欠这点无妨。
         * 实际下发时按设备 AE 补偿步长换算成整档（1/2 步长机型 ≈ -0.5EV，1/3 步长 ≈ -0.67EV）。
         */
        const val FLASH_EV_BIAS = -2f / 3f
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
                // 原闪光状态日志原样保留
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
     * 预览与拍照都挂 CONTROL_AE_MODE_ON_ALWAYS_FLASH，HAL 会在快门开启的瞬间打满功率
     * 脉冲，并按「有闪光」重新测光（主体提亮、背景压暗），这才是直闪该有的样子。
     */
    @Volatile private var flashEnabled: Boolean = false

    /** 设备是否有闪光灯单元；没有的话只能退回 torch 常亮补光 */
    @Volatile private var hasFlashUnit: Boolean = true

    /** 无闪光灯单元时的兜底：torch 常亮的开启状态 */
    @Volatile private var torchOn: Boolean = false

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

    // 复刻康泰时 T3 35mm 视角：主摄等效约23mm，裁切至35mm ≈ 1.52x
    private var zoomRatio = 1.52f

    /** 预览流实际尺寸（start 时确定），点屏对焦坐标映射用 */
    private var previewSize: Size = Size(1920, 1440)

    fun setExposureComp(comp: Int) {
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
            if (s != null && s.numerator > 0 && s.denominator > 0) aeCompStep = s
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

        // 预览分辨率：限制在 1920 宽以内。部分机型（三星 S25/Adreno + Android 16）
        // 把 12MP 全尺寸作为 SurfaceTexture 私有流时，GL 消费者绑定 EGLImage 会
        // 持续失败（bindTextureImage 0x502），取景器黑屏；且预览远超屏幕分辨率
        // 白白耗电。拍照走 ImageReader JPEG，仍取全尺寸，不受影响。
        val previewSize = map?.getOutputSizes(SurfaceTexture::class.java)
            ?.filter { maxOf(it.width, it.height) <= 1920 }
            ?.maxByOrNull { it.width * it.height }
            ?: map?.getOutputSizes(SurfaceTexture::class.java)?.minByOrNull { it.width * it.height }
            ?: Size(1920, 1440)
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
            applyFlashSettings(this)
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
                applyFlashSettings(this)
                // 采集端「不加工」：胶片模拟要从最生的像素开始
                applyCaptureTuning(this)
                // 直闪帧的专属调优（AWB 钉日光 / 线性色调曲线 / 负 EV），
                // 内部自带条件判断，非直闪帧直接返回
                applyFlashCaptureTuning(this, slow)
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
     *  3. 压 [FLASH_EV_BIAS]：ALWAYS_FLASH 测光只保主体正常，再压一点让背景
     *     进一步沉黑（负片肩部宽，主体欠这点无妨）。按设备 AE 步长换算成整档。
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

        var evBiasSteps = 0
        val stepEv = aeCompStep.numerator.toFloat() / aeCompStep.denominator
        if (stepEv > 0f) {
            // 除数与被除数都是 Float：Math.round(Float) 返回 Int（步长单位）
            evBiasSteps = Math.round(FLASH_EV_BIAS / stepEv)
            if (evBiasSteps != 0) {
                runCatching {
                    builder.set(
                        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        (exposureComp + evBiasSteps).coerceIn(aeCompRange.first, aeCompRange.last)
                    )
                }.onFailure { evBiasSteps = 0 }
            }
        }
        android.util.Log.i(
            "FlashTuning",
            "capture awbDaylight=$awbDaylight tonemapLinear=$tonemapLinear evBiasSteps=$evBiasSteps"
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
     * 把拍照请求真正下发。
     *
     * 开闪光灯时先跑一次 AE 预闪（precapture）：HAL 借这一步完成闪光测光与充电，
     * 等 AE 收敛（或超时兜底）再开快门，否则第一张常常曝光过亮/过暗、甚至错过闪。
     * 关闪光时走原路径，不加任何等待。
     */
    private fun submitStillCapture(captureBuilder: CaptureRequest.Builder, slowShutter: Boolean = false) {
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
            session.capture(captureBuilder.build(), stillCaptureCallback, handler)
            return
        }

        var fired = false
        val fire = {
            if (!fired) {
                fired = true
                runCatching {
                    session.capture(captureBuilder.build(), stillCaptureCallback, handler)
                }
            }
        }
        // 超时兜底：任何情况下都不能把快门卡住（最多等 700ms，够 HAL 完成测光充电）
        val timeout = Runnable { fire() }
        handler.postDelayed(timeout, FLASH_PRECAPTURE_TIMEOUT_MS)

        val preBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            applyManualSettings(this)
            applyFlashSettings(this)
            set(
                CaptureRequest.CONTROL_AWB_MODE,
                currentAwbMode ?: CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
        }
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
     * 闪光请求参数：必须在 applyManualSettings 之后调用，否则会被自动测光分支覆盖。
     * 只在自动曝光下生效 —— 手动 ISO/快门（外置闪光灯那条路）AE 是 OFF，HAL 不闪。
     */
    private fun applyFlashSettings(builder: CaptureRequest.Builder) {
        if (!flashEnabled) return
        if (!hasFlashUnit) return          // 没有闪光灯单元：走 torch，见 setFlashEnabled
        if (manualIso != null || manualExposure != null) return
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        )
    }

    /** 内置闪光灯开关：切换后预览立即刷新（HAL 会在随后的拍照里打闪光脉冲） */
    fun setFlashEnabled(enabled: Boolean) {
        val changed = flashEnabled != enabled
        flashEnabled = enabled
        if (!hasFlashUnit) {
            // 设备没有闪光灯单元：HAL 闪光模式无效，退回常亮手电补光
            if (torchOn != enabled) {
                torchOn = enabled
                enableFlash(enabled)
            }
            return
        }
        if (!changed) return
        previewRequestBuilder?.let {
            if (!enabled) {
                // 关闪光：把 AE 模式从 ALWAYS_FLASH 拨回普通自动
                it.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON
                )
            }
            applyFlashSettings(it)
            runCatching {
                captureSession?.setRepeatingRequest(it.build(), previewMetaCallback, backgroundHandler)
            }
        }
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
            if (exposureSec > 0f && isoAe > 0) {
                lastAeSec = exposureSec
                lastAeIso = isoAe
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
        manualIso = iso
        previewRequestBuilder?.let {
            applyManualSettings(it)
            captureSession?.setRepeatingRequest(it.build(), previewMetaCallback, backgroundHandler)
        }
    }

    fun setManualExposure(exposureNs: Long?) {
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
