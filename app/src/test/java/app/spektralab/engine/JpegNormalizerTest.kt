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
        val t = JpegNormalizer.contrastLut(0.18f)
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
}
