package com.filmcam.app.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * App 语言：跟随系统 / 简体中文 / 繁体中文（港台共用）/ English / 日本語 / 한국어。
 *
 * 走 AndroidX 的 AppCompatDelegate.setApplicationLocales —— 官方的多语言方案：
 * 设置后由框架统一维护（API 33+ 交给系统 LocaleManager 持久化，低版本存在 AppCompat
 * 自己的存储里），并自动重建 Activity，比自己 updateConfiguration 干净，
 * 也不会在 Android 13+ 上被系统语言设置覆盖。
 *
 * 港台共用一套繁体：[ZH_TW] 应用 zh-TW；系统语言为 zh-Hant / zh-HK / zh-TW 时也
 * 投影到 [ZH_TW]（values-zh-rHK 只是同内容的精确匹配目录，避免资源回退差异）。
 */
object AppLocale {

    const val SYSTEM = 0
    const val ZH = 1
    const val ZH_TW = 2
    const val EN = 3
    const val JA = 4
    const val KO = 5
    const val MAX_MODE = KO

    private const val PREF = "filmcam"
    private const val KEY = "appLanguage"

    /** 当前档位（进程内缓存，给没有 Context 的地方用，如胶卷库） */
    var mode: Int = SYSTEM
        private set

    /** 最近一次切换的时间戳：切语言会重建 Activity，重建后要继续压住取景器触摸 */
    var lastChangeMs: Long = 0L
        private set

    fun load(context: Context) {
        mode = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getInt(KEY, SYSTEM).coerceIn(SYSTEM, MAX_MODE)
    }

    private fun save(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt(KEY, mode).apply()
    }

    /** 直接选档（设置面板语言列表用）；同档重复点不触发重建。 */
    fun set(context: Context, target: Int): Int {
        val next = target.coerceIn(SYSTEM, MAX_MODE)
        if (next != mode) {
            mode = next
            lastChangeMs = android.os.SystemClock.uptimeMillis()
            save(context)
            AppCompatDelegate.setApplicationLocales(localesFor(mode))
        }
        return mode
    }

    /** 保留循环接口（兼容旧调用与契约）；新 UI 走 [set] 一次点选。 */
    fun cycle(context: Context): Int = set(context, (mode + 1) % (MAX_MODE + 1))

    private fun localesFor(m: Int): LocaleListCompat = when (m) {
        ZH -> LocaleListCompat.forLanguageTags("zh-Hans")
        ZH_TW -> LocaleListCompat.forLanguageTags("zh-TW")
        EN -> LocaleListCompat.forLanguageTags("en")
        JA -> LocaleListCompat.forLanguageTags("ja")
        KO -> LocaleListCompat.forLanguageTags("ko")
        else -> LocaleListCompat.getEmptyLocaleList()   // 空列表 = 跟随系统
    }

    /**
     * 胶卷文案用哪一档：手动选中时直接返回该档；跟随系统时按 Locale 判定。
     * 系统为非中英日韩（如法语）时回落英文，避免胶卷描述出现简体中文。
     */
    fun contentMode(): Int = if (mode != SYSTEM) mode else run {
        val loc = Locale.getDefault()
        when (loc.language.lowercase()) {
            "en" -> EN
            "ja" -> JA
            "ko" -> KO
            "zh" -> if (loc.script.equals("Hant", true) ||
                loc.country.uppercase() in setOf("TW", "HK", "MO")
            ) ZH_TW else ZH
            else -> EN
        }
    }

    /** 当前是否英文（跟随系统时按系统语言判断），供无 Context 的数据层使用 */
    val isEnglish: Boolean get() = contentMode() == EN

    /** 首次启动：把已保存的档位应用到进程（LocaleManager 已持久化，这里只补进程内缓存） */
    fun applySaved(context: Context) {
        AppCompatDelegate.setApplicationLocales(localesFor(mode))
    }
}
