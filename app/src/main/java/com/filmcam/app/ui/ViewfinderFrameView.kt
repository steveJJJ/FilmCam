package com.filmcam.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 康泰时 T3 取景器标记层，按真机取景玻璃内的显示复刻。
 *
 * 常驻显示：
 *  - ① 四角大亮框角标（Picture Area Frame）
 *  - ② 内侧小角标（近距视差补偿 Close-up Parallax Frame）
 *  - ④ 中央 AF 测距标记（Focus Frame，左右两段弧括号）
 *
 * 条件显示（T3 手册 P.4 的 ⑤⑥⑦⑧，停手 8 秒后自动熄灭省电）：
 *  - ⑤ 曝光补偿指示 ±
 *  - ⑥ 对焦指示绿色 LED：常亮=合焦 / 快闪 4Hz=失焦 / 慢闪 1Hz=焦点锁定(AFL)
 *  - ⑦ 闪光指示（闪电符号）
 *  - ⑧ 快门速度档位 500 / 125 / 30 / LT
 *
 * 标记绘制在「实际取景画面矩形」内，而不是整个 View：
 * 预览 shader（LutGLSurfaceView）按 contentAspect 3:4 满宽 letterbox，
 * 并以 offsetY = PREVIEW_OFFSET_Y 上移画面，此处用同一公式反推内容矩形。
 * 两处常量必须保持一致。
 */
class ViewfinderFrameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** LED 显示方式 */
    enum class LedMode { OFF, ON, BLINK_FAST, BLINK_SLOW }

    /** 取景器内的条件显示状态 */
    data class Indicators(
        val focus: LedMode = LedMode.OFF,
        val evCompOn: Boolean = false,
        val evSteps: Int = 0,              // -3 ~ +3
        val flash: LedMode = LedMode.OFF,  // 闪光指示
        val shutterLit: Set<String> = emptySet(),  // 点亮的快门档位，如 {"500","30"}
        val shutterBlink: Boolean = false          // 快门档闪烁（实际最快档/过曝警告）
    )

    private val density = resources.displayMetrics.density
    private val stroke = 3.0f * density

    // ① 大亮框角标
    private val brightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = 0xCCFFFFFF.toInt()
    }

    // ② 内侧视差小角标：略细、略淡
    private val parallaxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke * 0.85f
        color = 0x99FFFFFF.toInt()
    }

    // ④ 中央 AF 标记：左右两段弧括号
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = 0xB3FFFFFF.toInt()
    }

    private var indicators: Indicators? = null
    private var lastActivityAt = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 8 秒无操作后自动熄灭，随后 1.2 秒淡出（与真机省电行为一致） */
    private val AUTO_OFF_MS = 8_000L
    private val FADE_MS = 1_200L

    private fun indicatorsAlpha(): Float {
        val idle = SystemClock.uptimeMillis() - lastActivityAt
        return when {
            idle < AUTO_OFF_MS -> 1f
            idle < AUTO_OFF_MS + FADE_MS -> 1f - (idle - AUTO_OFF_MS).toFloat() / FADE_MS
            else -> 0f
        }
    }

    /** 任何操作重新点亮取景器显示 */
    fun notifyActivity() {
        lastActivityAt = SystemClock.uptimeMillis()
        if (indicators != null) {
            invalidate()
            scheduleTick()
        }
    }

    /**
     * 更新取景器内的条件显示内容。注意这里**不重置**熄灭计时——
     * 光源每 150ms 就有新数据，若每次都续期就永远不熄灭；
     * 只有真实用户操作（点按/按键/拍照）才调用 notifyActivity() 续期。
     */
    fun setIndicators(state: Indicators) {
        indicators = state
        invalidate()
        if (lastActivityAt == 0L) lastActivityAt = SystemClock.uptimeMillis()
        scheduleTick()
    }

    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            if (indicatorsAlpha() > 0f) handler.postDelayed(this, 100)
        }
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 100)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
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

        // ---- ① 四角大亮框角标 ----
        val bInset = cw * 0.035f
        val bLen = cw * 0.075f
        drawBrackets(canvas, cl + bInset, ct + bInset, cr - bInset, cb - bInset, bLen, brightPaint)

        // ---- ② 内侧视差补偿小角标 ----
        val pInset = cw * 0.105f
        val pLen = cw * 0.035f
        drawBrackets(canvas, cl + pInset, ct + pInset, cr - pInset, cb - pInset, pLen, parallaxPaint)

        // ---- ④ 中央 AF 标记：左右两段弧括号（上下留缺口、中间不相连、无竖线）----
        val cx = (cl + cr) / 2f
        val cy = (ct + cb) / 2f
        val r = minOf(cw, ch) * 0.055f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        // Android 角度：0°为 3 点方向、顺时针；左右各 140°，上下各留 40° 缺口
        canvas.drawArc(oval, 110f, 140f, false, centerPaint)
        canvas.drawArc(oval, -70f, 140f, false, centerPaint)

        // ---- 以下为条件显示itten је ----
        val st = indicators ?: return
        val alpha = indicatorsAlpha()
        if (alpha <= 0f) return

        val iconSize = minOf(cw, ch) * 0.032f
        val baseLine = cb - ch * 0.055f

        // ⑤ 曝光补偿 ±（左下）
        if (st.evCompOn) {
            val pmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = iconSize * 1.6f
                color = Color.argb((0xE6 * alpha).toInt(), 255, 255, 255)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val text = if (st.evSteps > 0) "+${st.evSteps}" else st.evSteps.toString()
            canvas.drawText(text, cl + cw * 0.07f, baseLine, pmPaint)
            canvas.drawText(
                "±", cl + cw * 0.07f + pmPaint.measureText(text) + 3 * density, baseLine, pmPaint
            )
        }

        // ⑦ 闪光指示（左下，位于补偿标记上方）
        if (st.flash != LedMode.OFF) {
            drawBolt(canvas, cl + cw * 0.075f, baseLine - iconSize * 2.2f, iconSize * 1.6f, st.flash, alpha)
        }

        // ⑥ 对焦指示绿色 LED：画面下方居中
        drawLed(canvas, cx, baseLine, iconSize, st.focus, alpha, 120, 255, 140)

        // ⑧ 快门速度档位：右侧竖排 500 / 125 / 30 / LT
        drawShutterScale(canvas, cr - cw * 0.075f, ct, cb, st.shutterLit, st.shutterBlink, alpha)
    }

    /** 小圆形 LED 指示灯：常亮 / 4Hz 快闪 / 1Hz 慢闪 */
    private fun drawLed(
        canvas: Canvas, cx: Float, cy: Float, r: Float, mode: LedMode,
        alpha: Float, red: Int, green: Int, blue: Int
    ) {
        if (mode == LedMode.OFF) return
        val phase = SystemClock.uptimeMillis()
        val on = when (mode) {
            LedMode.ON -> true
            LedMode.BLINK_FAST -> (phase / 125L) % 2L == 0L    // 4 次/秒
            LedMode.BLINK_SLOW -> (phase / 500L) % 2L == 0L    // 1 次/秒
            LedMode.OFF -> false
        }
        if (!on) return

        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, r * 2.8f,
                Color.argb((95 * alpha).toInt(), red, green, blue),
                Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, r * 2.8f, glow)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((240 * alpha).toInt(), red, green, blue)
        }
        canvas.drawCircle(cx, cy, r * 0.40f, dot)
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((255 * alpha).toInt(), 235, 255, 240)
        }
        canvas.drawCircle(cx, cy, r * 0.18f, core)
    }

    /** ⑦ 闪光符号（自绘闪电，避免 emoji 字体缺字） */
    private fun drawBolt(canvas: Canvas, x: Float, y: Float, h: Float, mode: LedMode, alpha: Float) {
        val phase = SystemClock.uptimeMillis()
        val on = when (mode) {
            LedMode.ON -> true
            LedMode.BLINK_FAST -> (phase / 125L) % 2L == 0L
            LedMode.BLINK_SLOW -> (phase / 500L) % 2L == 0L
            LedMode.OFF -> false
        }
        if (!on) return
        val w = h * 0.55f
        val p = android.graphics.Path().apply {
            moveTo(x + w * 0.55f, y)
            lineTo(x + w * 0.05f, y + h * 0.55f)
            lineTo(x + w * 0.42f, y + h * 0.55f)
            lineTo(x + w * 0.30f, y + h)
            lineTo(x + w * 0.95f, y + h * 0.40f)
            lineTo(x + w * 0.55f, y + h * 0.40f)
            lineTo(x + w * 0.72f, y)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((230 * alpha).toInt(), 255, 214, 90)
        }
        canvas.drawPath(p, paint)
    }

    /** ⑧ 右侧快门速度档位立柱：500 / 125 / 30 / LT */
    private fun drawShutterScale(
        canvas: Canvas, right: Float, top: Float, bottom: Float,
        lit: Set<String>, blink: Boolean, alpha: Float
    ) {
        val labels = listOf("500", "125", "30", "LT")
        val h = bottom - top
        val size = min(h * 0.036f, 13f * density)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val y0 = top + h * 0.30f
        val stepY = h * 0.12f
        val blinking = (SystemClock.uptimeMillis() / 125L) % 2L == 0L
        labels.forEachIndexed { i, label ->
            val isLit = label in lit
            val on = isLit && (!blink || blinking)
            paint.color = when {
                !isLit -> Color.argb((48 * alpha).toInt(), 255, 255, 255)
                on -> Color.argb((235 * alpha).toInt(), 150, 255, 170)
                else -> Color.argb((70 * alpha).toInt(), 255, 255, 255)
            }
            canvas.drawText(label, right, y0 + i * stepY, paint)
        }
    }

    /** 在四角画直角括号（l/t/r/b 为角标外沿矩形，len 为横向边长，竖向略长） */
    private fun drawBrackets(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, len: Float, p: Paint) {
        val vLen = len * 1.4f
        canvas.drawLine(l, t, l + len, t, p)
        canvas.drawLine(l, t, l, t + vLen, p)
        canvas.drawLine(r - len, t, r, t, p)
        canvas.drawLine(r, t, r, t + vLen, p)
        canvas.drawLine(l, b, l + len, b, p)
        canvas.drawLine(l, b - vLen, l, b, p)
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
