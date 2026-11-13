package com.filmcam.app.develop

import android.content.Context
import app.spektralab.engine.JpegNormalizer

/**
 * 机身设置面板上的「色彩浓度」档位（v73）。
 *
 * 底层复用的就是 v50 已有的 JPEG 输入侧反 ISP 归一化：工程师控制台里那根
 * 0–100% 滑杆写的是同一个 prefs 键 [PREF]。这里只是把它从「连点 10 次才看得见」
 * 的地方搬一格到机身铭牌上，做成和「胶片相框 / 冲洗画质」一样的就地循环。
 *
 * 为什么是三档而不是再开一个弹窗：vc69 的三档弹窗直选被用户否决（太丑），
 * vc70 完整回退成就地循环；这里沿用同一交互，面板不关、不叠系统列表。
 *
 * 语义：**数值越大越淡**（退掉的 ISP 饱和越多）。
 *  - [STANDARD]：v73 标定后的 DEFAULT_STRENGTH（0.7），可手动切回；
 *  - [SOFT]：滑杆满档，色度缩减吃满 [JpegNormalizer.MAX_CHROMA_REDUCTION]；
 *    ⚠️ 11-13 起这也是**新装默认档**（用户指定「淡」），见 [DEFAULT_VALUE]；
 *  - [ORIGINAL]：0 = 严格回退 v49 的 InputExposure 路径，最浓。
 *  - 工程师控制台把滑杆拖到任意中间值 → [Level.CUSTOM]，机身这行照实显示百分比，
 *    不擅自把用户的细调值吸附回档位。
 *
 * ⚠️ 只作用 JPEG 与导入照片，RAW 通路不经过 JpegNormalizer；不改胶片/印相参数，
 * 不按机型存值，下一次冲洗生效（整卷由首帧定基准，见 DevelopmentService）。
 */
object ColorStrength {

    private const val PREF = "filmcam_dev_jpeg_normalize"

    /** 标准档：与 JpegNormalizer.DEFAULT_STRENGTH 同源，别在两处写死数字。 */
    val STANDARD: Float get() = JpegNormalizer.DEFAULT_STRENGTH

    /** 淡档：滑杆满档（不写死 1.0f，跟值域上限同源）。 */
    const val SOFT = JpegNormalizer.MAX_STRENGTH

    /** 原始档：完全不做输入侧校正（逐像素等价 v49）。 */
    const val ORIGINAL = 0f

    /** 新装 / 清数据后的默认档：淡（11-13 用户指定）。 */
    val DEFAULT_VALUE: Float get() = JpegNormalizer.DEFAULT_LEVEL_STRENGTH

    /** 循环顺序：标准 → 淡 → 原始 → 标准。默认停在淡，点一下先到原始。 */
    fun presets(): FloatArray = floatArrayOf(STANDARD, SOFT, ORIGINAL)

    /** 纯函数：给定当前滑杆值，返回循环后的下一个档位；自定义值一律回默认档。 */
    fun next(current: Float): Float {
        val p = presets()
        val idx = p.indexOfFirst { kotlin.math.abs(it - current) < 1e-4f }
        return if (idx < 0) DEFAULT_VALUE else p[(idx + 1) % p.size]
    }

    /** 纯函数：当前滑杆值属于哪一档。 */
    fun levelOf(value: Float): Level {
        val p = presets()
        val idx = p.indexOfFirst { kotlin.math.abs(it - value) < 1e-4f }
        return when (idx) {
            0 -> Level.STANDARD
            1 -> Level.SOFT
            2 -> Level.ORIGINAL
            else -> Level.CUSTOM
        }
    }

    enum class Level { STANDARD, SOFT, ORIGINAL, CUSTOM }

    fun load(context: Context): Float =
        context.getSharedPreferences("filmcam", Context.MODE_PRIVATE)
            .getFloat(PREF, DEFAULT_VALUE)
            .coerceIn(0f, JpegNormalizer.MAX_STRENGTH)

    /** 就地循环并落盘，返回切换后的值。 */
    fun cycle(context: Context): Float {
        val next = next(load(context))
        context.getSharedPreferences("filmcam", Context.MODE_PRIVATE)
            .edit().putFloat(PREF, next).apply()
        return next
    }
}
