package com.filmcam.app.flash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import com.filmcam.app.R

/**
 * 自学习配对向导的 BLE 引擎：扫描 → 连接 → 枚举可写特征 → 试写任意帧。
 *
 * 与驱动的分工：本类只做「发现」，不负责长期连接 / 重连 / 快门时序 ——
 * 学会的协议存成 [FlashProfile]，之后拍摄时由 [LearnedProfileDriver] 按档案驱动。
 *
 * 候选帧 [CANDIDATE_FRAMES] 的顺序是先验概率排序（真机 0xFFE1 协议
 * 逆向经验 + 常见廉价 BLE 灯惯例）：单字节命令码先于双字节，05（闪光码）优先。
 */
class FlashPairEngine(private val context: Context) {

    companion object {
        /** 候选触发帧（hex 文本）。向导逐条试发，用户看灯确认。
         *  顺序 = 先验概率：05 是闪光命令码（真机 0xFFE1 上 05 01 与 05 00 都能闪，
         *  实测命中 05 01），两条置顶让用户少翻；其后单字节 → 双字节。 */
        val CANDIDATE_FRAMES = listOf(
            "05 01", "05 00", "01", "00", "FF", "51",
            "64", "0F", "03 01", "04 00", "06 01", "07 01",
            "01 64", "01 00", "A0 00", "90 01", "0A 01"
        )

        private const val TAG = "FlashPair"
        private const val SCAN_WINDOW_MS = 12_000L
        private const val MIN_RSSI = -90
        /** 写入后等 onCharacteristicWrite 的最长时间；超时说明这个通道不响应写入。 */
        private const val WRITE_TIMEOUT_MS = 900L

        /**
         * 神牛 A2 快速试闪时序（都取已验证常量，不猜新值）：
         * FEC7 四帧复位之间 120ms；复位完成后 1500ms 再发第一发（消化首发被吃）；
         * 两发试闪间隔 500ms（官方双发观测 ~440ms，留出连接间隔余量）。
         */
        private const val A2_RESET_GAP_MS = 120L
        private const val A2_PREROLL_MS = 1_500L
        private const val A2_TRIAL_GAP_MS = 500L

        /** 通用透传通道短码：Nordic UART / 常见 16-bit 透传 / NUS TX / 厂商自定义 128-bit。 */
        private val TRIGGER_CHAR_HINTS = listOf("FFE1", "FFE2", "FFF1", "FFF2", "6E400002")

        /**
         * 通道是否「像触发口」（通用协议形态，不认厂牌）：
         *  WRITE_NO_RESPONSE 的透传特征优先；标准 2Axx 配置类（设备名 / HID 控制点）
         *  一律排后 —— 往这些特征写指令不仅不闪，还会因为没有写回调把 GATT 卡死。
         */
        private fun looksLikeTrigger(charUuid: String, serviceUuid: String, writeNoResponse: Boolean): Boolean {
            val u = charUuid.uppercase()
            val s = serviceUuid.uppercase()
            val standardConfig = u.startsWith("00002A") || s.startsWith("0000180A") ||
                s.startsWith("00001812") || s.startsWith("0000180F")
            if (standardConfig) return false
            if (TRIGGER_CHAR_HINTS.any { u.contains(it) }) return true
            if (s.contains("FFE0") || s.contains("FFF0") || s.contains("6E400001")) return true
            // 自定义 128-bit 特征只有 WRITE_NO_RESPONSE 才算透传口；
            // 需要响应的控制点写进去常常不给回调，会把整条 GATT 写通道卡死。
            return writeNoResponse
        }

        /**
         * 「像一盏灯」的**通用协议特征**，不是品牌白名单：
         * HID/HOGP 0x1812（带快门键）、Nordic UART FFE0、常见透传 FFF0、NUS 6E400001。
         * 用来把系统里 8 台已配对设备收敛成 1~2 台候选置顶。
         * 名字兜底只认通用品类词（light / flash / lamp / cob），不认任何厂牌。
         */
        private val FLASH_SERVICE_HINTS = listOf("1812", "FFE0", "FFF0", "6E400001")
        private val FLASH_NAME_HINTS = listOf("flash", "light", "lamp", "cob")

        private fun looksLikeFlash(name: String, uuids: List<String>): Boolean =
            uuids.any { u -> FLASH_SERVICE_HINTS.any { h -> u.uppercase().contains(h) } } ||
                FLASH_NAME_HINTS.any { name.contains(it, ignoreCase = true) }
    }

