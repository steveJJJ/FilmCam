package com.filmcam.app.camera

import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 胶片曝光模型：把「曝光决策」从手机 AE 手里拿回来，交给胶卷。
 *
 * 真机 CONTAX T3 在暗光下没有「提 ISO」这个选项 —— 感光度由胶卷 DX 决定，
 * 机身只能把快门一路放慢（f/2.8 时 1/500 ~ 16 秒）。慢到 1/30 以下手持必抖，
 * 这一下抖动就是胶片相机独有的瞬间，和偶发失焦一样属于「这条卷的脾性」。
 *
 * 手机相反：AE 会把 ISO 拉到 3200，把快门死死按在 1/30 以上，画面干净，
 * 但永远没有那一下。所以这里按「如果是 T3 拍这一张」重新算快门：
 *
 *   EV100 = log2(N手机² / t_ae) − log2(ISO_ae / 100)     ← 用预览测光反推场景亮度
 *   t胶片 = N_T3² / (2^EV100 × ISO卷/100)                ← 换成 T3 的 f/2.8 + 胶卷感光度
 *
 * 曝光量守恒（t × ISO 不变），所以成片亮度与手机 AE 一致 —— 只是更慢、更抖。
 * 唯一例外：快门撞到「本档最慢值」或设备上限时，成片会欠曝（真机也一样）。
 *
 * 触发方式（纯概率派）：只有快门慢到「可能糊」的亮度区间才掷骰，命中才真的切手动慢门。
 * 同一场景有时糊有时不糊 —— 正是胶片的不确定性；而白天永远不触发，因为真机在白天也不会糊。
 */
object FilmExposure {

    /** T3 的固定光圈（Sonnar 35/2.8）。手机光圈不可调，模拟时一律按真机的 f/2.8 推算 */
    private const val T3_APERTURE = 2.8f

    /**
     * 抖动候选门槛：快门慢于 1/45 才值得掷骰。
     * 再快（1/60、1/125）手持也不会糊，掷中了也白掷，只会白白牺牲画质。
     */
    private const val CANDIDATE_SEC = 1f / 45f

    /** 本档最慢快门：1/8 秒。再慢废片率太高，也超出多数手机传感器的机械上限 */
    const val MAX_SLOW_SEC = 1f / 8f

    /** 慢门档位（设置面板「慢门」那一行的三个读数） */
    enum class Mode(val prefs: Int, private val baseProb: Float) {
        OFF(0, 0f),
        NATURAL(1, 0.40f),
        SLOW(2, 0.70f);

        /**
         * 本次掷骰的命中概率：越暗越高。
         * 暗处手抖的收益最大（快门 1/8 才是真胶片味），候选区边缘（1/45）意义不大。
         */
        fun probability(sec: Float): Float {
            if (this == OFF) return 0f
            val boost = when {
                sec >= 1f / 15f -> 0.30f
                sec >= 1f / 30f -> 0.15f
                else -> 0f
            }
            return (baseProb + boost).coerceAtMost(1f)
        }

        companion object {
            fun fromPrefs(v: Int): Mode = Mode.values().firstOrNull { it.prefs == v } ?: OFF
        }
    }

    /**
     * 一次拍摄的曝光方案。
     * [filmSec] 是「真机该用的快门」，即使本次没掷中也有值 —— 取景器要用它决定要不要闪警告灯。
     */
    data class Plan(
        val ev100: Float,
        val filmSec: Float,
        val iso: Int,
        val applied: Boolean,
        val exposureNs: Long,
    )

    /** 场景亮度（EV100）。手机光圈固定，用它的 t/ISO 反推 */
    fun ev100(tAeSec: Float, isoAe: Int, aperture: Float): Float? {
        if (tAeSec <= 0f || isoAe <= 0 || aperture <= 0f) return null
        return log2(aperture * aperture / tAeSec) - log2(isoAe / 100f)
    }

    /** 按 T3 的 f/2.8 + 胶卷感光度，算出这一张真机会用的快门（秒） */
    fun filmShutterSec(ev: Float, filmIso: Int, evCompSteps: Int): Float {
        val base = T3_APERTURE * T3_APERTURE / (2f.pow(ev) * (filmIso / 100f))
        return base * 2f.pow(evCompSteps.toFloat())
    }

    /**
     * 从胶卷名推 DX 感光度：PORTRA 400 → 400、EKTAR 100 → 100、CINESTILL 800T → 800。
     * 解析不出（自定义卷名）时退回 ISO 400 —— 最常见的通用感光度。
     */
    fun isoFromRollName(name: String?): Int {
        val m = Regex("(?:^|[^0-9])([0-9]{2,4})(?:[^0-9]|$)").find(name ?: "")
            ?: return 400
        return m.groupValues[1].toIntOrNull()?.coerceIn(25, 5000) ?: 400
    }

    /** 快门标签："1/8"（日志与调试用） */
    fun shutterLabel(sec: Float): String = when {
        sec <= 0f -> "-"
        sec >= 1f -> String.format("%.1fs", sec)
        else -> "1/" + (1f / sec).roundToInt()
    }

    /**
     * 生成本次拍摄方案。返回 null = 不干预（交给手机 AE）。
     *
     * @param tAeSec 预览 AE 最近一次的曝光时间（秒）
     * @param isoAe  预览 AE 最近一次的感光度
     * @param aperture 设备主摄光圈（f 值）
     * @param filmIso 胶卷 DX 感光度（被用户手动 ISO 覆盖时取用户值）
     * @param deviceMinSec / deviceMaxSec 设备传感器曝光范围（秒）
     * @param maxSlowSec 本档最慢快门
     */
    fun plan(
        tAeSec: Float,
        isoAe: Int,
        aperture: Float,
        filmIso: Int,
        mode: Mode,
        evCompSteps: Int,
        deviceMinSec: Float,
        deviceMaxSec: Float,
        deviceIsoRange: IntRange,
        maxSlowSec: Float = MAX_SLOW_SEC,
        random: java.util.Random = java.util.Random(),
    ): Plan? {
        if (mode == Mode.OFF) return null
        val ev = ev100(tAeSec, isoAe, aperture) ?: return null
        val slowest = minOf(maxSlowSec, deviceMaxSec)
        var iso = filmIso.coerceIn(deviceIsoRange.first, deviceIsoRange.last)
        var sec = filmShutterSec(ev, iso, evCompSteps)
        if (sec <= 0f) return null

        // 守住「最慢 1/8」：算出来更长就抬感光度把曝光量补回来（t×ISO 恒定 → 亮度不变）。
        // 这是对真机的唯一让步 —— 手机传感器到不了 1 秒，与其拍成死黑，不如让快门停在 1/8。
        if (sec > slowest) {
            iso = (iso * (sec / slowest)).roundToInt()
                .coerceIn(deviceIsoRange.first, deviceIsoRange.last)
            sec = filmShutterSec(ev, iso, evCompSteps)
        }
        sec = sec.coerceIn(deviceMinSec, slowest)

        // 不够慢就不干预：白天/阴天这台「T3」本来也不会糊
        if (sec < CANDIDATE_SEC) return Plan(ev, sec, iso, false, 0L)

        val hit = random.nextFloat() < mode.probability(sec)
        val ns = if (hit) (sec.toDouble() * 1_000_000_000.0).toLong() else 0L
        return Plan(ev, sec, iso, hit, ns)
    }
}
