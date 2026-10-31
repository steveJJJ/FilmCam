package com.filmcam.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 取景网格覆盖层：三等分线（FIMO 风格），可整体开/关。
 * 用代码绘制，避免 XML 百分比限制。
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4DFFFFFF.toInt()
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        // 竖直三等分线
        canvas.drawLine(w / 3f, 0f, w / 3f, h, paint)
        canvas.drawLine(w * 2 / 3f, 0f, w * 2 / 3f, h, paint)
        // 水平三等分线
        canvas.drawLine(0f, h / 3f, w, h / 3f, paint)
        canvas.drawLine(0f, h * 2 / 3f, w, h * 2 / 3f, paint)
    }
}
