package app.spektralab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v40 片框印刷字颜色恢复：纯函数部分在 JVM 上守护。
 * 核心是「只依赖像素自身 RGB 的确定性映射」，不统计、不 CDF、不参考图。
 */
class FrameBorderLockTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun rgb(p: Int): Triple<Int, Int, Int> = Triple(
        (p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF
    )

    @Test
    fun `竖拍去饱和印刷字被拉回标准橙`() {
        // 真机竖拍左右带实测 (200,161,111) → 标准浓度附近 (200,132,73)
        val out = rgb(FrameBorderLock.restoreArgb(argb(200, 161, 111)))
        assertEquals(Triple(200, 132, 73), out)
    }

    @Test
    fun `横拍标准浓度橙字完全不动`() {
        // (193,125,66) 正是 ORANGE_RG=68 / ORANGE_RB=127 的来源
        val p = argb(193, 125, 66)
        assertEquals(p, FrameBorderLock.restoreArgb(p))
    }

    @Test
    fun `白齿孔黑片基与亮中性一律不动`() {
        listOf(
            argb(250, 250, 250),   // 白齿孔
            argb(8, 12, 14),       // 黑片基
            argb(180, 180, 180),   // 亮中性（含小图插值灰边）
            argb(208, 205, 200),   // 齿孔边缘
        ).forEach { assertEquals(it, FrameBorderLock.restoreArgb(it)) }
    }

    @Test
    fun `同色输入永远同色输出且只改 GB 不动 R`() {
        // 密度（R）必须保持，否则会像 v37 那样把片基染红
        for (g in 100..170 step 10) {
            val p = argb(200, g, 90)
            val q = rgb(FrameBorderLock.restoreArgb(p))
            assertEquals(200, q.first)
            assertEquals(Triple(200, 132, 73), q)
            // 确定性：同一个像素反复映射结果不变
            assertEquals(q, rgb(FrameBorderLock.restoreArgb(p)))
        }
    }

    @Test
    fun `片框带只覆盖上下齿孔带与左右边，画面区不命中`() {
        val w = 1000
        val h = 800
        val topPx = (0.141f * h).toInt()
        val bottomPx = (0.866f * h).toInt()
        assertTrue(FrameBorderLock.isFrameBand(0, 400, w, h))
        assertTrue(FrameBorderLock.isFrameBand(999, 400, w, h))
        assertTrue(FrameBorderLock.isFrameBand(500, 0, w, h))
        assertTrue(FrameBorderLock.isFrameBand(500, h - 1, w, h))
        assertTrue(FrameBorderLock.isFrameBand(500, topPx - 1, w, h))
        // 0.866*800 = 692.8，取整后的 692 仍属画面，693 才进底部带
        assertTrue(FrameBorderLock.isFrameBand(500, bottomPx + 1, w, h))
        assertFalse(FrameBorderLock.isFrameBand(500, 400, w, h))
        assertFalse(FrameBorderLock.isFrameBand(500, topPx + 1, w, h))
        assertFalse(FrameBorderLock.isFrameBand(500, bottomPx, w, h))
        assertFalse(FrameBorderLock.isFrameBand(0, 0, 0, 0))
    }

    @Test
    fun `只有片框开着且热关没放才生效`() {
        val on = """{"overscan_active":true,"print_exposure":0.83}"""
        val off = """{"overscan_active":false}"""
        assertTrue(FrameBorderLock.shouldApply(on, false))
        assertFalse(FrameBorderLock.shouldApply(on, true))
        assertFalse(FrameBorderLock.shouldApply(off, false))
        assertFalse(FrameBorderLock.shouldApply("not json", false))
        assertFalse(FrameBorderLock.shouldApply("{}", false))
    }
}
