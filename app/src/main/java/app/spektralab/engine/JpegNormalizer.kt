package app.spektralab.engine

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min

/**
 * v50：JPEG / 导入通路的**输入侧反 ISP 归一化**（引擎前 IDT 的第一层）。
 *
 * ## 要解决的问题
 * 手机 HAL 出的 JPEG 已经是 display-referred 成品：ISP 做过饱和度提升、S 曲线对比、
 * 高光 rolloff、8bit 量化。引擎把它当「落在底片上的光」再走一遍负片 H&D + 印相，
 * 等于调味两次。真机定量（2026-11-08，GOLD 200 同场景）：
 *   印相 + RAW  高饱和像素 7.8% / 平均色度 0.102
 *   印相 + JPEG 高饱和像素 31.1% / 平均色度 0.188
 * 色域与档位均已排除：HAL 帧 Exif `ColorSpace=1(sRGB)` 且无 ICC，App 按 sRGB 读是对的；
 * 切成相档反而更浓（S +36%、chroma +46%）。所以只能从输入侧退掉 ISP 那一层。
 *
 * ## 为什么不按机型查表
 * 各家 ISP 调味强度不同，同品牌不同代 / 不同模式（夜景、HDR）也不同；App 无 INTERNET
 * 权限，机型表只能内置、随发版更新，新机永远滞后。这里**不认机型，只认这一帧**：
 * 从降采样图统计「被调了多少」，反推本帧需要的校正强度。不同机型自动得到不同强度，
 * 但都落到同一个目标分布 → 新机零适配。机型 profile 只作为未来的可选覆盖层。
 *
 * ## 目标值的来路与纪律
 * [TARGET_HI] / [REF_HI] 来自 S25U 同场景实测（线性底片基线 7.8% / HAL 原帧 18.5%），
 * 是**输入侧**锚点而非成片目标。跨机型数据到位后应改用「同帧 RAW 引导统计」
 * （见 isp_guide_report.txt 的 rawTarget p50/p90）替换，而不是给每家厂商填常数。
 * 滑杆默认 0 = 完全不改现有行为；强度上限 clamp，绝不动 `print_exposure` /
 * `dir_couplers_amount` 等胶片参数，RAW 通路完全不经过这里。
 *
 * ## 性能
 * 统计只在 256px 降采样图上做；像素变换一次 getPixels/setPixels 遍历完成
 * 线性域曝光 + 色度缩放 + 对比压缩，不产生第二份全尺寸位图（峰值内存已到 240MB）。
 * 编码用 4097 项预计算表代替逐像素 pow。
 */
object JpegNormalizer {

    private const val TAG = "JpegNormalizer"

    /** 用户滑杆的满档值；0 = 关闭（严格走旧的 InputExposure 路径）。 */
    const val MAX_STRENGTH = 1f

    /** 满档时最多缩减的色度比例。经验上限，待真机标定后收紧。 */
    const val MAX_CHROMA_REDUCTION = 0.35f

    /** 满档时最多压缩的对比比例（以 128 中灰为锚）。 */
    const val MAX_CONTRAST_REDUCTION = 0.18f

    /**
     * 输入侧「高饱和像素占比」的目标：贴近线性底片基线 7.8%，留一点胶片本身该有的饱和。
     * 注意这是**进引擎之前**的目标，不是成片目标。
     */
    const val TARGET_HI = 0.10f

    /** 典型「已被 ISP 调味」的 HAL 单帧 JPEG 占比（S25U 同场景实测 18.51%）。 */
    const val REF_HI = 0.185f

    /** 采样图长边（px）。统计用途，不参与出片。 */
    private const val SAMPLE_LONG_EDGE = 256

    /** 线性光 → sRGB 码值的查表精度。4096 级误差 < 0.25 码值。 */
    private const val ENC_STEPS = 4096

    data class Stats(
        /** 高饱和像素占比（HLS S > 0.4，且亮度落在 0.12..0.88）。 */
        val hi: Float,
        /** 有效像素的平均饱和度。 */
        val sMean: Float,
        /** 暗部像素占比（L < 0.25），用于夜景保护。 */
        val darkRatio: Float,
        /** 参与统计的像素数；0 = 全黑/全白等不可用场景。 */
        val samples: Int,
    )

