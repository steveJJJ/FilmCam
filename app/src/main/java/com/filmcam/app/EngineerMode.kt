package com.filmcam.app

import android.content.Context

/**
 * 工程师模式：把开发用的旋钮（慢门档位、输出风格、冲洗参数、模拟失焦…）
 * 从普通设置里摘出来，藏在这一层开关后面。
 *
 * **解锁状态要记住**：第一次在关于页连点版本号 10 次解锁，之后打开关于页点一下就能进 ——
 * 每次都要连点 10 次纯粹是折磨（开发期一天要进好几次）。想回到"藏起来"的状态，
 * 在工程师控制台里把开关关掉即可，那时又需要连点 10 次。
 *
 * 单独放一个 object 而不是散在 SharedPreferences 里，是为了让"解锁"这件事只有一个判据：
 * 关于页的入口、设置面板要不要显示这几项，都读这一个值。
 */
object EngineerMode {

    private const val PREF = "filmcam"
    private const val KEY = "engineer_mode"

    /** 连续点击版本号多少次解锁（与主流 App 的彩蛋入口一致） */
    const val UNLOCK_TAPS = 10

    fun isOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY, false)

    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY, on).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
