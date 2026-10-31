package com.filmcam.app.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日期戳渲染（规格 §6.4）。
 *
 * 逻辑照搬原 MainActivity.savePhoto：
 *  - 格式 `'yy MM dd`（SimpleDateFormat("''yy MM dd")，输出形如 `'26 10 30`）
 *  - 字体 dseg7.ttf（assets 已存在）
 *  - 主字 #FFC100，辉光 #FF8A00，阴影 #FF6600（setShadowLayer 18f）
 *  - 字号 = 宽 * 0.045，右下角 margin = 宽 * 0.04
 *  - letterSpacing 0.1f，抗锯齿
 *
 * 直接画在冲洗流程的输入位图上（进引擎之前），不产生任何 JPEG 编解码：
 * 省掉一次全尺寸 decode + encode，日期戳也会随胶卷 LUT 一起被调色，
 * 与真实底片上「拍摄时就把日期曝在胶片上」的成像方式一致。
 */
object DateStampRenderer {

    private const val COLOR_PRIMARY = 0xFFFFC100.toInt()
    private const val COLOR_GLOW = 0xFFFF8A00.toInt()
    private const val COLOR_SHADOW = 0xFFFF6600.toInt()

    @Volatile private var cachedTypeface: Typeface? = null

    private fun typeface(ctx: Context): Typeface {
        cachedTypeface?.let { return it }
        synchronized(this) {
            cachedTypeface?.let { return it }
            val tf = Typeface.createFromAsset(ctx.applicationContext.assets, "dseg7.ttf")
            cachedTypeface = tf
            return tf
        }
    }

    /**
     * 在 [bmp] 右下角就地绘制日期戳（要求 mutable）。
     * 调用前位图必须已按拍摄方向旋转到位，否则日期戳会落在错误的一角。
     */
    fun drawOnBitmap(ctx: Context, bmp: Bitmap, takenAtMs: Long) {
        require(bmp.isMutable) { "位图不可变，无法绘制日期戳" }
        val w = bmp.width
        val h = bmp.height

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(ctx)
            textSize = w * 0.045f
            color = COLOR_PRIMARY
            letterSpacing = 0.1f
            // 辉光/阴影：先画一层稍大的辉光，再用 setShadowLayer 加重
            setShadowLayer(18f, 0f, 0f, COLOR_SHADOW)
        }

        // 辉光层：把同一文字用 #FF8A00 再画一次（alpha 较低）
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(ctx)
            textSize = w * 0.045f
            color = COLOR_GLOW
            alpha = 110
            letterSpacing = 0.1f
            setShadowLayer(22f, 0f, 0f, COLOR_GLOW)
        }

        val text = SimpleDateFormat("''yy MM dd", Locale.US).format(Date(takenAtMs))
        val margin = w * 0.04f
        val fm = paint.fontMetrics
        val baseline = h - margin - fm.descent
        val x = w - margin - paint.measureText(text)

        val canvas = Canvas(bmp)
        canvas.drawText(text, x, baseline, glowPaint)
        canvas.drawText(text, x, baseline, paint)
    }
}