    /**
     * 纯函数：从采样像素（ARGB 码值）算统计。JVM 单测可直接构造像素，不依赖 Bitmap。
     * 亮度过滤掉极暗与极亮，避免黑位/白墙把饱和度分布压平。
     */
    fun statsFrom(pixels: IntArray): Stats {
        var n = 0
        var hi = 0
        var sumS = 0f
        var dark = 0
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val l = (mx + mn) * 0.5f
            if (l < 0.12f || l > 0.88f) continue
            val denom = 1f - abs(2f * l - 1f)
            val s = if (denom <= 1e-6f || mx == mn) 0f else (mx - mn) / denom
            n++
            sumS += s
            if (s > 0.4f) hi++
            if (l < 0.25f) dark++
        }
        if (n == 0) return Stats(0f, 0f, 1f, 0)
        return Stats(hi.toFloat() / n, sumS / n, dark.toFloat() / n, n)
    }

    /**
     * 纯函数：按帧统计反推校正强度 [0,1]，带场景保护。
     *
     * - `hi` 越偏离线性底片目标，校正越强；已达目标则完全不动
     * - 夜景（暗部 > 55%）：统计本身不可靠且容易洗灰，强度砍到 35%
     * - 近单色场景（平均饱和 < 0.06）：色彩信息太少，砍到 20%
     * - 无有效采样：直接 0
     *
     * 这里刻意不用 `Build.MODEL` —— 机型差异已经体现在输入像素里了。
     */
    fun suggestStrength(s: Stats): Float {
        if (s.samples == 0) return 0f
        val span = REF_HI - TARGET_HI
        if (span <= 0f) return 0f
        var k = ((s.hi - TARGET_HI) / span).coerceIn(0f, 1f)
        if (s.darkRatio > 0.55f) k *= 0.35f
        if (s.sMean < 0.06f) k *= 0.20f
        return k.coerceIn(0f, MAX_STRENGTH)
    }

    /** 单像素单通道的色度缩放（线性域）。中性灰 r=g=b 时严格不变。 */
    internal fun chromaChannel(v: Float, y: Float, keep: Float): Float =
        (y + (v - y) * keep).coerceIn(0f, 1f)

    /**
     * 对比压缩表：以 128 中灰为锚把码值向中间收。[reduction]=0 时严格恒等。
     */
    internal fun contrastLut(reduction: Float): IntArray {
        val keep = (1f - reduction.coerceIn(0f, 0.9f))
        if (keep >= 1f - 1e-6f) return IntArray(256) { it }
        return IntArray(256) { v -> (128f + (v - 128f) * keep).roundToInt().coerceIn(0, 255) }
    }

    /** 降采样到长边 [SAMPLE_LONG_EDGE] 并统计；失败返回空统计（调用方按 0 强度处理）。 */
    fun measure(bmp: Bitmap): Stats = runCatching {
        val scale = SAMPLE_LONG_EDGE.toFloat() / max(bmp.width, bmp.height)
        val sw = maxOf(1, (bmp.width * scale).roundToInt())
        val sh = maxOf(1, (bmp.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, false)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        small.recycle()
        statsFrom(px)
    }.onFailure {
        android.util.Log.w(TAG, "输入统计失败（按 0 强度处理）", it)
    }.getOrDefault(Stats(0f, 0f, 1f, 0))

    /**
     * 一次遍历完成：线性域曝光增益 + 色度缩放 + 对比压缩。
     *
     * [strength] = 0 时**委托** [InputExposure.apply]，保证关掉滑杆后与旧版逐像素一致
     * （包括 ev=0 时严格不改一个像素）。不可变位图 / 异常 → 返回 false 不动像素。
     */
    fun apply(bmp: Bitmap, ev: Double, strength: Float): Boolean {
        if (strength <= 1e-4f) return InputExposure.apply(bmp, ev)
        return runCatching {
            if (!bmp.isMutable || bmp.isRecycled) {
                android.util.Log.w(TAG, "位图不可变，跳过输入校正（应传 inMutable=true）")
                return false
            }
            val k = strength.coerceIn(0f, MAX_STRENGTH)
            val chromaKeep = 1f - MAX_CHROMA_REDUCTION * k
            val contrast = contrastLut(MAX_CONTRAST_REDUCTION * k)
            val gain = 2.0.pow(ev).toFloat()

            // 预计算：解码 256 项、编码 4097 项，热路径里不出现 pow
            val lin = FloatArray(256) { InputExposure.srgbToLinear(it).toFloat() }
            val enc = IntArray(ENC_STEPS + 1) { InputExposure.linearToSrgb(it.toDouble() / ENC_STEPS) }

            val w = bmp.width
            val h = bmp.height
            val row = IntArray(w)
            for (y in 0 until h) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                var touched = false
                for (x in 0 until w) {
                    val p = row[x]
                    val lr = lin[(p shr 16) and 0xFF] * gain
                    val lg = lin[(p shr 8) and 0xFF] * gain
                    val lb = lin[p and 0xFF] * gain
                    val lum = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
                    val r = contrast[enc[(chromaChannel(lr, lum, chromaKeep) * ENC_STEPS).roundToInt().coerceIn(0, ENC_STEPS)]]
                    val g = contrast[enc[(chromaChannel(lg, lum, chromaKeep) * ENC_STEPS).roundToInt().coerceIn(0, ENC_STEPS)]]
                    val b = contrast[enc[(chromaChannel(lb, lum, chromaKeep) * ENC_STEPS).roundToInt().coerceIn(0, ENC_STEPS)]]
                    val np = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                    if (np != p) {
                        row[x] = np
                        touched = true
                    }
                }
                if (touched) bmp.setPixels(row, 0, w, 0, y, w, 1)
            }
            true
        }.onFailure { android.util.Log.e(TAG, "输入校正异常（跳过）", it) }.getOrDefault(false)
    }
}
