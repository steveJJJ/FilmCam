package com.filmcam.app.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.graphics.SurfaceTexture
import android.view.Surface
import android.widget.Toast
import java.lang.Exception

/**
 * Camera2 控制器
 * 支持手动 ISO / 快门 + 全分辨率拍照
 */
class Camera2Controller(private val context: Context) {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var cameraId: String = "0"
    private var imageReader: ImageReader? = null

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
     * 拍照回调：相机 ISP 输出的原始 JPEG 字节 + 像素需顺时针旋转的角度（0/90/180/270）。
     * 不做任何解码/旋转/重编码——那会把一次编码变成两次并多吃约 100MB 内存。
     * 方向只作为元数据随帧记录，由冲洗侧统一旋转。
     */
    var onPhotoCaptured: ((ByteArray, Int) -> Unit)? = null

    /** 取景器元信息回调：(AF 状态, 曝光时间秒)，驱动 T3 取景器内的合焦灯与快门档显示 */
    var onViewfinderMeta: ((afState: Int?, exposureSec: Float) -> Unit)? = null
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
            onPhotoCaptured?.invoke(bytes, jpegRotationDegrees(sensorOrientation).also {
                android.util.Log.i("Camera2", "capture jpegRotation=$it deviceRotation=${deviceRotationDegrees()}")
            })
        }, backgroundHandler)

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
        val readerSurface = imageReader!!.surface

        previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyManualSettings(this)
            // 白平衡预设可能在相机开启前就被设置（restoreSettings 早于会话建立），此处补挂
            set(
                CaptureRequest.CONTROL_AWB_MODE,
                currentAwbMode ?: CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
        }

        camera.createCaptureSession(
            listOf(previewSurface, readerSurface),
            object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(previewRequestBuilder!!.build(), previewMetaCallback, backgroundHandler)
                }
                override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                    Toast.makeText(context, "会话创建失败", Toast.LENGTH_SHORT).show()
                }
            },
            backgroundHandler
        )
    }

    /** 拍摄全分辨率照片。defocus=true 时本次故意对焦不准（模拟偶发失焦） */
    fun takePicture(defocus: Boolean = false) {
        try {
            val camera = cameraDevice ?: return
            val readerSurface = imageReader!!.surface
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(readerSurface)
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
            }
            captureSession?.capture(captureBuilder.build(), null, backgroundHandler)
        } catch (_: Exception) {}
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
     * 坐标映射（sensorOrientation=90 的竖屏相机）：
     *   屏幕 u=x/W、v=y/H → sensor x = v、sensor y = u（含 zoom 裁切区域）。
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
            // 竖屏：画面是传感器 4:3 横画面旋转 90° 得到的 → 视口横轴对应传感器纵轴（互换）
            // 横屏：画面就是传感器原生方向 → 横轴对横轴、纵轴对纵轴（不互换）
            val landscape = viewW > viewH
            val sx = (cropX + (if (landscape) u else v) * cropW).toInt()
            val sy = (cropY + (if (landscape) v else u) * cropH).toInt()
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

            // 指标上报（限流 150ms，避免每帧刷 UI）
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastMetaPostAt > 150) {
                lastMetaPostAt = now
                val af = state
                mainHandler.post { onViewfinderMeta?.invoke(af, exposureSec) }
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

    fun stop() {
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        backgroundThread?.quitSafely()
        captureSession = null
        cameraDevice = null
    }
}
