package com.filmcam.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 首次使用引导状态机（三段式的段 2 / 段 3 落在 MainActivity 的检测点上）。
 *
 * 段 1 说明书卡见 [com.filmcam.app.ui.QuickStartCard]；段 2 = 机顶 LCD 实操引导
 * （做对动作自动前进，每条 15s 超时自动跳过，不卡人）；段 3 = 首卷护栏
 * （第 1 / 12 张各提醒一次，首卷冲洗完成出贺词后归档）。
 *
 * 🔴 11-25 重规划：段 2 从「对焦→EV→快门」三步扩成「一次完整拍摄」八步——
 * 选卷 → 进 Pro → 调 ISO → 调快门 → 退 Pro(回自动档) → 对焦 → 曝光 → 快门。
 * Pro 四步是「认识一下」：带用户进手动档各拧一次，随后退回自动档拍第一张，
 * 新手第一张用自动档不易拍坏。每步仍做对自动前进、15s 超时跳过、不卡人。
 *
 * 进度持久化进 prefs（onboarding_stage），中断 App 重进不回退、已完成不重播。
 * 纯状态机不持有 View：LCD 提示的展示由 MainActivity 用 showLcdNotice 落地，
 * 这样引导不引入第二个 UI 体系，机顶 LCD 本来就是机身说话的地方。
 */
object OnboardingGuide {

    /** 段 2 实操引导的 prefs 键：0 未开始，1..8 各步，9 完成 */
    const val PREF_STAGE = "onboarding_stage"

    /** 段 3 首卷护栏的 prefs 键：true = 首卷已冲洗（贺词已出），护栏停用 */
    const val PREF_FIRST_ROLL_DONE = "first_roll_guarded"

    /** 段 2 实操引导的步骤数（1 选卷 / 2 进Pro / 3 ISO / 4 快门 / 5 退Pro / 6 对焦 / 7 曝光 / 8 快门） */
    const val STAGE_PICK_ROLL = 1
    const val STAGE_ENTER_PRO = 2
    const val STAGE_ISO = 3
    const val STAGE_SHUTTER = 4
    const val STAGE_EXIT_PRO = 5
    const val STAGE_FOCUS = 6
    const val STAGE_EV = 7
    const val STAGE_SHOT = 8

    /** 段 2 完成（八步走完或全部超时） */
    const val STAGE_DONE = 9

    /** 段 3 护栏提示点：第 1 张与第 12 张（一卷 36 张的 1/3 处） */
    const val GUARD_FRAME_FIRST = 1
    const val GUARD_FRAME_THIRD = 12

    /** 段 2 每条 LCD 提示的超时（超时自动前进，不卡人） */
    const val HINT_TIMEOUT_MS = 15_000L

    /** 段 3 首张落卷后的「去暗房」框和常驻提示，挂太久会烦人，到时自己收 */
    const val DARKROOM_GUIDE_TIMEOUT_MS = 60_000L

    /** 从引导点进暗房时带的 extra：暗房页给一次「点冲洗」的接力提示 */
    const val EXTRA_FIRST_ROLL_GUIDE = "first_roll_guide"

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

    /** 段 2 检测点：用户手动选了胶卷。stage==1 → 2。 */
    fun onRollPicked(ctx: Context): Boolean = advanceIf(ctx, STAGE_PICK_ROLL)

    /** 段 2 检测点：点铭牌进入 Pro 专业档。stage==2 → 3。 */
    fun onProEntered(ctx: Context): Boolean = advanceIf(ctx, STAGE_ENTER_PRO)

    /** 段 2 检测点：Pro 档调了一次 ISO。stage==3 → 4。 */
    fun onIsoChanged(ctx: Context): Boolean = advanceIf(ctx, STAGE_ISO)

    /** 段 2 检测点：Pro 档调了一次快门。stage==4 → 5。 */
    fun onShutterChanged(ctx: Context): Boolean = advanceIf(ctx, STAGE_SHUTTER)

    /** 段 2 检测点：点铭牌退出 Pro、回到自动档。stage==5 → 6。 */
    fun onProExited(ctx: Context): Boolean = advanceIf(ctx, STAGE_EXIT_PRO)

    /** 段 2 检测点：对焦完成回调。stage==6 → 7。 */
    fun onFocusDone(ctx: Context): Boolean = advanceIf(ctx, STAGE_FOCUS)

    /** 段 2 检测点：取景框内 EV 手势变化。stage==7 → 8。 */
    fun onEvChanged(ctx: Context): Boolean = advanceIf(ctx, STAGE_EV)

    /** 段 2 检测点：首张落卷。stage==8 → 9（段 2 完成）。 */
    fun onShotTaken(ctx: Context): Boolean = advanceIf(ctx, STAGE_SHOT)

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
