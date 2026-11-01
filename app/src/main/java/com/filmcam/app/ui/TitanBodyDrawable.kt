package com.filmcam.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 钛银机身外壳。
 *
 * 工艺按真机来：T3 的机身各评测一致描述为 **smooth titanium**（光滑钛面，
 * 保时捷设计的流线平滑面），**不是拉丝面** —— 所以这里不做任何规则纹路，只有：
 *   1) 大面积平缓的受光明暗（顶缘肩线最亮 → 中段最暗 → 底缘一线反射）
 *   2) 极细的缎面砂感：金属哑光面那种肉眼几乎看不出、但不会显得像塑料的颗粒
 *   3) 四面极淡的转折暗角：面板边缘是圆角过渡到侧面的，纯平铺会显得像贴纸
 *
 * 直接画在 Canvas 上，不用图片，不随屏幕拉伸变形。
 */
class TitanBodyDrawable : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 砂面噪点图（128²，白黑随机点 + 随机 alpha，叠加后亮度均值不变） */
    private val grainBitmap: Bitmap by lazy { buildGrainBitmap() }

    override fun draw(canvas: Canvas) {
        val b: Rect = bounds
        if (b.isEmpty) return
        val l = b.left.toFloat(); val t = b.top.toFloat()
        val r = b.right.toFloat(); val bt = b.bottom.toFloat()

        // 1) 受光：5 档明暗，过渡做得很缓 —— 光滑金属的明暗是大面积的、不分区的
        val stops = BodyTheme.BODY_SHADE_STOPS
        val colors = BodyTheme.bodyShadeColors
        paint.shader = LinearGradient(
            0f, t, 0f, bt,
            colors, stops, Shader.TileMode.CLAMP
        )
        canvas.drawRect(l, t, r, bt, paint)
        paint.shader = null

        // 2) 缎面砂感：平铺噪点，幅度只有 ±1.4%，看不出颗粒但去掉"塑料感"
        paint.shader = BitmapShader(grainBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        paint.alpha = (BodyTheme.BODY_GRAIN / GRAIN_MEAN_ALPHA * 255).roundToInt().coerceIn(0, 255)
        canvas.drawRect(l, t, r, bt, paint)
        paint.shader = null
        paint.alpha = 255

        // 3) 边缘转折：四边各 2.5% 的范围压暗一点点（幅度很小，只是让面不是死平）
        val ex = (r - l) * 0.025f
        val ey = (bt - t) * 0.025f
        paint.shader = LinearGradient(l, t, l + ex, t, EDGE_DARK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(l, t, l + ex, bt, paint)
        paint.shader = LinearGradient(r, t, r - ex, t, EDGE_DARK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(r - ex, t, r, bt, paint)
        paint.shader = LinearGradient(l, t, l, t + ey, EDGE_DARK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(l, t, r, t + ey, paint)
        paint.shader = LinearGradient(l, bt, l, bt - ey, EDGE_DARK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(l, bt - ey, r, bt, paint)
        paint.shader = null
    }

    /**
     * 造一张中性噪点图：每个像素随机黑或白、随机 alpha。
     * 黑白各半所以叠加后整体亮度基本不变，只留下极细的颗粒。
     */
    private fun buildGrainBitmap(): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val px = IntArray(size * size)
        val rnd = Random(20261101)
        for (i in px.indices) {
            val white = rnd.nextBoolean()
            val a = rnd.nextInt(64)
            px[i] = if (white) (a shl 24) or 0xFFFFFF else (a shl 24)
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    override fun getIntrinsicWidth(): Int = 1
    override fun getIntrinsicHeight(): Int = 1

    private companion object {
        const val EDGE_DARK = 0x14000000
        /** 噪点图的平均 alpha（像素 alpha 取 0..63 的均匀分布 → 均值 32/255） */
        const val GRAIN_MEAN_ALPHA = 32f / 255f
    }
}
