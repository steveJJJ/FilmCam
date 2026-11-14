package com.filmcam.app

import android.content.Context
import android.content.SharedPreferences

/**
 * v82 首次使用引导状态机（三段式的段 2 / 段 3 落在 MainActivity 的检测点上）。
 *
 * 段 1 说明书卡见 [com.filmcam.app.ui.QuickStartCard]；段 2 = 机顶 LCD 实操引导
 * （做对动作自动前进，每条 15s 超时自动跳过，不卡人）；段 3 = 首卷护栏
 * （第 1 / 12 张各提醒一次，首卷冲洗完成出贺词后归档）。
 *
 * 进度持久化进 prefs（onboarding_stage），中断 App 重进不回退、已完成不重播。
 * 纯状态机不持有 View：LCD 提示的展示由 MainActivity 用 showLcdNotice 落地，
 * 这样引导不引入第二个 UI 体系，机顶 LCD 本来就是机身说话的地方。
 */
object OnboardingGuide {

    /** 段 2 实操引导的 prefs 键：0 未开始，1 待对焦，2 待曝光，3 待快门，4 完成 */
    const val PREF_STAGE = "onboarding_stage"

    /** 段 3 首卷护栏的 prefs 键：true = 首卷已冲洗（贺词已出），护栏停用 */
    const val PREF_FIRST_ROLL_DONE = "first_roll_guarded"

    /** 段 2 完成（实操三步走完或全部超时） */
    const val STAGE_DONE = 4

    /** 段 3 护栏提示点：第 1 张与第 12 张（一卷 36 张的 1/3 处） */
    const val GUARD_FRAME_FIRST = 1
    const val GUARD_FRAME_THIRD = 12

    /** 段 2 每条 LCD 提示的超时（超时自动前进，不卡人） */
    const val HINT_TIMEOUT_MS = 15_000L

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("filmcam", Context.MODE_PRIVATE)

    fun stage(ctx: Context): Int = prefs(ctx).getInt(PREF_STAGE, 0)

    fun setStage(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(PREF_STAGE, value).apply()
    }

    fun firstRollDone(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_FIRST_ROLL_DONE, false)

    fun markFirstRollDone(ctx: Context) {
        prefs(ctx).edit().putBoolean(PREF_FIRST_ROLL_DONE, true).apply()
    }

    /**
     * 段 2 检测点：对焦完成回调。stage==1 时前进到 2。
     * 返回 true 表示「这次检测确实推进了状态」，由调用方出下一条 LCD 提示。
     */
    fun onFocusDone(ctx: Context): Boolean = advanceIf(ctx, 1)

    /** 段 2 检测点：取景框内 EV 手势变化。stage==2 时前进到 3。 */
    fun onEvChanged(ctx: Context): Boolean = advanceIf(ctx, 2)

    /** 段 2 检测点：首张落卷。stage==3 时前进到 4（段 2 完成）。 */
    fun onShotTaken(ctx: Context): Boolean = advanceIf(ctx, 3)

    private fun advanceIf(ctx: Context, expected: Int): Boolean {
        if (stage(ctx) != expected) return false
        setStage(ctx, expected + 1)
        return true
    }

    /**
     * 段 3 护栏：落卷张数命中提醒点则返回 true（调用方出 LCD 提示）。
     * 首卷冲洗完成后 [markFirstRollDone] 归档，此后不再提醒。
     */
    fun guardFrameHit(ctx: Context, frameIndexInRoll: Int): Boolean {
        if (firstRollDone(ctx)) return false
        return frameIndexInRoll == GUARD_FRAME_FIRST || frameIndexInRoll == GUARD_FRAME_THIRD
    }
}
