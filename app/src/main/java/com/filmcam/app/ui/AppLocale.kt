package com.filmcam.app.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * App 语言：跟随系统 / 简体中文 / English。
 *
 * 走 AndroidX 的 AppCompatDelegate.setApplicationLocales —— 官方的多语言方案：
 * 设置后由框架统一维护（API 33+ 交给系统 LocaleManager 持久化，低版本存在 AppCompat
 * 自己的存储里），并自动重建 Activity，比自己 updateConfiguration 干净，
 * 也不会在 Android 13+ 上被系统语言设置覆盖。
 */
object AppLocale {

    const val SYSTEM = 0
    const val ZH = 1
    const val EN = 2

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
            .getInt(KEY, SYSTEM).coerceIn(SYSTEM, EN)
    }

    private fun save(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt(KEY, mode).apply()
    }

    /** 当前是否英文（跟随系统时按系统语言判断），供无 Context 的数据层使用 */
    val isEnglish: Boolean
        get() = when (mode) {
            EN -> true
            ZH -> false
            else -> Locale.getDefault().language.lowercase().startsWith("en")
        }

    /** 循环切换：跟随系统 → 简体中文 → English → 跟随系统 */
    fun cycle(context: Context): Int {
        mode = (mode + 1) % 3
        lastChangeMs = android.os.SystemClock.uptimeMillis()
        save(context)
        AppCompatDelegate.setApplicationLocales(localesFor(mode))
        return mode
    }

    private fun localesFor(m: Int): LocaleListCompat = when (m) {
        ZH -> LocaleListCompat.forLanguageTags("zh-Hans")
        EN -> LocaleListCompat.forLanguageTags("en")
        else -> LocaleListCompat.getEmptyLocaleList()   // 空列表 = 跟随系统
    }

    /** 首次启动：把已保存的档位应用到进程（LocaleManager 已持久化，这里只补进程内缓存） */
    fun applySaved(context: Context) {
        AppCompatDelegate.setApplicationLocales(localesFor(mode))
    }
}
