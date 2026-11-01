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
 * 过卷动画：**胶卷（平面，原样不旋转不缩放）固定在正中不动**，
 * 只有身后（左侧）的运动拖影沿「一个图标宽」的轨道由左向右快速流过并循环，
 * 主体始终清晰 —— 用"胶片被不停拉走"的拖影表现过片，胶卷本身不位移。
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
        val s = min(w * 0.34f / b.width.toFloat(), h * 0.58f / b.height.toFloat())
        val fw = b.width * s
        val fh = b.height * s
        val top = (h - fh) / 2f
        val cx = (w - fw) / 2f     // 主体固定在正中，全程不动

        // 身后（左侧）的拖影：多张残影沿「一个图标宽」的轨道由左向右快速流过，
        // 到主体位置就被主体盖掉并从左边重新进入 → 看起来胶片在被不停拉走。
        val ghosts = 5
        for (i in 0 until ghosts) {
            val t = ((scroll / fw) + i.toFloat() / ghosts) % 1f   // 0=最左 1=贴住主体
            val off = (t - 1f) * fw
            paint.alpha = (14f + 40f * t).toInt().coerceIn(0, 255)
            ghostRect.set(cx + off, top, cx + off + fw, top + fh)
            canvas.drawBitmap(b, null, ghostRect, paint)
        }

        paint.alpha = 255
        dstRect.set(cx, top, cx + fw, top + fh)
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
        applyBlur((abs(pxPerSecond) / 420f).coerceIn(0f, 3f))
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
