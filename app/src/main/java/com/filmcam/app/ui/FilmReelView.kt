package com.filmcam.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 过卷动画：**一卷竖着放的胶卷，绕自身竖直中轴自转**（像转筒/转椅那样绕 Y 轴转，
 * 不是把端面当轮子绕 Z 轴转）。
 *
 * 画面 = 一个竖直圆柱体：
 *  - 柱身按圆柱透视做贴图：屏幕每一列 x 对应柱面角度 asin((x-cx)/r)，
 *    于是贴图在中间铺开、到左右两侧被压缩——转起来时表面图案横向流动并在
 *    边缘挤压，这就是"绕竖轴转"的观感；
 *  - 贴图 = 当前胶卷封面 + 叠一层水平胶片缠绕纹（每层一道），并在水平方向
 *    做了镜像拼接，保证转满一圈接缝处连续；
 *  - 顶/底椭圆是卷的端面（同心胶片层 + 一条随转动走的胶片尾端标记）；
 *  - 柱身叠一道横向明暗渐变（中间偏左高光、两侧压暗）做出圆柱体积感；
 *  - 转动越快，每列采样窗口越宽 → 横向运动模糊越强。
 *
 * 之前几版（图标空转 / 单卷横掠 / 双卷轴+胶带 / 端面当轮子转）都不是这个意思。
 */
class FilmReelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val srcRect = Rect()
    private val dstRect = RectF()

    /** 柱面展开贴图（水平镜像拼接，宽 = 2×W0） */
    private var texture: Bitmap? = null
    private var texW = 0

    private var angle = 0f   // 累计转角（度）
    private var omega = 0f   // 角速度（度/秒）

    private var animator: ValueAnimator? = null
    private var lastFrameMs = 0L

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val tex = texture
        if (w <= 4f || h <= 4f || tex == null) return

        val cx = w / 2f
        val r = w / 2f * 0.92f
        val ry = r * 0.30f                    // 端面椭圆的短半轴（俯视透视）
        val top = ry                          // 顶面椭圆中心 y
        val bottom = h - ry                   // 底面椭圆中心 y

        val phase = (angle % 360f) / 360f     // 0..1：转过的圈数小数部分
        val smear = smearFor(omega)           // 每列额外采样宽度（px，越大越糊）

        // ---- 底面椭圆（先画，柱身会盖住它的上半） ----
        drawCap(canvas, cx, bottom, r, ry, phase, front = false)
        // ---- 柱身：逐列做圆柱透视贴图 ----
        drawBody(canvas, tex, cx, top, bottom, r, phase, smear)
        // ---- 圆柱明暗：中间偏左高光，两侧压暗 ----
        shadePaint.shader = LinearGradient(
            cx - r, 0f, cx + r, 0f,
            intArrayOf(
                0xE6000000.toInt(), 0x66000000.toInt(), 0x00000000.toInt(),
                0x33000000.toInt(), 0xF2000000.toInt()
            ),
            floatArrayOf(0f, 0.18f, 0.42f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(cx - r, top, cx + r, bottom, shadePaint)
        shadePaint.shader = null
        // ---- 顶面椭圆 ----
        drawCap(canvas, cx, top, r, ry, phase, front = true)
    }

    /** 柱身：屏幕每一列 → 柱面角度 → 贴图 u（含透视压缩） */
    private fun drawBody(
        canvas: Canvas, tex: Bitmap, cx: Float, top: Float, bottom: Float,
        r: Float, phase: Float, smear: Float
    ) {
        val tw = tex.width
        val th = tex.height
        val step = 2f
        val half = max(1f, smear)
        var x = cx - r
        while (x < cx + r) {
            val nx = (((x + step / 2f) - cx) / r).coerceIn(-1f, 1f)
            val theta = asin(nx)                       // -π/2 .. π/2（可见半圈）
            var u = theta / PI.toFloat() + phase + 0.5f // 0..1 循环
            u -= kotlin.math.floor(u)
            // 贴图是水平镜像拼接的（宽 2×W0），u 线性走完一整幅正好一圈
            val su = u * tw
            val s0 = (su - half / 2f).coerceIn(0f, (tw - half - 1f).coerceAtLeast(0f))
            val s1 = (s0 + half).coerceIn(s0 + 1f, tw.toFloat())
            srcRect.set(s0.toInt(), 0, s1.toInt(), th)
            dstRect.set(x, top, min(x + step, cx + r), bottom)
            bodyPaint.alpha = 255
            canvas.drawBitmap(tex, srcRect, dstRect, bodyPaint)
            x += step
        }
    }

    /** 端面椭圆：同心胶片层 + 一条随转角走的胶片尾端标记（能直接看出在转） */
    private fun drawCap(
        canvas: Canvas, cx: Float, cy: Float, r: Float, ry: Float, phase: Float, front: Boolean
    ) {
        capPaint.style = Paint.Style.FILL
        capPaint.color = Color.parseColor("#1B1813")
        canvas.drawOval(cx - r, cy - ry, cx + r, cy + ry, capPaint)

        layerPaint.style = Paint.Style.STROKE
        layerPaint.strokeWidth = max(1f, r * 0.02f)
        var k = 0.88f
        var idx = 0
        while (k > 0.20f) {
            layerPaint.color = if (idx % 2 == 0) 0xFF3A322A.toInt() else 0xFF241F1A.toInt()
            canvas.drawOval(cx - r * k, cy - ry * k, cx + r * k, cy + ry * k, layerPaint)
            k -= 0.14f
            idx++
        }

        // 胶片尾端标记：绕竖轴转，投影到椭圆上
        val phi = phase * 2.0 * PI
        val ex = cx + (r * 0.80f * cos(phi)).toFloat()
        val ey = cy + (ry * 0.80f * sin(phi)).toFloat()
        markPaint.style = Paint.Style.STROKE
        markPaint.strokeWidth = max(1.5f, r * 0.045f)
        markPaint.color = if (front) Color.parseColor("#B9A98F") else Color.parseColor("#6B6255")
        canvas.drawLine(cx, cy, ex, ey, markPaint)
        circle(canvas, ex, ey, max(1.5f, r * 0.05f), markPaint)
    }

    private fun circle(canvas: Canvas, x: Float, y: Float, r: Float, p: Paint) {
        val old = p.style
        p.style = Paint.Style.FILL
        canvas.drawCircle(x, y, r, p)
        p.style = old
    }

    /**
     * 造柱面贴图：封面（centerCrop）+ 水平胶片缠绕纹 + 水平镜像拼接。
     * 镜像拼接是为了让贴图首尾相接处连续（转满一圈不会出现硬缝）。
     */
    private fun buildTexture(src: Bitmap, w0: Int, h0: Int): Bitmap {
        val base = Bitmap.createBitmap(w0, h0, Bitmap.Config.ARGB_8888)
        val c = Canvas(base)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val s = max(w0 / src.width.toFloat(), h0 / src.height.toFloat())
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate((w0 - src.width * s) / 2f, (h0 - src.height * s) / 2f)
        }
        c.drawBitmap(src, m, p)

        // 一层层缠绕的胶片：水平细纹（亮/暗交替），让侧面像"卷起来的片"
        val lineP = Paint(Paint.ANTI_ALIAS_FLAG)
        var y = 0f
        var i = 0
        while (y < h0) {
            lineP.color = if (i % 2 == 0) 0x2A000000 else 0x14FFFFFF.toInt()
            c.drawRect(0f, y, w0.toFloat(), y + max(1f, h0 * 0.006f), lineP)
            y += h0 * 0.028f
            i++
        }

        val out = Bitmap.createBitmap(w0 * 2, h0, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        oc.drawBitmap(base, 0f, 0f, p)
        oc.drawBitmap(base, Matrix().apply { setScale(-1f, 1f); postTranslate(2f * w0, 0f) }, p)
        return out
    }

    fun setIcon(resId: Int) {
        val src = try {
            BitmapFactory.decodeResource(resources, resId)
        } catch (_: Exception) {
            null
        }
        texture = src?.let { buildTexture(it, TEX_W, TEX_H) }
        texW = TEX_W * 2
        invalidate()
    }

    /**
     * 开始自转。@param degPerSecond 角速度（度/秒）：绕竖直中轴，
     * 转得越快柱面图案流动越快、横向模糊越强。
     */
    fun startSpinning(degPerSecond: Float) {
        stopSpinning()
        omega = degPerSecond
        applyBlur((abs(degPerSecond) / 900f).coerceIn(0f, 2.5f))
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

    /** 横向模糊：转速越高，每列采样窗口越宽 */
    private fun smearFor(degPerSecond: Float): Float =
        (abs(degPerSecond) / 26f).coerceIn(1f, 34f)

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
        private const val TEX_W = 420      // 贴图半幅宽（整幅 = 2×，镜像拼接）
        private const val TEX_H = 560      // 贴图高（柱身高度方向）
    }
}
