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

    // ---- 外置闪光自适应触发时序（v30）----

    @Test
    fun `触发延迟 = 实测 HAL 延迟 + 起辉补偿`() {
        // 旧写死 385ms 的兜底值：385 - 200 = 185ms
        assertEquals(185L, Camera2Controller.flashTriggerDelayMs(385L, -200L))
        // EXTERNAL 直提交路径实测约 240ms：必须在提交后 40ms 就发指令
        assertEquals(40L, Camera2Controller.flashTriggerDelayMs(240L, -200L))
        // 放宽下限后能调到 0，短延迟机型不再被卡在 135ms
        assertEquals(0L, Camera2Controller.flashTriggerDelayMs(120L, -400L))
        assertEquals(0L, Camera2Controller.flashTriggerDelayMs(100L, -200L))
    }

    @Test
    fun `中位数抗单次抖动，空样本回落兜底`() {
        assertEquals(385L, Camera2Controller.medianLatencyMs(emptyList(), 385L))
        assertEquals(240L, Camera2Controller.medianLatencyMs(listOf(240L, 250L, 230L), 385L))
        // 一次 900ms 的异常回调不该把下一帧的触发时机带偏
        assertEquals(260L, Camera2Controller.medianLatencyMs(listOf(100L, 260L, 900L), 385L))
        // 偶数个取偏大那个（宁可稍晚，别提前太多）
        assertEquals(400L, Camera2Controller.medianLatencyMs(listOf(200L, 400L), 385L))
    }
}
