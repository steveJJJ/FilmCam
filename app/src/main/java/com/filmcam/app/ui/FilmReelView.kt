package com.filmcam.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * 过卷动画：**胶卷固定在正中，封面画面像水波一样从左向右流动**——
 * 图标不位移、不旋转；用 drawBitmapMesh 把封面按列做正弦剪切波，
 * 波形随时间向右传播，看起来整个画面在不停流动（转动感）。
 * 速度为 0 时波幅归零，画面就是静止原图。
 */
class FilmReelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var icon: Bitmap? = null

    /** 累计流动量，单位＝图标宽（波形传播距离） */
    private var flow = 0f
    private var laps = 0f   // 每秒流动几个图标宽

    private var animator: ValueAnimator? = null
    private var lastFrameMs = 0L

    private val meshCols = 28
    private val verts = FloatArray((meshCols + 1) * 2 * 2)

    override fun onDraw(canvas: Canvas) {
        val b = icon
        val w = width.toFloat()
        val h = height.toFloat()
        if (b == null || w <= 4f || h <= 4f) return

        // 胶卷本体：固定在正中，永不位移、不旋转
        val size = min(w, h) * 0.72f
        val s = min(size / b.width.toFloat(), size / b.height.toFloat())
        val fw = b.width * s
        val fh = b.height * s
        val left = (w - fw) / 2f
        val top = (h - fh) / 2f

        // 波幅随速度增大：流得越快波动越明显
        val amp = (fh * 0.055f * (abs(laps) / 1f)).coerceAtMost(fh * 0.10f)
        val waves = 1.6f   // 一个图标宽度里放的波数

        var idx = 0
        for (row in 0..1) {
            val yBase = top + row * fh
            for (col in 0..meshCols) {
                val x = left + col * fw / meshCols
                val phase = ((x - left) / fw - flow) * waves * 2.0 * Math.PI
                val dy = (sin(phase) * amp).toFloat()
                verts[idx++] = x
                verts[idx++] = yBase + dy
            }
        }
        canvas.drawBitmapMesh(b, meshCols, 1, verts, 0, null, 0, paint)
    }

    fun setIcon(resId: Int) {
        icon = try {
            BitmapFactory.decodeResource(resources, resId)
        } catch (_: Exception) {
            null
        }
        invalidate()
    }

    /**
     * 开始过片。@param lapsPerSecond 波形每秒传播几个图标宽（1.0 = 一卷/秒）。
     */
    fun startSpinning(lapsPerSecond: Float) {
        stopSpinning()
        laps = lapsPerSecond
        applyBlur((abs(lapsPerSecond) * 0.8f).coerceIn(0f, 1.8f))
        lastFrameMs = 0L
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
                if (lastFrameMs == 0L) lastFrameMs = now
                val dt = ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
                lastFrameMs = now
                flow = (flow + lapsPerSecond * dt) % 10f
                invalidate()
            }
            start()
        }
    }

    fun stopSpinning() {
        animator?.cancel()
        animator = null
        laps = 0f
        applyBlur(0f)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopSpinning()
    }

    private fun applyBlur(radius: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        setRenderEffect(
            if (radius > 0.3f) {
                android.graphics.RenderEffect.createBlurEffect(
                    radius, radius, Shader.TileMode.CLAMP
                )
            } else {
                null
            }
        )
    }
}
