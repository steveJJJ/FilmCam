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

    // ========================================================================
    // 外置闪光「每张独立」TTL（2026-11-22 重写，替代失败的跨张裸累积 ±2EV）
    // ========================================================================
    //
    // 🔴 为什么推翻旧的跨张闭环：旧逻辑「用上一张偏差调下一张」在有 af 距离的场景
    //    下完全失效 —— 用户移动拍摄时每张距离不同，上一张的偏差对下一张是噪声，
    //    再加 flashMiss 死锁（暗帧被误判成漏闪、修正被封锁）⇒ 越拍越黑（11-22 真机
    //    22 张：mean 238→8.6）。而神牛 A2 的 A1 功率帧真机证实不被接受 ⇒ 不能调灯，
    //    只能调相机 ISO。
    //
    // 正确模型（11-19 原始三件套设计，从没被完整实现）：
    //   P0 距离先验：A2 固定功率，闪光主体曝光 ∝ ISO / d² ⇒ 正确 ISO ∝ d²。
    //     **每张独立**从 AF 屈光度读距离算初值，绝不继承上一张。
    //   P1 拍后微调：量本张成片中心亮度，只在「同一距离」内累积一个小修正
    //     （反射率补偿：白墙 vs 黑衣），步长 ±0.5EV、死区 ±0.4EV。
    //   P2 换场景检测：距离变化 > 1.6× ⇒ 判定换场景，清掉 P1 累积修正，
    //     只用新距离的 P0 初值 —— 根治「移动拍摄越拍越黑」。

    /** 距离先验的标定基准 ISO（gain=4 × 57），在 [EXT_REF_DISTANCE_M] 距离曝光正确。 */
    const val FLASH_BASE_ISO = 228

    /** 距离先验的标定参考距离（m）：gain=4 / ISO=228 在此距离曝光正确。需真机标定微调。 */
    const val EXT_REF_DISTANCE_M = 1.0f

    /** 距离先验可信范围：AF 距离小于 0.2m 或大于 4m 时闪光灯物理上不可靠，回退固定曝光。 */
    const val EXT_MIN_DISTANCE_M = 0.2f
    const val EXT_MAX_DISTANCE_M = 4.0f

    /** 换场景判定：距离变化超过这个倍数就丢弃拍后累积修正。 */
    const val SCENE_CHANGE_RATIO = 1.6f

    /** 拍后微调步长（EV）——保守，避免单帧误判甩飞整卷（旧版 ±2EV 是灾难）。 */
    const val EXT_STEP_EV = 0.5f

    /** 拍后微调死区（EV）：目标附近来回抖就停手。 */
    const val EXT_DEADZONE_EV = 0.4f

    /** 拍后累积修正的绝对上限（EV）。 */
    const val EXT_CORR_LIMIT_EV = 2.0f

    /**
     * P0 距离先验：由 AF 距离给出这张该用的 ISO 增益倍率（相对标定 gain=4）。
     *
     * 闪光主体曝光 ∝ ISO/d²（A2 固定功率）：要维持恒定曝光，ISO ∝ d²。
     * 返回 `gainFactor = (d / REF)²`，调用侧 `gain = 4 × gainFactor`。
     * 距离无效（≤0 或超范围）返回 1f —— 退回标定 gain=4，不做猜测。
     *
     * 定量校验：d=0.5m → 0.25×（ISO 57，近距不白板）；
     *          d=2.0m → 4×（ISO 912，远距不黑片）；钳制交给调用侧设备 ISO 上限。
     */
    fun distanceGainFactor(distanceM: Float): Float {
        if (distanceM < EXT_MIN_DISTANCE_M || distanceM > EXT_MAX_DISTANCE_M) return 1f
        return (distanceM / EXT_REF_DISTANCE_M).pow(2f)
    }

    /**
     * P1 拍后微调：由本张成片中心亮度给出 EV 修正增量（施加到 corrEv）。
     *
     * 只在「同一距离」内调用（P2 判定换场景时调用侧先清 corrEv）。
     * 目标用 [TARGET_P50]，线性光域算比值，避免 sRGB gamma 在中高调失真。
     * 饱和 / 近黑的量级不可信 —— 只在死区内微调，不一次跳 2 档。
     *
     * @return 本张应累加的 EV（已含死区与步长钳制），调用侧 `corrEv = (corrEv + 返回值).coerceIn(±2)`
     */
    fun postShotCorrectionEv(reading: Reading, currentCorrEv: Float): Float {
        // 饱和（p50≥246）或近黑（p50≤25）的量级不可信：不做大修正，但允许一个保守步长
        // 缓慢爬回 —— 与旧版 flashMiss 死锁（完全封锁修正）不同，这里永远能恢复。
        val target = srgbToLinear(TARGET_P50)
        val measured = srgbToLinear(reading.p50.toFloat().coerceIn(0f, 255f))
        val raw = if (measured <= 0f) EXT_STEP_EV else log2(target / measured)
        val clamped = raw.coerceIn(-EXT_STEP_EV, EXT_STEP_EV)
        // 死区：已经很接近目标就别动
        val applied = if (kotlin.math.abs(clamped) < EXT_DEADZONE_EV) 0f else clamped
        // 已经到累积上限就别再同向加
        if (applied > 0f && currentCorrEv >= EXT_CORR_LIMIT_EV) return 0f
        if (applied < 0f && currentCorrEv <= -EXT_CORR_LIMIT_EV) return 0f
        return applied
    }

    /**
     * P2 换场景检测：新距离相对上一次是否发生场景切换（>1.6×）。
     * @param lastDistanceM 上一张的有效距离（0 = 无历史）
     */
    fun isSceneChange(lastDistanceM: Float, newDistanceM: Float): Boolean {
        if (lastDistanceM < EXT_MIN_DISTANCE_M || newDistanceM < EXT_MIN_DISTANCE_M) return false
        val ratio = maxOf(lastDistanceM, newDistanceM) / minOf(lastDistanceM, newDistanceM)
        return ratio > SCENE_CHANGE_RATIO
    }

    /**
     * 三件套一次性求解：给定本张 AF 距离、历史距离/修正、本张成片读数，
     * 返回这张应使用的 `gainFactor`（乘到标定 gain=4 上）与更新后的 corrEv / 距离。
     *
     * 调用侧（Camera2Controller）拍前用 AF 距离算 gain 初值、拍后喂本张读数更新 corrEv，
     * 下一张再用。每张独立看自己的距离，corrEv 只在同一场景内累积。
     */
    data class ExtResult(
        val gainFactor: Float,
        val corrEv: Float,
        val distanceM: Float,
        val sceneChanged: Boolean,
        val correctionAppliedEv: Float,
        /** 换场景清零后的修正基线（没换场景则 = 传入的 corrEv）。测试与诊断用。 */
        val baseCorrAfterSceneChange: Float
    )

    fun solveExternal(
        distanceM: Float,
        lastDistanceM: Float,
        corrEv: Float,
        reading: Reading?
    ): ExtResult {
        val changed = isSceneChange(lastDistanceM, distanceM)
        val baseCorr = if (changed) 0f else corrEv
        val applied = if (reading == null) 0f else postShotCorrectionEv(reading, baseCorr)
        val newCorr = (baseCorr + applied).coerceIn(-EXT_CORR_LIMIT_EV, EXT_CORR_LIMIT_EV)
        val distFactor = distanceGainFactor(distanceM)
        val corrFactor = 2f.pow(newCorr)
        return ExtResult(distFactor * corrFactor, newCorr, distanceM, changed, applied, baseCorr)
    }

    // ========================================================================
    // 灯侧 TTL（2026-11-22 抓到 A2 功率帧 `a0 01 arg crc` 后定稿，首选方案）
    // ========================================================================
    //
    // 相机 ISO 锁标定 228 不动，靠调灯的功率档适配距离 —— 背景曝光稳定、零延迟、
    // 对齐官方。功率档 arg：0=1/1 满功率，每 +10 减一档，70=1/128（抓包真值）。
    //
    // P0 距离初值：闪光 ∝ 1/d²，要维持曝光，功率 ∝ d² ⇒ 每远 1.4 倍（d²=2）升 1 档。
    //   arg(d) = clamp(10 × log2(d² / dRef²), 0, 70)，dRef=1m 时 arg=10（1/2 档留余量）。
    // P1 拍后微调：量本张成片 p50，偏亮→降功率（arg+10），偏暗→升功率（arg-10）。
    // P2 换场景：距离变 >1.6× 用新距离的 P0 初值，不继承旧修正。

    /** 灯侧 TTL 的功率参考距离（m）：此距离用 [FLASH_POWER_REF_ARG] 档。 */
    const val FLASH_POWER_REF_DISTANCE_M = 1.0f

    /** 参考距离的基础功率档（arg=10 = 1/2）：留上下调整余量，避免贴着满功率无法再升。 */
    const val FLASH_POWER_REF_ARG = 10

    /** A2 功率 arg 范围（抓包真值）。 */
    const val POWER_ARG_MIN = 0     // 1/1 满功率
    const val POWER_ARG_MAX = 70    // 1/128
    const val POWER_ARG_STEP = 10   // 每档 1EV

    /**
     * P0 灯侧功率初值：由 AF 距离给出功率档 arg。
     * 功率 ∝ d²（闪光平方反比），每远 √2 倍升 1 档（arg -10）。
     * 距离无效（≤0/超范围）返回 [FLASH_POWER_REF_ARG] 兜底。
     *
     * 定量（dRef=1m、refArg=10）：
     *   d=0.5m → arg=10+10=20（1/4，近距降功率防白板）
     *   d=1.0m → arg=10（1/2）
     *   d=2.0m → arg=0（1/1 满功率，远距不能再升）
     */
    fun flashPowerForDistance(distanceM: Float): Int {
        if (distanceM < EXT_MIN_DISTANCE_M || distanceM > EXT_MAX_DISTANCE_M) {
            return FLASH_POWER_REF_ARG
        }
        // 功率 ∝ d² ⇒ arg 随 d 增大而减小（档位升高）
        val evFromRef = 2f * kotlin.math.log2(distanceM / FLASH_POWER_REF_DISTANCE_M)
        val arg = FLASH_POWER_REF_ARG - (evFromRef * POWER_ARG_STEP).roundToInt()
        return arg.coerceIn(POWER_ARG_MIN, POWER_ARG_MAX)
    }

    /**
     * P1 拍后功率微调：由本张成片 p50 给出功率档增量。
     * 偏亮（p50 高）→ 需要降功率 → arg 增大（正）；偏暗 → arg 减小（负）。
     * 每次只走 1 档（±10），死区内不动，避免离散档位来回跳。
     *
     * @return 功率档增量（±10 或 0），调用侧 `arg = (arg + 返回值).coerceIn(0,70)`
     */
    fun flashPowerAdjust(p50: Int): Int {
        // 目标 [TARGET_P50]=195。线性光域算亮度比，转 EV，再转档位（1EV=1档=10）。
        val measured = srgbToLinear(p50.toFloat().coerceIn(0f, 255f))
        val target = srgbToLinear(TARGET_P50)
        val rawEv = if (measured <= 0f) -2f else kotlin.math.log2(measured / target)
        // rawEv>0 = 偏亮 = 降功率 = arg 增大。每满 1EV 走一档（±10），不足 1 档不动，
        // 避免离散档位在低偏差时来回跳（死区效果）。
        val evSteps = rawEv.roundToInt().coerceIn(-1, 1)
        return (evSteps * POWER_ARG_STEP)
    }
}
