package com.filmcam.app.develop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白边相框的几何契约（纯 JVM，不碰 Bitmap —— 真机渲染由冲洗验证）。
 */
class WhiteBorderRendererTest {

    @Test
    fun `四边绝对等宽且取短边比例`() {
        // 1440x960 → 短边 960 × 6% = 58px，四边同一个值
        val g = WhiteBorderRenderer.geometry(1440, 960)
        assertEquals(58, g.margin)
        assertEquals(1440 + 2 * 58, g.outW)
        assertEquals(960 + 2 * 58, g.outH)
        // 等宽口径下成片比例会偏离 3:2（这是 A1 与 A2 的区别）
        assertTrue("成片比例应偏离 3:2", Math.abs(g.outW.toFloat() / g.outH - 1.5f) > 0.01f)
    }

    @Test
    fun `竖拍同样按短边取宽度`() {
        // 960x1440 是 2:3 竖帧，短边 960，白边仍是 58px
        val g = WhiteBorderRenderer.geometry(960, 1440)
        assertEquals(58, g.margin)
        assertEquals(960 + 116, g.outW)
        assertEquals(1440 + 116, g.outH)
    }

    @Test
    fun `横竖同尺寸互换时输出也对称`() {
        val a = WhiteBorderRenderer.geometry(1440, 960)
        val b = WhiteBorderRenderer.geometry(960, 1440)
        assertEquals(a.margin, b.margin)
        assertEquals(a.outW, b.outH)
        assertEquals(a.outH, b.outW)
    }

    @Test
    fun `比例可覆盖且被夹到合法区间`() {
        // 0 也会保底 1px，避免 createBitmap 尺寸不变
        assertEquals(1, WhiteBorderRenderer.geometry(1000, 800, 0f).margin)
        assertEquals(200, WhiteBorderRenderer.geometry(1000, 800, 0.25f).margin)
        assertEquals(200, WhiteBorderRenderer.geometry(1000, 800, 5f).margin)
    }

    @Test
    fun `极小画面也不会算出 0 宽边`() {
        val g = WhiteBorderRenderer.geometry(3, 2)
        assertTrue(g.margin >= 1)
        assertEquals(3 + 2 * g.margin, g.outW)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非法尺寸直接拒绝`() {
        WhiteBorderRenderer.geometry(0, 960)
    }

    @Test
    fun `相纸色是暖白且完全不透明`() {
        val c = WhiteBorderRenderer.PAPER_COLOR_ARGB
        assertEquals(0xFF, (c ushr 24) and 0xFF)
        assertEquals(0xF7, (c ushr 16) and 0xFF)
        assertEquals(0xF5, (c ushr 8) and 0xFF)
        assertEquals(0xF0, c and 0xFF)
    }

    @Test
    fun `默认比例是 6 个百分点`() {
        assertEquals(0.06f, WhiteBorderRenderer.BORDER_FRACTION, 1e-7f)
    }
}
