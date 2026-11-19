package com.filmcam.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * 滚花转盘的**侧视图**（2026-11-19 为 Pro 模式新增）。
 *
 * 真机上的拇指轮从侧面看是一条竖棱纹的金属带，转动时棱纹往一侧走、中间露出当前刻度。
 * 这里按这个形态画：竖向棱纹铺满整块 → 中央压一条更亮的读数带 → 数值印在带中央。
 * 不用 SeekBar、不用滑条，因为滑条在这台机器的设计语汇里根本不存在（它只有旋钮和按键）。
 *
 * 交互（按用户 11-19 的要求：左右滑动循环）：
 *  - **左右滑动**：每滑过半格宽度进/退一格，带棘轮触感
 *  - **单击**：前进一格（给滑动不顺手的场合留的后路）
 *  - **长按**：回到默认档（AUTO / ±0），和旋钮「按下去复位」的机械直觉一致
 *
 * 数值循环（首尾相接），所以滑到头会绕回去，和真实拇指轮转到下一圈的刻度一致。
 */
class ThumbWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    /** 刻度文本（已格式化好，View 不做任何语义解释）。 */
    var labels: List<String> = emptyList()
        set(value) {
            field = value
            if (index >= value.size) index = 0
            invalidate()
        }

    /** 当前刻度下标。程序回填请用 [setIndexSilently]，否则会触发回调。 */
    var index: Int = 0
        private set

    /** 长按复位的目标档。 */
    var defaultIndex: Int = 0

    /**
     * 锁定态：外置闪光档下 ISO / 快门由同步模型接管，转盘画成灰的。
     *
     * 🔴 锁定不是「禁用」而是「如实说明」——转它会有触感但没有画面效果，
     * 那比灰着更糟，所以这里连手势都不接。
     */
    var locked: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** 刻度变化回调（只在用户操作时触发）。 */
    var onChanged: ((Int) -> Unit)? = null

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ribPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
        isFakeBoldText = true
    }
    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val bodyRect = RectF()

    // 手势状态
    private var downX = 0f
    private var lastX = 0f
    private var dragAcc = 0f
    private var downAt = 0L
    private var dragged = false
    private var lastTickAt = 0L

    init {
        isClickable = true
        isFocusable = true
    }

    fun setIndexSilently(i: Int) {
        val clamped = if (labels.isEmpty()) 0 else i.coerceIn(0, labels.size - 1)
        if (clamped != index) {
            index = clamped
            invalidate()
        }
    }

    private fun step(delta: Int) {
        if (labels.isEmpty()) return
        val n = labels.size
        val next = ((index + delta) % n + n) % n
        if (next == index) return
        index = next
        tick()
        invalidate()
        onChanged?.invoke(next)
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTickAt >= 40L) {
            lastTickAt = now
            Haptics.vibrate(context, 8, 110)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val dw = (56 * density).toInt()
        val dh = (40 * density).toInt()
        setMeasuredDimension(
            resolveSize(dw, widthMeasureSpec),
            resolveSize(dh, heightMeasureSpec)
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (locked) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                lastX = event.x
                dragAcc = 0f
                downAt = SystemClock.uptimeMillis()
                dragged = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                lastX = event.x
                if (abs(event.x - downX) > 6f * density) dragged = true
                // 半格宽一档：与真实拇指轮的刻度间距同量级，滑过一格就「卡」一下
                val stepPx = (width * 0.5f).coerceAtLeast(8f * density)
                dragAcc += dx
                while (dragAcc <= -stepPx) {
                    dragAcc += stepPx
                    step(+1)
                }
                while (dragAcc >= stepPx) {
                    dragAcc -= stepPx
                    step(-1)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val held = SystemClock.uptimeMillis() - downAt
                if (!dragged) {
                    if (held >= 480L) {
                        // 长按：回默认档（机械旋钮按下去复位）
                        if (defaultIndex in labels.indices && defaultIndex != index) {
                            index = defaultIndex
                            Haptics.vibrate(context, 20, 150)
                            invalidate()
                            onChanged?.invoke(index)
                        }
                    } else {
                        step(+1)
                    }
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragged = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h) * 0.18f
        bodyRect.set(0f, 0f, w, h)

        // 盘体
        bodyPaint.color = if (locked) Color.rgb(22, 22, 26) else Color.rgb(27, 27, 31)
        canvas.drawRoundRect(bodyRect, r, r, bodyPaint)

        // 竖向棱纹：转盘侧视图的主体特征
        ribPaint.strokeWidth = 1f * density
        ribPaint.color = if (locked) Color.rgb(28, 28, 32) else Color.rgb(46, 46, 52)
        val gap = 5f * density
        var x = gap * 0.5f
        while (x < w) {
            canvas.drawLine(x, 2f * density, x, h - 2f * density, ribPaint)
            x += gap
        }

        // 上下压边：金属轮的倒角
        bodyPaint.color = if (locked) Color.rgb(16, 16, 20) else Color.rgb(20, 20, 26)
        canvas.drawRect(0f, 0f, w, 2f * density, bodyPaint)
        canvas.drawRect(0f, h - 2f * density, w, h, bodyPaint)

        // 中央读数带：比盘体略亮，让数字「浮」在轮面上
        val bandH = h * 0.46f
        val band = RectF(0f, (h - bandH) / 2f, w, (h + bandH) / 2f)
        bodyPaint.color = if (locked) Color.rgb(26, 26, 30) else Color.rgb(38, 38, 44)
        canvas.drawRect(band, bodyPaint)

        // 外框
        edgePaint.strokeWidth = 1f * density
        edgePaint.color = if (locked) Color.rgb(38, 38, 44) else Color.rgb(58, 58, 64)
        canvas.drawRoundRect(bodyRect, r, r, edgePaint)

        // 读数
        val label = labels.getOrNull(index) ?: "--"
        textPaint.textSize = h * 0.40f
        textPaint.color = when {
            locked -> Color.rgb(74, 74, 80)
            else -> Color.rgb(220, 220, 224)
        }
        val baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, w / 2f, baseline, textPaint)

        // 锁定标记：右上角一枚小锁
        if (locked) {
            lockPaint.strokeWidth = 1.1f * density
            lockPaint.color = Color.rgb(74, 74, 80)
            val lw = 5f * density
            val lh = 4f * density
            val lx = w - lw - 4f * density
            val ly = 3.5f * density
            canvas.drawRoundRect(
                RectF(lx, ly + lh * 0.55f, lx + lw, ly + lh * 1.35f),
                1f * density, 1f * density, lockPaint
            )
            val arc = RectF(lx + lw * 0.2f, ly, lx + lw * 0.8f, ly + lh)
            canvas.drawArc(arc, 180f, 180f, false, lockPaint)
        }
    }
}
