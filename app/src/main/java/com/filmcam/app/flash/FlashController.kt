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
 *  - EXTERNAL：BLE 外接灯（可选配件）
 *
 * BUILT_IN 模式通过 CameraX CameraControl 控制（而非 CameraManager.setTorchMode），
 * 避免 CameraX 占用相机时 torch 不可用的问题。
 *
 * vc86：EXTERNAL 档不再硬编码 `new FotorGearFlash(...)`，改走
 * [ExternalFlashRegistry] —— 目前注册表里只有 FotorGear 一家且按已知 MAC
 * 直连，行为与之前完全一致；后续加品牌 / 自学习配对向导时本类零改动。
 */
class FlashController(
    private val context: Context
) : FlashDevice {

    companion object {
        /**
         * 进程内当前活着的 FlashController（MainActivity onCreate 建，release 清）。
         * 工程控制台（AboutActivity）靠它拿到外闪会话去控制 A2 造型灯 ——
         * 主界面在返回栈里活着，这个引用就是有效的。
         */
        @Volatile
        var active: FlashController? = null
    }

    init {
        active = this
    }

    override var mode: FlashMode = FlashMode.OFF
    override var onStatusChange: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())

    // ---- EXTERNAL 模式的 BLE 驱动（品牌由注册表决定，不感知具体实现） ----
    private var bleFlash: ExternalFlashDriver? = null

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
        // 以前每次都 new 一个驱动实例，旧 GATT 不关 → 同一台灯被反复注册
        // client_if（真机日志里能看到成串 GATT_Register / 连接互相顶掉）。
        // 一次 EXTERNAL 会话只建一条 GATT，连接状态由驱动自己维护。
        if (bleFlash != null) {
            android.util.Log.i("FlashDiag", "startScan 跳过：已有 BLE 会话")
            return
        }
        android.util.Log.i("FlashDiag", "startScan 新建 BLE 会话")
        // v59：BLE 建连（权限/适配器/协议）出任何异常都不能冒泡到 cycleFlashMode，
        // 否则档位切换后半段（相机同步、LCD、落盘）会整段被跳过，表现为「按键没反应」。
        runCatching {
            // 第二层：先同步自学习档案（用户在配对向导学过的灯优先于内置 FotorGear）
            ExternalFlashRegistry.syncLearned(FlashProfileStore(context).list())
            bleFlash = ExternalFlashRegistry.createPreferred(context).also {
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

    /**
     * 当前灯的同步偏移（ms；null = 用全局兜底）。
     * 拍照链路据此把触发时机对齐到具体灯：氙气灯 -80 / COB -200。
     */
    fun currentSyncOffsetMs(): Int? = bleFlash?.syncOffsetMs

    /** 当前灯一张照片要发几次触发帧（A2 = 3 段式：LED → 氙气预闪 → 氙气主闪）。 */
    fun triggerStages(): Int = bleFlash?.triggerStages ?: 1

    /** 当前灯的可读名字（控制台按灯型启用面板用；null = 无会话）。 */
    fun currentLightName(): String? = bleFlash?.displayName

    /** 当前灯是否需要「回电中」提示（氙气灯 true / COB false）。 */
    fun needsRecycleHint(): Boolean = bleFlash?.needsRecycleHint ?: false

    /**
     * 当前外闪的造型灯控制通道（null / 未就绪 = 控制台按钮置灰）。
     * A2 连上后返回 FEC7 命令通道封装（LED / 亮度 / 色温，CRC8-Maxim）。
     */
    fun lampControl(): FlashLampControl? =
        bleFlash?.lampControl?.takeIf { it.ready }

    /**
     * 丢弃当前 BLE 会话并按最新档案重建。
     *
     * 11-18 真机 bug：配对向导存完 A2 档案后，旧的 bleFlash（存档前用
     * FG-COB-FLASH 建的）仍活着，startScan 的「已有会话」幂等判断直接跳过，
     * 于是拍照一直连错灯（日志里 FG-COB 空转重连 277 次）。存档成功后调本方法
     * 立即切换；非 EXTERNAL 档只清引用，下次进档时自然重建。
     */
    fun rebuildSession() {
        android.util.Log.i("FlashDiag", "rebuildSession：丢弃旧会话并按最新档案重建")
        bleFlash?.release()
        bleFlash = null
        if (mode == FlashMode.EXTERNAL) startScan()
    }

    override fun release() {
        if (active === this) active = null
        onBuiltInFlash?.invoke(false)
        bleFlash?.release()
        // v59：release() 之后必须清空引用，否则 startScan() 的幂等判断会一直
        // 命中「已有会话」而永不重建 —— 灯被 close 后就再也连不回来。
        bleFlash = null
        handler.removeCallbacksAndMessages(null)
    }
}
