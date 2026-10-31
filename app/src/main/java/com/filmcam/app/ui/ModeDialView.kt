package com.filmcam.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 康泰时 T3 风格模式拨盘
 *
 * 外圈为可旋转的金属拨盘，档位沿圆周分布（P / A / 自拍）。
 * 手指围绕中心滑动可切换档位，带吸附与阻尼反馈。
 * 中心为快门按钮（由布局中的 btnShutter 覆盖，本 View 不处理快门）。
 */
class ModeDialView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode(val label: String, val code: String) {
        P("P", "P"),          // 程序自动
        A("A", "A"),          // 光圈优先
        SELF("SELF", "自拍")   // 自拍
    }

    var modes: List<Mode> = listOf(Mode.P, Mode.A, Mode.SELF)
        set(value) {
            field = value
            if (selectedIndex >= value.size) selectedIndex = 0
            invalidate()
        }

    var onModeChanged: ((Mode) -> Unit)? = null

    private var selectedIndex = 0

    // 触点历史，用于计算旋转增量
    private var lastAngle = 0.0

    private val sectorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        sectorPaint.style = Paint.Style.FILL
        ringPaint.style = Paint.Style.STROKE
        ringEdgePaint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        selectedTextPaint.textAlign = Paint.Align.CENTER
        selectedTextPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun setMode(mode: Mode) {
        val idx = modes.indexOf(mode)
        if (idx >= 0) {
            selectedIndex = idx
            invalidate()
        }
    }

    fun currentMode(): Mode = modes[selectedIndex]

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f
        val ringOuter = radius - 4f
        val ringInner = radius * 0.56f

        // 金属外环（径向渐变模拟金属质感）
        ringPaint.shader = RadialGradient(
            cx, cy, ringOuter,
            intArrayOf(Color.rgb(255, 255, 255), Color.rgb(200, 200, 205), Color.rgb(150, 150, 158), Color.rgb(90, 90, 98)),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        ringPaint.strokeWidth = ringOuter - ringInner
        canvas.drawCircle(cx, cy, (ringOuter + ringInner) / 2f, ringPaint)

        // 拨盘档位文字（沿圆周均匀分布）
        val n = modes.size
        val textRadius = (ringOuter + ringInner) / 2f
        for (i in 0 until n) {
            // 选中档位旋转到正上方（-90°），显示指示点
            val angleDeg = -90.0 + i * (360.0 / n)
            val rad = Math.toRadians(angleDeg)
            val tx = cx + (textRadius * cos(rad)).toFloat()
            val ty = cy + (textRadius * sin(rad)).toFloat() + 8f
            val isSelected = i == selectedIndex

            if (isSelected) {
                selectedTextPaint.color = Color.rgb(30, 30, 34)
                selectedTextPaint.textSize = radius * 0.26f
                canvas.drawText(modes[i].label, tx, ty, selectedTextPaint)
            } else {
                textPaint.color = Color.rgb(230, 230, 235)
                textPaint.textSize = radius * 0.2f
                canvas.drawText(modes[i].label, tx, ty, textPaint)
            }
        }

        // 指示点（当前档位正上方）
        val dotRadius = radius * 0.08f
        dotPaint.color = Color.rgb(255, 60, 60)
        canvas.drawCircle(cx, cy - ringOuter + dotRadius + 4f, dotRadius, dotPaint)

        // 中心凹陷阴影（为中央快门让位）
        innerShadowPaint.shader = RadialGradient(
            cx, cy, ringInner,
            intArrayOf(Color.rgb(60, 60, 66), Color.rgb(40, 40, 45), Color.rgb(25, 25, 28)),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, ringInner, innerShadowPaint)
    }

    private fun angleOf(x: Float, y: Float): Double {
        val cx = width / 2f
        val cy = height / 2f
        // atan2 返回 [-PI, PI]，转成 [0, 360)，并把 -90°(正上) 对齐到 0
        var deg = Math.toDegrees(atan2(y - cy, x - cx).toDouble())
        deg = (deg + 90 + 360) % 360
        return deg
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f
        val ringInner = radius * 0.56f

        val dx = event.x - cx
        val dy = event.y - cy
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastAngle = angleOf(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // 只在拨盘环区域响应旋转（不抢中心快门）
                if (dist < ringInner) return true
                val cur = angleOf(event.x, event.y)
                var delta = cur - lastAngle
                if (delta > 180) delta -= 360
                if (delta < -180) delta += 360
                lastAngle = cur

                // 每移动一格（360/n 度）切换一个档位
                val step = 360.0 / modes.size
                if (kotlin.math.abs(delta) >= step / 2) {
                    val steps = (delta / step).roundToInt()
                    selectedIndex = ((selectedIndex - steps) % modes.size + modes.size) % modes.size
                    invalidate()
                    onModeChanged?.invoke(modes[selectedIndex])
                }
                return true
            }
        }
        return true
    }
}
