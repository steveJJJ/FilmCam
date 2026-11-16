package com.filmcam.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * 腰平取景屏的罩框覆盖层（Contax 645 MF-2 语言）。
 *
 * 真机 MF-2 展开后，磨砂屏四周是一圈窄罩壁，屏框四角各有一个小三角刻标，
 * 中央是翻起来的放大镜（loupe）。这里只画三样东西：
 *  ① 四角小三角刻标（不是大 L 角标——实物就是小三角）
 *  ② 中央放大镜圈 + 十字（P0 视觉暗示，P2 接点按对焦）
 *  ③ 一圈细取景格线
 *
 * 本 View 的 bounds **就是画幅矩形**（WaistLevelChrome 已按 posture.gateAspect
 * 内接铺好，与 GL 表面同几何）：取景格 = 自身边界内缩一圈，不再内接换算 ——
 * 表面即成片（waistFill 裁切填充），格线画在哪成片就裁到哪。
 *
 * 另有一行**短时**白平衡读数（wbReadout）：腰平态把 T3 那层标记全部抑制了，
 * 横滑换片就没有任何视觉反馈，只剩震动。这行读数只在横滑期间由 MainActivity
 * 写入、抬手后清空，稳态为空字符串即完全不画 —— MF-2 静态语言不被常驻 HUD 破坏。
 *
 * 全程不碰预览本身，纯叠加绘制；尺寸随画幅矩形变化，onDraw 里按宽高算。
 */
class WaistFinderOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x59C9C7C1
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xB3C9C7C1.toInt()
    }
    private val loupePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x59C9C7C1
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        strokeCap = Paint.Cap.ROUND
        color = 0x59C9C7C1
    }
    private val triPath = Path()

    /** 短时白平衡读数（如 `WB ≈5500K · DAYLIGHT`）；空串 = 不画。写入即触发重绘。 */
    var wbReadout: String = ""
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val readoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textSize = dp(11f)
        letterSpacing = 0.08f
        color = 0xE69FE1CB.toInt()
    }
    private val readoutBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xB3000000.toInt()
    }
    private val readoutRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 边界即画幅：内缩 6dp 画细取景格，格线贴着成片边缘。
        val inset = dp(6f)
        val fl = inset
        val ft = inset
        val fr = w - inset
        val fb = h - inset
        if (fr - fl <= 1f || fb - ft <= 1f) return
        canvas.drawRect(fl, ft, fr, fb, framePaint)

        // 四角小三角：底边贴框、尖朝画面内，跟 MF-2 屏框刻标一致
        val t = dp(7f)
        drawCorner(canvas, fl, ft, t, true, true)
        drawCorner(canvas, fr, ft, t, false, true)
        drawCorner(canvas, fl, fb, t, true, false)
        drawCorner(canvas, fr, fb, t, false, false)

        // 中央放大镜位：细圈 + 十字
        val cx = w / 2f
        val cy = h / 2f
        val rad = dp(24f)
        canvas.drawCircle(cx, cy, rad, loupePaint)
        val c = dp(4f)
        canvas.drawLine(cx - c, cy, cx + c, cy, crossPaint)
        canvas.drawLine(cx, cy - c, cx, cy + c, crossPaint)

        // 短时 WB 读数：贴取景格下沿居中，避开四角小三角与中央放大镜位。
        // 半透明黑胶囊只是为在亮画面上保住可读性（T3 LCD 同源薄荷绿 monospace），
        // 不常驻 —— 稳态 wbReadout 为空，这一层完全不存在。
        if (wbReadout.isNotEmpty()) {
            val textY = fb - dp(16f)
            val padX = dp(10f)
            val padY = dp(6f)
            val top = textY - readoutPaint.textSize - padY
            if (top > ft + dp(20f)) {
                val halfW = readoutPaint.measureText(wbReadout) / 2f + padX
                readoutRect.set(cx - halfW, top, cx + halfW, textY + padY)
                canvas.drawRoundRect(readoutRect, dp(4f), dp(4f), readoutBgPaint)
                canvas.drawText(wbReadout, cx, textY, readoutPaint)
            }
        }
    }

    private fun drawCorner(
        canvas: Canvas, x: Float, y: Float, t: Float,
        left: Boolean, top: Boolean,
    ) {
        triPath.reset()
        val dx = if (left) t else -t
        val dy = if (top) t else -t
        triPath.moveTo(x, y)
        triPath.lineTo(x + dx, y)
        triPath.lineTo(x, y + dy)
        triPath.close()
        canvas.drawPath(triPath, markPaint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
