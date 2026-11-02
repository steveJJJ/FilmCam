package com.filmcam.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 过卷时序的回归测试。
 *
 * 用户报的 bug：「偶尔两次快门声、没有过卷声」。根因是过卷的声音/动画全靠
 * `isCapturing` 把关，而它在照片写完的瞬间就被置回 false —— 写入快的帧把过卷
 * 整个吞掉了。这几条断言锁住的是「过卷时序不随保存快慢改变」这个不变量。
 */
class AdvanceRitualTest {

    @Test
    fun normalShutter_hasNoSecondCurtainAndWindsAfterSilence() {
        val t = AdvanceRitual.plan(0L)
        assertEquals("常规快门不该有闭帘声", 0L, t.closeAtMs)
        // 开帘声约 82ms，人耳要约 300ms 静默才把两个声音听成"两件事"
        assertTrue("马达进场前要留足静默", t.advanceAtMs >= 300L)
    }

    @Test
    fun slowShutter_closesAfterExposureThenWinds() {
        val exposureNs = 500_000_000L   // 0.5s
        val t = AdvanceRitual.plan(exposureNs)
        // 闭帘 = 曝光走完 + 开帘延迟
        assertEquals(500L + AdvanceRitual.SHUTTER_OPEN_LATENCY_MS, t.closeAtMs)
        assertTrue("闭帘之后才过片", t.advanceAtMs > t.closeAtMs)
        assertTrue("闭帘到过片只留一点点机械延迟", t.advanceAtMs - t.closeAtMs <= 300L)
    }

    @Test
    fun ritualOutlastsTheWholeAdvanceChoreography() {
        for (exposureNs in listOf(0L, 30_000_000L, 500_000_000L, 2_000_000_000L)) {
            val t = AdvanceRitual.plan(exposureNs)
            // 收尾不能早于「马达走完这一格」：齿轮触感的最后一记顿挫也在这之前
            val lastHaptic = t.advanceAtMs + AdvanceRitual.GEAR_HAPTIC_SPAN_MS + 60L
            assertTrue(
                "曝光 ${exposureNs}ns：收尾(${"%.0f".format(t.ritualMs.toFloat())}ms) 不能早于末记顿挫($lastHaptic ms)",
                t.ritualMs >= lastHaptic
            )
        }
    }

    @Test
    fun fastSaveStillWaitsForTheMotor() {
        val t = AdvanceRitual.plan(0L)
        // 照片 20ms 就写完了：以前这时就收尾，过卷声（420ms 才播）直接被吞
        assertEquals("写得再快也要等马达走完", t.ritualMs - 20L, AdvanceRitual.finishDelayMs(20L, t.ritualMs))
        // 写得慢：写完了就可以收
        assertEquals(0L, AdvanceRitual.finishDelayMs(t.ritualMs + 500L, t.ritualMs))
        // 兜底：绝不返回负数（负延迟会变成"立即执行"，那是另一个坑）
        assertTrue(AdvanceRitual.finishDelayMs(99_999L, t.ritualMs) >= 0L)
    }

    @Test
    fun slowShotIsNeverShorterThanAFastOne() {
        val fast = AdvanceRitual.plan(0L)
        val slow = AdvanceRitual.plan(1_000_000_000L)   // 1s 曝光
        assertTrue("慢门的过卷不能比常规还早收尾", slow.ritualMs > fast.ritualMs)
        assertEquals(slow.advanceAtMs + AdvanceRitual.ADVANCE_TAIL_MS, slow.ritualMs)
    }
}
