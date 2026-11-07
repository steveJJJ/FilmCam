package app.spektralab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v33「设备定标」的纯函数契约。
 *
 * 这条链把「引擎逐张猜曝光」换成「同帧 ISP JPEG 报曝光」：目标分位来自
 * 三星 ISP 的 JPEG，候选分位来自逐档渲出的 RAW 小图，选最接近的一档
 * print_exposure。这里守住分位计算、选优权重、参数注入与跳过条件，
 * 保证失败时能干净回落到 v32 参数链。
 */
class IspGuideTest {

    private val base = """{"film_stock":"kodak_gold_200",""" +
        """"print_exposure":0.83,"grain_amount":1.0,""" +
        """"input_cctf_decoding":true,"auto_exposure_method":"balanced"}"""

    @Test
    fun `直方图分位数按累计桶计算`() {
        val hist = IntArray(256).also { for (i in 0..99) it[i] = 1 }
        val (p50, p90) = SpektraProcessor.luminancePercentiles(hist)
        assertEquals(50.0, p50, 1e-6)
        assertEquals(90.0, p90, 1e-6)
    }

    @Test
    fun `空直方图与非法长度返回零不炸`() {
        assertEquals(0.0 to 0.0, SpektraProcessor.luminancePercentiles(IntArray(256)))
        assertEquals(0.0 to 0.0, SpektraProcessor.luminancePercentiles(IntArray(10)))
    }

    @Test
    fun `选最接近目标分位的一档且 p90 参与约束`() {
        val target = 100.0 to 180.0
        val points = listOf(
            SpektraProcessor.IspGuidePoint(-1.0, 60.0, 120.0),
            SpektraProcessor.IspGuidePoint(0.0, 95.0, 175.0),
            SpektraProcessor.IspGuidePoint(1.0, 130.0, 210.0),
        )
        assertEquals(0.0, SpektraProcessor.pickIspGuideEv(target, points)!!, 1e-9)

        // p50 打平的时候，高光更接近目标的那一档胜出（防白墙被推到发白）
        val tie = listOf(
            SpektraProcessor.IspGuidePoint(-0.5, 100.0, 240.0),
            SpektraProcessor.IspGuidePoint(0.5, 100.0, 182.0),
        )
        assertEquals(0.5, SpektraProcessor.pickIspGuideEv(target, tie)!!, 1e-9)
    }

    @Test
    fun `候选全渲染失败时返回 null 让调用侧回落 v32`() {
        assertNull(SpektraProcessor.pickIspGuideEv(100.0 to 180.0, emptyList()))
    }

    @Test
    fun `基础放大机曝光基线可读且缺键兜底`() {
        assertEquals(0.83, SpektraProcessor.basePrintExposure(base), 1e-9)
        assertEquals(0.83, SpektraProcessor.basePrintExposure("{}"), 1e-9)
    }

    @Test
    fun `定标参数固定引擎曝光并只写一次 print_exposure`() {
        val out = SpektraProcessor.withIspGuidedExposure(base, -0.25)
        val o = org.json.JSONObject(out)
        assertFalse(o.getBoolean("auto_exposure"))
        assertEquals(0.58, o.getDouble("print_exposure"), 1e-9)
        assertEquals(1, Regex("\"print_exposure\"").findAll(out).count())
        assertEquals(1, Regex("\"auto_exposure\"(?!_)").findAll(out).count())
        // v32 的测光方式保留（AE 关掉时不生效），原基础串其它键不动
        assertEquals("balanced", o.getString("auto_exposure_method"))
        assertEquals(1.0, o.getDouble("grain_amount"), 1e-9)
        assertTrue(o.getBoolean("input_cctf_decoding"))
    }

    @Test
    fun `EV 偏移被钳制在引擎接受的正值范围内`() {
        val hi = org.json.JSONObject(SpektraProcessor.withIspGuidedExposure(base, 100.0))
        val lo = org.json.JSONObject(SpektraProcessor.withIspGuidedExposure(base, -100.0))
        assertEquals(6.0, hi.getDouble("print_exposure"), 1e-9)
        // v33 的 FAIL 根因：print_exposure 为负会被引擎整帧拒掉
        assertEquals(0.05, lo.getDouble("print_exposure"), 1e-9)
    }

    @Test
    fun `目标分位被钳制以挡住过曝的 ISP 参考`() {
        val normal = SpektraProcessor.clampIspTarget(103.3, 200.0)
        assertEquals(103.3, normal.first, 1e-9)
        assertEquals(200.0, normal.second, 1e-9)
        // v33 #2：同帧 JPEG 自己全白，照抄就把成片推到 meanL 171
        val blown = SpektraProcessor.clampIspTarget(202.8, 255.6)
        assertEquals(150.0, blown.first, 1e-9)
        assertEquals(230.0, blown.second, 1e-9)
        val dark = SpektraProcessor.clampIspTarget(12.0, 60.0)
        assertEquals(40.0, dark.first, 1e-9)
        assertEquals(100.0, dark.second, 1e-9)
    }

    @Test
    fun `相邻档之间按 p50 插值出精确 EV`() {
        val points = listOf(
            SpektraProcessor.IspGuidePoint(-0.5, 200.0, 215.0),
            SpektraProcessor.IspGuidePoint(-0.25, 140.0, 212.0),
            SpektraProcessor.IspGuidePoint(0.0, 50.0, 100.0),
            SpektraProcessor.IspGuidePoint(0.25, 20.0, 60.0),
        )
        // target 103 落在 140→50 之间：t=(103-140)/(50-140)=0.411
        val ev = SpektraProcessor.interpolateIspGuideEv(103.0, points)!!
        assertTrue(ev > -0.25 && ev < 0.0)
        assertEquals(-0.25 + ((103.0 - 140.0) / (50.0 - 140.0)) * 0.25, ev, 1e-9)
        // 超出覆盖范围时退化为最接近的一档，不炸
        assertEquals(-0.5, SpektraProcessor.interpolateIspGuideEv(240.0, points)!!, 1e-9)
        assertNull(SpektraProcessor.interpolateIspGuideEv(100.0, emptyList()))
    }

    @Test
    fun `直闪帧与热关开关都不跑引导`() {
        assertTrue(SpektraProcessor.shouldUseIspGuide(isFlash = false, guideEnabled = true))
        assertFalse(SpektraProcessor.shouldUseIspGuide(isFlash = true, guideEnabled = true))
        assertFalse(SpektraProcessor.shouldUseIspGuide(isFlash = false, guideEnabled = false))
    }

    @Test
    fun `候选档位恒正加密且含零档`() {
        val offsets = SpektraProcessor.ISP_GUIDE_EV_OFFSETS
        assertEquals(5, offsets.size)
        assertTrue(offsets.any { it == 0.0 })
        assertEquals(-0.5, offsets.first(), 1e-9)
        assertEquals(0.5, offsets.last(), 1e-9)
        assertEquals(0.25, offsets[1] - offsets[0], 1e-9)
        // 每档落到 print_exposure 都必须是正数，负值会被引擎整帧拒掉（v33 FAIL）
        offsets.forEach { assertTrue(0.83 + it > 0.0) }
    }
}
