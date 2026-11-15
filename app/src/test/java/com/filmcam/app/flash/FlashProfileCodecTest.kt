package com.filmcam.app.flash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档案编解码契约（vc86 第二层）。
 * 守的是「档案永远可救活」：任何一份合法文本进 [FlashProfileCodec.decode] 都能
 * 还原成可驱动 [LearnedProfileDriver] 的完整档案；任何残缺输入宁可拒绝也不半救。
 */
class FlashProfileCodecTest {

    private fun sample() = FlashProfile(
        brandId = "learned",
        displayName = "My Light",
        deviceName = "BLE-LIGHT-1",
        address = "AA:BB:CC:11:22:33",
        serviceUuid = "0000ffe0-0000-1000-8000-00805f9b34fb",
        writeCharUuid = "0000ffe1-0000-1000-8000-00805f9b34fb",
        writeNoResponse = true,
        triggerHex = "05 00",
        syncOffsetMs = -200,
        capability = FlashCapability.DUMB,
        pairedAt = 1794675007546L
    )

    @Test
    fun `编码解码往返无损`() {
        val text = FlashProfileCodec.encode(sample())
        val back = FlashProfileCodec.decode(text)
        assertEquals(sample(), back)
    }

    @Test
    fun `CRLF 与空行与注释行全部容忍`() {
        val raw = FlashProfileCodec.encode(sample())
            .replace("\n", "\r\n")
            .let { "# 备注：论坛抄来的\r\n\r\n" + it }
        assertEquals(sample(), FlashProfileCodec.decode(raw))
    }

    @Test
    fun `未知 key 忽略 缺省字段回默认值`() {
        val raw = """
            # FilmCam flash profile v1
            address=AA:BB:CC:11:22:33
            name=BLE-LIGHT-1
            service=FFE0
            char=FFE1
            trigger=0500
            future_field=whatever
        """.trimIndent()
        val p = FlashProfileCodec.decode(raw)!!
        assertEquals("BLE-LIGHT-1", p.displayName) // display 缺省回 name
        assertEquals("0000ffe0-0000-1000-8000-00805f9b34fb", p.serviceUuid)
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", p.writeCharUuid)
        assertTrue(p.writeNoResponse) // write 缺省 = no_response
        assertEquals(-200, p.syncOffsetMs)
        assertEquals(FlashCapability.DUMB, p.capability)
        assertEquals(0L, p.pairedAt)
    }

    @Test
    fun `必填缺失或非法一律拒绝 不半救`() {
        val base = FlashProfileCodec.encode(sample())
        fun drop(line: String) = base.lines().filter { !it.startsWith(line) }.joinToString("\n")
        assertNull("缺 address", FlashProfileCodec.decode(drop("address=")))
        assertNull("缺 name", FlashProfileCodec.decode(drop("name=")))
        assertNull("缺 service", FlashProfileCodec.decode(drop("service=")))
        assertNull("缺 char", FlashProfileCodec.decode(drop("char=")))
        assertNull("缺 trigger", FlashProfileCodec.decode(drop("trigger=")))
        assertNull("null 文本", FlashProfileCodec.decode(null))
        // trigger 非法 hex（奇数残串）
        val badHex = base.replace("trigger=05 00", "trigger=050")
        assertNull("奇数 hex", FlashProfileCodec.decode(badHex))
        // UUID 非法
        val badUuid = base.replace("service=0000ffe0", "service=zzzz")
        assertNull("非法 UUID", FlashProfileCodec.decode(badUuid))
        // MAC 非法
        val badMac = base.replace("address=AA:BB:CC:11:22:33", "address=不是MAC")
        assertNull("非法 MAC", FlashProfileCodec.decode(badMac))
    }

    @Test
    fun `UUID 规范化 短码与 0x 前缀与完整形式统一`() {
        assertEquals(
            "0000ffe1-0000-1000-8000-00805f9b34fb",
            FlashProfile.canonicalUuid("FFE1")
        )
        assertEquals(
            "0000ffe1-0000-1000-8000-00805f9b34fb",
            FlashProfile.canonicalUuid("0xFFE1")
        )
        assertEquals(
            "0000ffe1-0000-1000-8000-00805f9b34fb",
            FlashProfile.canonicalUuid("0000FFE1-0000-1000-8000-00805F9B34FB")
        )
        // 厂商自定义 128 位 UUID 原样小写保留（16 位展开规则不适用）
        assertEquals(
            "8ec90003-f315-4f60-9fb8-838830daea50",
            FlashProfile.canonicalUuid("8EC90003-F315-4F60-9FB8-838830DAEA50")
        )
    }

    @Test
    fun `触发帧字节可取 且与旧 hex 覆盖机制同解析器`() {
        val p = sample()
        assertArrayEquals(byteArrayOf(0x05, 0x00), p.triggerBytes())
        // "05, 01 // 注释" 这类旧风格输入同样有效（FlashTriggerCommand.parseHex）
        val q = sample().copy(triggerHex = "05, 01 // 预闪档位")
        assertArrayEquals(byteArrayOf(0x05, 0x01), q.triggerBytes())
    }
}
