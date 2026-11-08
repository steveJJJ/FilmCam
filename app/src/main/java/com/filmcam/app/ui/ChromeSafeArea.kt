package com.filmcam.app.ui

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * 机身 UI 安全区（v58）：让机顶 / 机身控件始终落在「预览内容之外的机身留白」上。
 *
 * ## 为什么需要它
 * [com.filmcam.app.R.layout.activity_main] 里 `brandLogo` 150dp、`bottomBar` 48dp、
 * `topBar` paddingTop 16dp、液晶屏 172dp 都是**在 S25U（1440x3120 @3.5、19.5:9、
 * 手势导航）这一台机器上目视标定**的固定值。而留白高度不是常数：它由
 * [ViewfinderFrameView.contentRectFor] 按屏幕比例算出，v54 起还会把系统栏 inset
 * 累加到 margin 上。于是遇到下面任何一条，控件就会越过留白压到预览画面上：
 *  - 屏幕短一点（16:9 / 20:9 之外的比例）→ 留白变矮
 *  - 三键导航 / 挖孔大状态栏 → inset 变大，margin 被顶上去
 *  - 「显示大小」调大、density 变化 → 固定 dp 换算出的 px 超出留白
 *  - 窄屏 → 顶栏固定宽 396dp 直接横向溢出
 *
 * ## 做法
 * 以 S25U 真机的**可用留白**（顶 102dp = 138.5dp 留白 − 36.6dp 状态栏；
 * 底 189dp = 204.3dp 留白 − 14.9dp 手势栏）为设计基准，运行时按实际可用留白等比
 * 缩放三个固定 dp，再夹紧到「控件顶边不得越过 content.bottom / 底边不得越过
 * content.top」。基准机型算出的 k=1，结果与现有 XML + inset 补偿**逐像素相同**，
 * 所以不会动到刚标定好的铭牌位置。屏幕过窄时只按 widthDp 缩液晶屏与按键间距，
 * 48dp 触控目标保持不变。
 *
 * 横屏是另一套 `layout-land` 布局（左右侧栏），几何不同且主拍摄方向敏感，
 * 这里明确不介入（`applicable=false`），避免把横屏改回归。
 *
 * 只处理 FrameLayout 子项；与 [EdgeToEdge] 的 inset 补偿叠加时按「后写、幂等」收敛。
 */
object ChromeSafeArea {

    /**
     * 设计基准（S25U 真机实测 1080x2340 @2.625，不是标称 1440x3120）：
     * 顶部留白 138.5dp − 状态栏 36.6dp = 102dp 可用；
     * 底部留白 204.3dp − 手势导航栏 14.9dp = 189dp 可用。
     * XML 的 16 / 48 / 150dp 就是在这台机器这组值上标定的，用它当基准
     * 才能保证「正常机型 k=1、一个像素都不改」。
     */
    private const val DESIGN_TOP_USABLE_DP = 102f
    private const val DESIGN_BOTTOM_USABLE_DP = 189f

    private const val DESIGN_TOP_PADDING_DP = 16f
    private const val DESIGN_BAR_MARGIN_DP = 48f
    private const val DESIGN_LOGO_MARGIN_DP = 150f

    /** 顶栏固定宽 396dp + 8dp 最小余量；低于它就按比例收窄。 */
    private const val DESIGN_WIDTH_DP = 404f
    private const val DESIGN_LCD_DP = 172f
    private const val DESIGN_KEY_GAP_DP = 24f
    private const val DESIGN_SIDE_PADDING_DP = 16f

    /** 控件与画面之间至少留的净空（dp）。 */
    private const val MIN_GAP_DP = 6f

    private const val MIN_SCALE = 0.50f
    private const val MIN_WIDTH_SCALE = 0.78f

    data class Input(
        val rootW: Int,
        val rootH: Int,
        val density: Float,
        /** 预览内容矩形，拆成纯数值以便 JVM 单测（RectF 在非 Robolectric 单测里是空壳）。 */
        val contentLeft: Float,
        val contentTop: Float,
        val contentRight: Float,
        val contentBottom: Float,
        val sbTop: Int,
        val sbBottom: Int,
        /** 顶栏去掉 paddingTop 之后的内容高度（px）。 */
        val topBarContentH: Int,
        val barH: Int,
        val logoH: Int,
    )

    data class Geometry(
        /** 竖屏才介入；横屏 false 表示保持 XML 原样。 */
        val applicable: Boolean,
        val topPaddingPx: Int = 0,
        val barMarginPx: Int = 0,
        val logoMarginPx: Int = 0,
        /** 顶栏横向缩放，1 = 不改。 */
        val widthScale: Float = 1f,
    )

