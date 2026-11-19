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
    fun `A2 老档案默认offset200自动纠正为80 显式标定值保留`() {
        val a2 = sample().copy(
            deviceName = "Godox-A2-2319",
            address = "A4:C1:38:3C:23:19",
            syncOffsetMs = -200
        )
        assertEquals("氙气灯老档案默认值自动纠正为 -80", -80, a2.effectiveSyncOffsetMs)
        assertEquals("磁盘/解码原值不变", -200, a2.syncOffsetMs)
        val custom = a2.copy(syncOffsetMs = -120)
        assertEquals("显式标定的 offset 必须尊重", -120, custom.effectiveSyncOffsetMs)
        assertEquals("普通灯保持 -200", -200, sample().effectiveSyncOffsetMs)
    }

    @Test
    fun `回电提示只对氙气灯开 COB 常亮灯不提示`() {
        // 氙气灯有电容回电（实测间隔 2.6s 第二张暗 29%），要提示；
        // COB 常亮灯不存在回电，提示会误导。
        assertTrue(
            "氙气灯要提示",
            sample().copy(deviceName = "Godox-A2-2319").needsRecycleHint
        )
        assertTrue(
            "普通 BLE 灯默认按氙气处理",
            sample().copy(deviceName = "BLE-LIGHT-1").needsRecycleHint
        )
        assertTrue(
            "COB 大小写混写也算常亮灯",
            !sample().copy(deviceName = "FG-Cob-500").needsRecycleHint
        )
    }

    @Test
    fun `A2 当前是单发触发（三段式实测三下全 LED 已回退）`() {
        // iPhone 官方 App 一次快门闪三下，但那是靠 0c/a009 参数帧配合；
        // 我们只发触发帧时三段全是 LED，所以维持单发（20:06 验证能出氙气）。
        val a2 = sample().copy(deviceName = "Godox-A2-2319")
        assertEquals(1, a2.triggerStages)
        assertEquals(1, sample().triggerStages)
    }

    @Test
    fun `v2 命令通道字段往返无损`() {
        val v2 = sample().copy(
            cmdServiceUuid = "0000fec0-0000-1000-8000-00805f9b34fb",
            cmdCharUuid = "0000fec7-0000-1000-8000-00805f9b34fb"
        )
        assertEquals(v2, FlashProfileCodec.decode(FlashProfileCodec.encode(v2)))
    }

    @Test
    fun `v1 老档案无命令字段解码不炸 A2 灯型自动补全 FEC0 服务 FEC7 特征`() {
        // 装机里的 A2 老档案（v1）解码后 cmd 字段为 null，但生效值按灯型补全
        val raw = """
            address=A4:C1:38:3C:23:19
            name=Godox-A2-2319
            service=FFF0
            char=FFF1
            trigger=31 30 30 30 2C 54 65 73 74
            offset=-200
        """.trimIndent()
        val p = FlashProfileCodec.decode(raw)!!
        assertNull("磁盘字段保持 null（如实解码）", p.cmdServiceUuid)
        assertNull(p.cmdCharUuid)
        assertEquals(
            "A2 灯型自动补全命令服务（FEC0，不是特征 FEC7）",
            "0000fec0-0000-1000-8000-00805f9b34fb",
            p.effectiveCmdServiceUuid
        )
        assertEquals(
            "A2 灯型自动补全命令特征（FEC7，A2 GATT 表里根本没有 F0A0）",
            "0000fec7-0000-1000-8000-00805f9b34fb",
            p.effectiveCmdCharUuid
        )
        // 普通灯没有命令通道，生效值也是 null
        val plain = sample()
        assertNull(plain.effectiveCmdServiceUuid)
        assertNull(plain.effectiveCmdCharUuid)
        // 显式存了 cmd 字段的档案原样尊重
        val explicit = sample().copy(
            deviceName = "Godox-A2-2319",
            cmdServiceUuid = "0000ffee-0000-1000-8000-00805f9b34fb",
            cmdCharUuid = "0000fff0-0000-1000-8000-00805f9b34fb"
        )
        assertEquals(
            "0000ffee-0000-1000-8000-00805f9b34fb",
            explicit.effectiveCmdServiceUuid
        )
    }

    @Test
    fun `A2 档案若存了 11-19 之前的错误命令 UUID 读取时自愈`() {
        // 11-19 之前把特征 FEC7 当服务、把帧头 F0A0 当特征写进档案，会让服务发现
        // 永远拿不到 cmdChar（控制台一直「未就绪」）。读取时按灯型纠正回 FEC0/FEC7。
        val broken = sample().copy(
            deviceName = "Godox-A2-2319",
            cmdServiceUuid = "0000fec7-0000-1000-8000-00805f9b34fb",
            cmdCharUuid = "0000f0a0-0000-1000-8000-00805f9b34fb"
        )
        assertEquals(FlashProfile.A2_CMD_SERVICE, broken.effectiveCmdServiceUuid)
        assertEquals(FlashProfile.A2_CMD_CHAR, broken.effectiveCmdCharUuid)
        assertEquals("0000fec0-0000-1000-8000-00805f9b34fb", broken.effectiveCmdServiceUuid)
        assertEquals("0000fec7-0000-1000-8000-00805f9b34fb", broken.effectiveCmdCharUuid)
        // 非 A2 灯型的显式值不动（不能把别人的档案改坏）
        val other = sample().copy(
            cmdServiceUuid = "0000fec7-0000-1000-8000-00805f9b34fb",
            cmdCharUuid = "0000f0a0-0000-1000-8000-00805f9b34fb"
        )
        assertEquals("0000fec7-0000-1000-8000-00805f9b34fb", other.effectiveCmdServiceUuid)
        assertEquals("0000f0a0-0000-1000-8000-00805f9b34fb", other.effectiveCmdCharUuid)
    }

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
