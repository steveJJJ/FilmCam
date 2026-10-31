package com.filmcam.app.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日期戳渲染（规格 §6.4，v2：仿真石英日期机背成像）。
 *
 * 参考真实胶片机背（Contax T3 Quartz Date 一类）的光学特征：
 *  - 七段 LCD 数码管字形（dseg7.ttf），字距紧、段间几乎贴合
 *  - 颜色为红色 LED 透橙色滤片曝光后的橙红（#FF7E15 一带），不是数码金黄
 *  - LCD 隔着机背玻璃与片基曝光 → 边缘天然失焦发软（小半径模糊）
 *  - LCD 表面反光在玻璃上 → 约 0.5px 的鬼影（低透明度错位重绘一次）
 *  - 只有微弱光渗（大半径、极低透明度），没有霓虹式辉光
 *  - 曝光在底片上而非后期叠加 → 整体略降不透明度，且随引擎一起吃颗粒/调色
 *
 * 直接画在冲洗流程的输入位图上（进引擎之前），不产生任何 JPEG 编解码。
 */
object DateStampRenderer {

    // 石英日期机背实际成色：橙红主色 + 更深的红做光渗
    private const val COLOR_INK = 0xFFFF7E15.toInt()      // 主体（略降 alpha 模拟曝光密度）
    private const val COLOR_BLOOM = 0xFFFF5A00.toInt()    // 光渗

    private const val INK_ALPHA = 232                     // 曝光在片基上达不到纯 255
    private const val BLOOM_ALPHA = 42                    // 微弱光渗

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

        val text = SimpleDateFormat("''yy MM dd", Locale.US).format(Date(takenAtMs))

        // 真机背的日期相对画面更小（约 3.6% 画幅宽），位置更贴边
        val textSize = w * 0.036f
        val margin = w * 0.032f

        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(ctx)
            this.textSize = textSize
            letterSpacing = 0.06f                       // 段间贴合，不再是霓虹宽字距
            color = COLOR_INK
            alpha = INK_ALPHA
            // 失焦感：极小半径的边缘模糊（软件画布，BlurMaskFilter 生效）
            maskFilter = BlurMaskFilter(textSize * 0.055f, BlurMaskFilter.Blur.NORMAL)
        }

        // 鬼影：LCD 反光，整体错位约 0.4px、低透明度
        val ghostPaint = Paint(basePaint).apply {
            alpha = (INK_ALPHA * 0.28f).toInt().coerceAtMost(255)
            maskFilter = BlurMaskFilter(textSize * 0.075f, BlurMaskFilter.Blur.NORMAL)
        }

        // 光渗：大半径、极低透明度，模拟弱曝光在片基上的光晕
        val bloomPaint = Paint(basePaint).apply {
            color = COLOR_BLOOM
            alpha = BLOOM_ALPHA
            maskFilter = BlurMaskFilter(textSize * 0.22f, BlurMaskFilter.Blur.NORMAL)
        }

        val fm = basePaint.fontMetrics
        val baseline = h - margin - fm.descent
        val x = w - margin - basePaint.measureText(text)

        val canvas = Canvas(bmp)
        // 鬼影偏移量：约 0.4px，缩放到不同分辨率下等比
        val ghostDx = textSize * 0.011f
        val ghostDy = -textSize * 0.006f
        canvas.drawText(text, x + ghostDx, baseline + ghostDy, ghostPaint)
        canvas.drawText(text, x, baseline, bloomPaint)
        canvas.drawText(text, x, baseline, basePaint)
    }
}
