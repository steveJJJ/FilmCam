package com.filmcam.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v68：横屏 EV `+3±` 压内侧视差小角标的回归测试。
 *
 * 复刻 ViewfinderFrameView.contentRectFor / gateRectFor 的**纯 Float** 几何
 * （JVM 单测里 RectF 是空壳，v58 教训），四组机型的数字来自
 * `tmp/sample_1109/xiaomi15_landscape_research.md` 的实测复算：
 *  小米15 横 2670×1200 / S25U 横 2340×1080 / X300 Pro 横 2800×1260 / S25U 竖 1080×2340
 */
class ViewfinderIndicatorLayoutTest {

    private data class Geom(
        val cl: Float, val ct: Float, val cr: Float, val cb: Float,
        val gl: Float, val gt: Float, val gr: Float, val gb: Float
    )

    /** 与 production 同源，但只用 Float，不碰 RectF */
    private fun geom(w: Float, h: Float): Geom {
        val ca = if (w >= h) 4f / 3f else 3f / 4f
        val (cl, ct, cr, cb) = if (w / h <= ca) {
            val ch = w / ca
            val cy = h / 2f + ViewfinderFrameView.PREVIEW_OFFSET_Y * ch
            listOf(0f, (cy - ch / 2f).coerceIn(0f, h), w, (cy + ch / 2f).coerceIn(0f, h))
        } else {
            val cw = h * ca
            val cx = w / 2f
            listOf((cx - cw / 2f).coerceIn(0f, w), 0f, (cx + cw / 2f).coerceIn(0f, w), h)
        }
        val gateAspect = if (w >= h) 3f / 2f else 2f / 3f
        val cw = cr - cl
        val ch = cb - ct
        val gw: Float
        val gh: Float
        if (cw / ch >= gateAspect) {
            gh = ch; gw = ch * gateAspect
        } else {
            gw = cw; gh = cw / gateAspect
        }
        val cx = (cl + cr) / 2f
        val cy = (ct + cb) / 2f
        return Geom(cl, ct, cr, cb, cx - gw / 2f, cy - gh / 2f, cx + gw / 2f, cy + gh / 2f)
    }

    /** 复刻真机字体度量：textSize = iconSize*1.6，"+3±" 实测约 1.85×textSize */
    private data class Text(val width: Float, val ascent: Float, val descent: Float)

    private fun textOf(g: Geom): Text {
        val icon = minOf(g.cr - g.cl, g.cb - g.ct) * 0.032f
        val size = icon * 1.6f
        return Text(size * 1.85f, -0.8f * size, 0.2f * size)
    }

    private fun obstacles(g: Geom): List<ViewfinderIndicatorLayout.Box> {
        val gw = g.gr - g.gl
        val bIn = gw * 0.055f
        val pIn = gw * 0.135f
        return ViewfinderIndicatorLayout.cornerBoxes(
            g.gl + bIn, g.gt + bIn, g.gr - bIn, g.gb - bIn, gw * 0.075f
        ) + ViewfinderIndicatorLayout.cornerBoxes(
            g.gl + pIn, g.gt + pIn, g.gr - pIn, g.gb - pIn, gw * 0.035f
        )
    }

    private fun original(g: Geom, t: Text): ViewfinderIndicatorLayout.Box {
        val left = g.cl + (g.cr - g.cl) * ViewfinderIndicatorLayout.EV_LEFT_FRACTION
        val base = g.cb - (g.cb - g.ct) * ViewfinderIndicatorLayout.EV_BASE_FRACTION
        return ViewfinderIndicatorLayout.Box(left, base + t.ascent, left + t.width, base + t.descent)
    }

    private fun solve(g: Geom, t: Text, density: Float) =
        ViewfinderIndicatorLayout.solve(
            g.cl, g.ct, g.cr, g.cb, g.gl, g.gt, g.gr, g.gb,
            t.width, t.ascent, t.descent, density,
            0.055f, 0.075f, 0.135f, 0.035f
        )

