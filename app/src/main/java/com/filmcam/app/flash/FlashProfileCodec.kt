package com.filmcam.app.flash

/**
 * 档案编解码：行式 `key=value`，纯 Kotlin 零 Android 依赖（沿袭
 * [FlashTriggerCommand] 的 JVM 单测纪律）。
 *
 * 为什么不用 JSON：档案要能 adb push / 邮件附件 / 论坛贴文本直接导入，
 * 行式格式肉眼可读可改，且不需要引入序列化库或 Android 的 org.json
 * （org.json 在 JVM 单测里是抛异常的 stub）。未知 key 忽略（向前兼容）。
 *
 * 格式（v1）：
 * ```
 * # FilmCam flash profile v1
 * address=DA:BA:CD:E2:BE:B0
 * name=FG-COB-FLASH
 * display=FotorGear COB
 * service=0000ffe0-0000-1000-8000-00805f9b34fb
 * char=0000ffe1-0000-1000-8000-00805f9b34fb
 * write=no_response
 * trigger=05 00
 * offset=-200
 * capability=DUMB
 * paired=1794675007546
 * ```
 * 必填：address / name / service / char / trigger。其余有默认值。
 */
object FlashProfileCodec {

    private const val HEADER = "# FilmCam flash profile v1"
    private const val REQUIRED_KEYS = 5

    fun encode(p: FlashProfile): String = buildString {
        append(HEADER).append('\n')
        append("address=").append(p.address).append('\n')
        append("name=").append(p.deviceName).append('\n')
        append("display=").append(p.displayName).append('\n')
        append("service=").append(p.serviceUuid).append('\n')
        append("char=").append(p.writeCharUuid).append('\n')
        append("write=").append(if (p.writeNoResponse) "no_response" else "default").append('\n')
        append("trigger=").append(p.triggerHex).append('\n')
        append("offset=").append(p.syncOffsetMs).append('\n')
        append("capability=").append(p.capability.name).append('\n')
        append("paired=").append(p.pairedAt).append('\n')
        // v2：造型灯命令通道（可选；空 = 纯触发灯）。CRC 方案隐含 CRC8-Maxim。
        p.cmdServiceUuid?.let { append("cmdservice=").append(it).append('\n') }
        p.cmdCharUuid?.let { append("cmdchar=").append(it).append('\n') }
    }

    /**
     * 解析档案文本。
     * @return null = 必填缺失 / trigger hex 非法 / UUID 非法（不完整地救半份档案，
     *         宁可让用户重配一次）。CRLF / 空行 / 注释行 / 未知 key 全部容忍。
     */
    fun decode(raw: String?): FlashProfile? {
        if (raw == null) return null
        val kv = mutableMapOf<String, String>()
        for (line in raw.lines()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            val idx = l.indexOf('=')
            if (idx <= 0) continue
            kv[l.substring(0, idx).trim().lowercase()] = l.substring(idx + 1).trim()
        }
        if (kv.size < REQUIRED_KEYS) return null
        val address = kv["address"] ?: return null
        val name = kv["name"] ?: return null
        val service = kv["service"] ?: return null
        val char = kv["char"] ?: return null
        val trigger = kv["trigger"] ?: return null
        // MAC 至少 17 位且像 MAC；名字非空
        if (!Regex("^[0-9a-fA-F:]{17}$").matches(address) || name.isEmpty()) return null
        // UUID：短码或完整 128 位
        if (!isValidUuid(service) || !isValidUuid(char)) return null
        // 触发帧必须是合法 hex（有字节、非奇数残串）
        if (FlashTriggerCommand.parseHex(trigger) == null) return null
        return FlashProfile(
            brandId = kv["brand"] ?: "learned",
            displayName = kv["display"]?.takeIf { it.isNotEmpty() } ?: name,
            deviceName = name,
            address = address.uppercase(),
            serviceUuid = FlashProfile.canonicalUuid(service),
            writeCharUuid = FlashProfile.canonicalUuid(char),
            writeNoResponse = kv["write"]?.lowercase() != "default",
            triggerHex = trigger,
            syncOffsetMs = kv["offset"]?.toIntOrNull() ?: -200,
            capability = runCatching {
                FlashCapability.valueOf(kv["capability"] ?: "DUMB")
            }.getOrDefault(FlashCapability.DUMB),
            pairedAt = kv["paired"]?.toLongOrNull() ?: 0L,
            cmdServiceUuid = kv["cmdservice"]?.takeIf { it.isNotEmpty() && isValidUuid(it) }
                ?.let { FlashProfile.canonicalUuid(it) },
            cmdCharUuid = kv["cmdchar"]?.takeIf { it.isNotEmpty() && isValidUuid(it) }
                ?.let { FlashProfile.canonicalUuid(it) }
        )
    }

    private fun isValidUuid(s: String): Boolean {
        val t = s.trim().removePrefix("0x")
        return Regex("^[0-9a-fA-F]{1,4}$").matches(t) ||
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(t)
    }
}
