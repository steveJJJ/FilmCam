package com.filmcam.app.flash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 守住 v60 的契约：**任何情况下点灯指令都非空**。
 * v50 起 `flash_trigger.hex` 被卸载清掉后，旧实现返回 null 并整帧跳过点灯，
 * 导致「App 快门点外置灯」静默失效。这组用例就是防止它回归。
 */
class FlashTriggerCommandTest {

    @Test
    fun `配置文件不存在时回退内置默认 05 00`() {
        assertArrayEquals(byteArrayOf(0x05, 0x00), FlashTriggerCommand.resolve(null))
    }

    @Test
    fun `空文件与纯注释都回退内置默认`() {
        for (raw in listOf("", "   ", "\n", "// 触发闪光\n", " // 只有注释")) {
            assertArrayEquals("raw=$raw", byteArrayOf(0x05, 0x00), FlashTriggerCommand.resolve(raw))
        }
    }

    @Test
    fun `奇数个 hex 字符或纯垃圾回退内置默认`() {
        assertArrayEquals(byteArrayOf(0x05, 0x00), FlashTriggerCommand.resolve("050"))
        assertArrayEquals(byteArrayOf(0x05, 0x00), FlashTriggerCommand.resolve("zzzz"))
        // 偶数位但长度异常仍按合法 hex 解析（外部调参文件自己的责任）
        assertArrayEquals(byteArrayOf(0x05, 0x00, 0x01), FlashTriggerCommand.resolve("05 00 01"))
    }

    @Test
    fun `合法的外部指令覆盖默认值`() {
        assertArrayEquals(byteArrayOf(0x05, 0x01), FlashTriggerCommand.resolve("05 01"))
        assertArrayEquals(byteArrayOf(0x02, 0x00), FlashTriggerCommand.resolve("02 00"))
    }

    @Test
    fun `空格逗号与行内注释都能解析`() {
        assertArrayEquals(
            byteArrayOf(0x05, 0x01),
            FlashTriggerCommand.resolve("05, 01 // 预闪档位 1\n")
        )
    }

    @Test
    fun `返回的是副本不会污染内置默认值`() {
        val a = FlashTriggerCommand.resolve(null)
        a[0] = 0x09
        assertArrayEquals(byteArrayOf(0x05, 0x00), FlashTriggerCommand.DEFAULT)
        assertArrayEquals(byteArrayOf(0x05, 0x00), FlashTriggerCommand.resolve(null))
    }

    @Test
    fun `parseHex 对非法输入返回 null 对合法输入返回字节`() {
        assertNull(FlashTriggerCommand.parseHex(""))
        assertNull(FlashTriggerCommand.parseHex("050"))
        assertNotNull(FlashTriggerCommand.parseHex("05 00"))
        assertArrayEquals(byteArrayOf(0x05, 0x00), FlashTriggerCommand.parseHex("05 00"))
    }
}
