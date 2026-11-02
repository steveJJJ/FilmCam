package com.filmcam.app.ui

/**
 * 过卷时序：从按下快门到「这一格走完」的时间线。
 *
 * 真机上这几个时刻是叶片和马达的机械节奏 —— **跟照片多快写完无关**：
 *  - 开帘（快门声）→ 曝光 → 闭帘（慢门才有第二声）→ 停顿 → 马达过片 → 这一格走完。
 *
 * 之前把「照片写完」当成过卷的终点，结果写入快的帧会把过卷声和动画整个吞掉：
 * 用户听到一声（慢门是两声）快门就没下文了，看着像没拍上，于是再按一次 —— 于是
 * 「两声快门、没有过卷」。这里把时序写成纯数据，照片写完只负责在仪式走完后收尾。
 *
 * 单位统一毫秒，全部相对「按下快门」那一刻。
 */
object AdvanceRitual {

    /** 从按下到 HAL 真正开帘的延迟：慢门的闭帘声要在这之后补，否则第二声比曝光还早 */
    const val SHUTTER_OPEN_LATENCY_MS = 120L

    /** 常规：开帘后留一段静默（人耳要约 300ms 才把两个声音听成"两件事"），马达再进场 */
    const val ADVANCE_SOUND_DELAY_MS = 420L

    /** 慢门：闭帘后马达进场的间隔（真机曝光一结束就过片，只留一点点机械延迟） */
    const val ADVANCE_AFTER_CLOSE_MS = 200L

    /** 「齿轮啃齿孔」触感的持续时间 */
    const val GEAR_HAPTIC_SPAN_MS = 640L

    /** 相邻两下齿轮触感的间隔 */
    const val GEAR_HAPTIC_STEP_MS = 58L

    /** 马达进场后到收尾的最短时长：齿轮触感走完 + 末尾那记顿挫 */
    const val ADVANCE_TAIL_MS = GEAR_HAPTIC_SPAN_MS + 60L

    /** 看门狗：相机回调迟迟不来时兜底复位，避免卡死在过卷界面 */
    const val WATCHDOG_MS = 20_000L

    /** 一次拍摄的过卷时间线 */
    data class Timeline(
        /** 慢门闭帘时刻；0 = 常规快门，没有第二声 */
        val closeAtMs: Long,
        /** 马达（过卷声）进场时刻 */
        val advanceAtMs: Long,
        /** 这一格走完的时刻：收尾不能早于它 */
        val ritualMs: Long,
    )

    /**
     * 排一次拍摄的时间线。
     * @param exposureNs 慢门曝光时长（纳秒）；0 = 常规快门
     */
    fun plan(exposureNs: Long): Timeline {
        val closeAt = if (exposureNs > 0L) exposureNs / 1_000_000L + SHUTTER_OPEN_LATENCY_MS else 0L
        val advanceAt = if (closeAt > 0L) closeAt + ADVANCE_AFTER_CLOSE_MS else ADVANCE_SOUND_DELAY_MS
        return Timeline(closeAt, advanceAt, advanceAt + ADVANCE_TAIL_MS)
    }

    /**
     * 照片在 [elapsedMs] 时写完，还要等多久才能收尾。
     * 写得再快也要等过卷走完（真机马达走完一格才允许拍下一张），所以最小值是 0，不会是负的。
     */
    fun finishDelayMs(elapsedMs: Long, ritualMs: Long): Long =
        (ritualMs - elapsedMs).coerceAtLeast(0L)
}
