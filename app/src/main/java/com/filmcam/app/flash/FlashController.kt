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

    // ---- BUILT_IN 模式：交给相机 HAL ----
    // 内置闪光不再是「快门那一刻点亮手电筒 200ms」，而是把相机切成拍照闪光模式
    // （CONTROL_AE_MODE_ON_ALWAYS_FLASH）：HAL 在快门开启瞬间打满功率脉冲，并按有闪光
    // 重新测光。MainActivity 把开关状态桥接到 Camera2Controller.setFlashEnabled()。
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
            // 内置闪光由相机 HAL 在快门那一刻自己打，这里不需要再动什么
            FlashMode.BUILT_IN -> {}
            FlashMode.EXTERNAL -> bleFlash?.trigger()
            FlashMode.OFF -> {}
        }
    }

    /** 内置闪光灯的「开 / 关」状态变化（切档、恢复设置时调用） */
    fun setBuiltInEnabled(enabled: Boolean) {
        try {
            onBuiltInFlash?.invoke(enabled)
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
