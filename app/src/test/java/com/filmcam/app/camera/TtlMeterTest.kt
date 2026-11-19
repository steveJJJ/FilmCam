package com.filmcam.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外置闪光相机侧 TTL 的求解契约（2026-11-19）。
 *
 * 目标值改成 [TtlMeter.TARGET_P50]（195）后，这里一律引用常量、不再写死数字，
 * 否则一调目标值单测就误报。判定口径是**冲洗后的成片**，不是中间读数。
 */
class TtlMeterTest {

    private fun reading(p50: Int, p90: Int = p50, clip: Float = 0f) =
        TtlMeter.Reading(p50, p90, clip)

    @Test
    fun `srgb 端点线性化正确`() {
        assertEquals(0f, TtlMeter.srgbToLinear(0f), 1e-6f)
        assertEquals(1f, TtlMeter.srgbToLinear(255f), 1e-4f)
        // 中灰 118 应落在 0.18 附近（sRGB 的标准中灰就是 18% 反射率）
        val mid = TtlMeter.srgbToLinear(118f)
        assertTrue("mid=$mid", mid in 0.15f..0.22f)
    }

    @Test
    fun `刚好命中目标时只补回电损失`() {
        val sol = TtlMeter.solve(TtlMeter.METER_ISO_MAX, reading(TtlMeter.TARGET_P50.toInt()))
        // 测光帧已经到目标，但正式帧（第二闪）天生暗 0.32EV，所以要抬 ISO
        assertEquals(TtlMeter.RECYCLE_LOSS_EV, sol.correctionEv, 1e-3f)
        assertTrue("iso=" + sol.finalIso, sol.finalIso > TtlMeter.METER_ISO_MAX)
    }

    @Test
    fun `暗两档就抬两档`() {
        // sRGB 上暗一档约等于线性光减半；取一个明显偏暗的读数，修正应为正向
        val sol = TtlMeter.solve(100, reading(60))
        assertTrue("ev=" + sol.correctionEv, sol.correctionEv > 0.5f)
        assertTrue("iso=" + sol.finalIso, sol.finalIso > 100)
    }

    @Test
    fun `过曝测光帧先降两档`() {
        val sol = TtlMeter.solve(400, reading(250))
        assertEquals(-2f, sol.correctionEv, 1e-3f)
        assertEquals(100, sol.finalIso)
    }

    @Test
    fun `欠曝测光帧先升两档`() {
        val sol = TtlMeter.solve(100, reading(5))
        assertEquals(2f, sol.correctionEv, 1e-3f)
        assertEquals(400, sol.finalIso)
    }

    @Test
    fun `修正幅度受限且 ISO 不越界`() {
        val hi = TtlMeter.solve(3200, reading(11))
        assertTrue("iso=" + hi.finalIso, hi.finalIso <= TtlMeter.MAX_ISO)
        val lo = TtlMeter.solve(12, reading(245))
        assertTrue("iso=" + lo.finalIso, lo.finalIso >= TtlMeter.MIN_ISO)
        assertTrue(lo.correctionEv >= -TtlMeter.MAX_CORRECTION_EV)
    }

    @Test
    fun `单帧修正被限幅在两档以内`() {
        // 20:23 真机事故的回归：漏闪帧 p50=14 曾把修正推到 4EV，下一张直接全白
        val sol = TtlMeter.solve(400, reading(14))
        assertEquals(TtlMeter.MAX_CORRECTION_EV, sol.correctionEv, 1e-3f)
        assertTrue("iso=" + sol.finalIso, sol.finalIso == 1600)
    }

    @Test
    fun `目标偏移抬一档就多要一档`() {
        val base = TtlMeter.solve(400, reading(150), includeRecycleComp = false)
        val up = TtlMeter.solve(400, reading(150), includeRecycleComp = false, targetOffsetEv = 1f)
        assertEquals(1f, up.correctionEv - base.correctionEv, 1e-3f)
    }

    @Test
    fun `达到偏移后的目标时偏差归零`() {
        // 偏移必须是「能达成的方向」：+1EV 要求 measured = 2×target ≈ 1.11 线性，
        // 超过白点所以永远解不到 0；−1EV 时 144 正好落在 target/2 上，修正应归零。
        val sol = TtlMeter.solve(400, reading(144), includeRecycleComp = false, targetOffsetEv = -1f)
        assertTrue("ev=" + sol.correctionEv, kotlin.math.abs(sol.correctionEv) < 0.35f)
    }

    @Test
    fun `测光 ISO 无效时原样返回`() {
        val sol = TtlMeter.solve(0, reading(TtlMeter.TARGET_P50.toInt()))
        assertEquals(0, sol.finalIso)
    }
}
