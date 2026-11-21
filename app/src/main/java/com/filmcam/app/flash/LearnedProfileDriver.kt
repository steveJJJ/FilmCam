package com.filmcam.app.flash

import android.annotation.SuppressLint
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
) : ExternalFlashDriver, FlashLampControl {

    companion object {
        private const val TAG = "LearnedFlash"
        /** 标准通知开关描述符（触发口灯主动上报的取证通道，11-21 07+tick 校准调研）。 */
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        /**
         * 连接看门狗：connectGatt 的 onConnectionStateChange 在部分 ROM 上会彻底
         * 丢失（133 / 0 之后静默）。`connecting` 一旦卡在 true，
         * [scheduleReconnect] 的守卫会把后续所有重连挡掉 —— 灯从此再也点不亮，
         * 而配对向导用的是自己那条 GATT，所以「试闪能亮、拍摄不闪」。
         */
        private const val CONNECT_WATCHDOG_MS = 6_000L

        /**
         * 连接后多久发预热触发：要等复位序列 `06 01 → 05 00 → 0c 00 → 03 00`
         * 四帧全部走完，否则预热帧会被写队列挤到复位前面。
         */
        private const val WARMUP_TRIGGER_DELAY_MS = 1_500L

        /**
         * 两次预热之间的最小间隔。
         *
         * 链路抖动反复重连时不能每次都闪一下（历史上曾空转重连 277 次），
         * 但灯真睡过去再连回来（超过这个间隔）时又要重新预热。
         */
        private const val WARMUP_RETRY_GUARD_MS = 60_000L
    }

    override var onStatusChange: ((String) -> Unit)? = null

    /** 用档案的生效同步偏移（A2 氙气灯老档案 -200 会自动纠正为 -80）。 */
    override val syncOffsetMs: Int? get() = profile.effectiveSyncOffsetMs

    override val triggerStages: Int get() = profile.triggerStages
    override val needsRecycleHint: Boolean get() = profile.needsRecycleHint

    override val displayName: String? get() = profile.displayName

    /** 造型灯命令通道（本驱动自己实现 [FlashLampControl]）。 */
    override val lampControl: FlashLampControl get() = this

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    /** 上次预热触发的时间戳（elapsedRealtime），0 = 本次会话还没预热过。 */
    private var lastWarmUpAt = 0L

    /** 造型灯命令特征（A2 = 服务 FEC0 / 特征 FEC7；null = 档案没有命令通道或未发现）。 */
    private var cmdChar: BluetoothGattCharacteristic? = null

    @Volatile private var connecting = false
    @Volatile private var released = false
    private var reconnectAttempts = 0

    // ---- 写串行化 ----
    // Android 的 GATT 一次只允许一个未完成操作：连着两次 writeCharacteristic，第二次
    // 直接返回 false（真机日志 `LAMP_CMD a007153a write=false` 就是这么丢帧的）。
    // 触发帧和造型灯帧共用一条 GATT，所以统一排一条队列，靠 onCharacteristicWrite
    // 推进；700ms 没回调就强制解锁，避免一条丢失的回调把整条链路永久堵死。
    private class PendingWrite(
        val char: BluetoothGattCharacteristic,
        val bytes: ByteArray,
        val writeType: Int,
        val label: String
    ) {
        var attempts = 0
    }

    private val writeQueue = ArrayDeque<PendingWrite>()
    private var writeInFlight = false
    private val writeWatchdog = Runnable {
        if (released) return@Runnable
        android.util.Log.w(TAG, "写回调超时，强制解锁写队列 ${profile.displayName}")
        writeInFlight = false
        drainWrites()
    }

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
        handler.removeCallbacks(connectWatchdog)
        handler.postDelayed(connectWatchdog, CONNECT_WATCHDOG_MS)
        runCatching { gatt?.close() }
        onStatusChange?.invoke("GATT_CONNECTING")
        val newGatt = runCatching {
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    connecting = false
                    handler.removeCallbacks(connectWatchdog)
                    android.util.Log.i(TAG, "连接状态 newState=$newState status=$status ${profile.displayName}")
                    onStatusChange?.invoke("STATE: $newState (status=$status)")
                    if (newState == BluetoothProfile.STATE_CONNECTED && status == 0) {
                        reconnectAttempts = 0
                        g.discoverServices()
                    } else {
                        runCatching { g.close() }
                        if (gatt === g) gatt = null
                        writeChar = null
                        cmdChar = null
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
                    // 11-21 取证：订阅触发口通知（灯主动上报：02 xx 电量心跳、
                    // 可能的 07+tick 时钟校准源）。只监听不回写，不违反「连接后
                    // 只重放复位序列」纪律；日志走 FlashNotify tag，不刷状态栏。
                    if (char != null &&
                        char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                    ) {
                        runCatching {
                            g.setCharacteristicNotification(char, true)
                            val cccd = char.getDescriptor(java.util.UUID.fromString(CCCD_UUID))
                            if (cccd != null) {
                                if (android.os.Build.VERSION.SDK_INT >=
                                    android.os.Build.VERSION_CODES.TIRAMISU
                                ) {
                                    g.writeDescriptor(
                                        cccd,
                                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    )
                                } else {
                                    @Suppress("DEPRECATION")
                                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    @Suppress("DEPRECATION")
                                    g.writeDescriptor(cccd)
                                }
                            } else {
                                android.util.Log.w(TAG, "触发口没有 CCCD，通知收不到")
                            }
                        }.onFailure { android.util.Log.w(TAG, "订阅通知失败", it) }
                    }
                    // v2 档案：造型灯命令通道（A2 = 服务 FEC0 / 特征 FEC7，老 v1 档案按灯型补全）。
                    // 找不到不阻塞触发链路 —— 触发是主功能，LED/亮度/色温是增值能力。
                    // 解析按「特征 UUID 全场扫」而不是「先锁服务再取特征」：档案里的服务
                    // UUID 写错（11-19 的 fec7/f0a0 事故）时仍能自愈，特征唯一就不怕扫错。
                    val cmdCharUuid = profile.effectiveCmdCharUuid?.let { java.util.UUID.fromString(it) }
                    val cmdSvcUuid = profile.effectiveCmdServiceUuid
                    val cmdSvc = cmdSvcUuid?.let { want ->
                        g.services.orEmpty().firstOrNull { it.uuid.toString() == want }
                    }
                    cmdChar = when {
                        cmdCharUuid == null -> null
                        cmdSvc != null && cmdSvc.getCharacteristic(cmdCharUuid) != null ->
                            cmdSvc.getCharacteristic(cmdCharUuid)
                        else -> g.services.orEmpty()
                            .mapNotNull { it.getCharacteristic(cmdCharUuid) }
                            .firstOrNull()
                    }
                    android.util.Log.i(
                        TAG,
                        "服务发现 status=$status svc=${svc != null} char=${char != null} " +
                            "cmdChar=${cmdChar != null} cmdSvcHit=${cmdSvc != null} " +
                            "${profile.displayName}"
                    )
                    onStatusChange?.invoke(if (char != null) "READY" else "NO_SERVICE")
                    // 连接后**只**重放这一条已被真机验证的复位序列，除此之外绝不自动下发
                    // 任何 FEC7 帧。11-19 反复证明：`0c = 50` 会让灯退化成只闪 LED，
                    // 而 `06 01 → 05 00 → 0c 00 → 03 00` 能把灯拉回纯氙气（用户手动
                    // 点「复位闪光模式」后连拍全为氙气）。这条序列是唯一有实证的，
                    // 所以固化成连接后的默认动作；其余「看起来合理」的初始化一律不许加。
                    if (char != null && cmdChar != null) {
                        applyVerifiedReset("连接后自动")
                        // 🔴 预热触发：真机两次实证（19:32 / 19:39，App 各重启一次）
                        // **重连后第一发触发会被灯吃掉** —— 首张成片全黑（p50=3），
                        // 第二发才正常出氙气。把这发消耗在连接阶段，用户按下的第一张
                        // 就是好的。代价是连接瞬间灯会闪一下（用户已确认接受）。
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastWarmUpAt > WARMUP_RETRY_GUARD_MS) {
                            lastWarmUpAt = now
                            handler.postDelayed({ warmUpTrigger() }, WARMUP_TRIGGER_DELAY_MS)
                        }
                    }
                    if (char == null) {
                        // 档案与灯对不上（固件换了 GATT 布局）：不硬重连，等用户重新配对
                        cmdChar = null
                        runCatching { g.disconnect() }
                    } else if (cmdChar == null) {
                        // 触发口在、命令口不在：照样能拍照，只是造型灯卡片保持置灰
                        android.util.Log.w(TAG, "命令口未解析，跳过闪光模式复位 ${profile.displayName}")
                    }
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    android.util.Log.i(TAG, "指令写入 ${characteristic.uuid} status=$status")
                    // 一条写完才放行下一条（GATT 不支持并发操作）
                    writeInFlight = false
                    handler.removeCallbacks(writeWatchdog)
                    drainWrites()
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
            })
        }.getOrNull()
        gatt = newGatt
        if (newGatt == null) {
            connecting = false
            scheduleReconnect()
        }
    }

    /** 看门狗实体：到点强制复位连接状态再重连（幂等，连上了就什么都不做）。 */
    private val connectWatchdog = Runnable { onConnectWatchdog() }

    private fun onConnectWatchdog() {
        if (released || writeChar != null) return
        android.util.Log.w(TAG, "连接回调超时，强制复位后重连 ${profile.displayName}")
        connecting = false
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        onStatusChange?.invoke("RECONNECT_WATCHDOG")
        scheduleReconnect(300L)
    }

    /** 重连预算：连试 10 次（约 1 分钟）仍失败就判死会话，交 rebuildSession 换血。
     *  11-21 真机事故：极影占住灯后死会话一直占「已有 BLE 会话」坑，全员 write=false。 */
    private val maxReconnectAttempts = 10

    /** 灯休眠后 Android 的 connectGatt 常直接给超时 status；定时重试直到连上。 */
    private fun scheduleReconnect(delayMs: Long = RECONNECT_DELAY_MS) {
        handler.postDelayed({
            if (released || connecting || writeChar != null) return@postDelayed
            if (reconnectAttempts >= maxReconnectAttempts) {
                android.util.Log.w(TAG, "重连预算耗尽，会话判死 ${profile.displayName}")
                onStatusChange?.invoke("SESSION_DEAD")
                return@postDelayed
            }
            reconnectAttempts++
            android.util.Log.i(TAG, "重连 ${profile.displayName} #$reconnectAttempts")
            val adapter = bluetoothAdapter ?: return@postDelayed
            val device = runCatching { adapter.getRemoteDevice(profile.address) }.getOrNull()
            if (device != null) connectGatt(device) else connecting = false
        }, delayMs)
    }

    /**
     * 预热触发：把「重连后第一发被灯吃掉」这件事消化在连接阶段。
     *
     * 走 [enqueueWrite] 而不是直写 —— 队列里可能还挂着复位序列的帧；
     * [WARMUP_TRIGGER_DELAY_MS] 的延时就是留给它们走完的。
     */
    private fun warmUpTrigger() {
        val char = writeChar ?: return
        val cmd = profile.triggerBytes() ?: return
        val writeType = if (profile.writeNoResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        android.util.Log.i(TAG, "预热触发（消化重连后首发的丢失）${profile.displayName}")
        enqueueWrite(char, cmd, writeType, "WARMUP")
    }

    @RequiresPermission("android.permission.BLUETOOTH_CONNECT")
    override fun trigger() {
        val char = writeChar ?: run {
            android.util.Log.w(TAG, "写特征未就绪，本帧漏闪，立即重连 ${profile.displayName}")
            onStatusChange?.invoke("TRIGGER_SKIP NOT_READY")
            // 没有活着的 gatt 却卡在 connecting：先复位，否则 500ms 后的重连会被守卫挡掉
            if (gatt == null) connecting = false
            scheduleReconnect(500L)
            return
        }
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
        // 队列空闲时 drainWrites 同步写出去，时序跟直接写完全一致；
        // 只有在造型灯帧还挂着时才排 80ms 后的下一轮（本帧最多晚 80ms，仍快过 flash offset）。
        enqueueWrite(char, cmd, writeType, "TRIGGER")
    }

    // ---- FlashLampControl：造型灯命令通道（A2 协议见 [A2LampProtocol]，CRC8-Maxim）----

    override val ready: Boolean
        // A2 有独立命令口；无独立命令口的档案（触发口即命令口，03/04 配置
        // 与触发同一根特征）——任一就绪即可用。
        get() = gatt != null && (cmdChar != null || writeChar != null)

    /** 造型灯模式是否已进入（0x05 01）。官方 App 没把这个做成开关，驱动内部自己管。 */
    private var ledModeOn = false

    override fun setLed(on: Boolean): Boolean {
        ledModeOn = on
        return writeCmd(A2LampProtocol.frame(A2LampProtocol.CMD_LAMP_MODE, if (on) 0x01 else 0x00))
    }

    /**
     * 造型灯亮度 0–100，**0 = 关**（照抄官方 App：开灯 = `05 01` + `06 100`，
     * 拖到最低 = `06 01` 紧跟 `05 00`，都是抓包里重复出现的固定配对）。
     */
    override fun setBrightnessPercent(pct: Int): Boolean {
        val v = pct.coerceIn(0, 100)
        if (v == 0) {
            val ok = writeCmd(A2LampProtocol.frame(A2LampProtocol.CMD_LAMP_BRIGHTNESS, 0x01))
            if (ledModeOn) setLed(false)
            return ok
        }
        if (!ledModeOn) setLed(true)
        return writeCmd(A2LampProtocol.frame(A2LampProtocol.CMD_LAMP_BRIGHTNESS, v))
    }

    override fun resetFlashMode(): Boolean = applyVerifiedReset("手动复位")

    /**
     * 唯一被真机验证能把灯拉回「纯氙气」的帧序列（顺序不能改）：
     *
     *   `06 01` 造型灯亮度归最低 → `05 00` 退出造型灯模式
     *   → `0c 00` 拍摄参数归零 → `03 00` 退出快门监听
     *
     * 关键在 `0c`：0c=0 打氙气、0c=50 只打 LED（相关性最硬）。连接后自动跑一遍，
     * 保证每次接上灯都是干净的闪光态 —— 代价是重连会清掉造型灯设置（拍照优先）。
     */
    private fun applyVerifiedReset(reason: String): Boolean {
        if (cmdChar == null) return false
        ledModeOn = false
        writeCmd(A2LampProtocol.frame(A2LampProtocol.CMD_LAMP_BRIGHTNESS, 0x01))
        writeCmd(A2LampProtocol.frame(A2LampProtocol.CMD_LAMP_MODE, 0x00))
        writeCmd(A2LampProtocol.frame(A2LampProtocol.CMD_FLASH_PARAM, 0x00))
        writeCmd(A2LampProtocol.frame(A2LampProtocol.CMD_SHUTTER_LISTEN, 0x00))
        android.util.Log.i(TAG, "闪光模式复位($reason) 06 01 / 05 00 / 0c 00 / 03 00")
        return true
    }

    /** 造型灯色温：0x07 档位（0–32），100% → 0x20 白光，1% → 0 暖光。 */
    override fun setColorTemp(pos: Int): Boolean {
        val v = pos.coerceIn(0, 100)
        return writeCmd(
            A2LampProtocol.frame(
                A2LampProtocol.CMD_LAMP_CCT,
                v * A2LampProtocol.CCT_STEP_MAX / 100
            )
        )
    }

    /**
     * 闪光输出功率（A1 长帧 `F0 A1 07 0A 01 <pwr> 00 00 00 00 <crc>`）。
     *
     * 这是**实验性能力**：官方 Godox Flash App 用的就是这族帧，但它是给
     * GDBA/GDBH/GDB5/Ami 那批引闪器设计的；A2 是否接受尚未在真机验证。
     * 帧一旦被接受，就是真正的「灯侧功率控制」，TTL 闭环的最后一环。
     */
    override fun setFlashPowerLevel(powerByte: Int): Boolean {
        val ok = writeCmd(A2PowerProtocol.manualFrame(powerByte))
        if (ok) A2PowerProtocol.lastSentPowerByte = powerByte
        return ok
    }

    /** 原样下发：协议验证用（控制台可以直接把抓到的帧丢进来）。 */
    override fun sendRawFrame(frame: ByteArray): Boolean {
        if (frame.isEmpty()) return false
        return writeCmd(frame)
    }

    /** 灯主动上报取证：tag=FlashNotify，带 elapsedRealtime，便于对齐 07+tick 时序。 */
    private fun logNotify(uuid: java.util.UUID, value: ByteArray?) {
        val hex = value?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } ?: "(null)"
        android.util.Log.i(
            "FlashNotify",
            "$uuid <- $hex @ ${android.os.SystemClock.elapsedRealtime()} ${profile.displayName}"
        )
   
    }

    private fun writeCmd(frame: ByteArray): Boolean {
        if (gatt == null) return false
        // 命令口优先（A2 有独立命令口）；无独立命令口的档案回落触发口
        //（03/04 配置帧走同一条透传口，见 FotorGearProtocol）。
        val char = (cmdChar ?: writeChar) ?: run {
            android.util.Log.w(TAG, "命令通道未就绪，造型灯指令丢弃 ${profile.displayName}")
            return false
        }
        val writeType = if (profile.writeNoResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        // 返回值只代表「已入队」——LED/亮度/色温都不需要等 write 回调的确认，
        // 具体成败看日志里的 LAMP_CMD write=。
        enqueueWrite(char, frame, writeType, "LAMP_CMD")
        return true
    }

    private fun enqueueWrite(
        char: BluetoothGattCharacteristic,
        bytes: ByteArray,
        writeType: Int,
        label: String
    ) {
        if (released) return
        // 上限 16 条：真出现堆积说明链路有问题，宁可丢最老的也不让队列无限长
        if (writeQueue.size >= 16) {
            writeQueue.removeFirstOrNull()
            android.util.Log.w(TAG, "写队列溢出，丢弃最早一条 ${profile.displayName}")
        }
        writeQueue.addLast(PendingWrite(char, bytes, writeType, label))
        drainWrites()
    }

    @SuppressLint("MissingPermission")
    private fun drainWrites() {
        if (released || writeInFlight) return
        val item = writeQueue.removeFirstOrNull() ?: return
        val g = gatt ?: run {
            writeQueue.clear()
            return
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(item.char, item.bytes, item.writeType) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                item.char.value = item.bytes
                @Suppress("DEPRECATION")
                item.char.writeType = item.writeType
                @Suppress("DEPRECATION")
                g.writeCharacteristic(item.char)
            }
        }.getOrDefault(false)
        val hex = item.bytes.joinToString("") { String.format("%02x", it) }
        android.util.Log.i(TAG, "${item.label} $hex write=$ok ${profile.displayName}")
        if (item.label == "TRIGGER") {
            onStatusChange?.invoke(if (ok) "TRIGGER_SENT" else "TRIGGER_FAIL")
        }
        if (!ok && item.attempts < 1) {
            // 只重试一次：多半是上一条的收尾还没到（80ms 足够一个连接间隔）
            item.attempts++
            writeQueue.addFirst(item)
            handler.postDelayed({ drainWrites() }, 80L)
            return
        }
        if (!ok) return
        writeInFlight = true
        handler.removeCallbacks(writeWatchdog)
        // WRITE_NO_RESPONSE 的回调经常不回（A2 的 FFF1/FEC7 都是这种），等满 700ms
        // 会把后续帧整体拖后 300ms —— 三段式的 0.40s / 1.00s 节奏就是这么被拉成
        // 0.70s / 0.70s 的。无响应写只给 150ms 让路（一个连接间隔足够）。
        val timeout = if (item.writeType ==
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ) 150L else 700L
        handler.postDelayed(writeWatchdog, timeout)
    }

    @SuppressLint("MissingPermission")
    override fun release() {
        released = true
        connecting = false
        handler.removeCallbacksAndMessages(null)
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        cmdChar = null
        writeInFlight = false
        writeQueue.clear()
        ledModeOn = false
    }
}
