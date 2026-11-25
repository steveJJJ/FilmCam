package com.filmcam.app.ui

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.filmcam.app.R

/**
 * 设置面板「语言」行的二级铭牌：六档一次点选。
 *
 * 版式与机身其他弹窗同一套（11-26 用户反馈原六行纯文字太简陋）：
 * T3 字标 + LANGUAGE·06 蚀刻码表头、等宽序号 + 语言自名 + 规格副标 +
 * 落位刻记 SET；选中行琥珀暖底 + 左侧竖条 + 语言名转琥珀。
 *
 * 交互：按下即震（Haptics 24ms/175，同机身按键）+ 机械缩放（attachPressFeedback），
 * 点选后播 120ms 落位动画再切档 —— 不立刻 dismiss，用户能看见「选中了」。
 * 宽度/背景在 show() 之前定死（DialogShowJumpTest 契约：首帧零跳变）；
 * dismiss 监听只有 guardDialog 一个槽，由调用方织进来。
 *
 * 语言名用各语言自名（在任何界面语言下都不翻译），只有「跟随系统」走资源，
 * 与设置面板当前值 [name] 同一口径。
 */
object LanguagePicker {

    private val MODES = intArrayOf(
        AppLocale.SYSTEM, AppLocale.ZH, AppLocale.ZH_TW,
        AppLocale.EN, AppLocale.JA, AppLocale.KO
    )

    private val ROW_IDS = intArrayOf(
        R.id.langRowSystem, R.id.langRowZh, R.id.langRowZhTw,
        R.id.langRowEn, R.id.langRowJa, R.id.langRowKo
    )

    /** 刻在机身侧的规格副标：不随界面语言翻译（同 LANGUAGE·06 蚀刻码） */
    private val SPECS = arrayOf(
        "AUTO · FOLLOW DEVICE",
        "SIMPLIFIED · CHINESE",
        "TRADITIONAL · CHINESE",
        "ENGLISH · US",
        "JAPANESE · JP",
        "KOREAN · KR"
    )

    private val CODES = arrayOf("00", "01", "02", "03", "04", "05")

    /** 档位名：与 MainActivity.languageName() 共用同一口径 */
    fun name(activity: Activity, mode: Int): String = when (mode) {
        AppLocale.ZH -> "简体中文"
        AppLocale.ZH_TW -> "繁體中文"
        AppLocale.EN -> "English"
        AppLocale.JA -> "日本語"
        AppLocale.KO -> "한국어"
        else -> activity.getString(R.string.lang_system)
    }

    fun show(
        activity: Activity,
        guard: (android.app.Dialog, () -> Unit) -> android.app.Dialog,
        current: Int,
        onPick: (Int) -> Unit,
    ) {
        val theme = BodyTheme
        theme.load(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_language, null)

        // 六行由行模板填充（主题遍历要在行齐之后跑，否则动态行拿不到机身配色）
        val rows = ROW_IDS.mapIndexed { i, id ->
            val slot = view.findViewById<LinearLayout>(id)
            val row = LayoutInflater.from(activity)
                .inflate(R.layout.item_language_row, slot, false) as LinearLayout
            slot.addView(row)
            Row(
                root = row,
                bar = row.findViewById(R.id.langBar),
                code = row.findViewById(R.id.langCode),
                name = row.findViewById(R.id.langName),
                sub = row.findViewById(R.id.langSub),
                mark = row.findViewById(R.id.langMark),
            )
        }
        theme.applyToViewTree(view)

        val logo = view.findViewById<View>(R.id.ivLanguageLogo)
        logo.alpha = if (theme.isTitan) 0.88f else 1f
        val tag = view.findViewById<TextView>(R.id.tvLanguageTag)
        tag.setTextColor(if (theme.isTitan) theme.panelTextSecondary else 0xFF8A8A8E.toInt())
        val title = view.findViewById<TextView>(R.id.tvLanguageTitle)
        val sub = view.findViewById<TextView>(R.id.tvLanguageSub)
        title.setTextColor(theme.panelText)
        sub.setTextColor(theme.panelTextSecondary)

        val accent = theme.accent
        val baseText = theme.panelText
        val baseSecondary = theme.panelTextSecondary
        val dim = if (theme.isTitan) Color.parseColor("#D8D0C2") else Color.parseColor("#26262A")

        var selectedIndex = MODES.indexOf(current).coerceAtLeast(0)

        fun paint(progress: Float = 1f) {
            rows.forEachIndexed { i, r ->
                val on = i == selectedIndex
                // 落位进度：仅当前点中的行走 0→1，其余行是常态（旧选中立刻撤）
                val p = if (on) progress else 0f
                val bg = GradientDrawable().apply {
                    setColor(ColorUtils.blendARGB(Color.TRANSPARENT, dim, p))
                    cornerRadius = 4f * activity.resources.displayMetrics.density
                }
                r.root.background = bg
                r.bar.setBackgroundColor(ColorUtils.blendARGB(Color.TRANSPARENT, accent, p))
                r.code.text = CODES[i]
                r.code.setTextColor(if (on) ColorUtils.blendARGB(baseSecondary, accent, p) else baseSecondary)
                r.name.text = name(activity, MODES[i])
                r.name.setTextColor(if (on) ColorUtils.blendARGB(baseText, accent, p) else baseText)
                r.sub.text = SPECS[i]
                r.sub.setTextColor(if (on) ColorUtils.blendARGB(baseSecondary, accent, 0.6f * p) else baseSecondary)
                r.mark.text = if (on) "SET" else "···"
                r.mark.alpha = if (on) p else 0.45f
                r.mark.setTextColor(accent)
            }
        }
        paint()

        val dm = activity.resources.displayMetrics
        val w = (dm.widthPixels * 0.78f).toInt().coerceAtMost((400 * dm.density).toInt())
        val dialog = androidx.appcompat.app.AlertDialog.Builder(activity, theme.dialogTheme)
            .setView(view)
            .create()
        guard(dialog) {}
        val win = dialog.window
        win?.decorView   // installDecor()，此后 setLayout 不再被重置
        win?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        win?.setBackgroundDrawable(theme.panelDrawable())

        fun commit(i: Int) {
            val picked = MODES[i]
            // 按下即震：机身按键口径（24ms/185）。原版直接 setOnClickListener 无震动。
            Haptics.vibrate(activity, 24, 185)
            // 点当前档也要看见落位反馈再关，不能「点了没反应」就消失。
            selectedIndex = i
            // 2×TICK = 240ms：120ms 落位肉眼几乎抓不到（真机 90ms 时弹窗已关），
            // 一记完整的琥珀落位 + SET 刻记要点亮才关，用户才认得出选中了哪档。
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = Motion.duration(activity, Motion.TICK * 2L)
                interpolator = Motion.decelerate
                addUpdateListener { paint(it.animatedValue as Float) }
            }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    paint(1f)
                    dialog.dismiss()
                    if (picked != current) onPick(picked)
                }
            })
            anim.start()
        }

        rows.forEachIndexed { i, r ->
            r.root.isClickable = true
            r.root.isFocusable = true
            r.root.attachPressFeedback(0.97f)
            r.root.setOnClickListener { commit(i) }
        }
        view.findViewById<View>(R.id.tvLanguageClose).apply {
            attachPressFeedback(0.96f)
            hapticClick(24, 185) { dialog.dismiss() }
        }

        dialog.show()
        win?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)  // 个别 ROM 兜底
        view.animatePanelIn()
    }

    private data class Row(
        val root: LinearLayout,
        val bar: View,
        val code: TextView,
        val name: TextView,
        val sub: TextView,
        val mark: TextView,
    )
}
