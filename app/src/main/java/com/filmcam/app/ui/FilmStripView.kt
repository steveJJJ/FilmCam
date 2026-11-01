package com.filmcam.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs

/**
 * 过卷动画的「胶卷」：屏幕上始终只有**一个**胶卷图标，它一边自转（像卷轴被
 * 马达带着转）一边横向掠过，并按滚动速度实时加运动模糊——
 * 旧版是图标原地 360° 空转（没有位移也没有模糊，看起来像加载圈），
 * 更早的一版把胶卷平铺成一条会同时露出 3~4 个，也不像"一卷在过片"。
 *
 * 动效全部内聚在视图里：调用方只需 setIcon + start/stopScrolling。
 */
class FilmStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private var icon: Bitmap? = null

    /** 累计滚动距离（px），只增不减，绘制时按行程取模循环 */
    private var scroll = 0f

    private var animator: ValueAnimator? = null
    private var lastFrameMs = 0L

    override fun onDraw(canvas: Canvas) {
        val b = icon ?: return
        if (width <= 0 || height <= 0) return

        val tile = height.toFloat()
        // 一个完整行程：从右边缘外进场 → 完全移出左边缘，保证任意时刻最多一卷可见
        val travel = width + tile
        val x = width - (scroll % travel)

        // 滚动 = 平移 + 自转（周长 π*d，走多远转多少，像卷轴滚过去）
        val angle = (scroll % travel) / tile * (360f / Math.PI.toFloat())

        val save = canvas.save()
        matrix.reset()
        matrix.postRotate(angle, tile / 2f, tile / 2f)
        matrix.postTranslate(x, 0f)
        canvas.concat(matrix)
        // 进出场时轻微淡入淡出，避免在屏幕边缘生硬地"啪"一下出现/消失
        val edge = tile * 0.35f
        val alpha = when {
            x > width - edge -> ((width - x) / edge).coerceIn(0f, 1f)
            x < -tile + edge -> ((x + tile) / edge).coerceIn(0f, 1f)
            else -> 1f
        }
        paint.alpha = (alpha * 255).toInt()
        canvas.drawBitmap(b, null, android.graphics.RectF(0f, 0f, tile, tile), paint)
        canvas.restoreToCount(save)
    }

    fun setIcon(resId: Int) {
        icon = BitmapFactory.decodeResource(resources, resId)
        invalidate()
    }

    /**
     * 开始滚动。@param pxPerSecond 横向速度（像素/秒），
     * 速度越快运动模糊越强（2~12px 线性映射，仅 Android 12+ 有真实模糊）。
     */
    fun startScrolling(pxPerSecond: Float) {
        stopScrolling()
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
                android.graphics.RenderEffect.createBlurEffect(
                    radius, radius, android.graphics.Shader.TileMode.CLAMP
                )
            } else {
                null
            }
        )
    }
}
