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
    val pairedAt: Long = 0L
) {
    /** 触发帧字节；hex 非法返回 null（调用方回退 / 拒绝保存）。 */
    fun triggerBytes(): ByteArray? = FlashTriggerCommand.parseHex(triggerHex)

    companion object {
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
