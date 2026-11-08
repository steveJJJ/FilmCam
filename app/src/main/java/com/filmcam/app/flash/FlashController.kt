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
        if (mode != FlashMode.EXTERNAL) {
            android.util.Log.i("FlashDiag", "startScan 跳过：mode=$mode")
            return
        }
        // restoreSettings / cycleFlashMode /「拍摄同款」三条路都会调到这里。
        // 以前每次都 new 一个 FotorGearFlash，旧 GATT 不关 → 同一台灯被反复注册
        // client_if（真机日志里能看到成串 GATT_Register / 连接互相顶掉）。
        // 一次 EXTERNAL 会话只建一条 GATT，连接状态由 FotorGearFlash 自己维护。
        if (bleFlash != null) {
            android.util.Log.i("FlashDiag", "startScan 跳过：已有 BLE 会话")
            return
        }
        android.util.Log.i("FlashDiag", "startScan 新建 BLE 会话")
        // v59：BLE 建连（权限/适配器/协议）出任何异常都不能冒泡到 cycleFlashMode，
        // 否则档位切换后半段（相机同步、LCD、落盘）会整段被跳过，表现为「按键没反应」。
        runCatching {
            bleFlash = FotorGearFlash(context).also {
                it.onStatusChange = { s -> onStatusChange?.invoke(s) }
                it.startScan()
            }
        }.onFailure {
            bleFlash = null
            android.util.Log.w("FlashDiag", "BLE 会话建立失败", it)
            onStatusChange?.invoke("SCAN_ERROR: ${it.message}")
        }
    }

    override fun trigger() {
        when (mode) {
            // 内置闪光由相机 HAL 在快门那一刻自己打，这里不需要再动什么
            FlashMode.BUILT_IN -> {}
            FlashMode.EXTERNAL -> {
                val dev = bleFlash
                if (dev == null) {
                    // 以前这里是 `bleFlash?.trigger()`，会话没建起来时连日志都没有，
                    // 表现为「切到外置档、按快门、灯毫无反应」，无法区分于指令缺失。
                    android.util.Log.w("FlashDiag", "trigger 跳过：无 BLE 会话，立即重建（本帧漏闪）")
                    startScan()
                    onStatusChange?.invoke("TRIGGER_SKIP NO_SESSION")
                } else {
                    dev.trigger()
                }
            }
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
        // v59：release() 之后必须清空引用，否则 startScan() 的幂等判断会一直
        // 命中「已有会话」而永不重建 —— 灯被 close 后就再也连不回来。
        bleFlash = null
        handler.removeCallbacksAndMessages(null)
    }
}
