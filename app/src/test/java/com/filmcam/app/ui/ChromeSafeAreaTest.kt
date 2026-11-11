package com.filmcam.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 机身安全区的几何契约。基准数据来自 S25U 真机 diag（1080x2340 @2.625 的 FHD+ 模式，
 * 手势导航）：contentRectFor 给出 top=363.6 / bottom=1803.6，sb=96/39 px。
 * 这组值算出的结果必须与 XML + v54 inset 补偿逐像素相同（否则会动到刚标定的铭牌）。
 */
class ChromeSafeAreaTest {

    private fun input(
        w: Int, h: Int, density: Float,
        top: Float, bottom: Float,
        sbTopDp: Float, sbBottomDp: Float,
        topBarContentDp: Float = 74f,
        barDp: Float = 72f,
        logoDp: Float = 22f,
    ) = ChromeSafeArea.Input(
        rootW = w, rootH = h, density = density,
        contentLeft = 0f, contentTop = top,
        contentRight = w.toFloat(), contentBottom = bottom,
        sbTop = (sbTopDp * density).toInt(),
        sbBottom = (sbBottomDp * density).toInt(),
        topBarContentH = (topBarContentDp * density).toInt(),
        barH = (barDp * density).toInt(),
        logoH = (logoDp * density).toInt(),
    )

    @Test
    fun `基准机型 S25U 逐像素等价现有布局`() {
        val d = 2.625f
        val g = ChromeSafeArea.solve(
            ChromeSafeArea.Input(
                rootW = 1080, rootH = 2340, density = d,
                contentLeft = 0f, contentTop = 363.6f,
                contentRight = 1080f, contentBottom = 1803.6f,
                sbTop = 96, sbBottom = 39,
                topBarContentH = (74f * d).toInt(),
                barH = (72f * d).toInt(),
                logoH = (22f * d).toInt(),
            )
        )
        assertTrue(g.applicable)
        // XML 16/48/150dp + v54 的 inset 补偿，真机实测值
        assertEquals(138, g.topPaddingPx)
        assertEquals(165, g.barMarginPx)
        assertEquals(433, g.logoMarginPx)
        assertEquals(1f, g.widthScale, 1e-6f)
    }

    @Test
    fun `三键导航机型铭牌与底栏都不越进预览画面`() {
        // 小米 17 1220x2656 @3.0，状态栏 40dp、导航栏 48dp
        val i = input(1220, 2656, 3.0f, 417.05f, 2043.7f, 40f, 48f)
        val g = ChromeSafeArea.solve(i)
        val botGap = i.rootH - i.contentBottom
        assertTrue("底栏越界", g.barMarginPx + i.barH <= botGap)
        assertTrue("铭牌越界", g.logoMarginPx + i.logoH <= botGap)
        assertTrue("顶栏越界", g.topPaddingPx + i.topBarContentH <= i.contentTop)
        assertTrue("铭牌应仍在底栏上方", g.logoMarginPx > g.barMarginPx + i.barH)
        assertEquals(1f, g.widthScale, 1e-6f)
    }

    @Test
    fun `小屏不越界且顶栏按比例收窄`() {
        val i = input(1080, 2340, 2.75f, 362.7f, 1802.7f, 32f, 24f)
        val g = ChromeSafeArea.solve(i)
        val botGap = i.rootH - i.contentBottom
        assertTrue(g.barMarginPx + i.barH <= botGap)
        assertTrue(g.logoMarginPx + i.logoH <= botGap)
        assertTrue("窄屏应收窄顶栏，实际 ${g.widthScale}", g.widthScale < 1f)
        assertTrue(g.widthScale >= 0.78f)
    }

