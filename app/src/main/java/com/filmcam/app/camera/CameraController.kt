package com.filmcam.app.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat

/**
 * CameraX 控制器
 *
 * 把 LutGLSurfaceView 创建的 SurfaceTexture 包成 Surface，喂给 CameraX Preview。
 * CameraX 帧 → SurfaceTexture → GL 滤镜 → 屏幕。
 */
class CameraController(
    private val context: Context,
    private val cameraProvider: ProcessCameraProvider
) {
    var camera: Camera? = null
        private set

    fun start(selector: CameraSelector, surfaceTexture: SurfaceTexture) {
        val previewSurface = Surface(surfaceTexture)

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotation = windowManager.defaultDisplay.rotation

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()
            .also {
                it.setSurfaceProvider { request ->
                    request.provideSurface(
                        previewSurface,
                        ContextCompat.getMainExecutor(context)
                    ) { }
                }
            }

        cameraProvider.unbindAll()
        camera = cameraProvider.bindToLifecycle(
            context as androidx.lifecycle.LifecycleOwner,
            selector,
            preview
        )
    }

    /** 打开手机 LED 闪光灯（CameraX 方式） */
    fun enableFlash(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }
}
