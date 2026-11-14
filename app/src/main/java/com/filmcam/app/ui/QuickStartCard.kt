package com.filmcam.app.ui

import android.app.Activity
import android.view.LayoutInflater
import android.widget.TextView
import com.filmcam.app.R

/**
 * v82 首次使用引导 · 段 1「快速上手卡」：机身上的一页说明书，3 屏翻页。
 * 铭牌设计语言与设置面板 / 反馈弹窗同一套；尺寸与背景在 show() **之前**定死
 * （DialogShowJumpTest 契约：不得首帧按默认宽度渲染再跳变）。
 *
 * 两个入口共用：
 *  · MainActivity 首启（maybeShowFirstRunGuide）——播完从机顶 LCD 接力段 2；
 *  · 关于页「快速上手 · 重看」——纯重播，不碰 onboarding 进度。
 */
object QuickStartCard {

    /** 三屏文案（标题/正文成对，中英由资源系统切换） */
    private val TITLES = intArrayOf(R.string.qs_title_1, R.string.qs_title_2, R.string.qs_title_3)
    private val BODIES = intArrayOf(R.string.qs_body_1, R.string.qs_body_2, R.string.qs_body_3)

    fun show(
        activity: Activity,
        guard: (android.app.Dialog, () -> Unit) -> android.app.Dialog,
        onDismissed: () -> Unit = {},
    ) {
        val theme = BodyTheme
        theme.load(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_quick_start, null)
        theme.applyToViewTree(view)

        val tvTitle = view.findViewById<TextView>(R.id.tvQuickStartTitle)
        val tvBody = view.findViewById<TextView>(R.id.tvQuickStartBody)
        val tvPage = view.findViewById<TextView>(R.id.tvQuickStartPage)
        val tvDots = view.findViewById<TextView>(R.id.tvQuickStartDots)
        val tvNext = view.findViewById<TextView>(R.id.tvQuickStartNext)
        val tvLogo = view.findViewById<TextView>(R.id.tvQuickStartLogo)

        fun paint(page: Int) {
            tvTitle.setText(TITLES[page])
            tvBody.setText(BODIES[page])
            tvPage.text = "QUICK START · ${page + 1}/3"
            tvDots.text = if (page == 0) "● ○ ○" else if (page == 1) "○ ● ○" else "○ ○ ●"
            tvNext.text =
                activity.getString(if (page == 2) R.string.main_guide_start else R.string.qs_next)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity, theme.dialogTheme)
            .setView(view)
            .create()
        // 蚀刻字标随钛银档换墨色（与设置面板铭牌头同口径）
        tvLogo.setTextColor(if (theme.isTitan) theme.panelText else 0xFFE8E4DC.toInt())
        tvNext.setTextColor(if (theme.isTitan) theme.panelText else 0xFFE8E4DC.toInt())
        var page = 0
        paint(page)
        tvNext.setOnClickListener {
            if (page < 2) {
                page++
                paint(page)
            } else {
                dialog.dismiss()
            }
        }
        // dismiss 回调由 guard 织入（guardDialog 会覆盖 setOnDismissListener，
        // 所以不能各自挂：调用方负责把 onDismissed 合进它自己的守卫监听）
        guard(dialog) {
            // dismiss 之后才启动段 2：说明书卡在前台时 LCD 不该抢戏
            onDismissed()
        }
        // 尺寸/背景在 show() 之前定死：首帧即铭牌形态，零跳变
        val dm = activity.resources.displayMetrics
        val w = (dm.widthPixels * 0.86f).toInt().coerceAtMost((400 * dm.density).toInt())
        dialog.window?.setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawable(theme.panelDrawable())
        dialog.show()
    }
}
