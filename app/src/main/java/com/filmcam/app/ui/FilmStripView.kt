package com.filmcam.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs

/**
 * 过卷动画的「胶卷条」：把当前胶卷图标横向平铺成一条胶带，
 * [startScrolling] 驱动胶带匀速滚动，滚动速度越快运动模糊越强——
 * 取代旧版"图标原地 360° 旋转"的过卷表现，更接近真实胶片被马达
 * 拖过片门的观感（胶片是平移着走过去的，不是转着走的）。
 *
 * 滚动与模糊全部内聚在视图里：调用方只需 setIcon + start/stop。
 */
class FilmStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var icon: Bitmap? = null

    /** 滚动偏移（px），只增不减，绘制时按周期取模循环 */
    private var scroll = 0f

    private var animator: ValueAnimator? = null
    private var lastFrameMs = 0L

    /** 相邻两卷之间的空隙（相对胶卷宽度） */
    private val gapFrac = 0.22f

    /** 当前滚动速度（px/s），用于计算模糊半径 */
    private var speed = 0f

    override fun onDraw(canvas: Canvas) {
        val b = icon ?: return
        if (width <= 0 || height <= 0) return

        val tileH = height.toFloat()
        val tileW = tileH * b.width / b.height.toFloat()
        val period = tileW * (1f + gapFrac)
        if (period <= 0f) return

        // 起点落在 (-period, 0]，保证覆盖整个可视宽度；scroll 增大 → 画面左移（胶片前进方向）
        var x = -(scroll % period) - period
        val dst = RectF()
        while (x < width) {
            dst.set(x, 0f, x + tileW, tileH)
            canvas.drawBitmap(b, null, dst, paint)
            x += period
        }
    }

    fun setIcon(resId: Int) {
        icon = BitmapFactory.decodeResource(resources, resId)
        invalidate()
    }

    /**
     * 开始匀速滚动。@param pxPerSecond 胶带速度（像素/秒），
     * 速度越快运动模糊越强（2~12px 线性映射，仅 Android 12+ 有真实模糊）。
     */
    fun startScrolling(pxPerSecond: Float) {
        stopScrolling()
        speed = pxPerSecond
        applyBlur(blurFor(pxPerSecond))
        lastFrameMs = 0L
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
                if (lastFrameMs == 0L) lastFrameMs = now
                val dt = (now - lastFrameMs) / 1000f
                lastFrameMs = now
                scroll += pxPerSecond * dt
                invalidate()
            }
            start()
        }
    }

    fun stopScrolling() {
        animator?.cancel()
        animator = null
        applyBlur(0f)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopScrolling()
    }

    private fun blurFor(pxPerSecond: Float): Float =
        (abs(pxPerSecond) / 60f).coerceIn(2f, 12f)

    private fun applyBlur(radius: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        setRenderEffect(
            if (radius > 0.5f) {
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            } else {
                null
            }
        )
    }
}
