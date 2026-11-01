package com.filmcam.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
 * 过卷动画：**胶卷固定在正中央不动，靠表面纹理循环流过表现转动**——
 * 胶卷本体不位移、不旋转；表面叠加细密的胶片条纹沿横向匀速流过并无缝循环，
 * 像卷轴在不停转动时表面纹路被拉着走。
 */
class FilmReelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dstRect = RectF()

    private var icon: Bitmap? = null

    private var scroll = 0f  // 表面纹理累计位移（px）
    private var speed = 0f   // 纹理流速（px/s）

    private var animator: ValueAnimator? = null
    private var lastFrameMs = 0L

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
        val cx = w / 2f
        val cy = h / 2f

        paint.alpha = 255
        dstRect.set(cx - fw / 2f, cy - fh / 2f, cx + fw / 2f, cy + fh / 2f)
        canvas.drawBitmap(b, null, dstRect, paint)

        // 表面纹理：细密的胶片条纹沿表面横向循环流过（图标本体不动）
        val left = cx - fw / 2f
        val top = cy - fh / 2f
        val period = (fw / 4f).coerceAtLeast(6f)
        val off = scroll % period
        canvas.save()
        canvas.clipRect(left + fw * 0.07f, top + fh * 0.07f, left + fw * 0.93f, top + fh * 0.93f)
        val count = (fw / period).toInt() + 2
        for (i in -1..count) {
            val x = left + off + i * period
            paint.color = Color.WHITE
            paint.alpha = 34
            canvas.drawRect(x, top, x + period * 0.20f, top + fh, paint)
            paint.color = Color.BLACK
            paint.alpha = 26
            canvas.drawRect(x + period * 0.55f, top, x + period * 0.75f, top + fh, paint)
        }
        canvas.restore()
        paint.alpha = 255
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
     * 开始过片。@param pxPerSecond 表面纹理流动速度（像素/秒，向右）。
     */
    fun startSpinning(pxPerSecond: Float) {
        stopSpinning()
        speed = pxPerSecond
        applyBlur((abs(pxPerSecond) / 300f).coerceIn(0f, 1.6f))
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
                scroll = (scroll + pxPerSecond * dt) % 100000f
                invalidate()
            }
            start()
        }
    }

    fun stopSpinning() {
        animator?.cancel()
        animator = null
        speed = 0f
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
