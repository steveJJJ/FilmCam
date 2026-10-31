package com.filmcam.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.animation.doOnEnd

/**
 * 康泰时 T3 取景器常驻标记层，按真机取景玻璃刻线复刻：
 * - 四角大亮框角标（bright frame，取景范围标记）
 * - 内侧四角小直角（近距视差补偿标记）
 * - 中央 AF 测距区标记（圆圈 + 中央竖线）
 *
 * 标记绘制在「实际取景画面矩形」内，而不是整个 View：
 * 预览 shader（LutGLSurfaceView）按 contentAspect 3:4 满宽 letterbox，
 * 并以 offsetY = PREVIEW_OFFSET_Y 上移画面，此处用同一公式反推内容矩形。
 * 两处常量必须保持一致。
 */
class ViewfinderFrameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val stroke = 1.6f * density

    // 大亮框角标
    private val brightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = 0xCCFFFFFF.toInt()
    }

    // 内侧视差小角标：更细更淡
    private val parallaxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke * 0.8f
        color = 0x99FFFFFF.toInt()
    }

    // 中央 AF 标记：圆 + 竖线，常驻显示
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = 0xB3FFFFFF.toInt()
    }

    // 点屏对焦标记（与中央标记同款，动画显示在点按处）
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        color = 0xFFFFFFFF.toInt()
    }
    private var markerX = 0f
    private var markerY = 0f
    private var markerAlpha = 0f
    private var markerScale = 1f
    private var markerAnim: android.animation.ValueAnimator? = null

    /**
     * 在点按处显示对焦标记：收拢(160ms) → 停留(650ms) → 淡出(280ms)。
     * 坐标为 View 内坐标（与 glSurfaceView 全屏一致）。
     */
    fun showFocusMarker(x: Float, y: Float) {
        markerX = x
        markerY = y
        markerAnim?.cancel()
        val settle = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 160
            addUpdateListener {
                val t = it.animatedValue as Float
                markerAlpha = 0.2f + 0.8f * t
                markerScale = 1.5f - 0.5f * t
                invalidate()
            }
            doOnEnd {
                postDelayed({
                    val fade = android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
                        duration = 280
                        addUpdateListener { a ->
                            markerAlpha = a.animatedValue as Float
                            invalidate()
                        }
                        doOnEnd {
                            markerAlpha = 0f
                            invalidate()
                        }
                    }
                    fade.start()
                }, 650)
            }
        }
        settle.start()
        markerAnim = settle
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 反推预览内容矩形（与 LutGLSurfaceView 的 shader 几何一致）
        val screenAspect = w / h
        val s = screenAspect / CONTENT_ASPECT
        val contentTop = (0.5f - s * (0.5f - PREVIEW_OFFSET_Y)) * h
        val contentBottom = (0.5f + s * (0.5f + PREVIEW_OFFSET_Y)) * h
        val cl = 0f
        val cr = w
        val ct = contentTop.coerceIn(0f, h)
        val cb = contentBottom.coerceIn(0f, h)
        val cw = cr - cl
        val ch = cb - ct

        // ---- 四角大亮框角标 ----
        val bInset = cw * 0.035f
        val bLen = cw * 0.075f
        drawBrackets(canvas, cl + bInset, ct + bInset, cr - bInset, cb - bInset, bLen, brightPaint)

        // ---- 内侧视差补偿小角标 ----
        val pInset = cw * 0.105f
        val pLen = cw * 0.035f
        drawBrackets(canvas, cl + pInset, ct + pInset, cr - pInset, cb - pInset, pLen, parallaxPaint)

        // ---- 中央 AF 标记：圆圈 + 中央竖线 ----
        val cx = (cl + cr) / 2f
        val cy = (ct + cb) / 2f
        val r = minOf(cw, ch) * 0.055f
        canvas.drawCircle(cx, cy, r, centerPaint)
        val vHalf = r * 0.85f
        canvas.drawLine(cx, cy - vHalf, cx, cy + vHalf, centerPaint)

        // ---- 点屏对焦标记（同款圆+竖线，动画） ----
        if (markerAlpha > 0f) {
            markerPaint.alpha = (markerAlpha * 255).toInt().coerceIn(0, 255)
            val mr = r * markerScale
            canvas.drawCircle(markerX, markerY, mr, markerPaint)
            canvas.drawLine(markerX, markerY - mr * 0.85f, markerX, markerY + mr * 0.85f, markerPaint)
        }
    }

    /** 在四角画直角括号（l/t/r/b 为角标外沿矩形，len 为横向角标边长，竖向略长） */
    private fun drawBrackets(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, len: Float, p: Paint) {
        val vLen = len * 1.4f
        // 左上
        canvas.drawLine(l, t, l + len, t, p)
        canvas.drawLine(l, t, l, t + vLen, p)
        // 右上
        canvas.drawLine(r - len, t, r, t, p)
        canvas.drawLine(r, t, r, t + vLen, p)
        // 左下
        canvas.drawLine(l, b, l + len, b, p)
        canvas.drawLine(l, b - vLen, l, b, p)
        // 右下
        canvas.drawLine(r - len, b, r, b, p)
        canvas.drawLine(r, b - vLen, r, b, p)
    }

    companion object {
        /** 预览内容宽高比（1440x1920，与 LutGLSurfaceView.renderTo 一致） */
        const val CONTENT_ASPECT = 1440f / 1920f

        /** 预览画面竖向偏移（与 LutGLSurfaceView onDrawFrame 的 offsetY 一致） */
        const val PREVIEW_OFFSET_Y = -0.08f
    }
}
