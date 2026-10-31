package com.filmcam.app.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * 正方形 ImageView：高度始终等于宽度（由 GridLayoutManager 分配宽度）。
 *
 * 相册网格原来把缩略图高度写死 400px，在不同密度/分辨率屏幕上是截然不同的比例
 * （2K 屏上变成很扁的细条，折叠屏展开后又变成大方块）。正方形容器让格子在
 * 任意屏幕、任意列数下都保持一致的观感。
 */
class SquareImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 用宽度值当高度值：父容器给的是 EXACTLY/MATCH_PARENT，直接复用即可
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
