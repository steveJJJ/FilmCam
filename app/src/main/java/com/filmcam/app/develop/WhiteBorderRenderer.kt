package com.filmcam.app.develop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * v66 白边相框（暗房留白 / A 方案）。
 *
 * 物理归属：真实暗房里白边是放大机压纸框的金属压条遮住相纸边缘、
 * 那一圈没被曝光所以显影后留白 —— 它属于**印相层**，不是拍摄层，
 * 也不是胶片本身的片基/齿孔。所以这里绝不下发 `overscan_*`：
 * 引擎输出纯画面，App 在落盘前只做「外扩画布 → 纸白底 → 贴画面」。
 *
 * 口径（用户 11-10 拍板）：四边**绝对像素等宽**，宽度 = 短边 × 6%，
 * 暖白相纸色 `#F7F5F0`，不画描边、不画阴影、不写日期到白边。
 * 日期戳仍留在画面内（两条通路都在白边合成之前绘制）。
 *
 * 几何计算与 Bitmap 渲染分离，前者是纯 Kotlin/零 Android 对象，可跑 JVM 单测。
 */
object WhiteBorderRenderer {

    const val BORDER_FRACTION = 0.06f
    const val PAPER_COLOR_ARGB = 0xFFF7F5F0.toInt()

    /** JPEG 回写质量：与引擎 exportJpeg 一致，避免白边档画质与别的档位不齐。 */
    private const val JPEG_QUALITY = 95

    data class Geometry(
        val srcW: Int,
        val srcH: Int,
        val margin: Int,
        val outW: Int,
        val outH: Int,
    )

    data class Size(val width: Int, val height: Int)

    /**
     * 四边绝对等宽：上/下/左/右都是同一个 margin，取**短边**的比例
     * （暗房 1/4" 压纸框就是绝对宽度，不随长边放大）。
     * 因此成片比例会略微偏离 3:2 —— 这是刻意选的 A1 口径。
     */
    fun geometry(srcW: Int, srcH: Int, fraction: Float = BORDER_FRACTION): Geometry {
        require(srcW > 0 && srcH > 0) { "非法画面尺寸 ${srcW}x${srcH}" }
        val f = fraction.coerceIn(0f, 0.25f)
        val margin = (minOf(srcW, srcH) * f).roundToInt().coerceAtLeast(1)
        return Geometry(srcW, srcH, margin, srcW + 2 * margin, srcH + 2 * margin)
    }

    /** RAW 通路：已有可变 Bitmap，外扩后由调用方回写 tmpJpeg。 */
    fun apply(src: Bitmap, fraction: Float = BORDER_FRACTION): Bitmap {
        val g = geometry(src.width, src.height, fraction)
        val out = Bitmap.createBitmap(g.outW, g.outH, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(PAPER_COLOR_ARGB)
            drawBitmap(src, g.margin.toFloat(), g.margin.toFloat(), null)
        }
        return out
    }

    /**
     * JPEG 通路：引擎直接写文件、没有现成 Bitmap，只能解码 → 外扩 → 回写。
     * ⚠️ 代价是多一次 JPEG 编解码（引擎已压过一次 95）。这是两通路都要落白边
     * 无法避免的取舍；白边是纯色大面积，95 质量二次压缩几乎无损。
     */
    fun applyToJpeg(file: File, fraction: Float = BORDER_FRACTION): Size {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val src = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IllegalStateException("白边相框：解码失败 ${file.absolutePath}")
        val g = geometry(src.width, src.height, fraction)
        val out = apply(src, fraction)
        src.recycle()
        try {
            FileOutputStream(file).use { f ->
                check(out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, f)) {
                    "白边相框：JPEG 压缩失败 ${file.absolutePath}"
                }
            }
        } finally {
            out.recycle()
        }
        return Size(g.outW, g.outH)
    }
}
