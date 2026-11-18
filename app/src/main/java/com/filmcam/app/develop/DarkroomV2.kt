package com.filmcam.app.develop

import android.content.Context

/**
 * v2 暗房改造的功能开关（默认**关闭**）。
 *
 * 改造链路：冲洗只出母片（留私有目录）→ 暗房编辑选相框/日期 → 保存才入相册。
 * 这条链路涉及核心成像流程，必须真机逐项验证后才打开。所以：
 *  - 默认 false：装机行为与改造前**逐字节一致**（冲洗照旧直接入相册）；
 *  - 开关打开后：冲洗改走母片模式，卷状态 editable，编辑保存才出成片。
 *
 * 开关放工程模式或调试入口，普通用户不暴露；老用户没这个键 → false。
 */
object DarkroomV2 {

    private const val PREFS = "filmcam"
    private const val KEY_ENABLED = "filmcam_darkroom_v2"

    @Volatile
    private var cached: Boolean? = null

    fun isEnabled(context: Context): Boolean {
        cached?.let { return it }
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
        cached = v
        return v
    }

    /** 工程模式切换用；清缓存保证本次进程立即生效。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        cached = enabled
    }

    /** 单测用：重置进程内缓存。 */
    fun resetCacheForTest() { cached = null }
}
