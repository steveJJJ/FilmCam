package com.filmcam.app.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/**
 * 钛银机身外壳：裸钛的观感来自三件事 ——
 *   1) 大面积的冷银灰（不是纯白，也不是不锈钢那种高亮）
 *   2) 极细的横向拉丝纹（T3 钛面板是细密横纹，不是镜面）
 *   3) 上亮下暗再回一点反射光的受光明暗
 * 这里直接画在 Canvas 上：一条垂直渐变打底 + 每 3dp 一条半透明细横线做拉丝，
 * 比用图片省事，也不会随屏幕拉伸变形。
 */
class TitanBodyDrawable : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b: Rect = bounds
        if (b.isEmpty) return

        // 1) 受光：顶部偏亮（环境光落在肩部），中段最暗，底部一线反射。
        //    取色与 BodyTheme.bodyTopRgb/Mid/Bot 同源（裸钛在自然光下偏暖的香槟灰，
        //    不是不锈钢冷银），GL 那侧的留白用的是同一组值。
        paint.shader = LinearGradient(
            0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(0xFFDAD2C4.toInt(), 0xFFC0B7A8.toInt(), 0xFFCEC6B8.toInt()),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
        paint.shader = null

        // 2) 拉丝：明暗交替的细横线，间隔 3dp、每条 1px
        val step = 3f * resourcesScalar()
        if (step <= 0f) return
        val top = b.top.toFloat()
        val bottom = b.bottom.toFloat()
        var y = top
        var i = 0
        while (y < bottom) {
            // 亮纹用暖白（不是纯白），暗纹用纯黑，钛面上才不会显出蓝灰调
            paint.color = if (i % 2 == 0) 0x16FFF6E6.toInt() else 0x15000000
            canvas.drawRect(b.left.toFloat(), y, b.right.toFloat(), y + 1f, paint)
            y += step
            i++
        }
    }

    private fun resourcesScalar(): Float =
        android.content.res.Resources.getSystem().displayMetrics.density

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    override fun getIntrinsicWidth(): Int = 1
    override fun getIntrinsicHeight(): Int = 1
}
