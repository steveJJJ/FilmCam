package com.filmcam.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 胶片曝光模型单测。
 *
 * 只钉住「算得对不对」，不钉概率分布 —— 概率是产品口味（40% / 70%），
 * 这里只验证骰子被真的用上了（固定随机数能控制命中/不命中）。
 */
class FilmExposureTest {

    /** 固定随机数：验证掷骰分支，不受随机性干扰 */
    private class FixedRandom(private val v: Float) : java.util.Random() {
        override fun nextFloat(): Float = v
    }

    private fun plan(
        tAe: Float,
        isoAe: Int,
        isoFilm: Int = 400,
        mode: FilmExposure.Mode = FilmExposure.Mode.NATURAL,
        comp: Int = 0,
        trigger: FilmExposure.Trigger = FilmExposure.Trigger.PROBABILITY,
        random: Float = 0.1f,
    ) = FilmExposure.plan(
        tAeSec = tAe,
        isoAe = isoAe,
        aperture = 1.7f,
        filmIso = isoFilm,
        mode = mode,
        evCompSteps = comp,
        deviceMinSec = 1f / 12000f,
        deviceMaxSec = 0.176f,
        deviceIsoRange = 12..3200,
        trigger = trigger,
        random = FixedRandom(random)
    )

    @Test
    fun ev100_fromPhoneMetering() {
        // 1/30 + ISO1600 + f/1.7 ≈ EV100 2.44（暗光餐厅）
        val ev = FilmExposure.ev100(1f / 30f, 1600, 1.7f)
        assertNotNull(ev)
        assertTrue("ev=$ev", ev!! in 2.3f..2.6f)
    }

    @Test
    fun ev100_invalidInputReturnsNull() {
        assertNull(FilmExposure.ev100(0f, 400, 1.7f))
        assertNull(FilmExposure.ev100(1f / 30f, 0, 1.7f))
        assertNull(FilmExposure.ev100(1f / 30f, 400, 0f))
    }

    @Test
    fun filmShutter_matchesT3AtEv10() {
        // T3 在 f/2.8、ISO400、EV10 时恰好顶到 1/500 —— 真机规格里的最快档
        val sec = FilmExposure.filmShutterSec(10f, 400, 0)
        assertTrue("sec=$sec", sec in 0.0018f..0.0021f)
    }

    @Test
    fun filmShutter_fasterFilmGivesFasterShutter() {
        val iso100 = FilmExposure.filmShutterSec(6f, 100, 0)
        val iso400 = FilmExposure.filmShutterSec(6f, 400, 0)
        assertTrue("ISO400 应该比 ISO100 快 2 档：$iso100 vs $iso400", iso100 > iso400 * 3.9f)
    }

    @Test
    fun filmShutter_positiveCompIsSlower() {
        val base = FilmExposure.filmShutterSec(6f, 400, 0)
        val plus1 = FilmExposure.filmShutterSec(6f, 400, 1)
        assertEquals(base * 2f, plus1, base * 0.01f)
    }

    @Test
    fun brightestScene_neverIntervenes() {
        // 大晴天：真机 ISO400 f/2.8 需要 1/2000 以上，早顶到 1/500 上限，
        // 这一档我们完全不干预 —— 否则白天会全部过曝
        val p = plan(tAe = 1f / 1000f, isoAe = 100)
        assertNotNull(p)
        assertTrue(!p!!.applied)
        assertEquals(0L, p.exposureNs)
    }

    @Test
    fun dimScene_diceHitGivesSlowExposure() {
        // 室内灯光（1/30 + ISO400 + f/1.7 ≈ EV4.4）→ 胶卷该用约 1/11 秒
        val p = plan(tAe = 1f / 30f, isoAe = 400, random = 0.1f)
        assertNotNull(p)
        assertTrue("filmSec=${p!!.filmSec}", p.filmSec in 0.08f..0.10f)
        assertTrue(p.applied)
        val diff = kotlin.math.abs(p.exposureNs - (p.filmSec * 1e9).toLong())
        assertTrue("曝光时间与 filmSec 不一致 diff=$diff", diff < 2_000_000L)
    }

    @Test
    fun dimScene_diceMissLeavesCameraAlone() {
        val p = plan(tAe = 1f / 30f, isoAe = 400, random = 0.99f)
        assertNotNull(p)
        assertTrue(!p!!.applied)
        assertEquals(0L, p.exposureNs)
    }

    @Test
    fun darkestScene_clampsToEighthSecondAndRaisesIso() {
        // 极暗：胶卷本该 1.4 秒，但本档只允许到 1/8 —— 用抬感光度把曝光量补回来
        val p = plan(tAe = 1f / 15f, isoAe = 3200, mode = FilmExposure.Mode.SLOW, random = 0.01f)
        assertNotNull(p)
        // 1/8 是硬上限：宁可抬感光度，也不让快门更长
        assertTrue("filmSec=${p!!.filmSec}", p.filmSec in 0.12f..0.13f)
        assertTrue("exposureNs=${p.exposureNs}", p.exposureNs in 120_000_000L..130_000_000L)
        // 抬到设备 ISO 上限（胶卷 ISO 400 撑不住 1/8 秒）
        assertEquals(3200, p.iso)
    }

