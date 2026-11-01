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
 * 过卷动画：**胶卷（平面，原样不旋转不缩放）从左到右走**——
 * 像过片时胶片被拖着往右走：从左边缘外进场 → 匀速向右 → 走出右边缘 → 再从左边进来，
 * 循环不断。全程画面里只有一个胶卷，身后（左侧）带横向拖影（运动模糊）。
 *
 * 走过的版本：图标原地空转 → 单卷横掠+自转 → 双卷轴+胶带 → 端面绕 Z 轴转 →
 * 竖直圆柱绕 Y 轴转 → 平面绕竖直轴翻转。用户要的其实是"胶卷从左到右走"。
 */
class FilmReelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dstRect = RectF()
    private val ghostRect = RectF()

    private var icon: Bitmap? = null

    /** 累计位移（px），只增不减 */
    private var scroll = 0f
    private var speed = 0f   // px/s

    private var animator: ValueAnimator? = null
    private var lastFrameMs = 0L

    override fun onDraw(canvas: Canvas) {
        val b = icon
        val w = width.toFloat()
        val h = height.toFloat()
        if (b == null || w <= 4f || h <= 4f) return

        // 胶卷按自身比例放进视图高度
        val s = min(w * 0.55f / b.width.toFloat(), h * 0.94f / b.height.toFloat())
        val fw = b.width * s
        val fh = b.height * s
        val top = (h - fh) / 2f

        // 一个周期 = 视图宽 + 胶卷宽：走出右边缘的瞬间正好从左边重新进场，
        // 既不会同时出现两个，也不会出现空档。
        val period = w + fw
        val x = -fw + (scroll % period)

        val trail = (abs(speed) / 55f).coerceIn(0f, fw * 0.55f)

        // 身后（左侧）的残影：横向运动模糊
        if (trail > 1.5f) {
            paint.alpha = 40
            ghostRect.set(x - trail * 0.55f, top, x - trail * 0.55f + fw, top + fh)
            canvas.drawBitmap(b, null, ghostRect, paint)
            paint.alpha = 70
            ghostRect.set(x - trail * 0.28f, top, x - trail * 0.28f + fw, top + fh)
            canvas.drawBitmap(b, null, ghostRect, paint)
        }

        paint.alpha = 255
        dstRect.set(x, top, x + fw, top + fh)
        canvas.drawBitmap(b, null, dstRect, paint)
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
     * 开始过片。@param pxPerSecond 胶卷横向移动速度（像素/秒，从左到右），
     * 速度越快拖影越长、整体模糊越强。
     */
    fun startSpinning(pxPerSecond: Float) {
        stopSpinning()
        speed = pxPerSecond
        applyBlur((abs(pxPerSecond) / 700f).coerceIn(0f, 3f))
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
                scroll += pxPerSecond * dt
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
