package com.filmcam.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 康泰时 T3 机顶液晶屏（自绘）。
 *
 * 真机是一块**反射式 TN 液晶**：银灰色（略带黄绿）底 + 深灰黑字符，
 * 未点亮的液晶段以极淡的灰色隐约透出（ghost segment），
 * 按机身背光键时整块屏亮起黄绿色 EL 背光并持续数秒。
 * 之前那版黑底荧光绿字是 CRT/示波器风格，完全不是 T3 的观感，故重做。
 *
 * 绘制工序（自下而上）：
 *   金属外框 → LCD 面板渐变 → 液晶像素栅格 → 未点亮的 ghost 段 →
 *   点亮内容（七段数码 + 标签）→ 表面玻璃斜向高光 → 内阴影
 */
class T3LcdView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 屏幕内容状态 */
    data class State(
        val count: Int,          // 曝光计数（已拍张数）
        val filmMax: Int,        // 胶卷额定张数（36）
        val mode: String,        // P / A / SELF
        val isoLabel: String,    // "A"(自动) / "100" / "400" ...
        val evSteps: Float,      // 曝光补偿档数（-3 ~ +3）
        val flashLabel: String,  // OFF / AUTO / FILL / NIGHT
        val battery: Int         // 0~3 格
    )

    private var state: State = State(0, 36, "P", "A", 0f, "OFF", 3)

    /** EL 背光：点亮后背光持续 BACKLIGHT_MS，再淡出 600ms */
    private var backlightUntil = 0L
    private val BACKLIGHT_FADE = 600L
    private val BACKLIGHT_MS = 4000L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = Runnable {
        invalidate()
        if (SystemClock.uptimeMillis() < backlightUntil + BACKLIGHT_FADE) scheduleTick()
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 80)
    }

    fun setState(s: State) {
        state = s
        invalidate()
    }

    /** 点亮背光（过卷、按键等动作时调用，复刻机身 EL 背光行为） */
    fun pulseBacklight(ms: Long = BACKLIGHT_MS) {
        backlightUntil = SystemClock.uptimeMillis() + ms
        invalidate()
        scheduleTick()
    }

    // ---------------------------------------------------------------
    // 资源
    // ---------------------------------------------------------------
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#232428")
    }
    private val frameEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#4A4C52")
        strokeWidth = 1f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    // 液晶基色
    private val lcdTop = floatArrayOf(0.757f, 0.784f, 0.721f)   // #C1C8B8 上部略亮
    private val lcdBottom = floatArrayOf(0.627f, 0.663f, 0.596f) // #A0A998 下部略暗
    private val backlightColor = floatArrayOf(0.796f, 0.839f, 0.463f) // #CBD676 EL 黄绿

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 背光强度 0..1
        val now = SystemClock.uptimeMillis()
        val bl = when {
            now < backlightUntil -> 1f
            now < backlightUntil + BACKLIGHT_FADE ->
                1f - (now - backlightUntil).toFloat() / BACKLIGHT_FADE
            else -> 0f
        }

        val rOuter = 8f * density
        val frameRect = RectF(0f, 0f, w, h)
        canvas.drawRoundRect(frameRect, rOuter, rOuter, framePaint)

        val pad = 2.6f * density
        val lcdRect = RectF(pad, pad, w - pad, h - pad)
        val rInner = 6f * density

        val baseTop = mix(lcdTop, backlightColor, bl)
        val baseBottom = mix(lcdBottom, backlightColor, bl)
        fillPaint.shader = LinearGradient(
            lcdRect.left, lcdRect.top, lcdRect.left, lcdRect.bottom,
            rgb(baseTop), rgb(baseBottom), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(lcdRect, rInner, rInner, fillPaint)
        fillPaint.shader = null

        canvas.save()
        canvas.clipRect(lcdRect)

        drawPixelGrid(canvas, lcdRect)
        drawContent(canvas, lcdRect, bl)
        drawGloss(canvas, lcdRect, bl)

        canvas.restore()

        // 面板外沿 + 内阴影（液晶面板四周压在机身上的暗边）
        canvas.drawRoundRect(frameRect, rOuter, rOuter, frameEdgePaint)
        strokePaint.color = Color.argb((70 * (1f - bl * 0.6f)).toInt(), 30, 32, 28)
        strokePaint.strokeWidth = 1.6f * density
        canvas.drawRoundRect(lcdRect, rInner, rInner, strokePaint)
    }

    // ---------------------------------------------------------------
    // 液晶细节
    // ---------------------------------------------------------------

    /** 液晶像素栅格：横竖每 STEP 一条极淡线，是「液晶感」的关键之一 */
    private fun drawPixelGrid(canvas: Canvas, rect: RectF) {
        val step = 3f * density
        gridPaint.color = Color.argb(16, 40, 44, 36)
        var x = rect.left
        while (x < rect.right) {
            canvas.drawLine(x, rect.top, x, rect.bottom, gridPaint)
            x += step
        }
        gridPaint.color = Color.argb(11, 40, 44, 36)
        var y = rect.top
        while (y < rect.bottom) {
            canvas.drawLine(rect.left, y, rect.right, y, gridPaint)
            y += step
        }
    }

    /** 表面玻璃斜向高光 */
    private fun drawGloss(canvas: Canvas, rect: RectF, bl: Float) {
        val glossAlpha = (0.16f * (1f - bl * 0.5f) * 255).toInt()
        val shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom * 0.6f + rect.top * 0.4f,
            Color.argb(glossAlpha, 255, 255, 255), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = shader
        canvas.drawRect(rect, fillPaint)
        fillPaint.shader = null
    }

    /**
     * 屏幕内容：左侧大号曝光计数七段，右侧 ISO / EV / 闪光 / 模式 / 电池。
     * 所有七段都会先画一次极淡的「全亮 ghost」，模拟未点亮的液晶段。
     */
    private fun drawContent(canvas: Canvas, rect: RectF, bl: Float) {
        val padX = 9f * density
        val padY = 5f * density
        val left = rect.left + padX
        val right = rect.right - padX
        val ch = rect.height() - padY * 2f

        // 点亮色：深灰黑（背光下更深、对比更强）
        val onColor = Color.argb(255, (34 - bl * 12).toInt(), (38 - bl * 14).toInt(), (30 - bl * 8).toInt())
        val labelPaint = Paint(textPaint).apply {
            color = onColor
            textSize = ch * 0.15f
            isFakeBoldText = true
        }
        val smallPaint = Paint(textPaint).apply {
            color = onColor
            textSize = ch * 0.135f
        }

        // ---- 行1：曝光计数（大号七段） + "/36" + 右端拍摄模式 ----
        val bigH = ch * 0.34f
        val bigW = bigH * 0.56f
        val countStr = state.count.toString().padStart(2, ' ').takeLast(2)
        var cx = left
        val y1 = rect.top + padY + ch * 0.04f
        countStr.forEach { c ->
            draw7(canvas, c, cx, y1, bigW, bigH, onColor)
            cx += bigW + bigH * 0.14f
        }
        smallPaint.textSize = ch * 0.14f
        canvas.drawText("/${state.filmMax}", cx + ch * 0.03f, y1 + bigH, smallPaint)

        val modeStr = state.mode
        canvas.drawText(
            modeStr, right - smallPaint.measureText(modeStr), y1 + bigH * 0.8f,
            Paint(smallPaint).apply { textSize = ch * 0.18f; isFakeBoldText = true }
        )

        // ---- 行2：ISO（七段） + EV（有补偿时：符号 + 一位七段） ----
        val y2 = y1 + bigH + ch * 0.085f
        val smH = ch * 0.165f
        val smW = smH * 0.56f
        var ix = left
        canvas.drawText("ISO", ix, y2 + smH, labelPaint)
        ix += labelPaint.measureText("ISO") + ch * 0.04f
        state.isoLabel.takeLast(3).padStart(3, ' ').forEach { c ->
            draw7(canvas, c, ix, y2, smW, smH, onColor)
            ix += smW + smH * 0.14f
        }

        val evX = left + (right - left) * 0.50f
        canvas.drawText("EV", evX, y2 + smH, labelPaint)
        if (state.evSteps != 0f) {
            var ex = evX + labelPaint.measureText("EV") + ch * 0.035f
            val sign = if (state.evSteps > 0) '+' else '-'
            draw7(canvas, sign, ex, y2, smW, smH, onColor)
            ex += smW + smH * 0.14f
            val absI = kotlin.math.abs(state.evSteps).toInt()
            if (absI > 0) draw7(canvas, (absI + '0'.code).toChar(), ex, y2, smW, smH, onColor)
        }

        // ---- 行3：闪光模式 + 闪光符号 + ± + 右端电池 ----
        val y3 = y2 + smH + ch * 0.085f
        val row3H = ch * 0.155f
        val flashOn = state.flashLabel != "OFF"
        canvas.drawText(state.flashLabel, left, y3 + row3H, smallPaint)
        val flashIconX = left + smallPaint.measureText(state.flashLabel) + ch * 0.05f
        drawBolt(canvas, flashIconX, y3 + row3H * 0.1f, row3H * 0.95f, onColor, flashOn)
        // ± 曝光补偿标记（T3 机顶屏同样会显示补偿已设定）
        if (state.evSteps != 0f) {
            canvas.drawText("±", flashIconX + row3H * 1.2f, y3 + row3H, smallPaint)
        }
        drawBattery(canvas, right - ch * 0.32f, y3 + row3H * 0.25f, ch * 0.32f, ch * 0.16f, onColor, state.battery)
    }

    // ---------------------------------------------------------------
    // 七段数码
    // ---------------------------------------------------------------

    private val SEGMENTS = mapOf(
        ' ' to "",
        '0' to "abcdef",
        '1' to "bc",
        '2' to "abdeg",
        '3' to "abcdg",
        '4' to "bcfg",
        '5' to "acdfg",
        '6' to "acdefg",
        '7' to "abc",
        '8' to "abcdefg",
        '9' to "abcdfg",
        '-' to "g",
        '+' to "gV",      // '+': 中间横 + 竖（用 V 表示额外竖段）
        'A' to "abcefg",
        'E' to "adefg",
        'F' to "aefg",
        'L' to "def",
        'P' to "abefg",
        'U' to "bcdef",
        'T' to "defg"
    )

    /**
     * 画一个七段字符：先画全部段的 ghost（未点亮的液晶隐约可见），再画点亮段。
     */
    private fun draw7(canvas: Canvas, ch: Char, x: Float, y: Float, w: Float, h: Float, onColor: Int) {
        val t = max(1.2f, h * 0.13f)     // 段宽（笔画粗）
        val gp = t * 0.30f                // 段间留隙
        val lit = SEGMENTS[ch.uppercaseChar()] ?: ""

        segPaint.strokeWidth = t
        // ghost：未点亮的段（极淡）
        segPaint.color = Color.argb(34, 40, 44, 36)
        drawSegSet(canvas, x, y, w, h, t, gp, "abcdefg")
        // 点亮
        segPaint.color = onColor
        drawSegSet(canvas, x, y, w, h, t, gp, lit)
        // '+' 的竖段
        if (lit.contains('V')) {
            val midX = x + w / 2f
            canvas.drawLine(midX, y + h * 0.28f + gp, midX, y + h * 0.72f - gp, segPaint)
        }
    }

    private fun drawSegSet(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, t: Float, gp: Float, set: String) {
        val x0 = x + t * 0.5f
        val x1 = x + w - t * 0.5f
        val yA = y + t * 0.5f
        val yG = y + h / 2f
        val yD = y + h - t * 0.5f
        val vTop = y + t * 0.5f + gp
        val vMid = yG - gp
        val vBot = yD - gp
        if (set.contains('a')) canvas.drawLine(x0 + gp, yA, x1 - gp, yA, segPaint)
        if (set.contains('g')) canvas.drawLine(x0 + gp, yG, x1 - gp, yG, segPaint)
        if (set.contains('d')) canvas.drawLine(x0 + gp, yD, x1 - gp, yD, segPaint)
        if (set.contains('f')) canvas.drawLine(x0, vTop, x0, vMid, segPaint)
        if (set.contains('b')) canvas.drawLine(x1, vTop, x1, vMid, segPaint)
        if (set.contains('e')) canvas.drawLine(x0, yG + gp, x0, vBot, segPaint)
        if (set.contains('c')) canvas.drawLine(x1, yG + gp, x1, vBot, segPaint)
    }

    // ---------------------------------------------------------------
    // 图标
    // ---------------------------------------------------------------

    /** 闪光符号（自绘闪电，避免 emoji 字体缺字） */
    private fun drawBolt(canvas: Canvas, x: Float, y: Float, h: Float, color: Int, filled: Boolean) {
        val w = h * 0.55f
        val p = Path().apply {
            moveTo(x + w * 0.55f, y)
            lineTo(x + w * 0.05f, y + h * 0.55f)
            lineTo(x + w * 0.42f, y + h * 0.55f)
            lineTo(x + w * 0.30f, y + h)
            lineTo(x + w * 0.95f, y + h * 0.40f)
            lineTo(x + w * 0.55f, y + h * 0.40f)
            lineTo(x + w * 0.72f, y)
            close()
        }
        fillPaint.color = if (filled) color else Color.argb(46, 40, 44, 36)
        fillPaint.style = Paint.Style.FILL
        canvas.drawPath(p, fillPaint)
    }

    /** 电池符号：外框 + 电量格 */
    private fun drawBattery(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int, level: Int) {
        val bodyW = w * 0.86f
        val capW = w * 0.10f
        strokePaint.color = color
        strokePaint.strokeWidth = max(1f, h * 0.12f)
        val body = RectF(x, y, x + bodyW, y + h)
        canvas.drawRoundRect(body, h * 0.18f, h * 0.18f, strokePaint)
        fillPaint.color = color
        canvas.drawRect(x + bodyW + strokePaint.strokeWidth * 0.2f, y + h * 0.28f,
            x + bodyW + capW, y + h * 0.72f, fillPaint)

        val inset = h * 0.22f
        val cells = 3
        val cellW = (bodyW - inset * 2f - (cells - 1) * h * 0.12f) / cells
        for (i in 0 until cells) {
            val cx = body.left + inset + i * (cellW + h * 0.12f)
            if (i < level.coerceIn(0, 3)) {
                canvas.drawRect(cx, body.top + inset, cx + cellW, body.bottom - inset, fillPaint)
            } else {
                fillPaint.color = Color.argb(40, 40, 44, 36)
                canvas.drawRect(cx, body.top + inset, cx + cellW, body.bottom - inset, fillPaint)
                fillPaint.color = color
            }
        }
    }

    private val density get() = resources.displayMetrics.density

    private fun mix(a: FloatArray, b: FloatArray, t: Float) =
        floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)

    private fun rgb(c: FloatArray) = Color.rgb(
        (c[0] * 255).toInt().coerceIn(0, 255),
        (c[1] * 255).toInt().coerceIn(0, 255),
        (c[2] * 255).toInt().coerceIn(0, 255)
    )

    /** 期望尺寸：未提供 XML 尺寸时的兜底（约 172x64dp） */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defW = (172 * resources.displayMetrics.density).toInt()
        val defH = (64 * resources.displayMetrics.density).toInt()
        val w = resolveSize(defW, widthMeasureSpec)
        val hh = resolveSize(defH, heightMeasureSpec)
        setMeasuredDimension(
            max(w, (min(defW, defH))),
            max(hh, (min(defW, defH) * 0.38f).toInt())
        )
    }
}
