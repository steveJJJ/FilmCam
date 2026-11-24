package com.filmcam.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 首次使用引导的「点击位置框」：盖在机身最上层，把当前步骤要点控件圈出来。
 *
 * 11-25 二版（用户拍板「提示框太丑」）：从「琥珀方框 + 四角刻线 + 整体闪烁」
 * 改成相机取景式的**聚光**——四周极轻压暗、目标圆角区域挖空透亮，外沿两层
 * 柔光 + 一圈细线，下方挂一枚「2/8 · 点铭牌进 Pro」小胶囊。
 * 动作反馈不靠 LCD 单独承担：提示常驻在液晶屏，框上同步写步骤和动作，
 * 用户不用在两块屏之间猜「我现在到第几步了」。
 *
 * 设计铁律：
 *  · **不拦截触摸**——本 View 不可点击、不 consume 事件，用户照常操作被框出的控件；
 *  · 只借用 LCD 的琥珀 + 机身深灰，不引入第二套颜色语言；
 *  · 呼吸只驱动柔光 alpha，细线和胶囊保持稳定，不会看着像坏屏在闪；
 *  · 目标 View 消失 / 换步时 [clear]，坐标按屏幕位置相减，规避 root 非 (0,0)。
 */
class GuideHighlightView(context: Context) : View(context) {

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x26000000          // 15% 黑：只把注意力往目标上收，不挡取景
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD8A152.toInt()
        style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE6B866.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        alpha = 225
    }
    private val capsulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xED26262A.toInt()
    }
    private val capsuleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66E0A94E
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2DCA8.toInt()
        textSize = 13f * density
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }

    private val box = RectF()
    private val capsule = RectF()
    private var hasTarget = false
    private var alphaMul = 1f
    private var animator: ValueAnimator? = null
    private var caption = ""

    private val density: Float get() = resources.displayMetrics.density

    /** 把框套到某个控件上；target 为 null 等同 clear。 */
    fun setTarget(target: View?, step: Int, total: Int, label: String) {
        if (target == null || !target.isAttachedToWindow) {
            clear()
            return
        }
        target.getLocationOnScreen(tmpTarget)
        getLocationOnScreen(tmpSelf)
        val pad = 7f * density
        box.set(
            tmpTarget[0] - tmpSelf[0] - pad,
            tmpTarget[1] - tmpSelf[1] - pad,
            tmpTarget[0] - tmpSelf[0] + target.width + pad,
            tmpTarget[1] - tmpSelf[1] + target.height + pad,
        )
        applyCaption(step, total, label)
    }

    /**
     * 框一个控件内部的子区域（坐标相对 anchor）——快门刻度列这种「只在右缘一小条」
     * 的目标，框住整个取景窗等于没框。
     */
    fun setTargetRegion(anchor: View?, region: RectF?, step: Int, total: Int, label: String) {
        if (anchor == null || region == null || region.isEmpty || !anchor.isAttachedToWindow) {
            clear()
            return
        }
        anchor.getLocationOnScreen(tmpTarget)
        getLocationOnScreen(tmpSelf)
        val pad = 6f * density
        box.set(
            tmpTarget[0] - tmpSelf[0] + region.left - pad,
            tmpTarget[1] - tmpSelf[1] + region.top - pad,
            tmpTarget[0] - tmpSelf[0] + region.right + pad,
            tmpTarget[1] - tmpSelf[1] + region.bottom + pad,
        )
        applyCaption(step, total, label)
    }

    /** 目标 View 可能延迟布局：post 一帧后再套框。 */
    fun setTargetLater(target: View?, step: Int, total: Int, label: String) {
        if (target == null) { clear(); return }
        target.post { setTarget(target, step, total, label) }
    }

    /** 子区域版：等 anchor 布局完再取 region（旋转后几何会变，必须重取）。 */
    fun setRegionLater(anchor: View?, region: () -> RectF?, step: Int, total: Int, label: String) {
        if (anchor == null) { clear(); return }
        anchor.post {
            if (!isAttachedToWindow || !anchor.isAttachedToWindow) return@post
            setTargetRegion(anchor, region(), step, total, label)
        }
    }

    private fun applyCaption(step: Int, total: Int, label: String) {
        if (box.width() <= 0 || box.height() <= 0) { clear(); return }
        // step<=0：段 3 跨页引导（去暗房）没有八步序号，只挂动作短句
        caption = when {
            label.isBlank() -> "$step/$total"
            step <= 0 -> label
            else -> "$step/$total · $label"
        }
        hasTarget = true
        // 旋转换 chrome 后本 View 可能被新子 View 压到下面，换步时重新提到最上层
        bringToFront()
        startPulse()
        invalidate()
    }

    fun clear() {
        hasTarget = false
        caption = ""
        animator?.cancel()
        animator = null
        alphaMul = 1f
        invalidate()
    }

    private fun startPulse() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0.62f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                alphaMul = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasTarget) return

        val r = 14f * density
        // 1) 四周轻压暗、目标挖空：用 Path 差集，圆角处不会露出方角
        val outer = Path().apply { addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW) }
        val hole = Path().apply { addRoundRect(box, r, r, Path.Direction.CW) }
        outer.op(hole, Path.Op.DIFFERENCE)
        canvas.drawPath(outer, dimPaint)

        // 2) 外柔光：两层递减描边，只有这层随呼吸动
        val g1 = RectF(box.left - 7f * density, box.top - 7f * density,
            box.right + 7f * density, box.bottom + 7f * density)
        haloPaint.strokeWidth = 9f * density
        haloPaint.alpha = (30 * alphaMul).toInt()
        canvas.drawRoundRect(g1, r + 5f * density, r + 5f * density, haloPaint)
        val g2 = RectF(box.left - 3f * density, box.top - 3f * density,
            box.right + 3f * density, box.bottom + 3f * density)
        haloPaint.strokeWidth = 3f * density
        haloPaint.alpha = (85 * alphaMul).toInt()
        canvas.drawRoundRect(g2, r + 2f * density, r + 2f * density, haloPaint)

        // 3) 细线本体：稳定不闪，像取景窗里的对焦框
        canvas.drawRoundRect(box, r, r, linePaint)

        // 4) 步骤胶囊：贴近目标上沿，空间不够就翻到下沿；左右不出屏
        val h = 30f * density
        val tw = textPaint.measureText(caption)
        val w = (tw + 22f * density).coerceAtMost(width - 24f * density)
        val cx = (box.left + box.right) / 2f
        val left = cx - w / 2f
        val top = if (box.top - h - 10f * density >= 8f * density) {
            box.top - h - 10f * density
        } else {
            box.bottom + 10f * density
        }
        capsule.set(
            left.coerceIn(12f * density, width - 12f * density - w),
            top.coerceIn(8f * density, height - h - 8f * density),
            0f, 0f,
        )
        capsule.right = capsule.left + w
        capsule.bottom = capsule.top + h
        canvas.drawRoundRect(capsule, h / 2f, h / 2f, capsulePaint)
        canvas.drawRoundRect(capsule, h / 2f, h / 2f, capsuleEdge)
        val baseline = capsule.centerY() - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2f
        canvas.drawText(caption, capsule.left + 11f * density, baseline, textPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    companion object {
        private val tmpTarget = IntArray(2)
        private val tmpSelf = IntArray(2)
    }
}
