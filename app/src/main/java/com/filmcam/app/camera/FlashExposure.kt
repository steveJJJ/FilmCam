package com.filmcam.app.camera

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 外置闪光的「设备能力判定 + 曝光量守恒求解」。
 *
 * 纯 Kotlin、零 Android 依赖，方便 JVM 单测守住 P0 的两条契约：
 *  1. **不支持 MANUAL_SENSOR 的机型不许锁曝光** —— 手写 CONTROL_AE_MODE_OFF
 *     与 SENSOR_* key 会被 LEGACY / LIMITED HAL 忽略甚至让整帧失败，宁可降级
 *     成「BLE 点灯 + HAL 自动曝光」也要先保证出图。
 *  2. 锁曝光时，快门必须落在设备真实可支持区间内；被截断后要按
 *     `ISO × t = 常量` 反算 ISO 补回曝光量，而不是像旧实现那样直接欠 1~2 EV。
 *
 * ⚠️ 边界：这里守恒的是**设备自身 ISO 刻度上的曝光量**，不是物理场景亮度。
 * ISO 绝对值在厂商之间不可移植（sensor 满阱、光圈、增益标定都不同），
 * 那属于 P1（按帧 ISP 引导定标），本文件不解决，也不假装解决。
 */
object FlashExposure {

    /** 一次拍照的设备能力快照。 */
    data class Caps(
        val hasManualSensor: Boolean,
        val minIso: Int,
        val maxIso: Int,
        val minShutterNs: Long,
        val maxShutterNs: Long,
    ) {
        /** 能否安全地走「关 AE + 手动曝光」这条锁定路径。 */
        fun canLockExposure(): Boolean =
            hasManualSensor &&
                minIso > 0 && maxIso > minIso &&
                minShutterNs > 0 && maxShutterNs >= minShutterNs
    }

    data class Result(
        val shutterNs: Long,
        val iso: Int,
        /** true = 设备能力不足，无法完全守恒曝光量（已取最接近的可行点）。 */
        val clamped: Boolean,
        /** 给 logcat 的一句话说明，clamped=false 时为空。 */
        val note: String,
    )

    /**
     * 求 `(shutterNs, iso)`，使 `iso × shutterNs` 尽量等于 `baseIso × baseShutterNs`。
     *
     * 求解顺序：先按基准快门 → 反算所需 ISO；若 ISO 越界，就调整快门把 ISO
     * 拉回合法区间（低于下限则缩短快门、高于上限则拉长快门）；快门再被设备
     * 上限夹住时，只能接受欠曝并标记 [Result.clamped]。
     *
     * 所有返回值都严格落在 `(minIso..maxIso)` × `(minShutterNs..maxShutterNs)` 内，
     * 调用方可以直接写进 CaptureRequest，不需要再 clamp 一次。
     */
    fun solve(
        baseIso: Float,
        baseShutterNs: Long,
        minIso: Int,
        maxIso: Int,
        minShutterNs: Long,
        maxShutterNs: Long,
    ): Result {
        val loI = maxOf(1, minIso).toDouble()
        val hiI = maxOf(minIso, maxIso).toDouble().coerceAtLeast(loI)
        val loT = maxOf(1L, minShutterNs).toDouble()
        val hiT = maxOf(minShutterNs, maxShutterNs).toDouble().coerceAtLeast(loT)
        val energy = baseIso.coerceAtLeast(1f).toDouble() *
            baseShutterNs.coerceAtLeast(1L).toDouble()

        var t = baseShutterNs.toDouble().coerceIn(loT, hiT)
        val wantedIso = energy / t
        if (wantedIso < loI) {
            t = (energy / loI).coerceIn(loT, hiT)
        } else if (wantedIso > hiI) {
            t = (energy / hiI).coerceIn(loT, hiT)
        }
        val shutterNs = t.roundToLong().coerceIn(loT.toLong(), hiT.toLong())
        val iso = (energy / shutterNs.toDouble()).roundToInt()
            .coerceIn(loI.toInt(), hiI.toInt())

        val achieved = iso.toDouble() * shutterNs.toDouble()
        val clamped = abs(achieved - energy) / energy > 0.02
        val note = if (clamped) {
            "设备能力不足，曝光量仅达成 %.1f%%（ISO %d~%d / 快门 %d~%dms）".format(
                achieved / energy * 100.0, loI.toInt(), hiI.toInt(),
                loT.toLong() / 1_000_000, hiT.toLong() / 1_000_000
            )
        } else ""
        return Result(shutterNs, iso, clamped, note)
    }
}
