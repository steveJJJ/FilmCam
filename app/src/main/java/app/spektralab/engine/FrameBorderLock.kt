package app.spektralab.engine

import android.graphics.Bitmap
import org.json.JSONObject

/**
 * v40：片框印刷字颜色**确定性**恢复 —— 不统计、不 CDF、不参考图。
 *
 * ## 前两版为什么失败
 * - v37 用「短边 18% 软圈 + l<110」猜片框，把画面暗部卷进 CDF，单帧改 314 万
 *   像素（成片 46%），LUT 被画面内容主导，黑片基染红、橙字漂白。
 * - v38 改成固定几何带 + 带内 CDF + 参考图亮度箱色度 LUT，但 CDF 是**分布匹配**：
 *   参考小图 320px、成片 2710px，带内文字边缘/抗锯齿/齿孔的像素构成差太多，
 *   把暗片基亮度硬推到中亮度箱再叠该箱的橙色 → 真机黑片基变成 (101,59,33)。
 *
 * ## v40 做法
 * 片框里真正会「去饱和发白」的只有暖色印刷字（KODAK 字 / 刻度 / 帧号）。
 * 横拍标准浓度实测橙字 (193,125,66)，即 **R−G≈68、R−B≈127**。竖拍版式错配
 * 让这些字被引擎当成影像走印相链，实测变成 (200,161,111)（R−G 只剩 39）。
 *
 * 于是只做一件确定的事：在**片框带内**，对暖色像素保持 R（密度）不动，把 G/B
 * 按固定比例拉回标准橙。变换只依赖该像素自己的 RGB，与画面内容、分辨率、
 * 参考图、帧号全都无关 → 跨帧 100% 一致，不可能再出现「偶尔变淡」。
 * 白齿孔（l>=215）、黑片基（r−b<=25 或 r<=90）、画面区一律零改动。
 *
 * 离线用 7 张真机成片 + 2 张探针小图验证：竖拍 (200,161,111)→(200,132,73)、
 * 横拍 (193,125,66)→(193,125,66) 基本不动，改动像素仅 1.9%~6.2%。
 *
 * 几何：调用侧在**旋转之前**执行，引擎输出恒为横图；横图的上下带旋转后
 * 正好变成竖拍成片的左右带，所以横竖共用同一套带，不需要按朝向分支。
 *
 * 失败一律静默返回 0。外部私有目录放 `frame_lock_off` 可热关。
 */
object FrameBorderLock {

    /** 135 横置版式实测：上下齿孔/暗边印刷带 y 0.059~0.141 / 0.866~0.946。 */
    private const val BAND_TOP = 0.141f
    private const val BAND_BOTTOM = 0.866f
    private const val BAND_SIDE = 0.01f

    /** 横拍标准浓度橙字 (193,125,66) 的色度比例。 */
    internal const val ORANGE_RG = 68
    internal const val ORANGE_RB = 127

    /** 白齿孔已饱和，本来就不随印相曝光变。 */
    internal const val HOLE_LUMA = 215

    internal fun luminance(r: Int, g: Int, b: Int): Int = (r * 77 + g * 150 + b * 29) shr 8

    /**
     * 单像素确定性映射：命中暖色印刷就返回新 ARGB，否则原样返回。
     * 纯函数、零分配，JVM 单测可直接验证「同色输入永远同色输出」。
     */
    internal fun restoreArgb(p: Int): Int {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val l = luminance(r, g, b)
        if (l >= HOLE_LUMA || r <= 90 || r - b <= 25) return p
        // 暖度不足的像素按比例渐入，避免把黑片基边缘/抗锯齿硬染成橙色
        val t = ((r - b - 25) / 40f).coerceIn(0f, 1f)
        val ng = (g + ((r - ORANGE_RG) - g) * t).toInt().coerceIn(0, 255)
        val nb = (b + ((r - ORANGE_RB) - b) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (ng shl 8) or nb
    }

    /** 是否落在引擎片框带内（旋转前恒为横图）。纯函数，单测覆盖。 */
    internal fun isFrameBand(x: Int, y: Int, w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        return y < BAND_TOP * h || y >= BAND_BOTTOM * h ||
            x < BAND_SIDE * w || x >= (1f - BAND_SIDE) * w
    }

    /** 是否该跑：片框开着 + 外部开关没关。解析失败按不跑处理。 */
    internal fun shouldApply(paramsJson: String, lockOff: Boolean): Boolean =
        !lockOff && runCatching {
            JSONObject(paramsJson).optBoolean("overscan_active", false)
        }.getOrDefault(false)

    /** 只扫片框带做颜色恢复，返回改动像素数；任何异常返回 0。 */
    fun apply(target: Bitmap): Int = runCatching {
        val w = target.width
        val h = target.height
        if (w < 64 || h < 64 || !target.isMutable) return 0

        val sidePx = (BAND_SIDE * w).toInt().coerceAtLeast(1)
        val row = IntArray(w)
        var changed = 0
        for (y in 0 until h) {
            // 与 isFrameBand 用同一个浮点阈值，避免取整后整行判断错位
            val fullRow = y < BAND_TOP * h || y >= BAND_BOTTOM * h
            target.getPixels(row, 0, w, 0, y, w, 1)
            var touched = false
            for (x in 0 until w) {
                if (!fullRow && x >= sidePx && x < w - sidePx) continue
                val p = row[x]
                val np = restoreArgb(p)
                if (np == p) continue
                row[x] = np
                touched = true
                changed++
            }
            if (touched) target.setPixels(row, 0, w, 0, y, w, 1)
        }
        changed
    }.onFailure { android.util.Log.e("FrameBorderLock", "片框颜色恢复异常（跳过）", it) }
        .getOrDefault(0)
}
