package com.filmcam.app.develop

import android.content.Context

/**
 * 冲洗画质档位。
 *
 * 为什么需要它：冲洗引擎是全分辨率 GPU 渲染，耗时与像素量成正比，而不同手机的
 * 主摄 JPEG 尺寸差很多（例如 Mate X5 是 5152×6864 ≈ 3540 万像素，多数机型约
 * 1200 万）。3540 万像素在麒麟 9000s / Maleoon 910 上要 85 秒一帧，同样的画面
 * 在 1200 万像素下只要约三分之一的时间。
 *
 * 所以把「成片分辨率」交给用户按自己手机的情况选：
 *  - FULL（完整）：原始分辨率冲洗，最慢，细节最多；
 *  - STANDARD（标准）：进引擎前降采样到约 1200 万像素以上，速度约 3 倍。
 *
 * 只影响冲洗分辨率，不改变色彩、颗粒、辉光的成像模型。
 */
object DevQuality {
    const val FULL = 0
    const val STANDARD = 1

    /** 标准档的引擎像素上限：约 1200 万（与主流机型默认成片一致）。 */
    const val STANDARD_MAX_PIXELS = 12_000_000

    private const val PREF = "devQuality"

    fun load(context: Context): Int =
        context.getSharedPreferences("filmcam", Context.MODE_PRIVATE)
            .getInt(PREF, FULL).coerceIn(FULL, STANDARD)

    fun save(context: Context, value: Int) {
        context.getSharedPreferences("filmcam", Context.MODE_PRIVATE)
            .edit().putInt(PREF, value.coerceIn(FULL, STANDARD)).apply()
    }

    fun cycle(context: Context): Int {
        val next = if (load(context) == FULL) STANDARD else FULL
        save(context, next)
        return next
    }

    /**
     * 本档位下允许进引擎的最大像素数；0 表示不限（由引擎自己的上限决定）。
     * 标准档同时尊重引擎上限：两者取小。
     */
    fun framePixelCap(context: Context, engineLimit: Int): Int {
        if (load(context) != STANDARD) return engineLimit
        return if (engineLimit > 0) minOf(engineLimit, STANDARD_MAX_PIXELS) else STANDARD_MAX_PIXELS
    }
}
