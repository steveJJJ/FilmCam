package com.filmcam.app.flash

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission

/**
 * FotorGear COB 闪光灯驱动
 *
 * 已探明的 GATT 结构（2026-10-25）：
 *   Device: FG-COB-FLASH  DA:BA:CD:E2:BE:B0
 *   Manufacturer: HideLink  Firmware: 2026.02.03
 *   VID 0x1915 = Nordic Semiconductor (nRF52832)
 *
 *   Service 0xFFE0 (Unknown)
 *     Characteristic 0xFFE1: NOTIFY, WRITE NO RESPONSE  ← 透明串口，灯控指令通道
 *
 *   HID Service 0x1812
 *     Report 0x2A4D: NOTIFY, READ, WRITE  ← 物理按键 = Volume Down 遥控快门
 *
 * ⚠️ 待逆向：往 0xFFE1 写什么字节能让灯闪（需 nRF52840 嗅探 + iPhone 官方 App）
 *    完成后把 byteArrayOf(...) 填进 trigger() 方法。
 */
class FotorGearFlash(
    private val context: Context
) : FlashDevice {

    // 已知的 GATT UUID
    private val serviceUUID = java.util.UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val writeCharUUID = java.util.UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    private val targetName = "FG-COB-FLASH"
    private val knownAddress = "DA:BA:CD:E2:BE:B0"  // 已知 MAC，直接连接
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())

    override var onStatusChange: ((String) -> Unit)? = null

    override var mode: FlashMode = FlashMode.EXTERNAL

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    @RequiresPermission(
        value = "android.permission.BLUETOOTH_SCAN",
    )
    override fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            onStatusChange?.invoke("NO_BT")
            return
        }

        // 直接用已知 MAC 连接，跳过扫描
        onStatusChange?.invoke("CONNECTING: $knownAddress")
        connect(knownAddress)

        // 15 秒超时
        handler.postDelayed({ onStatusChange?.invoke("SCAN_TIMEOUT") }, 15_000)
    }

    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private fun connect(address: String) {
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        onStatusChange?.invoke("GATT_CONNECTING")
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                onStatusChange?.invoke("STATE: $newState (status=$status)")
                if (newState == BluetoothProfile.STATE_CONNECTED && status == 0) {
                    g.discoverServices()
                }
            }

            @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(serviceUUID) ?: run {
                    onStatusChange?.invoke("NO_SERVICE (found ${g.services?.size} services)")
                    return
                }
                writeChar = svc.getCharacteristic(writeCharUUID)
                onStatusChange?.invoke("READY")
            }
        })
    }

    override fun trigger() {
        val char = writeChar ?: return
        // TODO: 填入逆向出的触发指令字节
        // 例：char.value = byteArrayOf(0xFF.toByte(), 0x01)
        //     gatt?.writeCharacteristic(char)
        // MVP 阶段：暂不发指令（灯只支持物理按键触发）
        onStatusChange?.invoke("TRIGGER (manual button only)")
    }

    override fun release() {
        gatt?.close()
        gatt = null
    }
}
