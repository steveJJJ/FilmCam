package app.spektralab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v50 JPEG 输入侧反 ISP 归一化：守的是「按帧自适应、不认机型」这条契约，
 * 以及默认关闭时与旧版逐像素一致。纯 JVM，不依赖 android.graphics.Bitmap。
 */
class JpegNormalizerTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `中性灰统计为零饱和`() {
        val px = IntArray(100) { argb(128, 128, 128) }
        val s = JpegNormalizer.statsFrom(px)
        assertEquals(100, s.samples)
        assertEquals(0f, s.hi, 1e-6f)
        assertEquals(0f, s.sMean, 1e-6f)
    }

    @Test
    fun `高饱和像素占比按 S 大于 0_4 计`() {
        // 纯红 (255,0,0) L=0.5 S=1；纯灰 L=0.5 S=0
        val px = IntArray(100) { i -> if (i < 30) argb(255, 0, 0) else argb(128, 128, 128) }
        val s = JpegNormalizer.statsFrom(px)
        assertEquals(100, s.samples)
        assertEquals(0.30f, s.hi, 1e-6f)
    }

    @Test
    fun `极暗极亮像素不参与统计`() {
        val px = IntArray(100) { i ->
            when {
                i < 40 -> argb(0, 0, 0)
                i < 80 -> argb(255, 255, 255)
                else -> argb(255, 0, 0)
            }
        }
        val s = JpegNormalizer.statsFrom(px)
        assertEquals(20, s.samples)
        assertEquals(1f, s.hi, 1e-6f)
    }

    @Test
    fun `全黑或全白返回空统计并给出 0 强度`() {
        val s = JpegNormalizer.statsFrom(IntArray(50) { argb(0, 0, 0) })
        assertEquals(0, s.samples)
        assertEquals(0f, JpegNormalizer.suggestStrength(s), 1e-6f)
    }

    @Test
    fun `达到线性底片目标时完全不校正`() {
        val s = JpegNormalizer.Stats(
            hi = JpegNormalizer.TARGET_HI,
            sMean = 0.20f,
            darkRatio = 0.10f,
            samples = 1000,
        )
        assertEquals(0f, JpegNormalizer.suggestStrength(s), 1e-6f)
    }

    @Test
    fun `典型已调味输入给出满档建议`() {
        val s = JpegNormalizer.Stats(
            hi = JpegNormalizer.REF_HI,
            sMean = 0.21f,
            darkRatio = 0.10f,
            samples = 1000,
        )
        assertEquals(1f, JpegNormalizer.suggestStrength(s), 1e-6f)
    }

    @Test
    fun `App 整图口径下典型已调味输入接近满档`() {
        // v50 初版把阈值按「中心裁切口径」设成 0.10/0.185，真机上 hi 只有 0.062~0.066
        // → 自适应恒为 0、校正从未施加。这里守住口径修正后的结果。
        val s = JpegNormalizer.Stats(hi = 0.066f, sMean = 0.16f, darkRatio = 0.24f, samples = 1000)
        val k = JpegNormalizer.suggestStrength(s)
        assertTrue("典型浓郁输入应接近满档，实际 $k", k > 0.8f)
    }

    @Test
    fun `平淡输入完全不校正`() {
        // 三星相机 P3 JPG 整图口径实测 hi≈0.019 / sMean≈0.089
        val s = JpegNormalizer.Stats(hi = 0.019f, sMean = 0.089f, darkRatio = 0.01f, samples = 1000)
        assertEquals(0f, JpegNormalizer.suggestStrength(s), 1e-6f)
    }

    @Test
    fun `强度恒在 0 到 1 之间`() {
        for (hi in listOf(-1f, 0f, 0.05f, 0.10f, 0.185f, 0.5f, 2f)) {
            val s = JpegNormalizer.Stats(hi, 0.2f, 0.1f, 1000)
            val k = JpegNormalizer.suggestStrength(s)
            assertTrue("hi=$hi 越界: $k", k >= 0f && k <= JpegNormalizer.MAX_STRENGTH)
        }
    }

    @Test
    fun `夜景与近单色场景自动减弱`() {
        val base = JpegNormalizer.Stats(JpegNormalizer.REF_HI, 0.21f, 0.10f, 1000)
        val full = JpegNormalizer.suggestStrength(base)
        val night = JpegNormalizer.suggestStrength(base.copy(darkRatio = 0.80f))
        val flat = JpegNormalizer.suggestStrength(base.copy(sMean = 0.02f))
        assertTrue("夜景应减弱", night < full)
        assertTrue("近单色应减弱", flat < full)
        assertTrue("夜景减弱幅度应为 35%", Math.abs(night - full * 0.35f) < 1e-5f)
    }

    @Test
    fun `对比压缩表在 0 时严格恒等`() {
        val t = JpegNormalizer.contrastLut(0f)
        for (v in 0..255) assertEquals(v, t[v])
    }

    @Test
    fun `对比压缩表单调且中灰不动`() {
        val t = JpegNormalizer.contrastLut(JpegNormalizer.MAX_CONTRAST_REDUCTION)
        assertEquals(128, t[128])
        for (v in 1..255) assertTrue("码值 $v 出现反转", t[v] >= t[v - 1])
        assertTrue("黑位应向中灰抬", t[0] > 0)
        assertTrue("白位应向中灰压", t[255] < 255)
    }

    @Test
    fun `色度缩放在中性灰上严格恒等`() {
        val v = 0.42f
        val y = 0.2126f * v + 0.7152f * v + 0.0722f * v
        for (keep in listOf(1f, 0.8f, 0.65f)) {
            assertEquals(v, JpegNormalizer.chromaChannel(v, y, keep), 1e-6f)
        }
    }

    @Test
    fun `色度缩放把偏离亮度的通道拉回且结果被夹在 0 到 1`() {
        val lum = 0.5f
        val out = JpegNormalizer.chromaChannel(1f, lum, 0.65f)
        assertTrue(out < 1f)
        assertTrue(out > lum)
        assertEquals(0f, JpegNormalizer.chromaChannel(-3f, lum, 2f), 1e-6f)
        assertEquals(1f, JpegNormalizer.chromaChannel(3f, lum, 2f), 1e-6f)
    }

    @Test
    fun `v52 码值域去饱和严格保持 HLS 亮度`() {
        // v50/v51 在线性域做，纯红 L 会 0.500 -> 0.686、窗外亮灰 0.843 -> 1.000，
        // 真机满档成片因此发白、高光烧白。这里守住亮度守恒。
        val keep = 1f - JpegNormalizer.MAX_CHROMA_REDUCTION
        val samples = listOf(
            Triple(255, 0, 0),      // 纯红
            Triple(200, 60, 50),    // 钢铁侠红
            Triple(220, 170, 140),  // 肤色
            Triple(210, 215, 220),  // 窗外亮灰
            Triple(128, 128, 128),  // 中性灰
        )
        for ((r, g, b) in samples) {
            val before = (maxOf(r, g, b) + minOf(r, g, b)) * 0.5f
            val l = JpegNormalizer.hlsL(r, g, b)
            val r1 = JpegNormalizer.desaturateChannel(r, l, keep)
            val g1 = JpegNormalizer.desaturateChannel(g, l, keep)
            val b1 = JpegNormalizer.desaturateChannel(b, l, keep)
            val after = (maxOf(r1, g1, b1) + minOf(r1, g1, b1)) * 0.5f
            assertEquals("像素 ($r,$g,$b) 亮度漂移", before, after, 1.5f)
        }
    }

    @Test
    fun `v52 去饱和确实降低饱和且不烧白`() {
        val keep = 1f - JpegNormalizer.MAX_CHROMA_REDUCTION
        val l = JpegNormalizer.hlsL(255, 0, 0)
        val r1 = JpegNormalizer.desaturateChannel(255, l, keep)
        val g1 = JpegNormalizer.desaturateChannel(0, l, keep)
        val b1 = JpegNormalizer.desaturateChannel(0, l, keep)
        assertTrue("纯红应被去饱和", maxOf(r1, g1, b1) - minOf(r1, g1, b1) < 255)
        assertTrue("不该出现新的纯白", maxOf(r1, g1, b1) < 255)

        val lw = JpegNormalizer.hlsL(210, 215, 220)
        listOf(210, 215, 220).forEach { c ->
            assertTrue("亮灰不该被抬到纯白", JpegNormalizer.desaturateChannel(c, lw, keep) < 255)
        }
    }

    @Test
    fun `曝光表在 0EV 严格恒等`() {
        val t = InputExposure.lut(0.0)
        for (v in 0..255) assertEquals(v, t[v])
    }

    @Test
    fun `v73 默认滑杆是 70 且可完全关闭`() {
        assertEquals(0.7f, JpegNormalizer.DEFAULT_STRENGTH, 1e-6f)
        assertTrue(JpegNormalizer.DEFAULT_STRENGTH > 0f)
        assertTrue(JpegNormalizer.DEFAULT_STRENGTH < JpegNormalizer.MAX_STRENGTH)
        // v73：满档天花板由 35% 提到 45%，但不得再往上加（11-08 真机满档已偏淡偏亮）
        assertEquals(0.45f, JpegNormalizer.MAX_CHROMA_REDUCTION, 1e-6f)
        assertTrue(JpegNormalizer.MAX_CHROMA_REDUCTION <= 0.45f)
        // 0 必须严格回退到 v49 行为
        assertEquals(0f, JpegNormalizer.effectiveStrength(1f, 0f), 1e-6f)
    }

    @Test
    fun `v53 整卷统一强度只由基准和滑杆决定`() {
        val baseline = 0.961f
        val k = JpegNormalizer.effectiveStrength(baseline, JpegNormalizer.DEFAULT_STRENGTH)
        assertTrue("默认档应落在中偏上强度，实际 $k", k > 0.6f && k < 0.75f)
        // 逐帧自适应本身确实会波动（同场景实测 hi 0.055~0.072），这正是 v53 要消除的
        val low = JpegNormalizer.suggestStrength(JpegNormalizer.Stats(0.055f, 0.2f, 0.23f, 1000))
        val high = JpegNormalizer.suggestStrength(JpegNormalizer.Stats(0.072f, 0.2f, 0.22f, 1000))
        assertTrue("逐帧自适应应存在明显波动", high - low > 0.2f)
        // 但整卷统一后，无论后续帧长什么样，强度都是同一个 k
        for (hi in listOf(0.02f, 0.055f, 0.072f, 0.15f)) {
            assertEquals(
                "整卷基准不应随帧统计变化 (hi=$hi)",
                k, JpegNormalizer.effectiveStrength(baseline, JpegNormalizer.DEFAULT_STRENGTH), 1e-6f
            )
        }
        assertEquals(baseline, JpegNormalizer.effectiveStrength(baseline, 1f), 1e-6f)
    }

    @Test
    fun `v53 平淡画面在默认档仍完全不校正`() {
        val flat = JpegNormalizer.Stats(0.019f, 0.089f, 0.01f, 1000)
        val baseline = JpegNormalizer.suggestStrength(flat)
        assertEquals(0f, JpegNormalizer.effectiveStrength(baseline, JpegNormalizer.DEFAULT_STRENGTH), 1e-6f)
    }
}
