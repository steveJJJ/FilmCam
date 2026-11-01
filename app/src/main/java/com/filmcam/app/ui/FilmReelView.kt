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
 * 过卷动画：**一个胶卷，在原地循环转动**——
 * 图标固定在正中央（不平移），绕自身中心匀速转一圈又一圈，
 * 身后带角度残影（动态模糊），像卷轴被马达带着转。
 */
class FilmReelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dstRect = RectF()

    private var icon: Bitmap? = null

    private var angle = 0f   // 当前转角（deg）
    private var omega = 0f   // 角速度（deg/s）

    private var animator: ValueAnimator? = null
    private var lastFrameMs = 0L

    override fun onDraw(canvas: Canvas) {
        val b = icon
        val w = width.toFloat()
        val h = height.toFloat()
        if (b == null || w <= 4f || h <= 4f) return

        // 图标内接在视图里：转满一圈也不会被裁掉
        val size = min(w, h) * 0.82f
        val s = min(size / b.width.toFloat(), size / b.height.toFloat())
        val fw = b.width * s
        val fh = b.height * s
        val cx = w / 2f
        val cy = h / 2f

        // 动态模糊：转得越快，身后拖的角度越长
        val ghost = (abs(omega) / 26f).coerceIn(0f, 30f)

        for (i in 4 downTo 1) {
            paint.alpha = (74 - i * 14).coerceIn(12, 255)
            drawRotated(canvas, b, cx, cy, fw, fh, angle - ghost * i / 4f)
        }
        paint.alpha = 255
        drawRotated(canvas, b, cx, cy, fw, fh, angle)
    }

    private fun drawRotated(
        canvas: Canvas, b: Bitmap, cx: Float, cy: Float, fw: Float, fh: Float, deg: Float
    ) {
        canvas.save()
        canvas.rotate(deg, cx, cy)
        dstRect.set(cx - fw / 2f, cy - fh / 2f, cx + fw / 2f, cy + fh / 2f)
        canvas.drawBitmap(b, null, dstRect, paint)
        canvas.restore()
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
     * 开始过片。@param degPerSecond 胶卷转动角速度（度/秒，>0 顺时针）。
     */
    fun startSpinning(degPerSecond: Float) {
        stopSpinning()
        omega = degPerSecond
        applyBlur((abs(degPerSecond) / 420f).coerceIn(0f, 2.5f))
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
                angle = (angle + degPerSecond * dt) % 360f
                invalidate()
            }
            start()
        }
    }

    fun stopSpinning() {
        animator?.cancel()
        animator = null
        omega = 0f
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
