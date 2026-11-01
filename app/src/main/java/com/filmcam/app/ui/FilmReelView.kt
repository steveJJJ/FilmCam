package com.filmcam.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 过卷动画：**一个**胶卷（卷轴端面视角）在原地被马达带着转。
 *
 * 画面构成（全部随同一角度旋转，所以转起来一眼可辨）：
 *  - 外圈：一层层缠绕的胶片（同心圆纹理 + 一圈卷片齿 + 一道胶片尾端切口）；
 *  - 卷芯：当前胶卷的封面（圆形裁切），转起来能认出在走哪一卷；
 *  - 中心：卷轴轴芯。
 * 转动的"速度感"用**角度残影**做：把整个卷盘按角度差重绘若干次、alpha 递减，
 * 转速越高残影拖得越长（等价于旋转方向的运动模糊）。
 *
 * 前几版都不对：图标原地空转（像加载圈）、单卷横向掠过、双卷轴+胶片带——
 * 用户要的是"一个胶卷在转动"。
 */
class FilmReelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val toothPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** 圆形裁切后的胶卷封面，贴在卷芯上 */
    private var cover: Bitmap? = null

    /** 当前角度（度）与角速度（度/秒） */
    private var angle = 0f
    private var omega = 0f

    private var animator: ValueAnimator? = null
    private var lastFrameMs = 0L

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) return
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f * 0.94f

        // 角度残影：转速越高，每一级间隔越大、拖得越长
        val spinning = omega > 1f
        val step = (abs(omega) * 0.013f).coerceIn(3f, 13f)
        val passes = if (spinning) TRAIL_PASSES else 1
        for (k in passes - 1 downTo 0) {
            val a = angle - k * step
            val alpha = if (k == 0) 255 else (78 - k * 9).coerceAtLeast(30)
            val save = canvas.saveLayerAlpha(0f, 0f, w, h, alpha)
            drawReel(canvas, cx, cy, r, a)
            canvas.restoreToCount(save)
        }
    }

    private fun drawReel(canvas: Canvas, cx: Float, cy: Float, r: Float, a: Float) {
        canvas.save()
        canvas.rotate(a, cx, cy)

        // 1) 卷盘底
        discPaint.style = Paint.Style.FILL
        discPaint.color = Color.parseColor("#17140F")
        canvas.drawCircle(cx, cy, r, discPaint)

        // 2) 缠绕的胶片层：同心细环，越往外越密，像卷起来的片基
        layerPaint.style = Paint.Style.STROKE
        layerPaint.strokeWidth = max(1.2f, r * 0.016f)
        var rr = r * 0.965f
        var idx = 0
        while (rr > r * 0.58f) {
            layerPaint.color = if (idx % 2 == 0) 0xFF332B24.toInt() else 0xFF211C17.toInt()
            canvas.drawCircle(cx, cy, rr, layerPaint)
            rr -= r * 0.055f
            idx++
        }

        // 3) 圆周卷片齿：转起来最能说明"在转"的非对称元素
        toothPaint.color = Color.parseColor("#6E665A")
        for (i in 0 until TEETH) {
            canvas.save()
            canvas.rotate(i * (360f / TEETH), cx, cy)
            canvas.drawRoundRect(
                cx - r * 0.042f, cy - r * 1.002f,
                cx + r * 0.042f, cy - r * 0.88f,
                r * 0.02f, r * 0.02f, toothPaint
            )
            canvas.restore()
        }

        // 4) 胶片尾端：从内圈斜切到外沿的一道亮边（胶片卷的收尾切口）
        tailPaint.style = Paint.Style.STROKE
        tailPaint.strokeWidth = r * 0.035f
        tailPaint.strokeCap = Paint.Cap.BUTT
        tailPaint.color = Color.parseColor("#4A4038")
        val inner = r * 0.60f
        val a0 = Math.toRadians(118.0)
        val a1 = Math.toRadians(6.0)
        canvas.drawLine(
            cx + (Math.cos(a0) * inner).toFloat(), cy + (Math.sin(a0) * inner).toFloat(),
            cx + (Math.cos(a1) * r * 0.97f).toFloat(), cy + (Math.sin(a1) * r * 0.97f).toFloat(),
            tailPaint
        )

        // 5) 卷芯：当前胶卷封面（圆裁），转起来能认出是哪一卷
        val cr = r * 0.52f
        cover?.let {
            coverPaint.alpha = 255
            canvas.drawBitmap(it, null, RectF(cx - cr, cy - cr, cx + cr, cy + cr), coverPaint)
        }
        discPaint.style = Paint.Style.STROKE
        discPaint.strokeWidth = r * 0.028f
        discPaint.color = Color.parseColor("#8C8275")
        canvas.drawCircle(cx, cy, cr, discPaint)

        // 6) 轴芯
        hubPaint.style = Paint.Style.FILL
        hubPaint.color = Color.parseColor("#2C2822")
        canvas.drawCircle(cx, cy, r * 0.13f, hubPaint)
        hubPaint.style = Paint.Style.STROKE
        hubPaint.strokeWidth = r * 0.026f
        hubPaint.color = Color.parseColor("#A09489")
        canvas.drawCircle(cx, cy, r * 0.13f, hubPaint)

        canvas.restore()
    }

    fun setIcon(resId: Int) {
        val src = try {
            BitmapFactory.decodeResource(resources, resId)
        } catch (_: Exception) {
            null
        }
        cover = src?.let { roundCrop(it, 256) }
        invalidate()
    }

    /** 把胶卷封面裁成圆形（centerCrop），贴在卷芯上 */
    private fun roundCrop(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val s = max(size / src.width.toFloat(), size / src.height.toFloat())
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate((size - src.width * s) / 2f, (size - src.height * s) / 2f)
        }
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(m)
        p.shader = shader
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        return out
    }

    /**
     * 开始转动。@param degPerSecond 角速度（度/秒），
     * 同时决定角度残影的长度——转得越快拖影越长。
     */
    fun startSpinning(degPerSecond: Float) {
        stopSpinning()
        omega = degPerSecond
        applyBlur((abs(degPerSecond) / 420f).coerceIn(0f, 3f))
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

    companion object {
        private const val TEETH = 16
        private const val TRAIL_PASSES = 5
    }
}
