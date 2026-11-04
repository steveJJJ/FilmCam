package com.filmcam.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RAW 负片选档规则（P3）：
 * 三星 Ultra 主摄可能把 200MP 全尺寸 Bayer 列进 RAW_SENSOR 档位，
 * 一张 DNG 四百 MB 起步 —— 必须选「不超过 16MP 的最大档」（正好覆盖 12MP 合并档）。
 */
class Camera2ControllerRawTest {

    @Test
    fun `16MP 内取最大档，超限档被跳过`() {
        // 12240x16320=200MP 应被跳过；4080x3060≈12.5MP > 4000x3000=12MP
        val sizes = listOf(4000 to 3000, 12240 to 16320, 4080 to 3060)
        assertEquals(4080 to 3060, Camera2Controller.pickRawSize(sizes))
    }

    @Test
    fun `全部超限时取最小档而不是最大档`() {
        val sizes = listOf(12240 to 16320, 10000 to 10000)   // 200MP / 100MP
        assertEquals(10000 to 10000, Camera2Controller.pickRawSize(sizes))
    }

    @Test
    fun `空表返回 null 表示设备不支持`() {
        assertNull(Camera2Controller.pickRawSize(emptyList()))
    }
}
