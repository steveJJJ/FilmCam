package com.filmcam.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 取景框线覆盖层：模拟康泰时 T3 取景窗内的白色取景框线。
 * 不做四周暗角、不遮挡预览——只在实时预览上叠加 T3 取景窗的白色框线：
 *  - 画面取景框（正常拍摄取景范围）
 *  - 近摄视差框（0.35–0.8m 近摄视差校正框）
 *  - 中央对焦框（黄斑对焦区指示）
 */
class ViewfinderOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    /** 画面取景框：白色实线 */
    private val pictureFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xE6FFFFFF.toInt()      // 白色
        strokeWidth = 1.8f * density
    }

    /** 近摄视差框 / 对焦框：白色细线，略淡 */
    private val subFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xBFFFFFFF.toInt()      // 白色（半透）
        strokeWidth = 1.1f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // ---- 画面取景框（正常取景范围）：居中，避开顶部 LCD 行与底部快门条 ----
        val l = w * 0.10f
        val r = w * 0.90f
        val t = h * 0.19f
        val b = h * 0.81f
        canvas.drawRect(l, t, r, b, pictureFramePaint)

        // ---- 近摄视差框（画面框内，较小）：标记 0.35–0.8m 近摄视差校正范围 ----
        val nl = w * 0.17f
        val nr = w * 0.83f
        val nt = h * 0.26f
        val nb = h * 0.74f
        canvas.drawRect(nl, nt, nr, nb, subFramePaint)

        // ---- 中央对焦框（黄斑对焦区）：取景画面中央的小白框 ----
        val cw = w * 0.16f
        val chh = h * 0.10f
        val cx = w / 2f - cw / 2f
        val cy = h * 0.50f - chh / 2f
        canvas.drawRect(cx, cy, cx + cw, cy + chh, subFramePaint)
    }
}
