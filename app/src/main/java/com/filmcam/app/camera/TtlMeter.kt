package com.filmcam.app.camera

import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 外置闪光「相机侧闭环 TTL」的求解核心（2026-11-19）。
 *
 * ## 为什么只能这么做
 *
 * 真 TTL 是灯内硬件「边闪边测、够了就掐灭」。神牛 A2 的 BLE 接口经实测**没有
 * 可用的模拟功率控制**（A1 帧 `F0 A1 07 …` 只有开/关两态、门槛还在 20~70 之间
 * 漂移；官方 TC 增量帧 `F0 A0 0A FF …` 发过去反而让灯熄火）。掐不断、也调不了，
 * 唯一还能动的量就是**相机侧的 ISO**：
 *
 *  - 快门对闪光无效（氙气闪持续 ~1/1000s，短于任何可选快门），只影响环境光；
 *  - 光圈是固定 f/1.7，不能动；
 *  - ISO 设备支持 12~3200，约 8 档，是足够宽的。
 *
 * ## 做法
 *
 * 按下快门先拍一张**测光帧**（同一快门、压到低 ISO、照常触发外置灯），从这张
 * JPEG 的中心区中位亮度反解「要多少 ISO 才能把主体打到目标亮度」，再触发第二闪
 * 拍正式帧。
 *
 * ## 目标亮度与两个补偿
 *
 *  - 目标：中心区 sRGB 中位数 [TARGET_P50] ≈ 118，对应胶片负片上一个舒服的中间调。
 *  - **回电损失**：A2 实测首张比 2.6s 后那张亮 29%（`FLASH_RECYCLE_LOSS_EV`），
 *    测光帧（第一闪）会偏亮，正式帧（第二闪）会偏暗，所以求解时要**多加**
 *    这么多 EV 的 ISO 把差额补回来。
 *  - 亮度换算在**线性光**里做（[srgbToLinear]），否则 sRGB 的 gamma 会让
 *    中高调处的比值严重失真。
 *
 * 本对象零 Android import，规则纯 Kotlin，可单测。
 */
object TtlMeter {

    /**
     * 目标：**拍摄侧 JPEG** 中心区 sRGB 中位亮度。
     *
     * 🔴 195 是 2026-11-19 用成片反标出来的，不是拍脑袋的 118：
     *  - 测光 p50=129 → 冲洗后成片 mean 71 / p50 60（偏暗，墙面发灰、木纹发闷）
     *  - 测光 p50=202 → 冲洗后成片 mean 119 / p50 125（正常，层次清楚）
     * 差别来自暗房显影的 `print_exposure`（正值压暗），拍摄侧到位了、成片还是暗一档半。
     * 所以这里必须瞄准「成片好看」对应的拍摄侧读数，而不是教科书的中灰 118。
     *
     * ⚠️ 换胶卷型号或改 `print_exposure` 后这个值要重新标；
     * 判定口径始终是**冲洗出来的成片**，不是这个中间读数。
     */
    const val TARGET_P50 = 195f

    /** 测光帧的 ISO 上限 —— 全功率闪光下再高就容易把测光帧打爆。 */
    const val METER_ISO_MAX = 100

    /**
     * 同步曝光窗口下限（ns）。
     *
     * 🔴 19:16 真机实测：ISO100 / 8ms 的测光帧 p50=1（全黑），而同场景 100ms
     * 窗口能正常吃到闪光。原因不是灯没闪 —— 预触发要提前 150~230ms 打灯，
     * 氙气脉冲只有约 1ms，8ms 的窗口一旦赶上时序抖动就整帧错过。
     * 所以**闪光帧的曝光窗口必须撑到 100ms 量级**，这也是 App 原本把同步曝光
     * 写死成 100ms 的真正原因。
     */
    const val SYNC_SHUTTER_NS = 100_000_000L

    /** 第一次闪光之后等多久再打第二闪（A2 实测回电约 2.6s）。 */
    const val SECOND_SHOT_DELAY_MS = 2600L

