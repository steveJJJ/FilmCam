package com.filmcam.app.flash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission

/**
 * 按档案驱动的通用外闪驱动（自学习配对向导的运行端）。
 *
 * 与 [FotorGearFlash]（专属逆向驱动）的区别：本类**不含任何品牌知识**，
 * 全部行为来自 [FlashProfile] —— 连哪台 MAC、找哪个服务、写哪个特征、
 * 写什么字节、用哪种写类型。向导学出什么它就开什么灯。
 *
 * 连接管理沿袭 FotorGear 真机踩坑结论：
 *  - 灯会休眠：GATT 超时 / 断连后定时重连（否则那一帧漏闪）；
 *  - connectGatt 前先 close 旧 GATT（避免 client_if 越堆越多互相顶掉）；
 *  - 只在 READY 后 trigger 才真正写帧，未就绪上报 + 立即重连。
 */
class LearnedProfileDriver(
    private val context: Context,
    private val profile: FlashProfile
) : ExternalFlashDriver {

    companion object {
        private const val TAG = "LearnedFlash"
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }

    override var onStatusChange: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    @Volatile private var connecting = false
    @Volatile private var released = false
    private var reconnectAttempts = 0

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    @SuppressLint("MissingPermission")
    override fun startScan() {
        // 档案驱动不做扫描发现：按档案里的 MAC 直连（与 FotorGear 现行为一致）
        val adapter = bluetoothAdapter ?: run {
            onStatusChange?.invoke("NO_BT")
            return
        }
        val device = runCatching { adapter.getRemoteDevice(profile.address) }.getOrNull()
        if (device == null) {
            onStatusChange?.invoke("BAD_ADDRESS")
            return
        }
        released = false
        onStatusChange?.invoke("CONNECTING: ${profile.displayName}")
        handler.postDelayed({ onStatusChange?.invoke("SCAN_TIMEOUT") }, CONNECT_TIMEOUT_MS)
        connectGatt(device)
    }

    private fun connectGatt(device: android.bluetooth.BluetoothDevice) {
        if (connecting || released) return
        connecting = true
        runCatching { gatt?.close() }
        onStatusChange?.invoke("GATT_CONNECTING")
        val newGatt = runCatching {
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    connecting = false
                    android.util.Log.i(TAG, "连接状态 newState=$newState status=$status ${profile.displayName}")
                    onStatusChange?.invoke("STATE: $newState (status=$status)")
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == 0) {
                        reconnectAttempts = 0
                        g.discoverServices()
                    } else {
                        runCatching { g.close() }
                        if (gatt === g) gatt = null
                        writeChar = null
                        scheduleReconnect()
                    }
                }

                @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val svc = g.services.orEmpty().firstOrNull {
                        it.uuid.toString() == profile.serviceUuid
                    }
                    val char = svc?.getCharacteristic(java.util.UUID.fromString(profile.writeCharUuid))
                    writeChar = char
                    android.util.Log.i(
                        TAG,
                        "服务发现 status=$status svc=${svc != null} char=${char != null} ${profile.displayName}"
                    )
                    onStatusChange?.invoke(if (char != null) "READY" else "NO_SERVICE")
                    if (char == null) {
                        // 档案与灯对不上（固件换了 GATT 布局）：不硬重连，等用户重新配对
                        runCatching { g.disconnect() }
                    }
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    android.util.Log.i(TAG, "指令写入 ${characteristic.uuid} status=$status")
                }
            })
        }.getOrNull()
        gatt = newGatt
        if (newGatt == null) {
            connecting = false
            scheduleReconnect()
        }
    }

    /** 灯休眠后 Android 的 connectGatt 常直接给超时 status；定时重试直到连上。 */
    private fun scheduleReconnect(delayMs: Long = RECONNECT_DELAY_MS) {
        handler.postDelayed({
            if (released || connecting || writeChar != null) return@postDelayed
            reconnectAttempts++
            android.util.Log.i(TAG, "重连 ${profile.displayName} #$reconnectAttempts")
            val adapter = bluetoothAdapter ?: return@postDelayed
            val device = runCatching { adapter.getRemoteDevice(profile.address) }.getOrNull()
            if (device != null) connectGatt(device) else connecting = false
        }, delayMs)
    }

    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    override fun trigger() {
        val char = writeChar ?: run {
            android.util.Log.w(TAG, "写特征未就绪，本帧漏闪，立即重连 ${profile.displayName}")
            onStatusChange?.invoke("TRIGGER_SKIP NOT_READY")
            scheduleReconnect(500L)
            return
        }
        val g = gatt ?: return
        val cmd = profile.triggerBytes() ?: run {
            // 档案保存前已校验过 hex，这里是双保险
            onStatusChange?.invoke("TRIGGER_SKIP BAD_PROFILE")
            return
        }
        val writeType = if (profile.writeNoResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(char, cmd, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = cmd
                @Suppress("DEPRECATION")
                char.writeType = writeType
                @Suppress("DEPRECATION")
                g.writeCharacteristic(char)
            }
        }.getOrDefault(false)
        android.util.Log.i(
            TAG,
            "TRIGGER -> ${profile.triggerHex} write=$ok ${profile.displayName}"
        )
        onStatusChange?.invoke(if (ok) "TRIGGER_SENT" else "TRIGGER_FAIL")
    }

    @SuppressLint("MissingPermission")
    override fun release() {
        released = true
        connecting = false
        handler.removeCallbacksAndMessages(null)
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
    }
}