    @Test
    fun `极端短屏不产生负值或负 margin`() {
        val i = input(1080, 1920, 3.0f, 153.6f, 1593.6f, 24f, 24f)
        val g = ChromeSafeArea.solve(i)
        assertTrue(g.topPaddingPx >= 0)
        assertTrue(g.barMarginPx >= i.sbBottom)
        assertTrue(g.logoMarginPx >= i.sbBottom)
        assertTrue(g.barMarginPx + i.barH <= i.rootH - i.contentBottom)
        assertTrue(g.logoMarginPx + i.logoH <= i.rootH - i.contentBottom)
    }

    @Test
    fun `横屏布局不介入`() {
        // 横屏内容满高、左右留白：content.width() 明显小于 rootW
        val g = ChromeSafeArea.solve(
            input(3120, 1440, 3.5f, 0f, 1440f, 32f, 24f)
                .copy(contentLeft = 600f, contentRight = 2520f)
        )
        assertFalse(g.applicable)
        assertEquals(1f, g.widthScale, 1e-6f)
    }

    @Test
    fun `零尺寸与非法密度安全返回`() {
        assertFalse(ChromeSafeArea.solve(input(0, 0, 3f, 0f, 0f, 24f, 24f)).applicable)
        assertFalse(ChromeSafeArea.solve(input(1080, 2340, 0f, 0f, 100f, 24f, 24f)).applicable)
    }

    // ---- v71：液晶屏宽度自适应（vivo X300 Ultra 横/竖屏溢出） ----
    // 数值来自 tmp/sample_1109/vivo_x300ultra_chrome_overlap_research.md 的定量复算。
    // 契约：vivo 两个朝向都要夹紧；S25U 两个朝向必须保持设计值（基准机逐像素零变化）。

    @Test
    fun `vivo 竖屏液晶屏收窄到设置键完整`() {
        // 1260x2772 @3.5 / 360dp / widthScale 0.8911
        // sidePad 50px、keyGap 75px、keyW 168px → 顶栏可用 1160px、按键组 654px、净空 14px
        val design = 536                       // round(172dp * 0.8911 * 3.5)
        val available = 1160 - 654 - 14
        val w = ChromeSafeArea.fitLcdWidth(design, available)
        assertEquals(492, w)
        assertTrue("vivo 竖屏必须夹紧", w < design)
        assertTrue("夹紧后整行放得下，设置键不被裁", w + 654 <= 1160)
    }

    @Test
    fun `S25U 竖屏保持设计值零变化`() {
        // 1080x2340 @2.625 / 411dp / widthScale 1.0
        // sidePad 42、keyGap 63、keyW 126 → 可用 996、按键组 504、净空 11
        val design = 452                       // round(172dp * 2.625)
        val available = 996 - 504 - 11
        assertEquals(design, ChromeSafeArea.fitLcdWidth(design, available))
    }

    @Test
    fun `vivo 横屏液晶屏不压预览框`() {
        // 2772x1260：4:3 内容宽 1680、左右黑边 546；paddingStart 63、净空 14
        val design = 518                       // 148dp * 3.5
        val available = 546 - 63 - 14
        val w = ChromeSafeArea.fitLcdWidth(design, available)
        assertEquals(469, w)
        assertTrue("液晶屏右边缘不得越过内容区左边界", 63 + w <= 546)
    }

    @Test
    fun `S25U 横屏保持 148dp 零变化`() {
        // 2340x1080 @2.625：黑边 450、paddingStart 47.25、净空 10.5
        val design = 389                       // round(148dp * 2.625)
        val available = (450f - 47.25f - 10.5f).toInt()
        assertEquals(design, ChromeSafeArea.fitLcdWidth(design, available))
    }

    @Test
    fun `极窄屏按 0_78 下限夹紧且非法输入安全`() {
        assertEquals(418, ChromeSafeArea.fitLcdWidth(536, -500))   // 536 * 0.78 = 418
        assertEquals(0, ChromeSafeArea.fitLcdWidth(0, 1000))
        assertEquals(0, ChromeSafeArea.fitLcdWidth(-10, 1000))
    }
}
