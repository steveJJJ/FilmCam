package com.filmcam.app.flash

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
 *   Service 0xFFE0 (Nordic UART 形态)
 *     Characteristic 0xFFE1: NOTIFY, WRITE NO RESPONSE  ← 透明串口，灯控指令通道
 *
 *   HID Service 0x1812
 *     Report 0x2A4D: NOTIFY, READ, WRITE  ← 物理按键 = Volume Down 遥控快门
 *
 * 官方说明（fotorgear.com.cn / 产品评测）：灯身按钮会「先预闪，再同步触发手机拍照」，
 * 专属 App 还能调「预闪时间 / 快门触发时间」，灯的闪光时序是可配的。
 *
 * 已探明的 0xFFE1 协议（2026-11-06 真机逐条轮转 + 人工判别）：
 *   帧格式 [cmd][param]，2 字节；灯只主动上报状态，对触发指令**不回 ACK**。
 *     02 xx  电量心跳（0x50=80% / 0x51=81% / 0x52=82%，约 15~20s 一次）
 *     01 00  查询参数 → 回 01 05（亮度/档位）
 *     02 00  查询电量 → 回 02 xx
 *     05 00 / 05 01  触发闪光 ✅（快门逐条轮转 + 人工判别确认）
 *   ⇒ 05 是闪光命令码，第二字节是参数（对应官方 App 的
 *     「预闪时间 / 快门触发时间」档位），00/01 都能闪。
 *   01 64 / 01 FF / 03 01 / 04 01 / 06 01 / 03 64 / 04 00 / 07 01 等均不触发。
 *
 * ⚠️ 时序（真机逐档扫描标定，最容易踩的坑）：灯收到 05 xx 后约 **200ms**
 * 才真正出光（预闪序列）。所以必须相对**曝光开始提前 200ms** 发指令
 * （Camera2Controller.DEFAULT_FLASH_SYNC_OFFSET_MS = -200）。
 * 在曝光开始或之后发，闪光会完全落在快门关闭之后：
 *   提前 250/200/150ms → RAW 净信号 953/957/956（闪光进画面）
 *   曝光开始 / +50 / +100ms → ~11（等于没闪）
 *
 * 正式触发指令内置为 `05 00`（见 [FlashTriggerCommand]）；外部私有目录的
 * `flash_trigger.hex` 只作调参覆盖，可随时 adb push 换参数，不用重新编译。
 * ⚠️ 该目录在卸载 / 清数据时会被删掉，所以点灯能力绝不能依赖这个文件存在。
 *
 * 逆向工具（保留，方便以后调参）：
 *  - [dumpGatt] 把整张 GATT 表打进 logcat；0xFFE1 通知打进 "FlashNotify" tag；
 *  - `flash_probe.txt` 一行一条十六进制，连上后按序自动写入；
 *  - `flash_candidates.txt` 快门逐条轮转，编号回显到机顶 LCD 供人工判别。
 */
