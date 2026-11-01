package com.filmcam.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.min

/**
 * 过卷动画：一条 35mm 胶片带在**两个旋转的卷轴**之间被拖过片门——
 * 左轴放卷、右轴收卷，两者同步顺时针转；带上的齿孔与画格随胶片横向流动，
 * 速度越快拖影越长（手绘残影 + 整体轻微模糊）。
 *
 * 这是真实胶片机过片的标准视觉（参考 35mm film strip gallery 一类素材：
 * film running between two spinning rolls）。此前两版都不对：
 *  - 第一版：图标原地 360° 空转（没有位移，像加载圈）；
 *  - 第二版：一个胶卷图标横着掠过（只有"飞过去"，不是"卷在转"）。
 *
 * 动效全部内聚在视图里：调用方只需 setIcon + start/stopScrolling。
 */
class FilmStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clipRectF = RectF()
    private val dstRectF = RectF()
    private val srcRect = Rect()

    private var icon: Bitmap? = null

    /** 累计走过的胶片长度（px），只增不减 */
    private var scroll = 0f
    /** 当前速度（px/s），决定拖影长度与模糊强度 */
    private var speed = 0f

    private var animator: ValueAnimator? = null
    private var lastFrameMs = 0L

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) return

        val cy = h / 2f
        // 卷轴直径：不超过高度，也不让两个轴占掉整条宽度
        val reelD = min(h * 0.80f, w * 0.24f)
        val r = reelD / 2f
        val leftCx = r + 2f
        val rightCx = w - r - 2f

        // 胶片带：高度约为轴径的 0.7，两端插进卷轴里
        val bh = reelD * 0.68f
        val top = cy - bh / 2f
        val x0 = leftCx + r * 0.30f
        val x1 = rightCx - r * 0.30f

        val smear = smearFor(speed)

        // ---------- 胶片带（齿孔 + 画格） ----------
        canvas.save()
        clipRectF.set(x0, top, x1, top + bh)
        canvas.clipRect(clipRectF)

        stripPaint.color = Color.parseColor("#15120F")
        canvas.drawRect(x0, top, x1, top + bh, stripPaint)

        val bandH = bh * 0.21f
        val phW = bh * 0.115f
        val phH = bandH * 0.60f
        val phPitch = phW * 2.05f
        val phOff = ((scroll % phPitch) + phPitch) % phPitch
        drawPerforations(canvas, x0, x1, top, bandH, phW, phH, phPitch, phOff, smear)
        drawPerforations(canvas, x0, x1, top + bh - bandH, bandH, phW, phH, phPitch, phOff, smear)

        // 画格：35mm 全画幅 3:2，画在两排齿孔之间
        val fh = bh - bandH * 2f
        val fw = fh * 1.5f
        val fGap = fh * 0.18f
        val fPitch = fw + fGap
        val fOff = ((scroll % fPitch) + fPitch) % fPitch
        drawFrames(canvas, x0, x1, top + bandH, fh, fw, fPitch, fOff, smear)
        canvas.restore()

        // ---------- 两个卷轴：随胶片走过的弧长同步旋转 ----------
        val angle = Math.toDegrees((scroll / r).toDouble()).toFloat()
        drawReel(canvas, leftCx, cy, r, angle, smear)
        drawReel(canvas, rightCx, cy, r, angle, smear)
    }

    /** 一排齿孔：向右流动，运动反方向拖一道残影（速度越快越长） */
    private fun drawPerforations(
        canvas: Canvas, x0: Float, x1: Float, bandTop: Float, bandH: Float,
        phW: Float, phH: Float, pitch: Float, off: Float, smear: Float
    ) {
        val hy = bandTop + (bandH - phH) / 2f
        val rad = phH * 0.30f
        var x = x0 - pitch + off
        while (x < x1) {
            if (x + phW > x0) {
                if (smear > 1f) {
                    holePaint.alpha = 62
                    canvas.drawRoundRect(x - smear, hy, x + phW, hy + phH, rad, rad, holePaint)
                }
                holePaint.color = HOLE_COLOR
                holePaint.alpha = 230
                canvas.drawRoundRect(x, hy, x + phW, hy + phH, rad, rad, holePaint)
            }
            x += pitch
        }
    }

    /** 画格：格内是当前胶卷的封面（过片时能认出在走哪一卷），同样带拖影 */
    private fun drawFrames(
        canvas: Canvas, x0: Float, x1: Float, fy: Float, fh: Float,
        fw: Float, pitch: Float, off: Float, smear: Float
    ) {
        val b = icon
        var x = x0 - pitch + off
        while (x < x1) {
            if (x + fw > x0) {
                val left = x
                // 格底：未曝光区的暖黑
                stripPaint.color = Color.parseColor("#0B0A09")
                canvas.drawRect(left, fy, left + fw, fy + fh, stripPaint)
                if (b != null && !b.isRecycled) {
                    centerCropSrc(b, fw, fh)
                    // 残影
                    if (smear > 1f) {
                        framePaint.alpha = 60
                        dstRectF.set(left - smear, fy, left - smear + fw, fy + fh)
                        canvas.drawBitmap(b, srcRect, dstRectF, framePaint)
                    }
                    framePaint.alpha = 255
                    dstRectF.set(left, fy, left + fw, fy + fh)
                    canvas.drawBitmap(b, srcRect, dstRectF, framePaint)
                }
            }
            x += pitch
        }
    }

    /** centerCrop：从 bitmap 里裁出与画格同比例的中间区域 */
    private fun centerCropSrc(b: Bitmap, fw: Float, fh: Float) {
        val bw = b.width.toFloat()
        val bh = b.height.toFloat()
        val scale = kotlin.math.max(fw / bw, fh / bh)
        val sw = fw / scale
        val sh = fh / scale
        val sx = ((bw - sw) / 2f).toInt().coerceIn(0, b.width - 1)
        val sy = ((bh - sh) / 2f).toInt().coerceIn(0, b.height - 1)
        val swi = sw.toInt().coerceAtLeast(1).coerceAtMost(b.width - sx)
        val shi = sh.toInt().coerceAtLeast(1).coerceAtMost(b.height - sy)
        srcRect.set(sx, sy, sx + swi, sy + shi)
    }

    /**
     * 一个卷轴：外圈 + 5 根辐条 + 轴心，辐条按胶片走过的弧长旋转（弧长/半径=角度），
     * 快速旋转时额外补一道角度残影，避免出现"倒着转"的频闪错觉。
     */
    private fun drawReel(canvas: Canvas, cx: Float, cy: Float, r: Float, angle: Float, smear: Float) {
        // 外圈实心
        reelPaint.style = Paint.Style.FILL
        reelPaint.color = Color.parseColor("#262320")
        canvas.drawCircle(cx, cy, r, reelPaint)
        // 外沿亮边（金属倒角）
        reelPaint.style = Paint.Style.STROKE
        reelPaint.strokeWidth = r * 0.09f
        reelPaint.color = Color.parseColor("#4E473E")
        canvas.drawCircle(cx, cy, r - reelPaint.strokeWidth / 2f, reelPaint)

        // 辐条（带角度残影：往回转 10° 再画一遍淡的）
        val trail = (smear / r * 26f).coerceIn(0f, 14f)
        spokePaint.color = Color.parseColor("#5A544B")
        for (pass in 0..1) {
            spokePaint.alpha = if (pass == 0 && trail > 1f) 70 else 255
            val a = if (pass == 0) angle - trail else angle
            canvas.save()
            canvas.rotate(a, cx, cy)
            for (i in 0 until SPOKES) {
                canvas.save()
                canvas.rotate(i * (360f / SPOKES), cx, cy)
                canvas.drawRoundRect(
                    cx - r * 0.058f, cy - r * 0.88f,
                    cx + r * 0.058f, cy - r * 0.18f,
                    r * 0.058f, r * 0.058f, spokePaint
                )
                canvas.restore()
            }
            canvas.restore()
            if (trail <= 1f) break
        }

        // 轴心
        reelPaint.style = Paint.Style.FILL
        reelPaint.color = Color.parseColor("#726A5F")
        canvas.drawCircle(cx, cy, r * 0.17f, reelPaint)
        reelPaint.style = Paint.Style.STROKE
        reelPaint.strokeWidth = r * 0.045f
        reelPaint.color = Color.parseColor("#948B80")
        canvas.drawCircle(cx, cy, r * 0.17f, reelPaint)
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
     * 开始过片。@param pxPerSecond 胶片线速度（像素/秒），
     * 同时决定卷轴转速（弧长/半径）与拖影长度。
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
                val dt = ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
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
        speed = 0f
        applyBlur(0f)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopScrolling()
    }

    /** 拖影长度（px）：速度越快，齿孔/画格被拉得越长 */
    private fun smearFor(pxPerSecond: Float): Float =
        (abs(pxPerSecond) / 95f).coerceIn(0f, 26f)

    private fun blurFor(pxPerSecond: Float): Float =
        (abs(pxPerSecond) / 700f).coerceIn(0f, 3.5f)

    private fun applyBlur(radius: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        setRenderEffect(
            if (radius > 0.3f) {
                android.graphics.RenderEffect.createBlurEffect(
                    radius, radius, android.graphics.Shader.TileMode.CLAMP
                )
            } else {
                null
            }
        )
    }

    companion object {
        private const val SPOKES = 5
        private const val HOLE_COLOR = 0xFFE8E2D6.toInt()
    }
}
