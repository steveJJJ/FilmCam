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
            captureSession?.setRepeatingRequest(it.build(), null, backgroundHandler)
        }
    }

    // 拍照回调
    /**
     * 拍照回调：相机 ISP 输出的原始 JPEG 字节 + 像素需顺时针旋转的角度（0/90/180/270）。
     * 不做任何解码/旋转/重编码——那会把一次编码变成两次并多吃约 100MB 内存。
     * 方向只作为元数据随帧记录，由冲洗侧统一旋转。
     */
    var onPhotoCaptured: ((ByteArray, Int) -> Unit)? = null

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

        // 归一化到 0/90/180/270（Activity 锁竖屏，无需再叠加设备旋转）
        val rotationDegrees = (((sensorOrientation % 360) + 360) % 360) / 90 * 90
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            val bytes = try {
                val buffer = image.planes[0].buffer
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } finally {
                image.close()
            }
            onPhotoCaptured?.invoke(bytes, rotationDegrees)
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
        }

        camera.createCaptureSession(
            listOf(previewSurface, readerSurface),
            object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
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
        // 对焦：模拟失焦用手动固定对焦距离，否则自动连续对焦
        val d = defocusDistance
        if (d != null) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, d)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
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
        defocusDistance = if (enabled && minFocusDistance > 0f) minFocusDistance * focusRatio else null
        previewRequestBuilder?.let {
            applyManualSettings(it)
            captureSession?.setRepeatingRequest(it.build(), null, backgroundHandler)
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

            // 预览画面 letterbox 几何：内容 3:4 满宽居中 + 上移 0.08（与
            // LutGLSurfaceView shader / ViewfinderFrameView 同一公式）。
            // 先把 view 坐标换算到画面内容坐标，再映射传感器，点哪对哪。
            val screenAspect = viewW.toFloat() / viewH
            val s = screenAspect / com.filmcam.app.ui.ViewfinderFrameView.CONTENT_ASPECT
            val contentTop = (0.5f - s * (0.5f - com.filmcam.app.ui.ViewfinderFrameView.PREVIEW_OFFSET_Y)) * viewH
            val contentH = s * viewH

            val u = (viewX / viewW).coerceIn(0f, 1f)
            val v = ((viewY - contentTop) / contentH).coerceIn(0f, 1f)
            val sx = (cropX + v * cropW).toInt()
            val sy = (cropY + u * cropH).toInt()
            val half = (minOf(cropW, cropH) * 0.1f).toInt().coerceAtLeast(16)
            val region = android.hardware.camera2.params.MeteringRectangle(
                (sx - half).coerceAtLeast(active.left),
                (sy - half).coerceAtLeast(active.top),
                half * 2, half * 2, 1000
            )

            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            // 先取消上一次未完的触发，再启动一次扫焦
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            session.capture(builder.build(), null, backgroundHandler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, backgroundHandler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)

            onFocused?.let { cb -> backgroundHandler?.postDelayed({ runCatching { cb() } }, 700) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun enableFlash(enabled: Boolean) {
        try {
            previewRequestBuilder?.apply {
                set(CaptureRequest.FLASH_MODE,
                    if (enabled) android.hardware.camera2.CameraMetadata.FLASH_MODE_TORCH
                    else android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF)
            }
            captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
        } catch (_: Exception) {}
    }

    fun setManualIso(iso: Int?) {
        manualIso = iso
        previewRequestBuilder?.let {
            applyManualSettings(it)
            captureSession?.setRepeatingRequest(it.build(), null, backgroundHandler)
        }
    }

    fun setManualExposure(exposureNs: Long?) {
        manualExposure = exposureNs
        previewRequestBuilder?.let {
            applyManualSettings(it)
            captureSession?.setRepeatingRequest(it.build(), null, backgroundHandler)
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