    /** 纯计算：输入几何，输出目标像素值。可单测、不依赖 Android View。 */
    fun solve(i: Input): Geometry {
        if (i.rootW <= 0 || i.rootH <= 0 || i.density <= 0f) return Geometry(false)
        // 内容满宽 = 上下留白的竖屏布局；横屏内容满高、左右留白，布局完全不同。
        if (i.contentRight - i.contentLeft < i.rootW - 2f) return Geometry(false)

        val gap = MIN_GAP_DP * i.density
        val topGap = i.contentTop.coerceIn(0f, i.rootH.toFloat())
        val botGap = (i.rootH - i.contentBottom).coerceIn(0f, i.rootH.toFloat())
        val topUsable = (topGap - i.sbTop).coerceAtLeast(0f)
        val botUsable = (botGap - i.sbBottom).coerceAtLeast(0f)

        val kt = (topUsable / (DESIGN_TOP_USABLE_DP * i.density)).coerceIn(MIN_SCALE, 1f)
        val kb = (botUsable / (DESIGN_BOTTOM_USABLE_DP * i.density)).coerceIn(MIN_SCALE, 1f)

        // 顶栏：paddingTop 既要让出状态栏，又不能把液晶屏/功能键推进画面
        val maxPad = (topGap - i.topBarContentH - gap).toInt().coerceAtLeast(0)
        val topPadding = (i.sbTop + (DESIGN_TOP_PADDING_DP * kt * i.density).roundToInt())
            .coerceIn(0, maxPad)

        // 底栏：margin + 自身高度 + 净空 <= 底部留白
        val maxBar = (botGap - i.barH - gap).toInt().coerceAtLeast(i.sbBottom)
        val barMargin = (i.sbBottom + (DESIGN_BAR_MARGIN_DP * kb * i.density).roundToInt())
            .coerceIn(i.sbBottom, maxBar)

        // 铭牌：在底栏上方，同时不越过 content.bottom
        val maxLogo = (botGap - i.logoH - gap).toInt().coerceAtLeast(i.sbBottom)
        val wantLogo = i.sbBottom + (DESIGN_LOGO_MARGIN_DP * kb * i.density).roundToInt()
        val aboveBar = barMargin + i.barH + gap.roundToInt()
        val logoMargin = maxOf(wantLogo, aboveBar).coerceAtMost(maxLogo)
            .coerceAtLeast(i.sbBottom)

        val widthScale = (i.rootW / i.density / DESIGN_WIDTH_DP)
            .coerceIn(MIN_WIDTH_SCALE, 1f)

        return Geometry(true, topPadding, barMargin, logoMargin, widthScale)
    }

    /**
     * 把 [solve] 的结果写进布局。只在值变化时写，避免 layout 回调自激循环。
     * @return 是否发生了改动
     */
    fun apply(
        root: View,
        topBar: View,
        bottomBar: View,
        brandLogo: View,
        lcd: View?,
        evKey: View?,
        settingsKey: View?,
        content: RectF,
        sbTop: Int,
        sbBottom: Int,
    ): Boolean {
        val density = root.resources.displayMetrics.density
        val topBarContentH = topBar.height - topBar.paddingTop
        val barH = bottomBar.height
        val logoH = brandLogo.height
        // 首帧还没测量完，等下一次 layout 回调；不能拿 0 高度去算（会把控件顶飞）
        if (topBarContentH <= 0 || barH <= 0 || logoH <= 0) return false

        val g = solve(
            Input(
                root.width, root.height, density,
                content.left, content.top, content.right, content.bottom,
                sbTop, sbBottom, topBarContentH, barH, logoH
            )
        )
        if (!g.applicable) return false

        val scale = g.widthScale
        val sidePad = (DESIGN_SIDE_PADDING_DP * scale * density).roundToInt()
        val wantLeft = if (scale < 1f) sidePad else topBar.paddingLeft
        var changed = false

        if (topBar.paddingTop != g.topPaddingPx ||
            topBar.paddingLeft != wantLeft || topBar.paddingRight != wantLeft
        ) {
            topBar.setPadding(
                wantLeft, g.topPaddingPx, wantLeft, topBar.paddingBottom
            )
            changed = true
        }

        val barLp = bottomBar.layoutParams as? FrameLayout.LayoutParams
        if (barLp != null && barLp.bottomMargin != g.barMarginPx) {
            barLp.bottomMargin = g.barMarginPx
            bottomBar.layoutParams = barLp
            changed = true
        }
        val logoLp = brandLogo.layoutParams as? FrameLayout.LayoutParams
        if (logoLp != null && logoLp.bottomMargin != g.logoMarginPx) {
            logoLp.bottomMargin = g.logoMarginPx
            brandLogo.layoutParams = logoLp
            changed = true
        }

        if (scale < 1f) {
            val lcdW = (DESIGN_LCD_DP * scale * density).roundToInt()
            lcd?.layoutParams?.let { lp ->
                if (lp.width != lcdW) {
                    lp.width = lcdW
                    lcd.layoutParams = lp
                    changed = true
                }
            }
            val keyGap = (DESIGN_KEY_GAP_DP * scale * density).roundToInt()
            listOfNotNull(evKey, settingsKey).forEach { v ->
                val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
                if (lp.marginStart != keyGap) {
                    lp.marginStart = keyGap
                    v.layoutParams = lp
                    changed = true
                }
            }
        }
        return changed
    }
}