class FotorGearFlash(
    private val context: Context
) : FlashDevice {

    companion object {
        private const val TAG = "FotorGearFlash"
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val TRIGGER_HEX_FILE = "flash_trigger.hex"
        private const val PROBE_FILE = "flash_probe.txt"
        private const val CANDIDATES_FILE = "flash_candidates.txt"
        private const val PROBE_INTERVAL_MS = 350L
    }

    // 已知的 GATT UUID
    private val serviceUUID = java.util.UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val writeCharUUID = java.util.UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    private val targetName = "FG-COB-FLASH"
    private val knownAddress = "DA:BA:CD:E2:BE:B0"  // 已知 MAC，直接连接
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val handler = Handler(Looper.getMainLooper())

    /** GATT 读操作必须串行；这里排队逐个读，避免一次下发多条被 Android 丢弃 */
    private val readQueue = ArrayDeque<BluetoothGattCharacteristic>()

    /** 逆向用：每按一次快门轮一条候选指令，机顶 LCD 显示编号供人工判别哪条点亮了灯 */
    private var candidates: List<Pair<String, ByteArray>> = emptyList()
    private var candidateIndex = 0

    /** 灯会休眠：GATT 超时(147)/断连后要自己爬回来，否则那一帧就漏闪了 */
    @Volatile private var connecting = false
    @Volatile private var released = false
    private var reconnectAttempts = 0

    override var onStatusChange: ((String) -> Unit)? = null

    override var mode: FlashMode = FlashMode.EXTERNAL

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    @RequiresPermission("android.permission.BLUETOOTH_SCAN")
    override fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            onStatusChange?.invoke("NO_BT")
            return
        }

        // 直接用已知 MAC 连接，跳过扫描
        released = false
        onStatusChange?.invoke("CONNECTING: $knownAddress")
        connect(knownAddress)

        // 15 秒超时
        handler.postDelayed({ onStatusChange?.invoke("SCAN_TIMEOUT") }, 15_000)
    }

    /** 灯休眠后 Android 的 connectGatt 常直接给 status=147 超时；定时重试直到连上。 */
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private fun scheduleReconnect(delayMs: Long = 2_000L) {
        handler.postDelayed({
            if (released || connecting || writeChar != null) return@postDelayed
            reconnectAttempts++
            android.util.Log.i(TAG, "重连外接灯 #$reconnectAttempts")
            connect(knownAddress)
        }, delayMs)
    }

    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private fun connect(address: String) {
        if (connecting || released) return
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        connecting = true
        // 关掉上一条 GATT，避免 client_if 越堆越多把连接互相顶掉
        runCatching { gatt?.close() }
        onStatusChange?.invoke("GATT_CONNECTING")
        val newGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                connecting = false
                android.util.Log.i(
                    TAG,
                    "连接状态 newState=$newState status=$status target=$targetName"
                )
                onStatusChange?.invoke("STATE: $newState (status=$status)")
                if (newState == BluetoothProfile.STATE_CONNECTED && status == 0) {
                    reconnectAttempts = 0
                    g.discoverServices()
                } else {
                    // 0xFFE1 立刻失效；本帧若正好在拍就会漏闪，所以尽快重连
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                    writeChar = null
                    scheduleReconnect()
                }
            }

            @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                // 先把整张表摊开：除了已知的 0xFFE1，其它特征值/描述符
                // （亮度、预闪时长、快门触发延迟）都在这里露出名字和属性。
                dumpGatt(g)
                val svc = g.getService(serviceUUID)
                writeChar = svc?.getCharacteristic(writeCharUUID)
                android.util.Log.i(
                    TAG,
                    "服务发现 status=$status services=${g.services?.size} 0xFFE1=${writeChar != null}"
                )
                onStatusChange?.invoke(if (writeChar != null) "READY" else "NO_SERVICE")
                enableNotifications(g, writeChar)
                // 所有可读寄存器逐个读出来：HID Report Map(0x2A4B) 会暴露灯的
                // 输出报告用途（有没有「闪光」这一位），Device Info 能确认固件。
                readAllReadable(g)
                // 指令探针：外部文件里一行一条十六进制，连上后按序写入 0xFFE1。
                // 逆向阶段可以反复 adb push 不同指令表，不用重新编译。
                loadCandidates()
                runProbe(g, writeChar)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                logRead(characteristic.uuid, characteristic.value, status)
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                logRead(characteristic.uuid, value, status)
            }

            @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                android.util.Log.i(TAG, "CCCD 写入 ${descriptor.uuid} status=$status")
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                logNotify(characteristic.uuid, characteristic.value)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                logNotify(characteristic.uuid, value)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                android.util.Log.i(TAG, "指令写入 ${characteristic.uuid} status=$status")
            }
        })
        gatt = newGatt
        if (newGatt == null) {
            connecting = false
            scheduleReconnect()
        }
    }

    /** 把整张 GATT 表打进 logcat，用于逆向灯的亮度/预闪/触发时序寄存器。 */
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private fun dumpGatt(g: BluetoothGatt) {
        runCatching {
            for (s in g.services.orEmpty()) {
                android.util.Log.i(TAG, "SVC ${s.uuid} type=${s.type}")
                for (c in s.characteristics.orEmpty()) {
                    val p = c.properties
                    val flags = buildList {
                        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
                        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
                        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NR")
                        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
                        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
                    }.joinToString("|")
                    android.util.Log.i(TAG, "  CHR ${c.uuid} props=$flags")
                    for (d in c.descriptors.orEmpty()) {
                        val v = d.value?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } ?: "-"
                        android.util.Log.i(TAG, "    DESC ${d.uuid} value=$v")
                    }
                }
            }
        }.onFailure { android.util.Log.w(TAG, "GATT 枚举失败", it) }
    }

    /** 逐个读所有 READ 特征；GATT 读必须串行，靠 onCharacteristicRead 推进队列。 */
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private fun readAllReadable(g: BluetoothGatt) {
        readQueue.clear()
        for (s in g.services.orEmpty()) {
            for (c in s.characteristics.orEmpty()) {
                if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    readQueue.add(c)
                }
            }
        }
        android.util.Log.i(TAG, "待读寄存器 ${readQueue.size} 个")
        pumpRead(g)
    }

    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private fun pumpRead(g: BluetoothGatt) {
        val c = readQueue.removeFirstOrNull() ?: return
        runCatching { g.readCharacteristic(c) }
            .onFailure { android.util.Log.w(TAG, "读 ${c.uuid} 失败", it) }
    }

    private fun logRead(uuid: java.util.UUID, value: ByteArray?, status: Int) {
        val hex = value?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } ?: "(null)"
        android.util.Log.i(TAG, "READ $uuid <- $hex status=$status")
        gatt?.let { pumpRead(it) }
    }

    /**
     * 快门逐条轮转的候选指令表。一行一条十六进制，可用 // 写注释。
     * 用户每按一次快门发下一条，机顶 LCD 显示编号 —— 灯闪了报编号即可定位指令。
     */
    private fun loadCandidates() {
        candidates = runCatching {
            val dir = context.getExternalFilesDir(null)
                ?: return@runCatching emptyList<Pair<String, ByteArray>>()
            val f = java.io.File(dir, CANDIDATES_FILE)
            if (!f.exists()) return@runCatching emptyList<Pair<String, ByteArray>>()
            f.readLines()
                .map { it.substringBefore("//").trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line ->
                    val cmd = parseHex(line) ?: return@mapNotNull null
                    line.replace(Regex("[^0-9a-fA-F]"), "").uppercase() to cmd
                }
        }.getOrDefault(emptyList())
        candidateIndex = 0
        if (candidates.isNotEmpty()) {
            android.util.Log.i(TAG, "候选指令 ${candidates.size} 条，快门逐条轮转")
        }
    }

    /** 逆向探针：flash_probe.txt 一行一条十六进制，连上后按序写入 0xFFE1。 */
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private fun runProbe(g: BluetoothGatt, char: BluetoothGattCharacteristic?) {
        if (char == null) return
        val cmds: List<ByteArray> = runCatching {
            val dir = context.getExternalFilesDir(null)
                ?: return@runCatching emptyList<ByteArray>()
            val f = java.io.File(dir, PROBE_FILE)
            if (!f.exists()) return@runCatching emptyList<ByteArray>()
            f.readLines()
                .map { it.substringBefore("//").trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { parseHex(it) }
        }.getOrDefault(emptyList())
        if (cmds.isEmpty()) return
        android.util.Log.i(TAG, "指令探针：共 ${cmds.size} 条，间隔 ${PROBE_INTERVAL_MS}ms")
        cmds.forEachIndexed { i, cmd ->
            handler.postDelayed({
                writeCommand(g, char, cmd, "PROBE#$i")
            }, (i + 1) * PROBE_INTERVAL_MS)
        }
    }

    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private fun writeCommand(
        g: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        cmd: ByteArray,
        label: String
    ): Boolean {
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(
                    char, cmd, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = cmd
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                g.writeCharacteristic(char)
            }
        }.getOrDefault(false)
        android.util.Log.i(
            TAG,
            "$label -> ${cmd.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }} write=$ok"
        )
        return ok
    }

    private fun parseHex(s: String): ByteArray? = FlashTriggerCommand.parseHex(s)

    /** 订阅 0xFFE1：灯身按键 / 预闪 / 闪光事件会上报字节，这是协议最直接的线索。 */
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    private fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic?) {
        if (c == null || c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
            android.util.Log.i(TAG, "0xFFE1 不支持 NOTIFY，跳过订阅")
            return
        }
        runCatching {
            g.setCharacteristicNotification(c, true)
            val cccd = c.getDescriptor(java.util.UUID.fromString(CCCD_UUID)) ?: run {
                android.util.Log.w(TAG, "0xFFE1 没有 CCCD，通知可能收不到")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }.onFailure { android.util.Log.w(TAG, "订阅通知失败", it) }
    }

    private fun logNotify(uuid: java.util.UUID, value: ByteArray?) {
        val hex = value?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } ?: "(null)"
        android.util.Log.i(
            "FlashNotify",
            "$uuid <- $hex @ ${android.os.SystemClock.elapsedRealtime()}"
        )
        onStatusChange?.invoke("NOTIFY $hex")
    }

    /**
     * 触发外接灯闪光（0xFFE1: 05 xx）。
     *
     * 调用点已从「按快门那一刻」挪到 Camera2Controller.onExposureStarted ——
     * 三星 HAL 从提交请求到真正开曝光要 ~240ms，提前发灯就白闪了。
     * 指令优先读 `flash_trigger.hex`，缺失 / 非法一律回退内置 `05 00`；
     * 若存在 `flash_candidates.txt` 则进入快门逐条轮转的逆向模式，供调参用。
     */
    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    override fun trigger() {
        val char = writeChar ?: run {
            android.util.Log.w(TAG, "0xFFE1 未就绪，本帧漏闪，立即重连")
            onStatusChange?.invoke("TRIGGER_SKIP NOT_READY")
            scheduleReconnect(500L)
            return
        }
        val g = gatt ?: return
        // 逆向模式：每按一次快门轮一条候选指令，编号回传到机顶 LCD。
        // 用户盯着灯，闪了报编号，就能定位真正的 0xFFE1 触发字节。
        if (candidates.isNotEmpty()) {
            val i = candidateIndex % candidates.size
            candidateIndex++
            val (label, cmd) = candidates[i]
            val no = i + 1
            val ok = writeCommand(g, char, cmd, "TRIG#$no/${candidates.size}")
            android.util.Log.i(TAG, "TRIG #$no/${candidates.size} ${label.take(16)} write=$ok")
            onStatusChange?.invoke("TRIG #$no/${candidates.size} ${label.take(12)}")
            return
        }
        // v60：以前这里读到 null 就 TRIGGER_SKIP NO_CMD 整帧跳过点灯，
        // 而配置文件在卸载 / 清数据后必然不存在 —— App 快门点灯因此静默失效。
        val cmd = readTriggerCommand()
        val ok = writeCommand(g, char, cmd, "TRIGGER")
        onStatusChange?.invoke(if (ok) "TRIGGER_SENT" else "TRIGGER_FAIL")
    }

    /**
     * 外部私有目录 `flash_trigger.hex` 优先；文件不存在 / 读失败 / 内容非法
     * 一律回退内置默认 05 00（[FlashTriggerCommand.DEFAULT]）。
     * 返回值永远非空，调用方不需要也不能再跳过点灯。
     */
    private fun readTriggerCommand(): ByteArray {
        val raw = runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching null
            val f = java.io.File(dir, TRIGGER_HEX_FILE)
            if (!f.exists()) return@runCatching null
            f.readText()
        }.onFailure {
            android.util.Log.w(TAG, "读取 flash_trigger.hex 失败，回退内置默认", it)
        }.getOrNull()
        val cmd = FlashTriggerCommand.resolve(raw)
        android.util.Log.i(
            TAG,
            "触发指令 ${if (raw != null) "外部覆盖" else "内置默认"} -> " +
                cmd.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        )
        return cmd
    }

    override fun release() {
        released = true
        connecting = false
        handler.removeCallbacksAndMessages(null)
        gatt?.close()
        gatt = null
        writeChar = null
    }
}
