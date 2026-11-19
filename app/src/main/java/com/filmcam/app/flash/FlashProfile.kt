package com.filmcam.app.flash

/**
 * 外置闪光灯设备档案（品牌无关的协议描述）。
 *
 * 这是「通用外闪」架构的第二层：把「点这盏灯需要写什么」从代码里抽出来变成
 * 数据。三种来源最终都汇成这个格式：
 *  1. 内置驱动注册表（[ExternalFlashRegistry.Entry] —— 代码级，FotorGear 现状）；
 *  2. 用户在配对向导（FlashPairActivity）里现场学出来的档案（自学习）；
 *  3. 众包档案库 / 手动导入（未来：nRF Connect 抓包后直接填）。
 *
 * 序列化见 [FlashProfileCodec]（行式 key=value，纯 Kotlin 可 JVM 单测）；
 * 持久化见 [FlashProfileStore]（filesDir，按 MAC 绑定，卸载才删）。
 *
 * ⚠️ 与旧 `flash_trigger.hex` 的关系：那个文件只覆盖触发字节这一个字段，
 * 且放在清数据会丢的外部目录；档案是完整协议描述（服务/特征/写类型/触发帧），
 * 放私有目录。旧机制保留不动（FotorGear 驱动还在用）。
 */
data class FlashProfile(
    /** 档案品牌标识。向导学出来的用 "learned"。 */
    val brandId: String,
    /** 人机可读名字（向导里显示、状态栏可复用）。 */
    val displayName: String,
    /** BLE 广播名（重连时按 MAC 直连，名字只用于展示与兜底识别）。 */
    val deviceName: String,
    /** MAC 地址 = 档案主键（一灯一档）。 */
    val address: String,
    /** 服务 UUID（标准 16 位短码或完整 128 位均可，见 [canonicalUuid]）。 */
    val serviceUuid: String,
    /** 可写特征 UUID（触发帧写到这里）。 */
    val writeCharUuid: String,
    /** 写类型：true = WRITE_NO_RESPONSE（大多数廉价灯），false = WRITE_DEFAULT。 */
    val writeNoResponse: Boolean = true,
    /** 触发帧（hex 文本，空格任意分隔）。 */
    val triggerHex: String,
    /**
     * 触发提前量（ms，负数 = 提前）。FotorGear 实测 -200（灯收到指令后 ~200ms
     * 才出光）。目前相机侧用全局常量，此字段先入档（信息完整），接线见后续版本。
     */
    val syncOffsetMs: Int = -200,
    /** 能力档（向导学出的都是 DUMB：只验证了触发）。 */
    val capability: FlashCapability = FlashCapability.DUMB,
    /** 配对时间戳（ms，创建时打上），多条档案时「最近配对的优先」。 */
    val pairedAt: Long = 0L,
    /**
     * v2 档案新增：造型灯命令通道（第二服务/特征，如 A2 的 FEC7/F0A0）。
     * null = 无命令通道（老 v1 档案 / 纯触发灯）；CRC 方案隐含 CRC8-Maxim
     * （目前唯一已知命令通道协议，见 [A2LampProtocol]）。
     */
    val cmdServiceUuid: String? = null,
    val cmdCharUuid: String? = null
) {
    /** 触发帧字节；hex 非法返回 null（调用方回退 / 拒绝保存）。 */
    fun triggerBytes(): ByteArray? = FlashTriggerCommand.parseHex(triggerHex)

    /**
     * 实际用于拍照时序的同步偏移：氙气灯（A2）出光快，实测 -80ms；
     * 老档案存的是 COB 默认 -200，读取时按灯型纠正（不改磁盘文本，解码保持如实）。
     * 显式存了非默认 offset 的档案原样尊重 —— 用户/众包可以标定自己的灯。
     */
    val effectiveSyncOffsetMs: Int
        get() = if (isXenonA2 && syncOffsetMs == DEFAULT_OFFSET_MS) -80 else syncOffsetMs

    /**
     * 造型灯命令通道（生效值）：老 v1 档案没存 cmd 字段，但 A2 的命令口布局
     * 固定（服务 **FEC0** / 特征 **FEC7**，handle 0x0012，prop=0x0c），按灯型
     * 自动补全 —— 已装机的 A2 档案不用重新配对就能拿到 LED/亮度/色温控制。
     *
     * 11-19 修正：早先误把帧头 `f0a0` 当成特征 UUID、把特征 `fec7` 当成服务 UUID，
     * 服务发现永远拿到 `cmdChar=null`（控制台一直「未就绪」）。真值见
     * `tmp/a2_packetlogger/bt_dump_full.txt` 的 `[gatt_cache_a4c1383c2319]` 段：
     * 服务 0x000c=fec0，特征 0x0011/0x0012=fec7。
     * 显式存了正确 cmd 字段的档案原样尊重（众包档案可覆盖固件改版）；
     * 存了旧的错误值（fec7/f0a0）按灯型自愈，避免老档案永久失效。
     */
    val effectiveCmdServiceUuid: String?
        get() = when {
            !isXenonA2 -> cmdServiceUuid
            cmdServiceUuid == null || cmdServiceUuid == A2_LEGACY_WRONG_SERVICE -> A2_CMD_SERVICE
            else -> cmdServiceUuid
        }
    val effectiveCmdCharUuid: String?
        get() = when {
            !isXenonA2 -> cmdCharUuid
            cmdCharUuid == null || cmdCharUuid == A2_LEGACY_WRONG_CHAR -> A2_CMD_CHAR
            else -> cmdCharUuid
        }

    /**
     * 一张照片需要发几次触发帧。
     *
     * A2 是**三段式**：`LED → 氙气预闪 → 氙气主闪`（iPhone 官方 App 实拍确认，
     * 抓包里一张照片对应 3 个 `Take`，间隔约 0.40s / 1.00s）。只发 1 发时灯永远
     * 停在第一段（LED），这就是我们之前「快门只有 LED / 偶尔氙气」的根因。
     */
    val triggerStages: Int
        get() = if (isXenonA2) A2_TRIGGER_STAGES else 1

    /**
     * 拍摄后是否需要提示「回电中」。
     *
     * 氙气灯靠电容储能，放过一发要数秒才充满，期间再拍输出明显偏弱（11-19 A2 实测：
     * 间隔 2.6s 的第二张比首张暗 29%）。COB 常亮灯没有电容回电这回事，提示会误导，
     * 所以按灯型区分。
     */
    val needsRecycleHint: Boolean
        get() = !deviceName.contains("COB", ignoreCase = true)

    private val isXenonA2: Boolean
        get() = deviceName.contains("A2", ignoreCase = true) ||
            address.equals("A4:C1:38:3C:23:19", ignoreCase = true)

    companion object {
        private const val DEFAULT_OFFSET_MS = -200

        /**
         * A2 一张照片的触发段数 —— **当前为 1（单发）**。
         *
         * 曾经按 iPhone 观察到的「三下闪烁」实现成 3 段式，但真机结果是**三下全 LED**：
         * 灯的三段推进要靠官方那两条拍摄参数帧（`0c` / `a009`）配合，我们只发触发帧时
         * 它不会切到氙气。单发 `1000,Test` 是唯一被验证能出氙气的路径（20:06 拍照链路
         * 验证、以及本日多轮真机），所以维持 1。三段式的调度代码保留在
         * Camera2Controller（STAGE2_MS/STAGE3_MS），将来破出 `0c`/`a009` 语义再启用。
         */
        const val A2_TRIGGER_STAGES = 1

        /** A2 造型灯命令口真值：服务 FEC0 / 特征 FEC7（handle 0x0012，prop=0x0c）。 */
        val A2_CMD_SERVICE: String = canonicalUuid("FEC0")
        val A2_CMD_CHAR: String = canonicalUuid("FEC7")

        /** 11-19 之前误存的值（把特征当服务、把帧头 f0a0 当特征），读取时自愈。 */
        private val A2_LEGACY_WRONG_SERVICE: String = canonicalUuid("FEC7")
        private val A2_LEGACY_WRONG_CHAR: String = canonicalUuid("F0A0")

        /**
         * UUID 规范化：接受 4 位短码（"FFE1" / "0xFFE1"）或完整 128 位形式，
         * 统一成小写完整 UUID（标准 16 位码展开为 0000xxxx-0000-1000-8000-00805f9b34fb）。
         * 非法输入原样小写返回（保存前由 [FlashProfileCodec] 校验）。
         */
        fun canonicalUuid(raw: String): String {
            val s = raw.trim().removePrefix("0x").lowercase()
            val short = Regex("^([0-9a-f]{1,4})$").find(s)?.groupValues?.get(1)
            if (short != null) {
                return "0000" + short.padStart(4, '0') + "-0000-1000-8000-00805f9b34fb"
            }
            return s
        }
    }
}
