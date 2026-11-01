package com.filmcam.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 过卷动画：**胶卷（平面）绕自身竖直中轴转动**——像一张卡片/一盒胶卷在原地转身。
 *
 * 做法就是平面绕 Y 轴的 2D 投影：宽度按 |cos θ| 伸缩，转到 90°/270°（侧面对着
 * 我们）时收成一条窄线，越过 90° 后水平镜像（转到背面，同时压暗一点）。
 * 横向速度 ∝ |sin θ|·ω，所以在"侧面掠过"的瞬间拖影和模糊最强、正面最清晰，
 * 这正是平面转动该有的运动模糊分布。
 *
 * 之前做过的圆柱贴图版被否（"太丑"）——这一版不加任何立体透视，就是平面在转。
 */
class FilmReelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dstRect = RectF()
    private val ghostRect = RectF()

    private var icon: Bitmap? = null
    private var angle = 0f    // 累计转角（度）
    private var omega = 0f    // 角速度（度/秒）

    private var animator: ValueAnimator? = null
    private var lastFrameMs = 0L

    override fun onDraw(canvas: Canvas) {
        val b = icon
        val w = width.toFloat()
        val h = height.toFloat()
        if (b == null || w <= 4f || h <= 4f) return

        val cx = w / 2f
        val cy = h / 2f
        // 胶卷在视图内的最大内接尺寸（保持自身比例）
        val s = min(w / b.width.toFloat(), h / b.height.toFloat()) * 0.94f
        val fw = b.width * s
        val fh = b.height * s

        val rad = Math.toRadians(angle.toDouble())
        val k = Math.cos(rad).toFloat()        // 正面 +1 / 背面 -1
        val cw = (fw * abs(k)).coerceAtLeast(fw * 0.035f)   // 侧面时留一条窄线
        val left = cx - cw / 2f
        val top = cy - fh / 2f

        // 横向速度 ∝ |sin θ|·ω：侧面掠过时最快 → 拖影最长
        val lateral = abs(Math.sin(rad).toFloat()) * (abs(omega) / 400f).coerceAtMost(1.6f)
        val trail = lateral * fw * 0.10f

        canvas.save()
        if (k < 0) canvas.scale(-1f, 1f, cx, cy)   // 背面：水平镜像
        dstRect.set(left, top, left + cw, top + fh)

        // 两侧残影（横向运动模糊）
        if (trail > 1.5f) {
            paint.alpha = 46
            ghostRect.set(left - trail, top, left - trail + cw, top + fh)
            canvas.drawBitmap(b, null, ghostRect, paint)
            ghostRect.set(left + trail, top, left + trail + cw, top + fh)
            canvas.drawBitmap(b, null, ghostRect, paint)
        }

        paint.alpha = 255
        canvas.drawBitmap(b, null, dstRect, paint)

        // 背面压暗，让"转到另一面"看得出来
        if (k < 0) {
            shadePaint.color = Color.parseColor("#7A000000")
            canvas.drawRect(dstRect, shadePaint)
        }
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
     * 开始转动。@param degPerSecond 角速度（度/秒）——绕竖直中轴的平面转动，
     * 转得越快，侧面掠过时的拖影越长。
     */
    fun startSpinning(degPerSecond: Float) {
        stopSpinning()
        omega = degPerSecond
        applyBlur((abs(degPerSecond) / 500f).coerceIn(0f, 2f))
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