    /**
     * 扫描到的设备（去重后排序：系统已配对置顶，其余按信号强度）。
     *
     * bonded = 从系统蓝牙「已配对设备」表读出来的，不是广播扫到的：
     * 带 HID（快门键）的灯被系统以 HOGP 绑定并自动连上后会停止广播，
     * 永远不出现在 BLE 扫描结果里，但可以按 MAC 直接 connectGatt。
     */
    data class ScanDevice(
        val name: String,
        val address: String,
        val rssi: Int,
        val bonded: Boolean = false,
        val serviceUuids: List<String> = emptyList(),
        val likelyFlash: Boolean = false
    )

    /** 一个可写特征（指令通道候选）。recommended = 通用透传形态，优先试它。 */
    data class CharInfo(
        val serviceUuid: String,
        val charUuid: String,
        val writeNoResponse: Boolean,
        val propsText: String,
        val recommended: Boolean = false
    )

    interface Listener {
        fun onScanDevices(devices: List<ScanDevice>)
        fun onConnected(address: String)
        fun onDisconnected()
        fun onWritableChars(chars: List<CharInfo>)
        fun onWriteSent(hex: String, ok: Boolean)
        fun onError(msg: String)
        /** 写入无回调，正在自动重连清 GATT pending（默认空实现，向导可接管提示）。 */
        fun onReconnecting() {}
        /** 自动重连完成，仍停在试闪页，请用户再试一次。 */
        fun onReconnected() {}
        /** 设备侧写回调结果；delivered=false 表示被设备拒绝。 */
        fun onWriteAck(delivered: Boolean) {}
        /** A2 快速试闪阶段提示（RESET / NO_CMD / FIRE_1 / FIRE_2 / DONE）。 */
        fun onA2TrialProgress(stage: String) {}
        /** FotorGear 快速配对阶段提示（FG_FIRE / DONE / NO_TRIGGER_CHAR）。 */
        fun onFgTrialProgress(stage: String) {}
    }

    var listener: Listener? = null

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    // ---- 扫描 ----
    private val found = linkedMapOf<String, ScanDevice>()
    @Volatile private var scanning = false
    private var scanCallback: ScanCallback? = null

    /**
     * 系统「已配对」里的 BLE 设备直接进列表。带 HID 快门键的灯会被 Android
     * 以 HOGP 自动绑定并连上，连上后不再广播 —— 纯 BLE 扫描永远找不到它。
     * 这里按 MAC 给出入口，[connect] 直接 connectGatt，与广播无关。
     * 经典蓝牙音频设备排除（不可能是 BLE 外闪），LE / DUAL / UNKNOWN 都保留。
     */
    @SuppressLint("MissingPermission")
    private fun seedBondedDevices() {
        val bonded = runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        for (d in bonded) {
            if (d.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) continue
            val name = runCatching { d.name }.getOrNull().takeUnless { it.isNullOrBlank() }
                ?: context.getString(R.string.flash_pair_unnamed)
            val uuids = runCatching { d.uuids?.map { it.uuid.toString() }.orEmpty() }
                .getOrDefault(emptyList())
            found[d.address] = ScanDevice(
                name = name,
                address = d.address,
                rssi = 0,
                bonded = true,
                serviceUuids = uuids,
                likelyFlash = looksLikeFlash(name, uuids)
            )
        }
        android.util.Log.i(
            TAG,
            "系统已配对 BLE 设备 ${found.size} 台，灯候选 ${found.values.count { it.likelyFlash }} 台"
        )
    }

    /** 灯候选置顶 → 其它已配对 → 广播设备按信号强度。UI 每次换代都拿全量列表。 */
    private fun publishDevices() {
        val list = found.values.sortedWith(
            compareByDescending<ScanDevice> { it.likelyFlash }
                .thenByDescending { it.bonded }
                .thenByDescending { it.rssi }
        )
        listener?.onScanDevices(list)
    }

