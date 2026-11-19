package com.filmcam.app.flash

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * A2 造型灯命令协议契约（11-18 nRF Sniffer 真机抓包 ground truth）。
 *
 * golden 向量 = iPhone 官方 Godox Camera v1.2.2 ↔ A2 实抓的 20 条去重命令帧
 * （`tmp/a2_snoop/a2_follow_live2.pcapng`，38 帧去重）。此前「写入成功但灯无反应」
 * 的根因就是校验字节算错 —— 这组测试守住：CRC 实现、组帧顺序、命令字节，三样
 * 任何一样漂移都会被 20 条实抓样本当场抓住。
 */
class FlashLampControlTest {

    /** 实抓去重命令帧（hex）：a0 cmd arg crc8。 */
    private val golden = listOf(
        "a00301fd",  // 进入快门监听
        "a00300a3",  // 退出快门监听
        "a0050157",  // LED 开
        "a0050009",  // LED 关
        "a0066458",  // 造型灯亮度 100
        "a0065d7a",  // 造型灯亮度 93
        "a0060102",  // 造型灯亮度 1（官方此时紧跟 05 00 关灯）
        "a0070098",  // 造型灯色温 0（最暖）
        "a00704f9",  // 造型灯色温 4
        "a007115b",  // 造型灯色温 17
        "a0071ca6",  // 造型灯色温 28
        "a0071f44",  // 造型灯色温 31
        "a00720bb",  // 造型灯色温 32（白光）
        "a00c32b9",  // 拍摄参数 50（官方拍照前默认）
        "a00c237a",  // 拍摄参数 35
        "a00c1447",  // 拍摄参数 20
        "a0090db9",  // 拍照伴随参数（抓包观察）
        "a0092385",
        "a00915e6",
        "a0090699"
    )

    private fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun `CRC8-Maxim 对 20 条实抓命令帧全部命中`() {
        for (frame in golden) {
            val b = hexToBytes(frame)
            val body = b.copyOfRange(0, 3)
            val expected = b[3].toInt() and 0xFF
            val calc = CRC8Maxim.compute(body)
            org.junit.Assert.assertEquals(
                "CRC 不匹配：$frame（算出 ${"%02x".format(calc)}）",
                expected, calc
            )
        }
    }

    @Test
    fun `组帧顺序 a0-cmd-arg-crc 与实抓一致`() {
        // LED 开：a0 05 01 57
        assertArrayEquals(hexToBytes("a0050157"), A2LampProtocol.frame(0x05, 0x01))
        // LED 关：a0 05 00 09
        assertArrayEquals(hexToBytes("a0050009"), A2LampProtocol.frame(0x05, 0x00))
        // 快门监听：a0 03 01 fd
        assertArrayEquals(hexToBytes("a00301fd"), A2LampProtocol.frame(0x03, 0x01))
        // 造型灯亮度 100：a0 06 64 58
        assertArrayEquals(hexToBytes("a0066458"), A2LampProtocol.frame(0x06, 0x64))
        // 造型灯色温 32（白光）：a0 07 20 bb
        assertArrayEquals(hexToBytes("a00720bb"), A2LampProtocol.frame(0x07, 0x20))
        // 拍摄参数 50：a0 0c 32 b9
        assertArrayEquals(hexToBytes("a00c32b9"), A2LampProtocol.frame(0x0c, 0x32))
    }

    @Test
    fun `命令字节常量与抓包命令表一致`() {
        org.junit.Assert.assertEquals(0x03, A2LampProtocol.CMD_SHUTTER_LISTEN)
        org.junit.Assert.assertEquals(0x05, A2LampProtocol.CMD_LAMP_MODE)
        // 11-19 按官方写入时序定稿：06 = 造型灯亮度、07 = 造型灯色温、0c = 拍摄侧参数
        org.junit.Assert.assertEquals(0x06, A2LampProtocol.CMD_LAMP_BRIGHTNESS)
        org.junit.Assert.assertEquals(0x07, A2LampProtocol.CMD_LAMP_CCT)
        org.junit.Assert.assertEquals(0x0c, A2LampProtocol.CMD_FLASH_PARAM)
        org.junit.Assert.assertEquals(0x20, A2LampProtocol.CCT_STEP_MAX)
        org.junit.Assert.assertEquals(50, A2LampProtocol.FLASH_PARAM_DEFAULT)
    }
}