    private fun placedBox(p: ViewfinderIndicatorLayout.Placement, t: Text) =
        ViewfinderIndicatorLayout.Box(
            p.colLeft, p.colBase + t.ascent, p.colLeft + t.width, p.colBase + t.descent
        )

    private data class Device(val w: Float, val h: Float, val density: Float)

    private val xiaomi15 = Device(2670f, 1200f, 3.0f)
    private val s25uLand = Device(2340f, 1080f, 2.625f)
    private val x300ProLand = Device(2800f, 1260f, 3.0f)
    private val s25uPortrait = Device(1080f, 2340f, 2.625f)

    @Test
    fun `取证基线 原位置在四组机型上确实复现横屏重叠`() {
        listOf(xiaomi15, s25uLand, x300ProLand).forEach { d ->
            val g = geom(d.w, d.h)
            val clear = ViewfinderIndicatorLayout.CLEARANCE_DP * d.density
            assertTrue(
                "${d.w}x${d.h} 应当复现重叠",
                obstacles(g).any { original(g, textOf(g)).hits(it, clear) }
            )
        }
        val gp = geom(s25uPortrait.w, s25uPortrait.h)
        val clear = ViewfinderIndicatorLayout.CLEARANCE_DP * s25uPortrait.density
        assertFalse(
            "竖屏基准不该有重叠",
            obstacles(gp).any { original(gp, textOf(gp)).hits(it, clear) }
        )
    }

    @Test
    fun `竖屏基准逐像素零变化`() {
        val g = geom(s25uPortrait.w, s25uPortrait.h)
        val p = solve(g, textOf(g), s25uPortrait.density)
        assertFalse(p.moved)
        assertEquals(g.cl + (g.cr - g.cl) * 0.085f, p.colLeft, 0.001f)
        assertEquals(g.cb - (g.cb - g.ct) * 0.28f, p.colBase, 0.001f)
    }

    @Test
    fun `四组机型修复后 EV 与两层角标均留出净空`() {
        listOf(xiaomi15, s25uLand, x300ProLand, s25uPortrait).forEach { d ->
            val g = geom(d.w, d.h)
            val t = textOf(g)
            val p = solve(g, t, d.density)
            val clear = ViewfinderIndicatorLayout.CLEARANCE_DP * d.density
            val box = placedBox(p, t)
            assertTrue("${d.w}x${d.h} 文字左边界越界", box.left >= g.cl)
            assertTrue("${d.w}x${d.h} 文字上边界越界", box.top >= g.ct)
            obstacles(g).forEachIndexed { i, o ->
                assertFalse(
                    "${d.w}x${d.h} 第 $i 个角标仍与 EV 相交",
                    box.hits(o, clear)
                )
            }
        }
    }

    @Test
    fun `横屏优先横向避让 基线不跳动`() {
        val g = geom(xiaomi15.w, xiaomi15.h)
        val p = solve(g, textOf(g), xiaomi15.density)
        assertTrue(p.moved)
        // 只左移，不改基线（闪光指示紧随其后，仍能保持上下节奏）
        assertEquals(g.cb - (g.cb - g.ct) * 0.28f, p.colBase, 0.001f)
        assertTrue(p.colLeft < g.cl + (g.cr - g.cl) * 0.085f)
    }

    @Test
    fun `极端比例 21比9 与 4比3 平板不越界不留 NaN`() {
        listOf(Device(3120f, 1300f, 3.0f), Device(1600f, 1200f, 2.0f)).forEach { d ->
            val g = geom(d.w, d.h)
            val t = textOf(g)
            val p = solve(g, t, d.density)
            assertFalse(p.colLeft.isNaN())
            assertFalse(p.colBase.isNaN())
            assertTrue(p.colLeft >= g.cl)
            assertTrue(p.colBase + t.ascent >= g.ct)
            assertTrue(p.colLeft + t.width <= g.cr)
        }
    }
}