    // ---- 连接 ----
    private var gatt: BluetoothGatt? = null
    private var selectedAddress: String? = null
    private val writableChars = mutableListOf<CharInfo>()
    /** Android GATT 同一时刻只允许一条未完成的写；不串行化会刷屏 prior command is not finished。 */
    @Volatile private var writePending = false
    private var pendingTimeout: Runnable? = null
    /** 超时自动重连时不要重新翻页 / 不要报「已断开」吓用户。 */
    @Volatile private var silentReconnect = false
    /** 快速配对序列代次（A2 / FotorGear 共用）；换灯 / 断开 / 自动重连时 ++ 作废未执行的帧。 */
    private var a2TrialSeq = 0

    @SuppressLint("MissingPermission")
    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            listener?.onError("NO_BT")
            return
        }
        found.clear()
        // 先把系统已配对的 BLE 设备放进列表（无需广播，也不需要再弹系统配对框）。
        seedBondedDevices()
        publishDevices()
        scanning = true
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.rssi < MIN_RSSI) return
                val address = result.device.address
                // ⚠️不能因为「广播里没有名字」就把设备丢掉：多数廉价灯 /
                // 已绑定设备只播 MAC + 服务 UUID，名字要连上后才拿得到。
                val advertisedName = result.scanRecord?.deviceName
                val cachedName = runCatching { result.device.name }.getOrNull()
                val fallback = context.getString(R.string.flash_pair_unnamed)
                val name = (advertisedName ?: cachedName).takeUnless { it.isNullOrBlank() } ?: fallback
                val uuids = result.scanRecord?.serviceUuids
                    ?.map { it.uuid.toString() }.orEmpty()
                val known = found[address]
                found[address] = ScanDevice(
                    name = if (known?.bonded == true && name == fallback) known.name else name,
                    address = address,
                    rssi = result.rssi,
                    bonded = known?.bonded == true,
                    serviceUuids = uuids.ifEmpty { known?.serviceUuids.orEmpty() },
                    likelyFlash = known?.likelyFlash == true ||
                        looksLikeFlash(name, uuids)
                )
                publishDevices()
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
                listener?.onError("SCAN_FAILED: $errorCode")
            }
        }
        scanCallback = cb
        runCatching { scanner.startScan(null, android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build(), cb) }
            .onFailure { scanning = false; listener?.onError("SCAN_ERROR: ${it.message}") }
        handler.postDelayed({ if (scanning) stopScan() }, SCAN_WINDOW_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanning = false
        val cb = scanCallback
        scanCallback = null
        if (cb != null) {
            adapter?.bluetoothLeScanner?.let { s ->
                runCatching { s.stopScan(cb) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        stopScan()
        cancelA2Trial()
        selectedAddress = address
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            listener?.onError("BAD_ADDRESS")
            return
        }
        runCatching { gatt?.close() }
        writableChars.clear()
        val newGatt = runCatching {
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == 0) {
                        g.discoverServices()
                        handler.post { listener?.onConnected(address) }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        runCatching { g.close() }
                        if (gatt === g) gatt = null
                        // 自动重连的中间态不通知 UI，用户应停留在试闪页
                        if (!silentReconnect) handler.post { listener?.onDisconnected() }
                    }
                }

                @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != 0) {
                        handler.post { listener?.onError("DISCOVER_FAILED: $status") }
                        return
                    }
                    writableChars.clear()
                    for (svc in g.services.orEmpty()) {
                        for (c in svc.characteristics.orEmpty()) {
                            val p = c.properties
                            val wr = p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                            val wnr = p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                            if (wr || wnr) {
                                val svcUuid = svc.uuid.toString()
                                val charUuid = c.uuid.toString()
                                writableChars.add(
                                    CharInfo(
                                        serviceUuid = svcUuid,
                                        charUuid = charUuid,
                                        writeNoResponse = wnr,
                                        propsText = buildList {
                                            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
                                            if (wr) add("WRITE")
                                            if (wnr) add("WRITE_NR")
                                            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
                                            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
                                        }.joinToString("|"),
                                        recommended = looksLikeTrigger(charUuid, svcUuid, wnr)
                                    )
                                )
                            }
                        }
                    }
                    // 推荐通道置顶：用户第一眼就点到透传口，而不是 2A00 / 2A4C 配置类特征
                    writableChars.sortWith(
                        compareByDescending<CharInfo> { it.recommended }
                            .thenByDescending { it.writeNoResponse }
                            .thenBy { it.charUuid }
                    )
                    // 万一一个推荐都没有（协议没见过），至少把最像透传的第一条标出来，
                    // 不能让用户面对清一色「配置类」无从下手。
                    if (writableChars.isNotEmpty() && writableChars.none { it.recommended }) {
                        writableChars[0] = writableChars[0].copy(recommended = true)
                    }
                    for (c in writableChars) {
                        android.util.Log.i(
                            TAG,
                            "WRITABLE ${c.charUuid} svc=${c.serviceUuid} ${c.propsText} rec=${c.recommended}"
                        )
                    }
                    android.util.Log.i(TAG, "可写特征 ${writableChars.size} 个")
                    if (silentReconnect) {
                        silentReconnect = false
                        handler.post { listener?.onReconnected() }
                    } else {
                        handler.post { listener?.onWritableChars(writableChars.toList()) }
                    }
                }

                // 写回调必须清 pending；否则 Android 会一直拒绝后续写。
                @Suppress("DEPRECATION")
                override fun onCharacteristicWrite(
                    g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
                ) = ackWrite(status)
            })
        }.getOrNull()
        gatt = newGatt
        if (newGatt == null) listener?.onError("CONNECT_ERROR")
    }

    /** 写回调：清 pending，防止 Android 无限期拒绝后续 writeCharacteristic。 */
    private fun ackWrite(status: Int) {
        writePending = false
        pendingTimeout?.let { handler.removeCallbacks(it) }
        pendingTimeout = null
        android.util.Log.i(TAG, "写入回调 status=$status")
        handler.post { listener?.onWriteAck(status == BluetoothGatt.GATT_SUCCESS) }
    }

    /** 试写一帧（hex 文本）。返回是否提交成功（不等于灯有反应 —— 那由用户判断）。 */
    @SuppressLint("MissingPermission")
    fun writeFrame(charInfo: CharInfo, hex: String): Boolean {
        // 上一条还没回调：不再硬写。硬写会被 HAL 直接拒，且把错误刷满屏。
        if (writePending) {
            android.util.Log.w(TAG, "WRITE_BUSY 跳过 $hex")
            listener?.onError("WRITE_BUSY")
            return false
        }
        val g = gatt ?: return false
        val cmd = FlashTriggerCommand.parseHex(hex) ?: return false
        val svc = g.services.orEmpty().firstOrNull { it.uuid.toString() == charInfo.serviceUuid }
            ?: return false
        val char = svc.getCharacteristic(java.util.UUID.fromString(charInfo.charUuid)) ?: return false
        val writeType = if (charInfo.writeNoResponse) {
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
        if (ok) {
            writePending = true
            val timeout = Runnable {
                if (!writePending) return@Runnable
                writePending = false
                pendingTimeout = null
                android.util.Log.w(TAG, "写入 900ms 无回调，自动重连清 GATT pending")
                handler.post { listener?.onReconnecting() }
                reconnectSilently()
            }
            pendingTimeout = timeout
            handler.postDelayed(timeout, WRITE_TIMEOUT_MS)
        }
        android.util.Log.i(TAG, "试写 $hex -> ${charInfo.charUuid} write=$ok")
        listener?.onWriteSent(hex, ok)
        return ok
    }

    /**
     * 神牛 A2 快速试闪：协议族与候选帧模型不同，不能靠用户盲点 05 xx。
     *
     * 已验证路径（不新增任何未验证帧）：
     *  1. 在 FEC7 上重放 `06 01 → 05 00 → 0c 00 → 03 00`（0c 必须是 00，不能是 50）；
     *  2. 在 FFF1 上写 ASCII `1000,Test` 两发（重连后首发会被灯吃掉）。
     *
     * 这里**不走** [writeFrame] 的 900ms 无回调重连：FFF1 / FEC7 都是 WRITE_NO_RESPONSE，
     * 没有写回调是常态，900ms 重连会把双发序列拦腰砍断。序列自己按固定间隔推进，
     * 换灯 / 断开 / 自动重连时用 [a2TrialSeq] 代次作废。
     */
    @SuppressLint("MissingPermission")
    fun quickTrialA2(): Boolean {
        val g = gatt ?: run {
            listener?.onError("NOT_CONNECTED")
            return false
        }
        val triggerInfo = writableChars.firstOrNull {
            it.charUuid.uppercase().contains("FFF1") && it.serviceUuid.uppercase().contains("FFF0")
        } ?: writableChars.firstOrNull { it.charUuid.uppercase().contains("FFF1") }
        if (triggerInfo == null) {
            listener?.onA2TrialProgress("NO_TRIGGER_CHAR")
            listener?.onError("A2_TRIGGER_CHAR_MISSING")
            return false
        }
        val cmdInfo = writableChars.firstOrNull {
            it.charUuid.uppercase().contains("FEC7") && it.serviceUuid.uppercase().contains("FEC0")
        } ?: writableChars.firstOrNull { it.charUuid.uppercase().contains("FEC7") }

        cancelA2Trial()
        val seq = a2TrialSeq
        val trigger = FlashTriggerCommand.parseHex(FlashProfile.A2_TRIGGER_HEX) ?: return false

        data class Step(val info: CharInfo, val bytes: ByteArray, val delay: Long, val stage: String)
        val steps = mutableListOf<Step>()
        if (cmdInfo != null) {
            steps += Step(cmdInfo, A2LampProtocol.frame(A2LampProtocol.CMD_LAMP_BRIGHTNESS, 0x01), A2_RESET_GAP_MS, "RESET")
            steps += Step(cmdInfo, A2LampProtocol.frame(A2LampProtocol.CMD_LAMP_MODE, 0x00), A2_RESET_GAP_MS, "RESET")
            steps += Step(cmdInfo, A2LampProtocol.frame(A2LampProtocol.CMD_FLASH_PARAM, 0x00), A2_RESET_GAP_MS, "RESET")
            steps += Step(cmdInfo, A2LampProtocol.frame(A2LampProtocol.CMD_SHUTTER_LISTEN, 0x00), A2_PREROLL_MS, "FIRE_1")
        } else {
            // 命令口不在：不阻塞试闪，但明确告诉用户可能只闪 LED（连拍前需回工程控制台复位）
            listener?.onA2TrialProgress("NO_CMD")
        }
        steps += Step(triggerInfo, trigger, if (cmdInfo == null) A2_RESET_GAP_MS else A2_TRIAL_GAP_MS, "FIRE_1")
        steps += Step(triggerInfo, trigger, A2_TRIAL_GAP_MS, "FIRE_2")

        var totalDelay = 0L
        var lastSent = false
        for ((index, step) in steps.withIndex()) {
            totalDelay += step.delay
            handler.postDelayed({
                if (seq != a2TrialSeq) return@postDelayed
                lastSent = writeDirect(step.info, step.bytes, step.stage)
                if (index == steps.lastIndex) {
                    handler.post {
                        if (seq != a2TrialSeq) return@post
                        listener?.onA2TrialProgress("DONE")
                        listener?.onWriteSent(FlashProfile.A2_TRIGGER_HEX, lastSent)
                    }
                }
            }, totalDelay)
        }
        android.util.Log.i(TAG, "A2 快速试闪已排程 ${steps.size} 帧")
        return true
    }

    /**
     * FotorGear COB 快速配对：选 FFE1（服务 FFE0）直接发已验证 `05 00`。
     *
     * COB 灯约 200ms 后出光，不回 ACK；这里复用 [writeDirect] 的无重连通道，
     * 避免 900ms 无回调重连把试闪状态作废。命中确认后由 Activity 存完整档案
     * （FFE0 / FFE1 / 05 00 / -200），不依赖用户选的通道。
     */
    @SuppressLint("MissingPermission")
    fun quickTrialFotorGear(): Boolean {
        val triggerInfo = writableChars.firstOrNull {
            it.charUuid.uppercase().contains("FFE1") && it.serviceUuid.uppercase().contains("FFE0")
        } ?: writableChars.firstOrNull { it.charUuid.uppercase().contains("FFE1") }
        if (triggerInfo == null) {
            listener?.onFgTrialProgress("NO_TRIGGER_CHAR")
            listener?.onError("FG_TRIGGER_CHAR_MISSING")
            return false
        }
        val frame = FlashTriggerCommand.parseHex(FlashProfile.FG_TRIGGER_HEX) ?: return false
        cancelA2Trial()
        val seq = a2TrialSeq
        handler.postDelayed({
            if (seq != a2TrialSeq) return@postDelayed
            val sent = writeDirect(triggerInfo, frame, "FG_FIRE", fg = true)
            handler.post {
                if (seq != a2TrialSeq) return@post
                listener?.onFgTrialProgress("DONE")
                listener?.onWriteSent(FlashProfile.FG_TRIGGER_HEX, sent)
            }
        }, 0L)
        android.util.Log.i(TAG, "FotorGear 快速配对已排程 ${FlashProfile.FG_TRIGGER_HEX}")
        return true
    }

    /** 直接写一帧，不碰通用试写的 writePending / 900ms 重连计时。 */
    @SuppressLint("MissingPermission")
    private fun writeDirect(
        info: CharInfo,
        bytes: ByteArray,
        stage: String,
        fg: Boolean = false
    ): Boolean {
        val g = gatt ?: return false
        val svc = g.services.orEmpty().firstOrNull { it.uuid.toString() == info.serviceUuid } ?: return false
        val char = svc.getCharacteristic(java.util.UUID.fromString(info.charUuid)) ?: return false
        val writeType = if (info.writeNoResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(char, bytes, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = bytes
                @Suppress("DEPRECATION")
                char.writeType = writeType
                @Suppress("DEPRECATION")
                g.writeCharacteristic(char)
            }
        }.getOrDefault(false)
        val hex = bytes.joinToString("") { String.format("%02x", it) }
        android.util.Log.i(TAG, "${if (fg) "FG_QUICK" else "A2_QUICK"} $stage $hex write=$ok")
        handler.post {
            if (fg) listener?.onFgTrialProgress(stage) else listener?.onA2TrialProgress(stage)
        }
        return ok
    }

    /** 换灯 / 断开 / 自动重连时调用：让已排程但未执行的快配帧自动作废。 */
    private fun cancelA2Trial() {
        a2TrialSeq++
    }

    /**
     * 写到一个不给回调的特征后，Android 的 GATT 客户端会永久停在
     * 「prior command is not finished」，App 层无法清理 —— 只能换一条 GATT 连接。
     * 这里静默断开重连同一台灯，重连完成不翻页，用户继续在试闪页点下一帧。
     */
    private fun reconnectSilently() {
        val address = selectedAddress ?: return
        cancelA2Trial()
        silentReconnect = true
        writePending = false
        pendingTimeout?.let { handler.removeCallbacks(it) }
        pendingTimeout = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writableChars.clear()
        connect(address)
    }

    val connectedAddress: String? get() = selectedAddress

    /** 返回上一页时释放 GATT，避免同一台灯被两条连接互相顶掉。 */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        cancelA2Trial()
        writePending = false
        pendingTimeout?.let { handler.removeCallbacks(it) }
        pendingTimeout = null
        silentReconnect = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        selectedAddress = null
        writableChars.clear()
    }

    @SuppressLint("MissingPermission")
    fun close() {
        stopScan()
        cancelA2Trial()
        writePending = false
        pendingTimeout?.let { handler.removeCallbacks(it) }
        pendingTimeout = null
        silentReconnect = false
        handler.removeCallbacksAndMessages(null)
        runCatching { gatt?.close() }
        gatt = null
        selectedAddress = null
    }
}
