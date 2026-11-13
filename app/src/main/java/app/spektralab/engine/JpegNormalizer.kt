package app.spektralab.engine

import android.graphics.Bitmap
import kotlin.math.abs
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
 * [TARGET_HI] / [REF_HI] 是**输入侧**锚点而非成片目标，且必须用与 [statsFrom]
 * 完全相同的统计口径标定（v50 初版误用了中心裁切口径的 18.5%，导致自适应恒为 0）。
 * 跨机型数据到位后应改用「同帧 RAW 引导统计」（见 isp_guide_report.txt 的
 * rawTarget p50/p90）替换，而不是给每家厂商填常数。
 * 滑杆 0 = 严格回退 v49 行为（逐像素走 InputExposure）；强度上限 clamp，
 * 绝不动 `print_exposure` / `dir_couplers_amount` 等胶片参数，RAW 通路完全不经过这里。
 * v53 起滑杆默认不再是 0（见 [DEFAULT_STRENGTH]），v73 起机身设置面板也有一格
 * 「色彩浓度」就地循环，与工程师控制台滑杆共用同一个 prefs 键。
 *
 * ## 性能
 * 统计只在 256px 降采样图上做；像素变换一次 getPixels/setPixels 遍历完成
 * 曝光 256 项查表 + 色度缩放 + 对比压缩，不产生第二份全尺寸位图（峰值内存已到
 * 240MB），热路径里没有 pow / 没有 4097 项编码表（v52 去掉，顺带更快）。
 *
 * ## v52 教训（勿退回去）
 * 色度缩放曾放在线性域，看似「物理正确」，实测却把 HLS 亮度整体抬高约 0.15~0.19，
 * 满档成片发白、高光烧白。退 ISP 的饱和是 display-referred 域的操作，
 * 必须在码值域保持 L。真机同场景对照数据见 2026-11-08 日志。
 */
object JpegNormalizer {

    private const val TAG = "JpegNormalizer"

    /** 用户滑杆的满档值；0 = 关闭（严格走旧的 InputExposure 路径）。 */
    const val MAX_STRENGTH = 1f

    /**
     * 滑杆默认值（v53 由 0 改为 0.6；v73 由 0.6 改为 0.7）。
     *
     * 依据：2026-11-08 同场景三档严格单变量真机对照（GOLD 200 / 印相 / 曝光0 / 带框）：
     *   校正 0        S 0.2153  hi 10.07%  chroma 0.1237  L50 0.588
     *   滑杆 60%      S 0.1822  hi  7.18%  chroma 0.1037  L50 0.592  ← 当时目视最自然
     *   滑杆 100%     S 0.1473  hi  5.08%  chroma 0.0830  L50 0.628  ← 偏淡偏亮
     * 60% 把多余饱和退掉约 15~29%，中位亮度几乎不动（+0.7%）；满档则 +6.7%。
     *
     * v73：用户 2026-11-12 反馈「JPG 颜色还是太浓郁」。定量复核见
     * `tmp/sample_1109/jpeg_color/jpeg_color_strength_research.md`：
     *   - 同期同场景 JPEG 成片 S 0.2464 / chroma 0.1593，RAW 成片 0.1455 / 0.0804（约 2×）；
     *   - 近期成片普遍 S 0.20~0.34，比当年定 60% 的标定场景（0.1822）浓得多；
     *   - 且 60% 是**乘性上限**，typical baseline 0.632 时实际 k 只有 0.379，
     *     色度只退 13.3%，平淡/夜景/近单色场景几乎不校正。
     * 按上面三档的 S–keep 插值，默认 0.7 + 上限 0.45 → keep≈0.685 → S≈0.154，
     * 比 60% 档淡约 15%，但**不到**满档 0.147 的「偏淡偏亮」。这是初值，
     * 仍待真机三档复标（尤其 L50 抬升来自引擎对低饱和输入的响应，不是色度缩放本身）。
     * 0 仍可手动关掉，逐像素回退到 v49；机身「原始」档即此值。
     */
    const val DEFAULT_STRENGTH = 0.7f

    /**
     * 机身「色彩浓度」的**默认档位**：淡 = 滑杆满档（用户 2026-11-13 指定）。
     *
     * ⚠️ 与 [DEFAULT_STRENGTH] 不是一回事：0.7 是「标准」这一档的算法强度、也是
     * 上面那次定量标定的结论，继续由 `ColorStrength.STANDARD` 引用；这里只决定
     * 「用户从没动过设置」时读到的档位。别把两者合成一个常量，否则三档会塌成两档。
     * 新装 / 清数据后所有 prefs 读取点统一回落到这个值（见 com.filmcam.app.DefaultSettings）。
     */
    const val DEFAULT_LEVEL_STRENGTH = MAX_STRENGTH

    /**
     * 满档时最多缩减的色度比例。v73 由 0.35 提到 0.45：
     * 给机身「淡」档留出比 11-08 满档更淡的空间（keep 0.65 → 0.55）。
     * ⚠️ 注意 11-13 起默认档已改为「淡」（会吃满这个天花板）；标定场景的
     * 0.7「标准」档仍可在机身面板里手动切回去。
     * ⚠️ 经验上限，真机复标前不要再往上加：11-08 满档已 L50 +6.7%、目视偏淡偏亮。
     */
    const val MAX_CHROMA_REDUCTION = 0.45f

