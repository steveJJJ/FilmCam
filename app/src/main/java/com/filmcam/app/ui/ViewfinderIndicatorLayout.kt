package com.filmcam.app.ui

/**
 * v68：取景器 ⑤ EV ± / ⑦ 闪光指示的避让几何（**纯 Kotlin，零 Android 依赖**）。
 *
 * ## 根因（11-10 小米 15 横屏实证，见 tmp/sample_1109/xiaomi15_landscape_research.md）
 * EV 列写死 `colLeft = cl + cw*0.085`、`colBase = cb - ch*0.28`；而两层取景角标
 * 按**片门宽 gw** 内收（`pInset = gw*0.135`）。横屏 gw 从竖屏 960 涨到 1600（1.67×），
 * 角标横向右移 216px，EV 只右移 136px 且字号也随之变大 → 所有横屏下 `+3±` 都压在
 * 内侧视差小角标上（小米 15 重叠 33×15px、S25U 30×13px、X300 Pro 34×16px），
 * 竖屏刚好错开。与 ChromeSafeArea / layout-land 无关。
 *
 * ## 解法（不猜参数）
 * 把两层角标的 8 个角包围盒算出来，只在**当前位置真的相交**时才挪：
 *  1. 优先横向把整段文字挪到相交角标左侧，留 [CLEARANCE_DP] 净空、夹紧到内容左边界；
 *  2. 横向放不下才纵向抬到角标横臂之上；
 *  3. 都不行取重叠面积最小的候选。
 * 不相交就原样返回 —— **基准机竖屏逐像素零变化**（这是硬约束，别改成「一律重排」）。
 *
 * ⚠️ 纯 JVM 单测里 `android.graphics.RectF` 是空壳（v58 教训），所以几何入参
 * 全部拆成 Float，不碰任何 Android 类。
 */
object ViewfinderIndicatorLayout {

    const val CLEARANCE_DP = 6f
    const val MIN_MARGIN_DP = 4f

    /** 与 ViewfinderFrameView.onDraw 同源的两套比例，调用方传入以免两处漂移 */
    const val EV_LEFT_FRACTION = 0.085f
    const val EV_BASE_FRACTION = 0.28f

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        /** 带净空的相交判定：把对方矩形四周各撑开 pad */
        fun hits(o: Box, pad: Float): Boolean =
            left < o.right + pad && right > o.left - pad &&
                top < o.bottom + pad && bottom > o.top - pad

        fun overlapArea(o: Box): Float {
            val w = minOf(right, o.right) - maxOf(left, o.left)
            val h = minOf(bottom, o.bottom) - maxOf(top, o.top)
            return if (w > 0f && h > 0f) w * h else 0f
        }
    }

    data class Placement(val colLeft: Float, val colBase: Float, val moved: Boolean)

    /** 一层取景亮框的四个角包围盒（角标是 L 形，用其外接正方形做保守避让） */
    fun cornerBoxes(
        left: Float, top: Float, right: Float, bottom: Float, len: Float
    ): List<Box> = listOf(
        Box(left, top, left + len, top + len),
        Box(right - len, top, right, top + len),
        Box(left, bottom - len, left + len, bottom),
        Box(right - len, bottom - len, right, bottom)
    )

    /**
     * @param cl/ct/cr/cb 预览内容矩形
     * @param gl/gt/gr/gb 片门矩形（亮框基准）
     * @param textWidth `measureText("+3") + 3*density + measureText("±")`
     * @param textAscent / textDescent Paint.fontMetrics 值（ascent 为负）
     */
    fun solve(
        cl: Float, ct: Float, cr: Float, cb: Float,
        gl: Float, gt: Float, gr: Float, gb: Float,
        textWidth: Float, textAscent: Float, textDescent: Float, density: Float,
        bracketInset: Float, bracketLen: Float,
        parallaxInset: Float, parallaxLen: Float
    ): Placement {
        val cw = cr - cl
        val ch = cb - ct
        if (cw <= 0f || ch <= 0f || textWidth <= 0f) {
            return Placement(cl + cw * EV_LEFT_FRACTION, cb - ch * EV_BASE_FRACTION, false)
        }
        val gw = gr - gl
        val clear = CLEARANCE_DP * density
        val minLeft = cl + MIN_MARGIN_DP * density
        val minTop = ct + MIN_MARGIN_DP * density

        val bIn = gw * bracketInset
        val pIn = gw * parallaxInset
        val obstacles = cornerBoxes(gl + bIn, gt + bIn, gr - bIn, gb - bIn, gw * bracketLen) +
            cornerBoxes(gl + pIn, gt + pIn, gr - pIn, gb - pIn, gw * parallaxLen)

        val colLeft0 = cl + cw * EV_LEFT_FRACTION
        val colBase0 = cb - ch * EV_BASE_FRACTION
        fun evBox(left: Float, base: Float) =
            Box(left, base + textAscent, left + textWidth, base + textDescent)

        val original = evBox(colLeft0, colBase0)
        val hits = obstacles.filter { original.hits(it, clear) }
        if (hits.isEmpty()) return Placement(colLeft0, colBase0, false)

        // ① 横向避让：整段文字退到所有相交角标左侧
        val allowedRight = hits.minOf { it.left - clear }
        val leftB = allowedRight - textWidth
        val boxB = evBox(leftB, colBase0)
        if (leftB >= minLeft && obstacles.none { boxB.hits(it, clear) }) {
            return Placement(leftB, colBase0, true)
        }

        // ② 纵向避让：抬到相交角标横臂之上
        val allowedBottom = hits.minOf { it.top - clear }
        val baseC = allowedBottom - textDescent
        val boxC = evBox(colLeft0, baseC)
        if (baseC + textAscent >= minTop && obstacles.none { boxC.hits(it, clear) }) {
            return Placement(colLeft0, baseC, true)
        }

        // ③ 极端比例兜底：取重叠面积更小者，且夹紧到内容边界
        val areaB = obstacles.sumOf { boxB.overlapArea(it).toDouble() }.toFloat()
        val areaC = obstacles.sumOf { boxC.overlapArea(it).toDouble() }.toFloat()
        return if (areaB <= areaC) {
            Placement(maxOf(leftB, minLeft), colBase0, true)
        } else {
            Placement(colLeft0, maxOf(baseC, minTop - textAscent), true)
        }
    }
}
