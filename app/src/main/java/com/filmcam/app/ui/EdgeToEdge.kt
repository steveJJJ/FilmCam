package com.filmcam.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Android 15+(target 35+) 强制边到边适配：
 *  - v57 起改用官方 `enableEdgeToEdge()`（androidx.activity 1.8+）：它在所有
 *    API 级别统一关掉 decor fits、把系统栏设为透明，是 Play 要求的向后兼容做法；
 *    原先手写的 `WindowCompat.setDecorFitsSystemWindows(window, false)` 已移除。
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
        /**
         * v58：把本次派发的系统栏 inset 回传给调用方，供 [ChromeSafeArea] 用
         * 「预览留白」重新定位机身控件。不改变这里的补偿逻辑。
         * v68：前两个是「忽略可见性」的最大 inset（隐藏系统栏后仍稳定），
         * 后两个是当前**可见** inset，供诊断报告对照口径。
         */
        onSystemBarInsets: ((topPx: Int, bottomPx: Int, visibleTopPx: Int, visibleBottomPx: Int) -> Unit)? = null,
        /**
         * v68：只在取景器页（MainActivity）开启沉浸式隐藏系统栏。
         * 其余 4 个 Activity 保留系统栏 —— 看设置 / 相册 / 暗房仍能看到时间和通知。
         */
        immersive: Boolean = false,
    ) {
        val window = activity.window
        // 官方无边框开关：内部完成 decorFits=false + 系统栏透明，并向后兼容旧 API。
        // 透明后露出的是根布局机身底色，所以不需要（也不能再）设置栏颜色。
        activity.enableEdgeToEdge()
        BodyTheme.applySystemBars(window)
        if (immersive) window.decorView.post { applyImmersive(activity) }

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
            // v68 防回归铁律：隐藏系统栏后 getInsets(systemBars()) 会变成 0，
            // 直接用它会把 v54 修过的铭牌 / 底栏补偿一口气撤掉（控件下移一个
            // 导航栏高度），ChromeSafeArea.usableDp 也跟着跳。改用「忽略可见性」
            // 的最大 inset：栏在不在，补偿值都一致，布局一个像素都不动。
            val sb = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val visible = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            onSystemBarInsets?.invoke(sb.top, sb.bottom, visible.top, visible.bottom)
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

    /**
     * v68 沉浸式：隐藏状态栏 + 导航栏，边缘下滑可临时呼出（不是永久锁死）。
     * 官方 WindowInsetsControllerCompat，不需要弃用 API / 不新增权限、与 v57
     * 的 enableEdgeToEdge() 不冲突。切后台、下拉通知、弹权限框后系统会恢复系统栏，
     * 所以调用方必须在 onResume / onWindowFocusChanged 里重复调用。
     */
    fun applyImmersive(activity: AppCompatActivity) {
        runCatching {
            WindowInsetsControllerCompat(
                activity.window, activity.window.decorView
            ).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    /** 取 Activity 内容根布局（setContentView inflate 出来的那一层） */
    fun contentRoot(activity: AppCompatActivity): View? =
        (activity.findViewById<ViewGroup>(android.R.id.content))?.getChildAt(0)
}
