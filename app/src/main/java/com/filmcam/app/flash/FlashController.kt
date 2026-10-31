package com.filmcam.app.flash

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 闪光灯控制器（统一入口）
 *
 * 支持三种模式：
 *  - OFF：不闪
 *  - BUILT_IN：手机自带 LED（默认，无需任何配件）
 *  - EXTERNAL：FotorGear BLE 外接灯（可选配件）
 *
 * BUILT_IN 模式通过 CameraX CameraControl 控制（而非 CameraManager.setTorchMode），
 * 避免 CameraX 占用相机时 torch 不可用的问题。
 */
class FlashController(
    private val context: Context
) : FlashDevice {

    override var mode: FlashMode = FlashMode.OFF
    override var onStatusChange: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    // ---- EXTERNAL 模式的 BLE 逻辑 ----
    private var bleFlash: FotorGearFlash? = null

    // ---- BUILT_IN 模式：通过 CameraX 控制 ----
    // MainActivity 设置这个回调，桥接到 CameraController.enableFlash()
    var onBuiltInFlash: ((Boolean) -> Unit)? = null

    override fun startScan() {
        if (mode == FlashMode.EXTERNAL) {
            bleFlash = FotorGearFlash(context).also {
                it.onStatusChange = { s -> onStatusChange?.invoke(s) }
                it.startScan()
            }
        }
    }

    override fun trigger() {
        when (mode) {
            FlashMode.BUILT_IN -> flashBuiltInLed()
            FlashMode.EXTERNAL -> bleFlash?.trigger()
            FlashMode.OFF -> {}
        }
    }

    /** 手机自带 LED 短闪（通过 CameraX CameraControl） */
    private fun flashBuiltInLed() {
        try {
            onBuiltInFlash?.invoke(true)
            handler.postDelayed({
                onBuiltInFlash?.invoke(false)
            }, 200)
        } catch (e: Exception) {
            onStatusChange?.invoke("LED_ERROR: ${e.message}")
        }
    }

    override fun release() {
        onBuiltInFlash?.invoke(false)
        bleFlash?.release()
        handler.removeCallbacksAndMessages(null)
    }
}
