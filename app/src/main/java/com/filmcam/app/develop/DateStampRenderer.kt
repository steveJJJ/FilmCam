package com.filmcam.app.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import java.io.File
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
 * 就地重绘：解码 jpegFile → 可变 Bitmap → 画日期戳 → JPEG 95 写回同文件。
 */
object DateStampRenderer {

    private const val TAG = "DateStampRenderer"
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
     * 就地给 [jpegFile] 右下角盖日期戳（takenAtMs）。
     * 失败时只打日志、不抛异常（不影响整卷冲洗）。
     */
    fun apply(ctx: Context, jpegFile: File, takenAtMs: Long) {
        var src: Bitmap? = null
        var mutable: Bitmap? = null
        try {
            src = BitmapFactory.decodeFile(jpegFile.absolutePath)
                ?: throw IllegalStateException("decode 失败: ${jpegFile.absolutePath}")
            val w = src.width
            val h = src.height

            mutable = src.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("无法 copy 为 mutable Bitmap: ${jpegFile.absolutePath}")
            // src 不再需要，立即释放
            src.recycle()
            src = null

            val canvas = Canvas(mutable)
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

            canvas.drawText(text, x, baseline, glowPaint)
            canvas.drawText(text, x, baseline, paint)

            // 写回同文件（JPEG 95）
            jpegFile.outputStream().use { out ->
                mutable.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        } catch (e: Throwable) {
            // 失败不再静默吞掉：rethrow 进 processOneFrame 的 catch → 计入 errorCount → PHASE_ERROR
            Log.e(TAG, "日期戳失败: ${e.message}", e)
            throw e
        } finally {
            // 确保所有路径都回收 Bitmap，避免 12MP × 36 帧 OOM
            src?.recycle()
            mutable?.recycle()
        }
    }
}