    /**
     * 满档时最多压缩的对比比例（以 128 中灰为锚）。
     * v52：0.18 -> 0.08。满档 0.18 时黑位被抬到 23、白位压到 232，
     * 叠上去饱和后画面明显发灰；这一层只是退 ISP 的 S 曲线，不该成为主机制。
     */
    const val MAX_CONTRAST_REDUCTION = 0.08f

    /**
     * 输入侧「高饱和像素占比」的目标：靠近线性底片一侧的平淡输入。
     *
     * ⚠️ 口径必须与 [statsFrom] 一致：**整张位图 256px 降采样**（此时已裁片门、
     * 已叠暗角与日期戳），亮度过滤 0.12..0.88。这里不能用「中心裁切」口径的数字 ——
     * 同一张 HAL 原帧中心裁切是 18.5%，整图口径只有 9.26%，两者差一倍。
     *
     * S25U 实测（2026-11-08）：已调味 GOLD200 输入 0.062~0.066；平淡输入
     * 0.019（三星相机 P3 JPG）；HAL 原帧整图 0.0926（未经片门裁切/暗角）。
     * 取 0.030 作目标、0.070 作「典型已调味」参考，让浓郁场景接近满档、
     * 平淡场景自动不校正。跨机型数据到位后应改用同帧 RAW 引导统计替换，
     * 而不是给每家厂商填常数。
     */
    const val TARGET_HI = 0.030f

    /** 典型「已被 ISP 调味」的 JPEG 占比（App 整图口径，见 [TARGET_HI] 注释）。 */
    const val REF_HI = 0.070f

    /** 采样图长边（px）。统计用途，不参与出片。 */
    private const val SAMPLE_LONG_EDGE = 256

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

    /**
     * v53：整卷统一强度 = 首帧自适应基准 × 用户滑杆倍率。
     * 抽成纯函数便于 JVM 单测守住「整卷一致、滑杆 0 可完全关闭」这条契约。
     * [baseline] 由首帧 [suggestStrength] 定出，一卷内所有帧传同一个值。
     */
    fun effectiveStrength(baseline: Float, slider: Float): Float =
        (baseline.coerceIn(0f, MAX_STRENGTH) * slider.coerceIn(0f, MAX_STRENGTH))
            .coerceIn(0f, MAX_STRENGTH)

    /**
     * 单通道色度缩放：`y + (v-y)*keep`，中性灰 r=g=b 时严格不变。
     * v52 起调用方传入的是 **sRGB 码值域归一化值** 与 HLS 的 L；
     * 传线性域值会导致亮度漂移（见类注释）。
     */
    internal fun chromaChannel(v: Float, y: Float, keep: Float): Float =
        (y + (v - y) * keep).coerceIn(0f, 1f)

    /** 码值域单通道去饱和；[l] 为该像素 HLS 的 L（归一化），缩放后 L 严格不变。 */
    internal fun desaturateChannel(code: Int, l: Float, keep: Float): Int =
        (chromaChannel(code / 255f, l, keep) * 255f).roundToInt().coerceIn(0, 255)

    /** HLS 亮度 L=(max+min)/2（归一化）。 */
    internal fun hlsL(r: Int, g: Int, b: Int): Float =
        (max(r, max(g, b)) + min(r, min(g, b))) * 0.5f / 255f

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
     * 一次遍历完成：线性域曝光增益（走 [InputExposure.lut]，与旧版逐像素一致）
     * → **码值域**保持亮度的色度缩放 → 轻微对比压缩。
     *
     * ⚠️ v52 关键修正：色度缩放**不能在线性域做**。v50/v51 用线性能量亮度
     * `0.2126R+0.7152G+0.0722B` 作锚，数学上守恒的是线性能量，但编码回 sRGB 后
     * HLS 亮度会大幅抬升 —— 实测典型像素：纯红 L 0.500→0.686、肤色 0.706→0.859、
     * 窗外亮灰 0.843→1.000（直接烧白）。真机满档成片因此「发白、发灰、高光过曝」，
     * 并非单纯变淡。这里改在 display-referred 码值域以 HLS 的 L=(max+min)/2 为锚，
     * 去饱和严格不改变亮度；ISP 当初加饱和也是在显示域做的，退它自然该在同一域。
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
            // 曝光段直接复用旧表：ev=0 严格恒等，ev≠0 与 v49 逐像素一致
            val exp = InputExposure.lut(ev)

            val w = bmp.width
            val h = bmp.height
            val row = IntArray(w)
            for (y in 0 until h) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                var touched = false
                for (x in 0 until w) {
                    val p = row[x]
                    val r0 = exp[(p shr 16) and 0xFF]
                    val g0 = exp[(p shr 8) and 0xFF]
                    val b0 = exp[p and 0xFF]
                    // HLS 的 L：去饱和时严格不变，避免高光烧白 / 整体抬亮
                    val l = hlsL(r0, g0, b0)
                    val r1 = desaturateChannel(r0, l, chromaKeep)
                    val g1 = desaturateChannel(g0, l, chromaKeep)
                    val b1 = desaturateChannel(b0, l, chromaKeep)
                    val r = contrast[r1]
                    val g = contrast[g1]
                    val b = contrast[b1]
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