    /** 首张比第二张亮 29% ≈ 0.32EV，正式帧天生偏暗，要提前补回来。 */
    const val RECYCLE_LOSS_EV = 0.32f

    const val MIN_ISO = 12
    const val MAX_ISO = 3200

    /**
     * 单次求解允许的最大调整量。
     *
     * 🔴 原来是 4EV，后果很糟：真机 20:23 实测，一张**漏闪**的近黑帧（p50=14）
     * 被判成欠曝，直接 +4EV，下一张 p50 冲到 253 全白。漏闪是物理常态
     * （首帧、灯没连上、被系统杀掉进程后重启），所以单帧修正必须有上限，
     * 宁可用两三张慢慢收敛，也不能一帧把画面甩飞。
     */
    const val MAX_CORRECTION_EV = 2f

    /** 中位数到这两个值就认为测光帧已经裁切/漏闪，量级不可信。 */
    const val CLIP_HIGH = 246

    /**
     * 下限从 10 放宽到 25：漏闪的帧通常落在 3~20（环境光残留），
     * 10 这个阈值会把 p50=14 的漏闪帧当成"轻微欠曝"去正常求解，进而拉满修正。
     */
    const val CLIP_LOW = 25

    /** 一张测光帧的读数（只取中心 50% 区域）。 */
    data class Reading(
        val p50: Int,
        val p90: Int,
        /** 接近满阶（≥250）的像素占比，百分数。 */
        val clippedPct: Float
    )

    data class Solution(
        val meterIso: Int,
        val finalIso: Int,
        /** 实际采用的 EV 修正（含回电补偿）。 */
        val correctionEv: Float,
        val note: String
    )

    fun srgbToLinear(v: Float): Float {
        val s = (v / 255f).coerceIn(0f, 1f)
        return if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
    }

    /**
     * 由测光帧反解正式帧该用的 ISO。
     *
     * 测光帧被裁切时量级测不准，只能先按固定方向推两档，靠下一次迭代收敛
     * （连续拍摄时 ISO 会带回下一次测光，所以两三轮内一定收敛）。
     */
    /**
     * @param targetOffsetEv 目标亮度偏移（EV）。机顶 EV 键的闪光补偿走这里，
     *   而**不是**乘进曝光增益 —— 乘进增益会被 TTL 闭环原样抵消掉（它在追绝对亮度），
     *   用户按了 EV 键画面却没变化。
     */
    fun solve(
        meterIso: Int,
        reading: Reading,
        includeRecycleComp: Boolean = true,
        targetOffsetEv: Float = 0f
    ): Solution {
        if (meterIso <= 0) {
            return Solution(meterIso, meterIso, 0f, "测光 ISO 无效")
        }
        val ev: Float
        val note: String
        when {
            reading.p50 >= CLIP_HIGH -> {
                ev = -2f
                note = "测光帧过曝（p50=${reading.p50}），先降 2 档"
            }
            reading.p50 <= CLIP_LOW -> {
                ev = 2f
                note = "测光帧欠曝（p50=${reading.p50}），先升 2 档"
            }
            else -> {
                val target = srgbToLinear(TARGET_P50)
                val measured = srgbToLinear(reading.p50.toFloat())
                val comp = if (includeRecycleComp) RECYCLE_LOSS_EV else 0f
                // 目标偏移直接加在求解结果上：稳态下 log2 项会把偏移自己抵消成 0，
                // 所以它等价于「把闭环的目标亮度整体挪一段」，不会被闭环吃掉。
                ev = if (measured <= 0f) MAX_CORRECTION_EV
                else (log2(target / measured) + comp + targetOffsetEv)
                note = "中位数求解（p50=${reading.p50}）"
            }
        }
        val clamped = ev.coerceIn(-MAX_CORRECTION_EV, MAX_CORRECTION_EV)
        val iso = (meterIso * 2f.pow(clamped)).roundToInt().coerceIn(MIN_ISO, MAX_ISO)
        return Solution(meterIso, iso, clamped, note)
    }
}
