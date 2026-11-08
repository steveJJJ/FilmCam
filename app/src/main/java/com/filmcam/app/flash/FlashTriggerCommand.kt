package com.filmcam.app.flash

/**
 * 外置闪光灯（FG-COB-FLASH）的触发指令解析与内置默认值。
 *
 * 纯 Kotlin、零 Android 依赖，方便 JVM 单测守住这条契约：
 * **没配置文件也必须能点灯**。
 *
 * 2026-11-06 真机逐条轮转标定的 0xFFE1 协议：
 *   `05 00` / `05 01` 触发闪光（第二字节对应官方 App 的「预闪时间」档位，两者都能闪）；
 *   `01 64` / `01 FF` / `03 01` / `04 00` / `06 01` / `07 01` 等均不触发。
 * 帧格式固定 [cmd][param] 两字节，灯不回 ACK。
 *
 * ⚠️ 历史事故（v50 起潜伏）：`flash_trigger.hex` 放在 `getExternalFilesDir()`，
 * 这个目录在**卸载 App / 清除数据**时会被系统整个删掉。而旧实现读到文件不存在
 * 就直接 `TRIGGER_SKIP NO_CMD` 跳过点灯 —— 于是「App 快门点外置灯」在
 * 任何新装机、清数据或卸载重装之后都静默失效，日志只写「灯由物理按键自行闪光」。
 * 外部文件从现在起只作为**调参覆盖**，点灯能力本身由 [DEFAULT] 兜底。
 */
object FlashTriggerCommand {

    /** 内置默认触发指令：`05 00`。外部文件缺失 / 为空 / 格式非法时一律用它。 */
    val DEFAULT: ByteArray = byteArrayOf(0x05, 0x00)

    /**
     * 解析触发指令。
     *
     * @param raw 外部 `flash_trigger.hex` 的全文；可为 null（文件不存在 / 读失败）。
     *            支持 `//` 行内注释与任意分隔符（空格、逗号、0x 前缀等）。
     * @return 合法指令字节；无法解析时回退 [DEFAULT] 的副本。
     */
    fun resolve(raw: String?): ByteArray {
        if (raw != null) {
            val cmd = parseHex(raw.substringBefore("//"))
            if (cmd != null && cmd.isNotEmpty()) return cmd
        }
        return DEFAULT.copyOf()
    }

    /** 从十六进制文本取字节；非 hex 字符一律过滤，奇数个 hex 字符或空内容视为非法。 */
    fun parseHex(s: String): ByteArray? = runCatching {
        val hex = s.replace(Regex("[^0-9a-fA-F]"), "")
        if (hex.isEmpty() || hex.length % 2 != 0) return@runCatching null
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }.getOrNull()
}
