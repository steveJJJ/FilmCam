package com.filmcam.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.min

/**
 * 过卷动画：**胶卷一直往前走，走出画面就从后面接上，循环不停**——
 * 动画区只有一个胶卷大小的小窗口，胶卷以原样（不旋转不缩放）匀速向前（向右）通过，
 * 走出右缘立刻从左缘重新进场，永远只有一个胶卷在走；身后带残影和轻模糊。
 */
class FilmReelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dstRect = RectF()

    private var icon: Bitmap? = null

    /** 累计进度，单位＝周期（1.0 = 胶卷走完一趟并回到起点） */
    private var flow = 0f
    private var laps = 0f   // 每秒走几趟

    private var animator: ValueAnimator? = null
    private var lastFrameMs = 0L

    override fun onDraw(canvas: Canvas) {
        val b = icon
        val w = width.toFloat()
        val h = height.toFloat()
        if (b == null || w <= 4f || h <= 4f) return

        // 一个胶卷标准大小，放进这个小窗口
        val size = min(w, h) * 0.78f
        val s = min(size / b.width.toFloat(), size / b.height.toFloat())
        val fw = b.width * s
        val fh = b.height * s
        val top = (h - fh) / 2f

        // 胶卷一个紧挨一个排成队列（略微重叠，彻底没有缝隙），
        // 整体匀速平移：窗口里永远填着胶卷，看不到接头和空档。
        val step = fw * 0.94f
        var x = -step + (flow % 1f) * step
        while (x < w) {
            paint.alpha = 255
            dstRect.set(x, top, x + fw, top + fh)
            canvas.drawBitmap(b, null, dstRect, paint)
            x += step
        }
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
     * 开始过片。@param lapsPerSecond 每秒走几趟（1.0 = 一秒走完一个来回）。
     */
    fun startSpinning(lapsPerSecond: Float) {
        stopSpinning()
        laps = lapsPerSecond
        applyBlur((abs(lapsPerSecond) * 0.7f).coerceIn(0f, 2f))
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
                flow = (flow + lapsPerSecond * dt) % 1f
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
