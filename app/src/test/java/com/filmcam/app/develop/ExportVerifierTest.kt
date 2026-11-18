package com.filmcam.app.develop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导出验真纯算法契约（移植自同引擎参照 App）。 */
class ExportVerifierTest {

    @Test
    fun `NCC 相同序列为1 反相关为负1`() {
        val a = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(1.0, ExportVerifier.ncc(a, a.copyOf()), 1e-9)
        val inv = doubleArrayOf(5.0, 4.0, 3.0, 2.0, 1.0)
        assertEquals(-1.0, ExportVerifier.ncc(a, inv), 1e-9)
    }

    @Test
    fun `NCC 无关噪声接近0 判为花屏`() {
        val a = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0)
        val noise = doubleArrayOf(8.0, 1.0, 6.0, 3.0, 4.0, 7.0, 2.0, 5.0)
        val score = ExportVerifier.ncc(a, noise)
        assertTrue("score=$score", score < ExportVerifier.NCC_MIN)
        assertTrue(ExportVerifier.isCorruptByNcc(score))
        assertFalse(ExportVerifier.isCorruptByNcc(0.97))
    }

    @Test
    fun `NCC 常量序列或空序列返回-1`() {
        assertEquals(-1.0, ExportVerifier.ncc(doubleArrayOf(), doubleArrayOf()), 1e-9)
        assertEquals(-1.0, ExportVerifier.ncc(doubleArrayOf(5.0, 5.0, 5.0), doubleArrayOf(1.0, 2.0, 3.0)), 1e-9)
    }

    @Test
    fun `X Y 对比度 正常图判null`() {
        val w = 64; val h = 64
        val gray = IntArray(w * h) { (it % w) }
        assertNull(ExportVerifier.contrastRatioVerdict(gray, w, h))
    }

    @Test
    fun `X Y 对比度 横向剧烈纵向平缓判异常`() {
        val w = 64; val h = 64
        // x 方向 alternation 强，y 方向只做缓慢渐变（dy>0 但很小）→ ratio 超上限
        // 注意：y 完全恒定时参照算法 dy==0 会直接返回 null（不判），故这里给微弱纵向变化
        val gray = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            (if (x % 2 == 0) 0 else 255) + y / 8
        }
        val verdict = ExportVerifier.contrastRatioVerdict(gray, w, h)
        assertTrue("verdict=$verdict", verdict != null && verdict.contains("X/Y contrast"))
    }

    @Test
    fun `X Y 对比度 小图与空数据判null`() {
        assertNull(ExportVerifier.contrastRatioVerdict(IntArray(0), 10, 10))
        assertNull(ExportVerifier.contrastRatioVerdict(IntArray(32 * 32), 32, 32))
    }

    @Test
    fun `ARGB 转灰度 与参照公式一致`() {
        assertEquals(100, ExportVerifier.argbToGray(0xFF646464.toInt()))
        assertEquals(0, ExportVerifier.argbToGray(0xFF000000.toInt()))
        assertEquals(255, ExportVerifier.argbToGray(0xFFFFFFFF.toInt()))
    }
}
