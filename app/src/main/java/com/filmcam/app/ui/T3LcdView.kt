package com.filmcam.app.ui

import android.content.Context
import android.graphics.Bitmap
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
        val now = SystemClock.uptimeMillis()
        // 背光淡出、或机身提示未到期时继续走帧（提示需要闪烁）
        if (now < backlightUntil + BACKLIGHT_FADE || now < messageUntil) scheduleTick()
    }

    private fun scheduleTick() {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 80)
    }

    /** 机身提示文案：非空且未到期时整屏显示（取代挂 intends 在机身上的浮层铭牌） */
    private var message: String? = null
    private var messageUntil = 0L

    /**
     * 在液晶屏上显示一句机身提示，到时自动恢复常规内容。
     *
     * 放在 LCD 里而不是另做一个浮层：这块屏本来就是机身的状态输出口，
     * 换卷互锁、不支持的胶卷这类「机身状态」从它这里走最自然，
     * 也不会额外引入一套与机身质感竞争的颜色。
     */
    fun showMessage(text: String, durationMs: Long = 3200L) {
        message = text
        messageUntil = SystemClock.uptimeMillis() + durationMs
        pulseBacklight(durationMs.coerceAtMost(BACKLIGHT_MS))
        invalidate()
        scheduleTick()
    }

    /** 从字符串资源取文案显示（机身提示一律走资源，跟随 App 语言）。 */
    fun showMessage(resId: Int, vararg args: Any?, durationMs: Long = 3200L) {
        showMessage(resources.getString(resId, *args), durationMs)
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
     * 屏幕内容：按真机排布——
     *   右侧：大号曝光计数七段（真机计数在屏右侧）；
     *   左列上：闪光 bolt + 闪光模式文字（AUTO 等），其下 AF eye 图标；
     *   底行小字：ISO / EV（有补偿时）/ 模式 + 电池。
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

        // 机身提示优先：占用整屏（比如换卷互锁），到时自动回到常规内容
        if (message != null) {
            if (SystemClock.uptimeMillis() < messageUntil) {
                drawMessage(canvas, rect, onColor)
                return
            }
            message = null
        }

        val ghostColor = Color.argb(46, 40, 44, 36)
        val labelPaint = Paint(textPaint).apply {
            color = onColor
            textSize = ch * 0.15f
            isFakeBoldText = true
        }
        val smallPaint = Paint(textPaint).apply {
            color = onColor
            textSize = ch * 0.135f
        }
        val flashOn = state.flashLabel != "OFF"

        // ---- 右侧：大号曝光计数（真机位置） ----
        val bigH = ch * 0.46f
        val bigW = bigH * 0.56f
        val countStr = state.count.toString().padStart(2, ' ').takeLast(2)
        var cx = right - (bigW * 2 + bigH * 0.14f)
        val y1 = rect.top + padY + ch * 0.02f
        countStr.forEach { c ->
            draw7(canvas, c, cx, y1, bigW, bigH, onColor)
            cx += bigW + bigH * 0.14f
        }

        // ---- 左列上：闪光 bolt + 模式文字（AUTO 等） ----
        val boltH = ch * 0.30f
        drawBolt(canvas, left, y1 + boltH * 0.08f, boltH, onColor, flashOn)
        canvas.drawText(state.flashLabel, left + boltH * 0.78f, y1 + boltH * 0.92f, labelPaint)

        // ---- 左列下：AF eye 图标（对焦确认） ----
        val eyeY = y1 + boltH + ch * 0.05f
        drawEye(canvas, left + boltH * 0.06f, eyeY, ch * 0.36f, ch * 0.20f, onColor, ghostColor)

        // ---- 底行小字：ISO / EV / 模式 + 电池 ----
        val smH = ch * 0.15f
        val smW = smH * 0.56f
        val y3 = rect.bottom - padY - smH - ch * 0.03f
        var ix = left
        canvas.drawText("ISO", ix, y3 + smH, smallPaint)
        ix += smallPaint.measureText("ISO") + ch * 0.04f
        state.isoLabel.takeLast(3).padStart(3, ' ').forEach { c ->
            draw7(canvas, c, ix, y3, smW, smH, onColor)
            ix += smW + smH * 0.14f
        }
        if (state.evSteps != 0f) {
            ix += ch * 0.06f
            canvas.drawText("EV", ix, y3 + smH, smallPaint)
            ix += smallPaint.measureText("EV") + ch * 0.035f
            val sign = if (state.evSteps > 0) '+' else '-'
            draw7(canvas, sign, ix, y3, smW, smH, onColor)
            ix += smW + smH * 0.14f
            val absI = kotlin.math.abs(state.evSteps).toInt()
            if (absI > 0) draw7(canvas, (absI + '0'.code).toChar(), ix, y3, smW, smH, onColor)
        }
        // 模式 + 电池靠右
        val modeStr = state.mode
        canvas.drawText(
            modeStr, right - ch * 0.40f - smallPaint.measureText(modeStr), y3 + smH,
            Paint(smallPaint).apply { isFakeBoldText = true }
        )
        drawBattery(canvas, right - ch * 0.32f, y3 + smH * 0.1f, ch * 0.32f, smH * 0.85f, onColor, state.battery)
    }

    // ---------------------------------------------------------------
    // 机身提示
    // ---------------------------------------------------------------

    /**
     * 整屏机身提示：居中，1.4Hz 闪烁。
     * 闪烁是液晶报警的通用语汇（数码相机报错、机身异常都这么做），
     * 比常亮更能表达「这是一个阻止你操作的互锁状态」。
     *
     * 字体走**点阵化**路径：先用小字号（关抗锯齿 + 等宽 + 字距）画到离屏位图，
     * 再整数倍硬放大贴回屏幕。小字号关抗锯齿后笔画是硬边锯齿，放大就是
     * 一格一格的像素块——这才是点阵液晶的字形，而不是普通黑体放大后的圆滑轮廓。
     * （试过内嵌像素字体包，GitHub 下载反复中断，这条零依赖路径效果等价且不加体积。）
     */
    private fun drawMessage(canvas: Canvas, rect: RectF, onColor: Int) {
        if ((SystemClock.uptimeMillis() / 350L) % 2L == 1L) return
        val msg = message ?: return

        val padY = 5f * density
        val ch = rect.height() - padY * 2f
        val scale = 2                                  // 硬放大倍数（整数倍才有整块像素）
        val maxW = (rect.width() - 16f * density) / scale

        val src = Paint().apply {
            typeface = Typeface.MONOSPACE
            isAntiAlias = false                        // 关抗锯齿 = 硬边 = 点阵感
            color = onColor
            textSize = (ch * 0.38f / scale).coerceAtLeast(9f)
            isFakeBoldText = true
            letterSpacing = 0.08f                      // 字距拉开，液晶段之间本就有间隙
        }
        // 文案长短差异大，按可用宽度自动缩字号，保证一句放得下
        while (src.measureText(msg) > maxW && src.textSize > 8f) src.textSize -= 0.5f

        val bmp = messageBitmap(msg, src)
        val dw = bmp.width * scale.toFloat()
        val dh = bmp.height * scale.toFloat()
        val left = rect.left + (rect.width() - dw) / 2f
        val top = rect.centerY() - dh / 2f
        dstRect.set(left, top, left + dw, top + dh)
        canvas.drawBitmap(bmp, null, dstRect, bitmapPaint)
    }

    /** 提示位图缓存：闪烁时每 80ms 重绘一次，缓存避免反复建图 */
    private var msgBmpKey = ""
    private var msgBmp: Bitmap? = null
    private val dstRect = RectF()
    private val bitmapPaint = Paint().apply { isFilterBitmap = false }
    private val msgCanvas = Canvas()

    private fun messageBitmap(msg: String, src: Paint): Bitmap {
        val key = msg + "|" + src.textSize.toInt()
        val cached = msgBmp
        if (cached != null && key == msgBmpKey) return cached
        val w = (src.measureText(msg) + 4f).toInt().coerceAtLeast(1)
        val h = (src.textSize * 1.5f).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        msgCanvas.setBitmap(bmp)
        bmp.eraseColor(Color.TRANSPARENT)
        val baseline = h * 0.5f - (src.fontMetrics.ascent + src.fontMetrics.descent) / 2f
        msgCanvas.drawText(msg, 2f, baseline, src)
        msgBmp = bmp
        msgBmpKey = key
        return bmp
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

    /** AF 眼睛符号：外轮廓椭圆 + 中央瞳孔（真机 LCD 上的对焦确认图标） */
    private fun drawEye(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, onColor: Int, ghostColor: Int) {
        strokePaint.color = onColor
        strokePaint.strokeWidth = max(1f, h * 0.13f)
        canvas.drawOval(RectF(x, y, x + w, y + h), strokePaint)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = onColor
        canvas.drawCircle(x + w * 0.5f, y + h * 0.5f, h * 0.22f, fillPaint)
        // 未点亮 ghost（左右两段弧，模拟熄灭段）
        strokePaint.color = ghostColor
        strokePaint.strokeWidth = max(1f, h * 0.10f)
        val gr = RectF(x - w * 0.10f, y + h * 0.08f, x + w * 1.10f, y + h * 0.92f)
        canvas.drawArc(gr, 150f, 60f, false, strokePaint)
        canvas.drawArc(gr, -30f, 60f, false, strokePaint)
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
