package com.filmcam.app.develop

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * 暗房显影"液体波动"叠加 View（规格 §6.2）。
 *
 *  - ValueAnimator 无限循环驱动相位偏移
 *  - 几条低透明度正弦波（红光下像显影液）+ 移动光斑
 *  - onDraw 半透明叠在照片上，营造红光安全灯氛围
 *  - onDetachedFromWindow 停动画，避免泄漏
 */
class LiquidWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 90, 60)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(28, 255, 120, 80)
        style = Paint.Style.FILL
    }

    private val wavePath = Path()
    private var phase: Float = 0f

    /** 预计算的 X 坐标采样点（onSizeChanged 时按宽度生成，onDraw 直接用）。 */
    private var xPoints: FloatArray = FloatArray(0)

    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 按 width 生成固定采样点，避免 onDraw 每帧重复算 X 坐标
        val count = if (w > 0) w / 4 + 1 else 0
        xPoints = FloatArray(count) { (it * 4).toFloat() }
    }

    fun start() {
        stop()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                phase = (va.animatedValue as Float) * (2f * Math.PI.toFloat())
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 3 条不同相位/频率的正弦波，低透明度
        val waveCount = 3
        val pts = xPoints
        if (pts.isEmpty()) return
        for (i in 0 until waveCount) {
            val yBase = h * (0.35f + i * 0.18f)
            val amp = h * 0.04f
            val freq = 3.5f + i * 0.8f
            val shift = phase + i * 1.3f
            val twoPiFreq = freq * 2f * Math.PI.toFloat() / w

            wavePath.reset()
            for (j in pts.indices) {
                val xx = pts[j]
                val yy = yBase + sin(xx * twoPiFreq + shift) * amp
                if (j == 0) wavePath.moveTo(xx, yy) else wavePath.lineTo(xx, yy)
            }
            wavePaint.alpha = 50 - i * 8
            canvas.drawPath(wavePath, wavePaint)
        }

        // 移动光斑：一个缓慢横向飘的柔光团
        val spotX = (sin(phase * 0.5f) * 0.5f + 0.5f) * w
        val spotY = h * 0.5f + sin(phase * 0.8f) * h * 0.15f
        canvas.drawCircle(spotX, spotY, w * 0.18f, glowPaint)
    }
}