    @Test
    fun offMode_returnsNull() {
        assertNull(plan(tAe = 1f / 15f, isoAe = 3200, mode = FilmExposure.Mode.OFF))
    }

    @Test
    fun slowModeIsMoreEagerThanNatural() {
        assertTrue(
            FilmExposure.Mode.SLOW.probability(1f / 20f) >
                FilmExposure.Mode.NATURAL.probability(1f / 20f)
        )
        assertEquals(0f, FilmExposure.Mode.OFF.probability(1f / 20f), 0f)
        // 越暗越容易触发，但不会超过 1
        assertTrue(
            FilmExposure.Mode.SLOW.probability(1f / 8f) >=
                FilmExposure.Mode.SLOW.probability(1f / 60f)
        )
    }

    @Test
    fun realTrigger_ignoresDice() {
        // 骰子给 0.99：纯概率派必不中，纯真实派照样慢门（真机没有随机）
        val p = plan(tAe = 1f / 30f, isoAe = 400, trigger = FilmExposure.Trigger.REAL, random = 0.99f)
        assertNotNull(p)
        assertTrue(p!!.applied)
        assertTrue(p.exposureNs > 0L)
    }

    @Test
    fun realTrigger_stillIgnoresDaylight() {
        // 纯真实派也不是无脑慢门：白天真机不糊，这里同样不干预
        val p = plan(tAe = 1f / 1000f, isoAe = 100, trigger = FilmExposure.Trigger.REAL, random = 0.99f)
        assertNotNull(p)
        assertTrue(!p!!.applied)
        assertEquals(0L, p.exposureNs)
    }

    @Test
    fun realMix_withoutExtraStopEqualsReal() {
        val real = plan(tAe = 1f / 60f, isoAe = 400, trigger = FilmExposure.Trigger.REAL)
        val mix = plan(tAe = 1f / 60f, isoAe = 400, trigger = FilmExposure.Trigger.REAL_MIX, random = 0.99f)
        assertEquals(real!!.filmSec, mix!!.filmSec, 1e-6f)
        assertEquals(real.iso, mix.iso)
    }

    @Test
    fun realMix_extraStopDoublesExposureAndHalvesIso() {
        // 额外慢一档：快门 ×2，感光度 ÷2 —— t×ISO 守恒，亮度不变只是更抖
        val real = plan(tAe = 1f / 60f, isoAe = 400, trigger = FilmExposure.Trigger.REAL)
        val mix = plan(
            tAe = 1f / 60f, isoAe = 400,
            trigger = FilmExposure.Trigger.REAL_MIX, random = 0.05f
        )
        assertNotNull(real)
        assertNotNull(mix)
        assertTrue("real=${real!!.filmSec} mix=${mix!!.filmSec}", mix.filmSec > real.filmSec * 1.9f)
        assertEquals(real.iso / 2, mix!!.iso)
        assertEquals(real.filmSec * real.iso, mix.filmSec * mix.iso, real.filmSec * real.iso * 0.02f)
    }

    @Test
    fun triggerFromPrefs_defaultsToProbability() {
        assertEquals(FilmExposure.Trigger.PROBABILITY, FilmExposure.Trigger.fromPrefs(0))
        assertEquals(FilmExposure.Trigger.REAL, FilmExposure.Trigger.fromPrefs(1))
        assertEquals(FilmExposure.Trigger.REAL_MIX, FilmExposure.Trigger.fromPrefs(2))
        // 脏数据 / 旧版本没写过 → 退回纯概率派（现有默认行为）
        assertEquals(FilmExposure.Trigger.PROBABILITY, FilmExposure.Trigger.fromPrefs(99))
    }

    @Test
    fun isoFromRollName_readsDxSpeed() {        assertEquals(400, FilmExposure.isoFromRollName("PORTRA 400"))
        assertEquals(100, FilmExposure.isoFromRollName("EKTAR 100"))
        assertEquals(800, FilmExposure.isoFromRollName("CINESTILL 800T"))
        assertEquals(800, FilmExposure.isoFromRollName("PORTRA 800 PUSH 1"))
        assertEquals(400, FilmExposure.isoFromRollName(null))
        assertEquals(400, FilmExposure.isoFromRollName("MY CUSTOM ROLL"))
    }

    @Test
    fun shutterLabel_formatsLikeT3() {
        assertEquals("1/8", FilmExposure.shutterLabel(1f / 8f))
        assertEquals("1/500", FilmExposure.shutterLabel(1f / 500f))
    }
}
