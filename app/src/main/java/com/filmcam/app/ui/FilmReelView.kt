package com.filmcam.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.min

/**
 * 过卷动画：**胶卷外框固定不动，图案（封面画面）在里面横向循环流过**——
 * 图标本体不位移、不旋转；窗口内的画面用「原图 + 水平镜像」无缝拼接成一条带，
 * 以 REPEAT 平铺并随时间匀速平移，看起来就是胶卷表面被不停拉走（转动感）。
 */
class FilmReelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val flowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dstRect = RectF()
    private val flowRect = RectF()
    private val flowPath = Path()
    private val flowMatrix = Matrix()

    private var icon: Bitmap? = null
    /** 无缝拼接带：原图 + 水平镜像图，宽 2×原图 */
    private var tile: Bitmap? = null

    /** 累计流动量，单位＝图标宽（1.0 = 画面走了一个图标宽） */
    private var flow = 0f
    private var laps = 0f   // 每秒流动几个图标宽

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
        val left = (w - fw) / 2f
        val top = (h - fh) / 2f

        paint.alpha = 255
        dstRect.set(left, top, left + fw, top + fh)
        canvas.drawBitmap(b, null, dstRect, paint)

        // 画面流动：窗口（胶卷内部）固定，图案带在里面横向平移
        val t = tile
        if (t == null || abs(laps) < 0.01f) return

        val insetX = fw * 0.10f
        val insetY = fh * 0.10f
        flowRect.set(left + insetX, top + insetY, left + fw - insetX, top + fh - insetY)

        // 采样坐标：把屏幕坐标平移 -flow*fw 后映射回 bitmap 空间
        val off = (flow % 2f) * fw
        flowMatrix.reset()
        flowMatrix.setScale(1f / s, 1f / s)
        flowMatrix.postTranslate(-(left - off) / s, -top / s)
        if (flowPaint.shader == null) {
            flowPaint.shader = BitmapShader(t, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
        }
        (flowPaint.shader as? BitmapShader)?.setLocalMatrix(flowMatrix)

        val r = min(flowRect.width(), flowRect.height()) * 0.22f
        flowPath.reset()
        flowPath.addRoundRect(flowRect, r, r, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(flowPath)
        flowPaint.alpha = 255
        canvas.drawRect(flowRect, flowPaint)
        canvas.restore()
    }

    fun setIcon(resId: Int) {
        icon = try {
            BitmapFactory.decodeResource(resources, resId)
        } catch (_: Exception) {
            null
        }
        tile = icon?.let(::buildMirroredTile)
        flowPaint.shader = null
        invalidate()
    }

    /** 原图 + 水平镜像拼成 2 倍宽带子，循环平铺时接缝左右对称、看不出跳变 */
    private fun buildMirroredTile(b: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(b.width * 2, b.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(b, 0f, 0f, null)
        val m = Matrix().apply {
            setScale(-1f, 1f)
            postTranslate((b.width * 2).toFloat(), 0f)
        }
        c.drawBitmap(b, m, null)
        return out
    }

    /**
     * 开始过片。@param lapsPerSecond 画面每秒流过几个图标宽（1.0 = 一卷/秒）。
     */
    fun startSpinning(lapsPerSecond: Float) {
        stopSpinning()
        laps = lapsPerSecond
        applyBlur((abs(lapsPerSecond) * 0.9f).coerceIn(0f, 2f))
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
                flow = (flow + lapsPerSecond * dt) % 2f
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
