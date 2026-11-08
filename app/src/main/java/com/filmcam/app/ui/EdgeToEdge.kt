package com.filmcam.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15+(target 35+) 强制边到边适配：
 *  - 关掉 decor fits，让内容延伸到状态栏 / 导航栏之下；
 *  - 系统栏图标深浅交给 [BodyTheme.applySystemBars]（钛银=深图标、黑钛=浅图标）；
 *  - 把「顶部 inset」加到 [topView] 的 paddingTop、「底部 inset」加到 [bottomView]
 *    的 paddingBottom（[bottomAsMargin]=true 时加到 marginBottom）。
 *
 * 各页面根布局背景已经是机身黑 / 暗房红，透明系统栏下露出的就是机身色，
 * 与过去「状态栏同色」观感一致，所以这里只负责把顶 / 底控件推出系统栏，
 * 不改动相机预览、相册照片这类「本就该铺满全屏」的内容。
 */
object EdgeToEdge {

    fun setup(
        activity: AppCompatActivity,
        topView: View? = null,
        bottomView: View? = null,
        bottomAsMargin: Boolean = false,
        /**
         * 额外要一起补偿底部 inset 的控件。v54：竖屏机身铭牌与 [bottomBar] 同为
         * `bottom` gravity，只补 bottomBar 会让铭牌相对下移一个导航栏高度
         * （真机竖屏实测：铭牌上方空 130px、下方只剩 10px，v48 边到边回归）。
         * 只处理 [FrameLayout.LayoutParams]：横屏布局里铭牌是 LinearLayout 子项，
         * 会自动跳过，不受影响。
         */
        extraBottomMarginViews: List<View> = emptyList(),
    ) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        BodyTheme.applySystemBars(window)

        val root = activity.findViewById<View>(android.R.id.content)
        val topBase = topView?.paddingTop ?: 0
        val botBase = if (bottomAsMargin) {
            (bottomView?.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        } else {
            bottomView?.paddingBottom ?: 0
        }
        // -1 = 不是 FrameLayout 子项（横屏铭牌），跳过补偿
        val extraBases = extraBottomMarginViews.map { v ->
            (v.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin ?: -1
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topView?.let { v ->
                v.setPadding(v.paddingLeft, topBase + sb.top, v.paddingRight, v.paddingBottom)
            }
            if (bottomView != null) {
                if (bottomAsMargin) {
                    val lp = bottomView.layoutParams as? ViewGroup.MarginLayoutParams
                    if (lp != null) {
                        lp.bottomMargin = botBase + sb.bottom
                        bottomView.layoutParams = lp
                    }
                } else {
                    bottomView.setPadding(
                        bottomView.paddingLeft, bottomView.paddingTop,
                        bottomView.paddingRight, botBase + sb.bottom
                    )
                }
            }
            extraBottomMarginViews.forEachIndexed { i, v ->
                val base = extraBases[i]
                if (base < 0) return@forEachIndexed
                val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return@forEachIndexed
                if (lp.bottomMargin != base + sb.bottom) {
                    lp.bottomMargin = base + sb.bottom
                    v.layoutParams = lp
                }
            }
            insets
        }
    }

    /** 取 Activity 内容根布局（setContentView inflate 出来的那一层） */
    fun contentRoot(activity: AppCompatActivity): View? =
        (activity.findViewById<ViewGroup>(android.R.id.content))?.getChildAt(0)
}
