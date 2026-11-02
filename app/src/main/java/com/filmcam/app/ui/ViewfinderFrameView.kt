package com.filmcam.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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

    /** 白平衡图标种类（对应用户可用的 WB 预设，语义等同胶片机的校色滤镜片） */
    enum class WbIcon { AUTO, BULB, TUBE, SUN, CLOUD_SUN, SHADE, TWILIGHT }

    /** 取景器内的条件显示状态 */
    data class Indicators(
        val focus: LedMode = LedMode.OFF,
        val evCompOn: Boolean = false,
        val evSteps: Int = 0,              // -3 ~ +3
        val flash: LedMode = LedMode.OFF,  // 闪光指示
        val shutterLit: Set<String> = emptySet(),  // 点亮的快门档位，如 {"500","30"}
        val shutterBlink: Boolean = false,         // 快门档闪烁（实际最快档/过曝警告）
        val shutterSlow: Boolean = false,          // 慢门警告：快门慢到会抖，档位慢闪（T3 手册：当心手抖）
        val wbOn: Boolean = false,         // 白平衡面板是否在显示（横滑调 WB 期间）
        val wbIndex: Int = 0,              // 当前 WB 档位序号
        val wbCount: Int = 7               // 总档位数（画档位小点时用）
    )

    private val density = resources.displayMetrics.density
    private val stroke = 3.0f * density

    // ① 大亮框角标
    private val brightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xCCFFFFFF.toInt()
    }

    // ② 内侧视差小角标：略细、略淡
    private val parallaxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke * 0.85f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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

    /** 取景器左侧指示灯之间的最小净空 */
    private val INDICATOR_GAP = 9f * density

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

        // 预览内容矩形（几何唯一来源：contentRectFor，与 shader / 对焦映射一致）
        val content = contentRectFor(w, h)
        val cl = content.left
        val cr = content.right
        val ct = content.top
        val cb = content.bottom
        val cw = cr - cl
        val ch = cb - ct

        // ---- ① 四角大亮框角标 ----
        // 内收量由占内容宽的 3.5% 提到 6%：原先角标贴着画面边缘，
        // 与手机屏幕黑边只隔几十像素，看起来像屏幕 UI 而不是光学取景框。
        // 真机 T3 的亮框是有明显余量的——取景范围本来就略小于实际成像，
        // 框线离开边缘正是那个「视感想」的来源。
        val bInset = cw * 0.060f
        val bLen = cw * 0.075f
        drawBrackets(canvas, cl + bInset, ct + bInset, cr - bInset, cb - bInset, bLen, brightPaint)

        // ---- ② 内侧视差补偿小角标（随大框同步内收） ----
        val pInset = cw * 0.135f
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

        // ---- 以下为条件显示（对焦/曝光/闪光/白平衡随状态浮现）----
        val st = indicators ?: return
        val alpha = indicatorsAlpha()
        if (alpha <= 0f) return

        val iconSize = minOf(cw, ch) * 0.032f
        val baseLine = cb - ch * 0.055f

        // 左侧指示灯列：自下而上依次排 ⑤ EV ± → ⑦ 闪光。
        // 基线随内容上移时用「字体度量 + 固定间距」算净空，不再用魔术数字——
        // 之前 ± 的文字实际高度（约 1.2×iconSize）大于两者间距（0.6×iconSize），
        // 导致闪光闪电压在 ± 上。
        val colLeft = cl + cw * 0.085f
        var colBase = cb - ch * 0.28f

        // ⑤ 曝光补偿 ±（左侧偏下）：
        // 不能放在画面底部角落——那里有亮框和视差小角标两层横线，± 文字会压线。
        // 放到左侧、垂直约 7/10 高度处，四周留出净空（与真机 T3 左侧指示灯位一致）。
        if (st.evCompOn) {
            val pmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = iconSize * 1.6f
                color = Color.argb((0xE6 * alpha).toInt(), 255, 255, 255)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val text = if (st.evSteps > 0) "+${st.evSteps}" else st.evSteps.toString()
            canvas.drawText(text, colLeft, colBase, pmPaint)
            canvas.drawText(
                "±", colLeft + pmPaint.measureText(text) + 3 * density, colBase, pmPaint
            )
            // 上移到文字顶部之上，再留一段净空
            colBase += pmPaint.fontMetrics.ascent - INDICATOR_GAP
        }

        // ⑦ 闪光指示（左侧，紧贴 EV 上方；无 EV 时固定在同高度，避免忽上忽下）
        if (st.flash != LedMode.OFF) {
            val boltH = iconSize * 1.6f
            drawBolt(canvas, colLeft + 1.5f * density, colBase - boltH, boltH, st.flash, alpha)
        }

        // ⑥ 对焦指示绿色 LED：画面下方居中
        drawLed(canvas, cx, baseLine, iconSize, st.focus, alpha, 120, 255, 140)

        // ⑧ 快门速度档位：右侧竖排 500 / 125 / 30 / LT
        drawShutterScale(
            canvas, cr - cw * 0.075f, ct, cb, st.shutterLit,
            st.shutterBlink, st.shutterSlow, alpha
        )

        // ⑨ 白平衡面板：改为在取景器这张「玻璃」上一并绘制，样式同 ⑤⑧，
        //    不再用屏幕中间那块中文浮层——中文字体与这层线性标记不是一套东西。
        if (st.wbOn) drawWbPanel(canvas, cx, cy, iconSize, st, alpha)
    }

    // ---------------------------------------------------------------
    // 白平衡面板
    // ---------------------------------------------------------------

    /**
     * 白平衡面板：左右一对三角箭头 + 中间的 WB 图标 + 下方一排档位小点。
     *
     * 位置在中央 AF 弧括号下方——既避开四角亮框和视差小角标两层横线，
     * 也避开⑥合焦绿灯所在的底部居中位，是画面里唯一还空着的地带。
     * 画法沿用 brightPaint 的白描边：不引入新颜色，和 ①②③ 是同一层「玻璃上的
     * 蚀刻线」，这样在不同亮背景上都能读出来。
     */
    private fun drawWbPanel(
        canvas: Canvas, cx: Float, cy: Float, iconSize: Float, st: Indicators, alpha: Float
    ) {
        // 图标按 iconSize 的 2.9 倍定高（原为 2.0 倍，真机上看偏小、白线细节糊在一起）；
        // 同时整体下移到 5.0 倍处，给上方 AF 弧括号留出净空，下方档位点仍有余量。
        val y = cy + iconSize * 5.0f
        val iconH = iconSize * 2.9f
        val iconW = iconH * 1.35f
        val arrowH = iconH * 0.55f
        val arrowW = arrowH * 0.72f
        val gap = iconSize * 0.85f

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke * 0.92f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.argb((0xCC * alpha).toInt(), 255, 255, 255)
        }

        // 左右箭头：第一/最后一档时收淡，表示到头了
        drawTriangleArrow(canvas, cx - iconW / 2f - gap - arrowW, y, arrowW, arrowH, false, alpha, st.wbIndex > 0)
        drawTriangleArrow(canvas, cx + iconW / 2f + gap, y, arrowW, arrowH, true, alpha, st.wbIndex < st.wbCount - 1)

        // 中间当前档位的图形图标
        drawWbIcon(canvas, cx - iconW / 2f, y - iconH * 0.5f, iconW, iconH, st.wbIndex, p, alpha)

        // 下方档位小点：当前档实心，其余空心
        if (st.wbCount > 1) {
            val dotR = iconSize * 0.185f
            val dotGap = iconSize * 0.72f
            val totalW = (st.wbCount - 1) * dotGap
            val dy = y + iconH * 0.72f
            for (i in 0 until st.wbCount) {
                val dx = cx - totalW / 2f + i * dotGap
                val selected = i == st.wbIndex
                val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(((if (selected) 0xE6 else 0x80) * alpha).toInt(), 255, 255, 255)
                    style = if (selected) Paint.Style.FILL else Paint.Style.STROKE
                    strokeWidth = stroke * 0.6f
                }
                canvas.drawCircle(dx, dy, dotR, dp)
            }
        }
    }

    /** 左右切换箭头 */
    private fun drawTriangleArrow(
        canvas: Canvas, x: Float, centerY: Float, w: Float, h: Float,
        right: Boolean, alpha: Float, enabled: Boolean
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(((if (enabled) 0xCC else 0x4D) * alpha).toInt(), 255, 255, 255)
        }
        val path = Path().apply {
            if (right) {
                moveTo(x, centerY - h / 2f); lineTo(x + w, centerY); lineTo(x, centerY + h / 2f)
            } else {
                moveTo(x + w, centerY - h / 2f); lineTo(x, centerY); lineTo(x + w, centerY + h / 2f)
            }
            close()
        }
        canvas.drawPath(path, p)
    }

    /**
     * WB 图标：七个档位各一个白描边图形。
     * 全部 stroke 绘制，与①~④同一根线宽，保证缩放后仍是「线」而不是「块」。
     */
    private fun drawWbIcon(
        canvas: Canvas, x: Float, y: Float, w: Float, h: Float, index: Int, p: Paint, alpha: Float
    ) {
        val cx = x + w / 2f
        val cy = y + h / 2f
        val r = minOf(w, h) * 0.20f
        when (index) {
            0 -> {                                  // AUTO：半填充的圆（自动白平衡的通用符号）
                canvas.drawCircle(cx, cy, r, p)
                val f = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb((0xB3 * alpha).toInt(), 255, 255, 255)
                    style = Paint.Style.FILL
                }
                canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 0f, 180f, true, f)
            }
            1 -> {                                  // 白炽灯：灯泡
                canvas.drawCircle(cx, cy - r * 0.25f, r * 0.78f, p)
                val neck = Path().apply {
                    moveTo(cx - r * 0.34f, cy + r * 0.42f)
                    lineTo(cx - r * 0.34f, cy + r * 0.92f)
                    moveTo(cx + r * 0.34f, cy + r * 0.42f)
                    lineTo(cx + r * 0.34f, cy + r * 0.92f)
                    moveTo(cx - r * 0.34f, cy + r * 0.92f)
                    lineTo(cx + r * 0.34f, cy + r * 0.92f)
                }
                canvas.drawPath(neck, p)
            }
            2 -> {                                  // 荧光灯：直管灯（两端带帽）
                canvas.drawRoundRect(
                    RectF(cx - r * 1.05f, cy - r * 0.34f, cx + r * 1.05f, cy + r * 0.34f),
                    r * 0.16f, r * 0.16f, p
                )
                canvas.drawLine(cx - r * 1.05f, cy - r * 0.62f, cx - r * 1.05f, cy + r * 0.62f, p)
                canvas.drawLine(cx + r * 1.05f, cy - r * 0.62f, cx + r * 1.05f, cy + r * 0.62f, p)
            }
            3 -> {                                  // 日光：太阳（日轮 + 八道芒）
                canvas.drawCircle(cx, cy, r * 0.60f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val i0 = r * 0.86f
                    val i1 = r * 1.32f
                    canvas.drawLine(
                        cx + (Math.cos(a) * i0).toFloat(), cy + (Math.sin(a) * i0).toFloat(),
                        cx + (Math.cos(a) * i1).toFloat(), cy + (Math.sin(a) * i1).toFloat(), p
                    )
                }
            }
            4 -> {                                  // 阴天：云 + 半遮的日轮
                canvas.drawArc(RectF(cx - r * 0.95f, cy - r * 1.05f, cx + r * 0.15f, cy - r * 0.05f),
                    200f, 140f, false, p)
                cloudPath(canvas, cx - r * 0.35f, cy + r * 0.15f, r * 1.25f, r * 0.72f, p)
            }
            5 -> {                                  // 阴影：纯云（无日轮）
                cloudPath(canvas, cx - r * 1.05f, cy - r * 0.35f, r * 2.1f, r * 1.15f, p)
            }
            6 -> {                                  // 暮光：地平线 + 下沉的日轮
                canvas.drawLine(cx - r * 1.2f, cy + r * 0.15f, cx + r * 1.2f, cy + r * 0.15f, p)
                canvas.drawArc(RectF(cx - r * 0.65f, cy - r * 1.0f, cx + r * 0.65f, cy + r * 0.30f),
                    0f, 180f, false, p)
            }
        }
    }

    /** 云：三段叠圆弧（T3 风格线条：连续一笔而成，不画成孤立气泡） */
    private fun cloudPath(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, p: Paint) {
        val path = Path().apply {
            moveTo(x, y + h * 0.62f)
            arcTo(RectF(x, y + h * 0.10f, x + w * 0.44f, y + h * 0.86f), 110f, 150f)
            arcTo(RectF(x + w * 0.22f, y, x + w * 0.78f, y + h * 0.66f), 250f, 140f)
            arcTo(RectF(x + w * 0.52f, y + h * 0.14f, x + w, y + h * 0.90f), 285f, 120f)
        }
        canvas.drawPath(path, p)
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
        lit: Set<String>, blink: Boolean, slow: Boolean, alpha: Float
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
        // 慢门用 2Hz 慢闪：这是「当心手抖」的提醒，不是过曝那种急促报警，
        // 节奏慢下来才像 T3 取景器里那盏在暗处一下一下闪的灯。
        val blinking = if (slow) {
            (SystemClock.uptimeMillis() / 250L) % 2L == 0L
        } else {
            (SystemClock.uptimeMillis() / 125L) % 2L == 0L
        }
        labels.forEachIndexed { i, label ->
            val isLit = label in lit
            val on = isLit && (!(blink || slow) || blinking)
            paint.color = when {
                !isLit -> Color.argb((48 * alpha).toInt(), 255, 255, 255)
                on -> Color.argb((235 * alpha).toInt(), 150, 255, 170)
                else -> Color.argb((70 * alpha).toInt(), 255, 255, 255)
            }
            canvas.drawText(label, right, y0 + i * stepY, paint)
        }
    }

    /**
     * 四角括号。原先用两条 drawLine 交成直角，交角处会出现可见的拼接/锯齿痕迹；
     * 改为单笔 Path + 圆弧拐角（arcTo），配合 ROUND 的 strokeCap/strokeJoin，
     * 线条首尾也都收圆，整体更像玻璃上的刻线。
     */
    private fun drawBrackets(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, len: Float, p: Paint) {
        val vLen = len * 1.4f
        // 圆角半径取三者最小，避免超过横/竖边长度导致圆弧溢出偏转 90°
        val rr = minOf(stroke * 1.5f, len * 0.25f, vLen * 0.25f)

        // 左上：竖线下端 → 上行到接近拐角 → 圆弧 → 横线右行
        val tl = android.graphics.Path().apply {
            moveTo(l, t + vLen)
            lineTo(l, t + rr)
            arcTo(RectF(l, t, l + 2 * rr, t + 2 * rr), 180f, 90f, false)
            lineTo(l + len, t)
        }
        // 右上：横线右行 → 圆弧 → 竖线下行
        val tr = android.graphics.Path().apply {
            moveTo(r - len, t)
            lineTo(r - rr, t)
            arcTo(RectF(r - 2 * rr, t, r, t + 2 * rr), 270f, 90f, false)
            lineTo(r, t + vLen)
        }
        // 左下：横线左行 → 圆弧 → 竖线上行
        val bl = android.graphics.Path().apply {
            moveTo(l + len, b)
            lineTo(l + rr, b)
            arcTo(RectF(l, b - 2 * rr, l + 2 * rr, b), 90f, 90f, false)
            lineTo(l, b - vLen)
        }
        // 右下：竖线上端下行 → 圆弧 → 横线左行
        val br = android.graphics.Path().apply {
            moveTo(r, b - vLen)
            lineTo(r, b - rr)
            arcTo(RectF(r - 2 * rr, b - 2 * rr, r, b), 0f, 90f, false)
            lineTo(r - len, b)
        }

        canvas.drawPath(tl, p)
        canvas.drawPath(tr, p)
        canvas.drawPath(bl, p)
        canvas.drawPath(br, p)
    }

    /**
     * 预览内容矩形——即实际有画面的区域（letterbox 黑边之外），
     * 公式与 onDraw 内、以及 LutGLSurfaceView 的 shader 几何完全一致。
     *
     * 供 MainActivity 的触摸判定复用：落在黑边上的点按不应触发对焦。
     * ViewfinderFrameView 与 glSurfaceView 同为 match_parent 且完全重叠，
     * 因此返回的坐标可直接与 MotionEvent.x/y（相对 glSurfaceView）比较。
     */
    fun contentRect(): RectF {
        return contentRectFor(width.toFloat(), height.toFloat())
    }

    companion object {
        /** 竖屏预览内容宽高比 3:4（1440x1920，与 LutGLSurfaceView.renderTo 一致） */
        const val CONTENT_ASPECT_PORTRAIT = 1440f / 1920f

        /**
         * 横屏预览内容宽高比 4:3。
         * 传感器原生就是 4:3 横画面：竖屏时把它旋转 90° 显示成 3:4（JPEG 旋转 90°），
         * 横屏时直接按原方向显示成 4:3（JPEG 旋转 0°/180°）—— 两者恰好互为 90°，
         * 所以横屏只是换了内容比例，画面不会躺倒。
         */
        const val CONTENT_ASPECT_LANDSCAPE = 1920f / 1440f

        /**
         * 预览画面竖向偏移（仅竖屏有效）：0 = 在顶栏（铭牌+液晶屏）与底栏（快门条）之间的
         * 剩余空间里垂直居中。负值会把画面上移、导致取景框顶到机顶液晶屏，
         * 正值反之。取景框标记层与预览 shader 共用此常量，必须一致。
         *
         * -0.06：画面整体上移，让内容下沿（0.8077h→0.769h）落到机身铭牌
         * （取景框下方、快门条上方）之上，铭牌完全移出预览窗。
         */
        const val PREVIEW_OFFSET_Y = -0.06f

        /** 按屏幕朝向取内容比例：横屏 4:3、竖屏 3:4 */
        fun contentAspectFor(w: Float, h: Float): Float =
            if (w >= h) CONTENT_ASPECT_LANDSCAPE else CONTENT_ASPECT_PORTRAIT

        /**
         * 预览内容矩形——即实际有画面的区域（letterbox 黑边之外）。
         *
         * 竖屏：内容满**宽**（左右不留边，上下留黑边）；
         * 横屏：内容满**高**（上下不留边，左右留黑边，正好给侧边按键腾出位置）。
         * 判定用"屏幕比例 vs 内容比例"：屏幕比内容更宽就满高，否则满宽。
         *
         * 这是**唯一**的几何来源：onDraw、contentRect()、
         * LutGLSurfaceView 的 shader、Camera2Controller 的对焦映射全部走这里，
         * 避免四处公式各写一遍导致标记和画面对不上。
         */
        fun contentRectFor(w: Float, h: Float): RectF {
            if (w <= 0f || h <= 0f) return RectF()
            val ca = contentAspectFor(w, h)
            return if (w / h <= ca) {
                val ch = w / ca
                val cy = h / 2f + PREVIEW_OFFSET_Y * ch
                RectF(0f, (cy - ch / 2f).coerceIn(0f, h), w, (cy + ch / 2f).coerceIn(0f, h))
            } else {
                val cw = h * ca
                val cx = w / 2f
                RectF((cx - cw / 2f).coerceIn(0f, w), 0f, (cx + cw / 2f).coerceIn(0f, w), h)
            }
        }
    }
}
