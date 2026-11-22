package com.filmcam.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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


    // ============ 外置闪光「每张独立」TTL 三件套（11-22）============

    @Test
    fun `距离先验 ISO正比于距离平方 近距不白板远距不黑片`() {
        // A2 固定功率，闪光主体曝光 ∝ ISO/d² ⇒ 正确 ISO ∝ d²。
        // gain=4/ISO=228 是 d=1m 标定（REF），近距 0.5m 要 1/4、远距 2m 要 4 倍。
        assertEquals(1f, TtlMeter.distanceGainFactor(1.0f), 0.01f)
        assertEquals(0.25f, TtlMeter.distanceGainFactor(0.5f), 0.01f)
        assertEquals(4f, TtlMeter.distanceGainFactor(2.0f), 0.01f)
        // 无效距离回退标定（不做猜测）
        assertEquals(1f, TtlMeter.distanceGainFactor(0f), 0.01f)
        assertEquals(1f, TtlMeter.distanceGainFactor(0.1f), 0.01f)
        assertEquals(1f, TtlMeter.distanceGainFactor(10f), 0.01f)
        // 换算成 ISO：0.5m→57（近距不白板）、2m→912（远距不黑片）
        assertEquals(57, (TtlMeter.FLASH_BASE_ISO * TtlMeter.distanceGainFactor(0.5f)).toInt())
        assertEquals(912, (TtlMeter.FLASH_BASE_ISO * TtlMeter.distanceGainFactor(2f)).toInt())
    }

    @Test
    fun `拍后微调步长保守0_5EV且有死区 不再单帧甩飞`() {
        // 旧版 ±2EV 是灾难（白板→-2EV→ISO57→flashMiss 死锁全黑）。
        // 新版：过曝 p50=255 只降 0.5EV、欠曝 p50=25 只升 0.5EV。
        val over = TtlMeter.postShotCorrectionEv(TtlMeter.Reading(255, 255, 60f), 0f)
        val under = TtlMeter.postShotCorrectionEv(TtlMeter.Reading(25, 30, 0f), 0f)
        assertEquals(-TtlMeter.EXT_STEP_EV, over, 0.01f)
        assertEquals(TtlMeter.EXT_STEP_EV, under, 0.01f)
        // 目标附近（p50=195）死区内不动
        assertEquals(0f, TtlMeter.postShotCorrectionEv(TtlMeter.Reading(195, 200, 1f), 0f), 0.01f)
        // 累积到上限后不再同向加（防失控）
        assertEquals(0f, TtlMeter.postShotCorrectionEv(TtlMeter.Reading(255, 255, 60f), -TtlMeter.EXT_CORR_LIMIT_EV), 0.01f)
        assertEquals(0f, TtlMeter.postShotCorrectionEv(TtlMeter.Reading(25, 30, 0f), TtlMeter.EXT_CORR_LIMIT_EV), 0.01f)
    }

    @Test
    fun `换场景检测距离突变1_6倍清修正 根治移动拍摄越拍越黑`() {
        // 这就是 11-22 失败的真机场景：0.5m→2m（4倍）→ 必须清掉旧修正。
        assertTrue(TtlMeter.isSceneChange(0.5f, 2.0f))
        assertTrue(TtlMeter.isSceneChange(2.0f, 0.5f))
        // 同一距离轻微抖动（1.1倍）不清
        assertFalse(TtlMeter.isSceneChange(1.0f, 1.1f))
        // 无效距离不触发（AF 读不到时不做判断）
        assertFalse(TtlMeter.isSceneChange(0f, 2.0f))

        // 端到端：上一张 2m 过曝累积了 -2EV 修正，换到 0.5m → 修正清零、只用新距离先验
        val r = TtlMeter.solveExternal(
            distanceM = 0.5f, lastDistanceM = 2.0f, corrEv = -2f,
            reading = TtlMeter.Reading(130, 150, 2f)
        )
        assertTrue("换场景必须丢弃上一场的修正", r.sceneChanged)
        assertEquals(0f, r.baseCorrAfterSceneChange, 0.01f)
        // 0.5m 距离因子 0.25，新修正很小 ⇒ gainFactor 约 0.25 量级，不会卡在 -2EV 的全黑
        assertTrue("新场景 gainFactor 应回到 0.25 量级而非被旧 -2EV 压死",
            r.gainFactor > 0.15f && r.gainFactor < 0.5f)
    }

    @Test
    fun `每张独立 相同距离连拍收敛而不同距离互不污染`() {
        // 同一距离 1m：首张偏暗读数 90 → +0.5EV，第二张读数接近目标 → 收敛
        val first = TtlMeter.solveExternal(1f, 1f, 0f, TtlMeter.Reading(90, 110, 0f))
        assertTrue("偏暗要往上调", first.corrEv > 0f)
        val second = TtlMeter.solveExternal(1f, 1f, first.corrEv, TtlMeter.Reading(190, 200, 1f))
        assertTrue("接近目标时不再大幅修正", kotlin.math.abs(second.correctionAppliedEv) < TtlMeter.EXT_STEP_EV)
        // 换到 2m：距离因子立刻变 4 倍（远距补光），且不受 1m 的 corrEv 污染
        val far = TtlMeter.solveExternal(2f, 1f, first.corrEv, null)
        assertTrue("换距离必须清零旧修正", far.sceneChanged)
        assertEquals(0f, far.corrEv, 0.01f)
        assertEquals(4f, far.gainFactor, 0.01f)
    }


    // ============ 灯侧 TTL 功率档求解（11-22 抓到 a0 01 功率帧后）============

    @Test
    fun `灯侧功率初值随距离平方变化 近距降功率远距升功率`() {
        // 抓包真值：arg 0=1/1, 10=1/2, ... 70=1/128；ref=1m 用 arg=10 留余量
        assertEquals(10, TtlMeter.flashPowerForDistance(1.0f))
        // 0.5m：距离平方 1/4 → 光照 4x → 降 2 档 → arg=30（1/8）
        assertEquals(30, TtlMeter.flashPowerForDistance(0.5f))
        // 2m：距离平方 4x → 光照 1/4 → 升 2 档 → arg=0（1/1 满功率，触顶）
        assertEquals(0, TtlMeter.flashPowerForDistance(2.0f))
        // 无效距离回退 ref
        assertEquals(10, TtlMeter.flashPowerForDistance(0f))
    }

    @Test
    fun `拍后功率微调偏亮降功率偏暗升功率每步一档`() {
        // p50=255（过曝白板）→ 降功率 → arg 增大 +10
        assertEquals(TtlMeter.POWER_ARG_STEP, TtlMeter.flashPowerAdjust(255))
        // p50=25（欠曝）→ 升功率 → arg 减小 -10
        assertEquals(-TtlMeter.POWER_ARG_STEP, TtlMeter.flashPowerAdjust(25))
        // 目标附近不动
        assertEquals(0, TtlMeter.flashPowerAdjust(195))
    }

    @Test
    fun `功率帧a0_01真值CRC8与抓包7帧全吻合`() {
        // 抓包实证的 7 帧（iPhone 官方 App 手动档拨功率）
        val captured = mapOf(
            0x00 to "A0010032", 0x07 to "A00107B1", 0x0A to "A0010A4C",
            0x14 to "A00114CE", 0x1E to "A0011EB0", 0x3C to "A0013C2F",
            0x46 to "A00146A9"
        )
        for ((arg, hex) in captured) {
            val f = com.filmcam.app.flash.A2ShortPower.frame(arg)
            assertEquals("arg=$arg 帧不匹配", hex, f.joinToString("") { "%02X".format(it.toInt() and 0xFF) })
        }
        // ev↔档位换算：降 2EV = arg 20（1/4）
        assertEquals(20, com.filmcam.app.flash.A2ShortPower.evToPowerByte(2f))
    }
}
