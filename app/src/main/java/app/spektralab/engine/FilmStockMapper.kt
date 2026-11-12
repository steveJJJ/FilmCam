package app.spektralab.engine

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * FilmRoll.name → 引擎 film_stock / print_stock id 映射（规格 §1 冻结表）。
 * 并负责组装引擎 setParams 的冻结 JSON 模板（规格 §2）。
 */
object FilmStockMapper {

    /**
     * 旧版本胶卷记录可能存了空相纸 id（反转片曾映射为空串）；
     * 冲洗时用它兜底——引擎要求 print_stock 必须是合法 profile，
     * 反转片虽走 scan_film 直扫，但仍需一个可加载的占位 profile。
     */
    const val DEFAULT_PRINT_FALLBACK = "kodak_portra_endura"

    /**
     * 放大机曝光（引擎 `print_exposure`）**恒等于这个值**，不再随用户旋钮变化。
     *
     * 为什么锁死：引擎的 135 片框（黑片基 / 橙色暗边印刷 / 白齿孔）是在**印相阶段**
     * 跟画面一起叠上去的，`print_exposure` 一变，片框就跟着一起变浓变淡 —— 用户
     * 11-07 明确「这个也不应该作用于胶片框」。真机上片基密度是冲洗定死的，
     * 换印相曝光只该改变画面，所以曝光调整整体搬到**输入层**（见 [DevSettings.exposureEv]）。
     *
     * 0.83 = 相纸标准浓度（paper_grades.json 的 3 号档），与 v40 起 RAW 通路的恒定值一致。
     * 注意量纲：**正 EV = 更暗**（放大机照得更久）。
     */
    const val PRINT_EXPOSURE_LOCKED = 0.83f

    /** 输入层曝光补偿的允许区间（EV）：负 = 更暗，正 = 更亮。 */
    const val INPUT_EV_MIN = -1.5f
    const val INPUT_EV_MAX = 1.5f

    /**
     * JPEG / 导入通路的**基础**输入曝光（EV）。
     *
     * RAW 通路有「同帧 ISP JPEG 引导」逐帧追平设备的自动曝光，JPEG 通路没有参考可追，
     * 实测成片整体比 RAW 暗一档左右（用户 11-07：「JPG 的曝光有点暗，导入照片冲洗的
     * 照片也比较暗，暗了影响成片效果」）。这里给一个固定基准把两条通路拉到同一水平，
     * 用户仍可在工程师面板上再叠 [DevSettings.exposureEv]。
     */
    const val JPEG_INPUT_BASE_EV = 0.6f

    /**
     * 导入 RAW 的固定输入曝光（EV）。
     *
     * 相机拍的 RAW 能用「同一次快门的 ISP JPEG」当曝光参考；导入的 RAW 没有这张参考 ——
     * 入卷时随帧存的那张 JPEG 是**引擎自己渲的兜底图**（已经过了整条胶片链 + 印相），
     * 拿它当目标等于把印相曝光算两遍，成片发闷发暗。所以导入 RAW 跳过引导，用固定值，
     * 取值贴着相机 RAW 引导的中位选择。
     */
    const val RAW_IMPORT_BASE_EV = 1.0f

    /**
     * 这一卷实际要施加的**输入层**曝光（EV）。
     *
     * @param fullScanFinish 店扫档只有完整档才带曝光倾向（保守档与印相档基线一致）。
     * 店扫的曝光倾向本来写在 `print_exposure` 上（正 = 更暗），搬到输入层要取反。
     */
    fun inputEvFor(
        settings: DevSettings,
        fullScanFinish: Boolean = true,
    ): Float {
        val styleEv = if (settings.outputStyle.isScan && fullScanFinish) {
            -styleExposureOffsetEv(settings.outputStyle)
        } else 0f
        return (settings.exposureEv + styleEv).coerceIn(INPUT_EV_MIN - 0.3f, INPUT_EV_MAX + 0.3f)
    }

    /** 该 film stock 是否为反转片。 */
    fun isPositive(filmStockId: String): Boolean = filmTypeOf(filmStockId) == "positive"

    /**
     * 成片出口：同一卷底片，最后一步怎么变成你看到的那张图。
     *
     * 真机的成片从来不是「负片本身」，而是负片经过某个出口之后的产物，
     * 而出口不同，味道完全不同：
     *  - 印相：放大机把负片投到相纸上，纸基白、黑位实、高光脆。
     *  - 扫描：店里把冲好的负片放进扫描仪读出来（网上绝大多数 T3 样片都是这个）。
     *    Frontier 与 Noritsu 是两家铺得最广的店扫，差别一眼可辨。
     *
     * 引擎里这两条路是分开的：`scan_film=true` 且不带 print_stock 时走扫描通路
     * （放大机/相纸整段跳过），否则走印相通路。
     */
    enum class OutputStyle(val prefs: Int, val wire: String) {
        /** 光学印相：放大机 → 相纸，纸面反射光被"看"到 */
        PRINT(0, "print"),

        /**
         * 富士 Frontier 店扫：暗部偏冷、对比强、高光柔（店员常开 Highlight Soft）、
         * 颗粒被扫描镜头轻微抹平。网上 T3 样片里最常见的那一种。
         */
        FRONTIER(1, "frontier"),

        /** 诺日士 Noritsu 店扫：整体更平、颗粒更清晰偏单色，暗部细节留得多，适合后期再调。 */
        NORITSU(2, "noritsu"),

        /** 现有机身上没有的第三档以后：未知值一律按印相处理 */
        ;

        val isScan: Boolean get() = this != PRINT

        companion object {
            fun fromPrefs(v: Int): OutputStyle =
                values().firstOrNull { it.prefs == v } ?: PRINT

            fun fromWire(s: String?): OutputStyle =
                values().firstOrNull { it.wire == s } ?: PRINT
        }
    }

    /**
     * 片框档位：成片要不要把片基一起扫进来，扫哪一格。
     *
     * 真机那卷胶卷是"一整条"——画面之外还有齿孔、片基边缘印的胶卷型号和帧号。
     * 店里扫描扫的就是这一整条，所以成片带片框才是"这是一卷胶卷"最直白的证据。
     *
     * 只有一格：**135 全画幅 `135`**，36×24mm，就是康泰时 T3 那一格，3:2。
     * 取景是 4:3，要套这一格就得先按片门裁一下——真机也是这么回事：
     * 镜头成像圈是圆的，机身只取中间那块 36×24 的矩形，画面外的部分从来就没落在底片上。
     *
     * **片门会跟着横竖拍转**：真机竖拍时，画面在底片上是竖着的（24 宽 × 36 高），
     * 齿孔照样在上下（胶卷只朝一个方向走）。所以竖拍走的是 [gatePortrait]，
     * 裁的是左右两条边（各约 5.6%），而不是把竖照片硬压成横的 3:2。
     *
     * 引擎是照着片门形状校验输入尺寸的，比例不对它会在渲染期直接拒绝
     * （`overscan: a W x H frame is not the 135 gate's shape (36 x 24)`），
     * 所以 [gateAspect] 同时用于冲洗前的裁切和自检小图的尺寸。
     */
    enum class FrameFormat(val key: String, val overscanFormat: String?, val gateAspect: Float) {
        /** 不扫片基：成片就只是画面（画幅仍按 135 全画幅片门出，见 DevSettings.outputGate） */
        OFF("off", null, 36f / 24f),

        /** 135 全画幅（36×24mm，3:2，T3 这一格）：4:3 取景上下各裁掉约 5.6% */
        FULL("135", OVERSCAN_FORMAT_FULL, 36f / 24f),

        /**
         * v66 白边相框（暗房留白）：引擎仍按 135 片门出**纯画面**，
         * 由 `WhiteBorderRenderer` 在落盘前外扩四边等宽的纸白边。
         * 这一档绝不下发任何 `overscan_*` —— 片基/齿孔是引擎片框，不是相纸白边。
         */
        WHITE("white", null, 36f / 24f),
        ;

        /** 是否走引擎的片框合成（白边档由 App 侧绘制，不算） */
        val isOn: Boolean get() = overscanFormat != null

        /** 是否走 App 侧白边合成 */
        val isWhiteBorder: Boolean get() = this == WHITE

        /** 竖拍那一格：画面转 90°，宽高比取倒数（135 竖拍 = 24×36 = 2:3） */
        val gatePortrait: Float get() = 1f / gateAspect

        /** 按朝向取片门宽高比：竖帧（宽<高）走竖置片门，横帧走横置片门 */
        fun gateAspectFor(portrait: Boolean): Float =
            if (portrait) gatePortrait else gateAspect

        companion object {
            /** 旧版存过的半格档位串：现在只剩 135 一格，升级时按「仍要片框」处理 */
            const val LEGACY_KEY_HALF = "135_half"

            fun fromKey(s: String?): FrameFormat = when (s) {
                LEGACY_KEY_HALF -> FULL   // 老用户选过的半格 → 归到 135，别把片框悄悄关掉
                else -> values().firstOrNull { it.key == s } ?: OFF
            }

            /** 按引擎版式串反查档位（自检/日志里拿到的就是这种串）。 */
            fun fromOverscanFormat(s: String?): FrameFormat =
                values().firstOrNull { it.overscanFormat != null && it.overscanFormat == s } ?: OFF

            /** 档位循环：关 → 全画幅 135 → 白边 → 关。设置面板点一下就地切换。 */
            fun next(current: FrameFormat): FrameFormat = when (current) {
                OFF -> FULL
                FULL -> WHITE
                WHITE -> OFF
            }
        }
    }

    /**
     * 引擎已知参数名白名单（逐条对照 libspektrafilm_engine.so 字符串表核过）。
     *
     * 引擎对不认识的键直接报 `unknown parameter 'xxx'` 并让 setParams 失败——
     * 也就是说，往这份 JSON 里塞任何一个「只给我们自己看」的字段，
     * 代价是整卷一帧都出不来。这类标记必须走 [buildExifParamsJson]（只进 EXIF）。
     * OutputStyleTest 会拿这份集合把三档参数逐键校验一遍。
     */
    val ENGINE_KNOWN_KEYS: Set<String> = setOf(
        // 通路 / 介质
        "film_stock", "print_stock", "scan_film", "film_format_mm",
        // 色彩
        "input_color_space", "input_cctf_decoding", "output_color_space",
        // 曝光 / 预览
        "print_exposure", "preview_long_edge", "exposure_compensation_ev",
        "auto_exposure", "auto_exposure_method", "lens_blur_um",
        // 胶片质感
        "grain_amount", "halation_amount", "glare_amount", "glare_active",
        // 放大机（滤色片 / 预闪）：店扫档用滤色片表达扫描仪校色的冷暖
        "m_filter_shift", "y_filter_shift", "filter_shift_scale", "preflash_exposure",
        // 密度曲线 gamma（整体反差）、层间效应（负片饱和度）
        "density_curve_gamma", "dir_couplers_active", "dir_couplers_amount",
        // 扫描仪
        "scanner_lens_blur", "scanner_black_correction", "scanner_white_correction",
        // 局部反差（print_render.contrast_mask.*）
        "contrast_mask_active", "contrast_mask_scheme", "contrast_mask_scale",
        "contrast_mask_core", "contrast_mask_shadows", "contrast_mask_highlights",
        // 场景宽容度（camera.scene_latitude.*）
        "scene_latitude_active", "scene_latitude_b_active", "scene_latitude_rolloff",
        "scene_latitude_max_lift", "scene_latitude_norm", "scene_latitude_split",
        "scene_latitude_highlight_knee", "scene_latitude_highlight_room",
        "scene_latitude_shadow_knee", "scene_latitude_shadow_room",
        // 片框（film_render.overscan.*）：片基 / 齿孔 / 暗边文字 / 帧号
        "overscan_active", "overscan_mode", "overscan_format", "overscan_gate",
        "overscan_holes", "overscan_fog", "overscan_leaks", "overscan_pair",
        "overscan_carrier", "overscan_edge_text", "overscan_frame_number",
        "overscan_frame_seed", "overscan_camera_seed", "overscan_f_number",
        // 注意：io.geometry.* 整组真机实测被引擎拒收——crop_* 两种量纲都在
        // spk_open 报错，quarter_turns 直接让竖拍帧掉兜底档（冲洗完成页报
        // 「有帧未按要求出图」）。整组不收录、不发。
        // RAW 片门预裁走 DNG ActiveArea 改写（见 SpektraProcessor.prepareGateDng）。
    )

    /** 冲洗（暗房）用户可调参数；未改设置时全部取冻结模板默认值。 */
    data class DevSettings(
        val grainAmount: Float = 1.0f,
        val halationAmount: Float = 1.0f,
        val glareAmount: Float = 3.0f,
        /**
         * 曝光补偿（EV，**作用在引擎输入层**，不是放大机曝光）。
         *
         * 11-07 用户定调：放大机曝光（`print_exposure`）一变，整张片基/齿孔/暗边
         * 印刷字跟着一起变浓变淡 ——「这个不应该作用于胶片框」。物理上也对：
         * 片基密度是冲洗出来的，不该随印相曝光改变。
         * 所以 `print_exposure` 锁死为 [PRINT_EXPOSURE_LOCKED]，这把旋钮改成
         * 在**进引擎之前**对像素加线性增益（RAW 走 DNG Bayer，JPEG 走 sRGB LUT），
         * 等价于换了一档感光度/曝光时间，片框由引擎以固定浓度叠上去。
         *
         * 正 = 更亮（与旧的 `print_exposure` 方向相反，那个是「正 = 更暗」）。
         */
        val exposureEv: Float = 0f,
        val paperOverride: String? = null, // 预留：相纸 id 覆盖（null/空白 = 用胶卷映射的 print_stock）
        val outputStyle: OutputStyle = OutputStyle.PRINT, // 成片出口：印相 / Frontier / Noritsu
        /**
         * 镜头柔度（μm）：Sonnar T* 35/2.8 打在 36mm 底片上的模糊斑尺度。
         * 默认 [LENS_BLUR_UM]；工程师「冲洗参数」面板里可全局调（镜头是机身的一部分，
         * 不随胶卷换、不按卷存），用于标定最终定值。0 = 关（纯数码锐）。
         */
        val lensBlurUm: Float = LENS_BLUR_UM,
        /**
         * 片框档位：把片基、齿孔、暗边文字一起扫进来，成片像一张底片扫描件（引擎 overscan）。
         * 开着就按 135 全画幅 36×24 片门裁成 3:2。
         */
        val frameFormat: FrameFormat = FrameFormat.OFF,
        /**
         * 全局色彩微调（工程师「色彩微调」面板，全局存、不按胶卷——
         * 对的是「这台手机 + 这套引擎」的整体成色，换胶卷不换口味）。
          * ccY = 放大机黄滤片偏移（**正=冷/白，负=暖/黄**）；ccM = 品红滤片偏移
         * （**正=偏青绿，负=偏洋红**——肤色「不够白」通常要配一点负值）。
         * 默认 = 0（冻结定值已取消，纯净基线，见 [CC_Y_DEFAULT]）；
         * 店扫档不叠色彩微调（只属印相档，店扫色彩由签名决定）。
         */
        val ccY: Float = CC_Y_DEFAULT,
        val ccM: Float = CC_M_DEFAULT,
        /**
         * 滤色强度增益 `filter_shift_scale`：滤色片本身太弱（真机标定 ±1.0 也只挪
         * ~1.6 个 R−B 色阶），要真正挪动肤色得靠它放大。默认 [CC_SCALE_DEFAULT]；
         * null = 不发（用户在面板上显式拖到 0 = 主动关掉）。
         * 值域未标定——DevelopmentService 会在冲洗前单独预检，被拒整场撤掉。
         */
        val ccScale: Float? = CC_SCALE_DEFAULT,
        /**
         * v50：JPEG / 导入通路的**输入侧反 ISP 归一化**强度倍率 [0,1]。
         *
         * 0 = 完全关闭，逐像素行为与 v49 一致；>0 时由**首帧**统计定出整卷基准
         * （见 [JpegNormalizer.suggestStrength]），整卷共用同一强度再乘这个倍率。
         * 一卷胶卷是整卷一起冲的，逐帧各算各的会让同卷浓淡不一（v53 修正）。
         * 默认 [JpegNormalizer.DEFAULT_STRENGTH]，依据见该常量注释的真机标定数据。
         * 只作用 JPEG 通路，RAW 通路不经过；不按机型存，也不改任何胶片参数。
         * 全局一个值，工程模式滑杆可改，下一次冲洗生效。
         */
        val jpegNormalizeStrength: Float = JpegNormalizer.DEFAULT_STRENGTH,
    ) {
        /**
         * 成片按哪一格片门出片。片框关着时也一样按 135 全画幅那一格（3:2）——
         * 取景器看到的是 4:3 一整块，真正落到底片上的只有中间那块 36×24。
         */
        val outputGate: FrameFormat
            get() = if (frameFormat == FrameFormat.OFF || frameFormat == FrameFormat.WHITE) {
                FrameFormat.FULL
            } else frameFormat
    }

    /** 相纸下拉项（保序，来自 stock_catalog.json prints[] 顺序）。 */
    data class PrintOption(val id: String, val name: String)

    /** 相纸解析结果：最终使用的 paperId 与是否因规则回退。 */
    data class ResolvedPrint(val paperId: String, val fellBack: Boolean)

    /** roll name -> (film_stock, print_stock)。键全部大写以容忍外部大小写抖动。 */
    private val ROLL_TABLE: Map<String, Pair<String, String>> = linkedMapOf(
        "PORTRA 400"       to ("kodak_portra_400"            to "kodak_portra_endura"),
        "EKTAR 100"        to ("kodak_ektar_100"             to "kodak_endura_premier"),
        "GOLD 200"         to ("kodak_gold_200"               to "kodak_endura_premier"),
        "SUPERIA 400"      to ("fujifilm_xtra_400"           to "fujifilm_crystal_archive_typeii"),
        // 早期版本曾把这支胶卷误叫 "CLASSIC CHROME"（富士的数字胶片模拟名），
        // 实际引擎 profile 是柯达 Kodachrome 64 反转片——已更正为真实胶卷名；
        // 旧键保留作兼容别名（老代码路径若仍传旧名不至于崩溃）。
        "KODACHROME 64"    to ("kodak_kodachrome_64"         to "kodak_endura_premier"),
        "CLASSIC CHROME"   to ("kodak_kodachrome_64"         to "kodak_endura_premier"),
        // 早期误用了别的品牌名（Eterna 是富士电影卷、CN800 是乐魔的型号），
        // 引擎实际 profile 分别是柯达 Vision3 250D 与 CineStill 800T——已更正；
        // 旧键保留作兼容别名。
        "VISION3 250D"     to ("kodak_vision3_250d"          to "kodak_2383"),
        "ETERNA"           to ("kodak_vision3_250d"          to "kodak_2383"),
        "TRI-X 400"        to ("kodak_tri_x_400"             to "ilford_multigrade_iv_rc"),
        "HP5 PLUS"         to ("ilford_hp5_plus_400"         to "ilford_multigrade_iv_rc"),
        "CINESTILL 800T"   to ("cinestill_800t"              to "kodak_endura_premier"),
        "CN800"            to ("cinestill_800t"              to "kodak_endura_premier"),
        // ---- 2025-11 扩充：引擎 catalog 全量 25 支胶卷 ----
        // 配对依据（三项交叉核对）：
        //   1) stock_catalog.json 的 type/use 字段（negative / positive / use=cine）
        //   2) print_luts.json 的 paired_film 声明（相纸侧标注的典型配对胶片）
        //   3) 品牌官方推荐：柯达负片印 Kodak 相纸、富士负片印 Crystal Archive、
        //      黑白印 Ilford Multigrade、电影卷印 Kodak 2383 正片拷贝
        // 注：原项目 SpektraLab-Android 无配对表（胶片/相纸是两个独立下拉），
        //     这张表是本产品的默认搭配，用户在冲洗参数里可手动覆盖。
        // 反转片（ProVia / Velvia / Ektachrome / Kodachrome）映射仅作保留，
        //     UI 已禁用选择——原项目明确「本版暂只支持负片」。
        "C200"             to ("fujifilm_c200"                to "fujifilm_crystal_archive_typeii"),
        "NEOPAN ACROS 100 II" to ("fujifilm_neopan_acros_100_ii" to "ilford_multigrade_iv_rc"),
        "PRO 400H"         to ("fujifilm_pro_400h"            to "fujifilm_crystal_archive_typeii"),
        "PROVIA 100F"      to ("fujifilm_provia_100f"         to "fujifilm_crystal_archive_typeii"),
        "VELVIA 100"       to ("fujifilm_velvia_100"          to "fujifilm_crystal_archive_typeii"),
        "EKTACHROME 100"   to ("kodak_ektachrome_100"         to "kodak_endura_premier"),
        "PORTRA 160"       to ("kodak_portra_160"             to "kodak_portra_endura"),
        "PORTRA 800"       to ("kodak_portra_800"             to "kodak_portra_endura"),
        "PORTRA 800 PUSH 1" to ("kodak_portra_800_push1"      to "kodak_portra_endura"),
        "PORTRA 800 PUSH 2" to ("kodak_portra_800_push2"      to "kodak_portra_endura"),
        "T-MAX 100"        to ("kodak_tmax_100"               to "ilford_multigrade_iv_rc"),
        "ULTRAMAX 400"     to ("kodak_ultramax_400"           to "kodak_endura_premier"),
        "VERITA 200D"      to ("kodak_verita_200d"            to "kodak_endura_premier"),
        "VISION3 50D"      to ("kodak_vision3_50d"            to "kodak_2383"),
        "VISION3 200T"     to ("kodak_vision3_200t"           to "kodak_2383"),
        "VISION3 500T"     to ("kodak_vision3_500t"           to "kodak_2383"),
    )

    // ---------- stock_catalog.json 名称缓存 ----------

    @Volatile
    private var displayNames: Map<String, String>? = null

    /** film id -> type（"negative"/"positive"/...），未命中/未初始化返回 null。 */
    @Volatile
    private var filmTypes: Map<String, String>? = null

    /** prints[] 保序列表（id+name），catalog 读失败时为空表。 */
    @Volatile
    private var printOptionsList: List<PrintOption> = emptyList()

    /**
     * catalog 解析结果快照（纯数据，便于单元测试直接喂 JSON 文本校验）。
     */
    data class CatalogSnapshot(
        val names: Map<String, String>,
        val types: Map<String, String>,
        val prints: List<PrintOption>,
    )

    /**
     * 读取 assets/resources/stock_catalog.json 的 films[]/prints[]，建立 id→name、
     * id→type 缓存，以及保序的 prints[] 列表。
     * 由 SpektraProcessor.ensureEngine 在拿到 Context 后调用一次。
     */
    fun initialize(context: Context) {
        if (displayNames != null) return
        synchronized(this) {
            if (displayNames != null) return
            val snapshot = try {
                context.assets.open("resources/stock_catalog.json").use { input ->
                    parseCatalog(input.bufferedReader(Charsets.UTF_8).readText())
                }
            } catch (_: Throwable) {
                // 读不到 catalog 就退化为返回 id 本身 / 空相纸表。
                CatalogSnapshot(emptyMap(), emptyMap(), emptyList())
            }
            installCatalog(snapshot)
        }
    }

    /**
     * 纯函数：把 stock_catalog.json 文本解析成快照。
     * 解析失败（非法 JSON）返回空快照，不抛异常——调用方按"无 catalog"降级。
     */
    fun parseCatalog(text: String): CatalogSnapshot {
        val names = HashMap<String, String>(64)
        val types = HashMap<String, String>(32)
        val prints = ArrayList<PrintOption>(16)
        try {
            val root = JSONObject(text)
            root.optJSONArray("films")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id")
                    val name = o.optString("name")
                    if (id.isNotEmpty() && name.isNotEmpty()) names[id] = name
                    val type = o.optString("type")
                    if (id.isNotEmpty() && type.isNotEmpty()) types[id] = type
                }
            }
            root.optJSONArray("prints")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optString("id")
                    val name = o.optString("name")
                    if (id.isNotEmpty()) {
                        names[id] = name
                        prints.add(PrintOption(id, name))
                    }
                }
            }
        } catch (_: Throwable) {
            // 非法 JSON：返回空快照
            return CatalogSnapshot(emptyMap(), emptyMap(), emptyList())
        }
        return CatalogSnapshot(names, types, prints)
    }

    /** 安装快照到进程内缓存（initialize 与单元测试共用）。 */
    fun installCatalog(snapshot: CatalogSnapshot) {
        displayNames = snapshot.names
        filmTypes = snapshot.types
        printOptionsList = snapshot.prints
    }

    /** 清空缓存（单元测试隔离用）。 */
    fun clearCatalogForTest() {
        displayNames = null
        filmTypes = null
        printOptionsList = emptyList()
    }

    /** ROLL_TABLE 的全部键（胶卷显示名），供一致性校验。 */
    fun rollNames(): List<String> = ROLL_TABLE.keys.toList()

    /** ROLL_TABLE 的完整映射快照（name -> filmId to printId），供一致性校验。 */
    fun rollTable(): Map<String, Pair<String, String>> = ROLL_TABLE.toMap()

    /** 返回 film 的 type（"negative"/"positive"/...）；未初始化或未知 id 返回 null。 */
    fun filmTypeOf(filmStockId: String): String? {
        return filmTypes?.get(filmStockId)
    }

    /**
     * 相纸下拉项（保序，按 stock_catalog.json prints[] 出现顺序）。
     * 内部自动 initialize(context)；catalog 读失败返回空表。
     */
    fun printStockOptions(context: Context): List<PrintOption> {
        if (displayNames == null) initialize(context.applicationContext)
        return printOptionsList
    }

    /**
     * 相纸解析：
     *  - 反转片（type=="positive"）：不使用任何相纸，直接显影（[NO_PRINT]）；
     *    用户若选了相纸则忽略并置 fellBack=true（提示用）。
     *  - 负片：选中即用、未选回落默认。
     */
    fun resolvePrintStock(
        filmStockId: String,
        mappedDefault: String,
        selectedPaper: String?,
    ): ResolvedPrint {
        val sel = selectedPaper?.takeIf { it.isNotBlank() }
        return if (isPositive(filmStockId)) {
            // 反转片直接扫描胶片出正像，相纸不参与成像；仍需一个合法 id 供引擎加载
            ResolvedPrint(mappedDefault, sel != null)
        } else {
            ResolvedPrint(sel ?: mappedDefault, false)
        }
    }

    // ---------- 冻结 API ----------

    fun filmStockIdFor(filmRollName: String): String {
        return lookup(filmRollName).first
    }

    fun printStockIdFor(filmRollName: String): String {
        return lookup(filmRollName).second
    }

    fun stockDisplayName(stockId: String): String {
        return displayNames?.get(stockId) ?: stockId
    }

    /**
     * 从 SharedPreferences("filmcam") 读指定胶卷的用户冲洗参数（按卷独立）。
     * 键名冻结：filmcam_dev_grain_<stockId> / filmcam_dev_halation_<stockId> /
     *           filmcam_dev_glare_<stockId> / filmcam_dev_exposure_ev_<stockId> /
     *           filmcam_dev_paper_<stockId>（stockId 即引擎 film_stock id，[a-z0-9_]）。
     * 范围钳制：grain/halation 0–2，glare 0–6，exposureEv −1.5–+1.5 EV。
     * 未写过的卷全部回退到 DevSettings 默认值（1.0/1.0/3.0/0.0/null）。
     *
     * 旧键 `filmcam_dev_print_exposure_<stockId>` 已废：那个值会同时改片框浓度，
     * 现在曝光整体在输入层做（见 [PRINT_EXPOSURE_LOCKED]），旧值读出来就丢掉。
     */
    fun readDevSettings(context: Context, filmStockId: String): DevSettings {
        val sp = context.getSharedPreferences("filmcam", Context.MODE_PRIVATE)
        val grain = sp.getFloat("filmcam_dev_grain_$filmStockId", 1.0f).coerceIn(0f, 2f)
        val halation = sp.getFloat("filmcam_dev_halation_$filmStockId", 1.0f).coerceIn(0f, 2f)
        val glare = sp.getFloat("filmcam_dev_glare_$filmStockId", 3.0f).coerceIn(0f, 6f)
        val exposureEv = sp.getFloat("filmcam_dev_exposure_ev_$filmStockId", 0f)
            .coerceIn(INPUT_EV_MIN, INPUT_EV_MAX)
        val paper = sp.getString("filmcam_dev_paper_$filmStockId", null)?.takeIf { it.isNotBlank() }
        return DevSettings(
            grainAmount = grain,
            halationAmount = halation,
            glareAmount = glare,
            exposureEv = exposureEv,
            paperOverride = paper,
            // 出口是整卷统一的口味，不按胶卷存；脏值回退印相
            // 片框是整卷统一的输出样式（跟出口档位一样），不按胶卷存
            frameFormat = readFrameFormat(context),
            outputStyle = OutputStyle.fromPrefs(sp.getInt("filmcam_output_style", OutputStyle.PRINT.prefs)),
            // 镜头柔度同样全局存（机身的一部分），工程师面板标定用；区间 = 引擎真机验证过的接受范围
            lensBlurUm = sp.getFloat("filmcam_dev_lens_blur", LENS_BLUR_UM).coerceIn(0f, LENS_BLUR_MAX_UM),
            // 色彩微调同样全局存（整体成色，不按胶卷）：y 正=冷/白、负=暖/黄；
            // m 正=青绿、负=洋红。没动过滑杆 = 0（不校色，纯净基线）。
            ccY = sp.getFloat("filmcam_dev_cc_y", CC_Y_DEFAULT).coerceIn(-CC_SHIFT_MAX, CC_SHIFT_MAX),
            ccM = sp.getFloat("filmcam_dev_cc_m", CC_M_DEFAULT).coerceIn(-CC_SHIFT_MAX, CC_SHIFT_MAX),
            // 滤色强度：默认 15（仅在滤色片非零时才随键下发）；contains 区分
            // 「没存过」和「显式存 0」（= 主动不发）——getFloat 默认值分不出来
            ccScale = if (sp.contains("filmcam_dev_cc_scale")) {
                sp.getFloat("filmcam_dev_cc_scale", CC_SCALE_DEFAULT).takeIf { it > 0f }
            } else CC_SCALE_DEFAULT,
            // 输入侧归一化：v73 默认 70%（同场景三档标定 + 11-12 反馈上调），0 仍可关。\n            // 机身「色彩浓度」三档与工程师滑杆写的是同一个键。脏值收进 [0,1]。
            jpegNormalizeStrength = sp.getFloat(
                "filmcam_dev_jpeg_normalize", JpegNormalizer.DEFAULT_STRENGTH
            ).coerceIn(0f, JpegNormalizer.MAX_STRENGTH),
        )
    }

    private const val PREF_FRAME_BORDER = "filmcam_frame_border"   // 旧版开关（布尔），仅用于迁移
    private const val PREF_FRAME_FORMAT = "filmcam_frame_format"

    /** 片框档位（整卷统一）。旧版只有"开/关"，开过的一律按 135 全画幅迁移。 */
    fun readFrameFormat(context: Context): FrameFormat {
        val sp = context.getSharedPreferences("filmcam", Context.MODE_PRIVATE)
        val saved = sp.getString(PREF_FRAME_FORMAT, null)
        if (saved != null) return FrameFormat.fromKey(saved)
        // 旧包升级：以前开着片框的用户，档位就是当时那个（半格串由 fromKey 归一成 135）
        return if (sp.getBoolean(PREF_FRAME_BORDER, false)) FrameFormat.FULL else FrameFormat.OFF
    }

    fun saveFrameFormat(context: Context, format: FrameFormat) {
        context.getSharedPreferences("filmcam", Context.MODE_PRIVATE)
            .edit().putString(PREF_FRAME_FORMAT, format.key).apply()
    }

    /** 档位循环：关 → 全画幅 135 → 白边 → 关，返回新档位 */
    fun cycleFrameFormat(context: Context): FrameFormat {
        val next = FrameFormat.next(readFrameFormat(context))
        saveFrameFormat(context, next)
        return next
    }

    /**
     * 按档位裁到片门：横帧走横置片门（36×24），竖帧走竖置片门（24×36）。
     *
     * 竖拍不能套横置片门——那等于把一张竖照片硬压成横的 3:2，画面要裁掉一半高度。
     * 真机竖拍时画面在底片上本来就是竖着的，这里照做。
     */
    fun gateCrop(format: FrameFormat, srcW: Int, srcH: Int): GateCrop =
        gateCrop(srcW, srcH, format.gateAspectFor(srcW < srcH))

    /**
     * 按片门形状裁画面：从 [srcW]×[srcH] 里居中外切出宽高比正好等于 [gateAspect] 的一块。
     *
     * 引擎照着片门校验输入尺寸（差一点就拒绝出图），所以这里取整后**反推**另一边，
     * 保证出来的宽高比是精确的 3:2 / 4:3，而不是"四舍五入差不多"。
     * 比例已经对的（或尺寸非法）就原样不动。
     */
    fun gateCrop(srcW: Int, srcH: Int, gateAspect: Float): GateCrop {
        if (srcW <= 0 || srcH <= 0 || gateAspect <= 0f) return GateCrop(0, 0, srcW, srcH)
        val src = srcW.toFloat() / srcH.toFloat()
        // 差不到千分之五就算已经是片门形状（4:3 与 24/18 只在浮点末位有差别），别白裁一行
        if (kotlin.math.abs(src - gateAspect) < gateAspect * 0.005f) {
            return GateCrop(0, 0, srcW, srcH)
        }
        // 宽了裁宽、高了裁高；先按源边算，再取整反推另一边，比例才精确
        val w: Int
        val h: Int
        if (src >= gateAspect) {
            h = srcH
            w = (h * gateAspect).toInt().coerceAtMost(srcW)
        } else {
            w = srcW
            h = (w / gateAspect).toInt().coerceAtMost(srcH)
        }
        if (w <= 0 || h <= 0 || (w == srcW && h == srcH)) return GateCrop(0, 0, srcW, srcH)
        return GateCrop((srcW - w) / 2, (srcH - h) / 2, w, h)
    }

    /** 裁切矩形（像素，相对源图左上角）。 */
    data class GateCrop(val x: Int, val y: Int, val w: Int, val h: Int) {
        /** true = 要动刀（比例与片门不符）；false = 原图就是片门形状，一格不裁 */
        val cropped: Boolean get() = x != 0 || y != 0
    }

    /**
     * 片框参数（引擎 `film_render.overscan.*`）：真实扫描是把片基一起扫进来的，
     * 所以成片四周会有齿孔、暗边印刷字和帧号 —— 这也是「这是一卷胶卷」最直白的证据。
     *
     * 取值全部贴着引擎默认值（真机 spektra_params.json 读出来的），只把 active 打开、
     * 再填上暗边文字与帧号：
     *   `mode=strip` 整条片基、`gate=auto`、`holes=white`（齿孔透光）、
     *   `fog=1`、`leaks=0`（不漏光）、`pair=false`（不双联）。
     *
     * **format 决定片门（gate）的形状**，而引擎是拿输入尺寸对着片门校验的：
     * 拿 `135`（36×24，3:2）去配 4:3 的底片，参数会被收下、冲到渲染才炸 ——
     *   `overscan: a 1440 x 1080 frame is not the 135 gate's shape (36 x 24)`
     * 所以冲洗侧要先用 [gateCrop] 把画面裁成 3:2 再送进引擎
     * （真机也是这么回事：机身只取镜头成像圈中间那块 36×24）。
     *
     * 帧号**逐帧不同**，由冲洗侧用 [withFrameNumber] 覆盖，这里只占位。
     */
    fun frameBorderEntries(
        filmStockId: String,
        frameNumber: Int = 1,
        format: String = OVERSCAN_FORMAT_FULL,
    ): List<Pair<String, String>> =
        listOf(
            "overscan_active" to "true",
            "overscan_mode" to "\"strip\"",
            "overscan_format" to "\"" + esc(format) + "\"",
            "overscan_gate" to "\"auto\"",
            "overscan_holes" to "\"white\"",
            "overscan_fog" to "1",
            "overscan_leaks" to "0",
            "overscan_pair" to "false",
            // 暗边印刷字：真卷片基边缘都印着胶卷型号和帧号，留空就没有"底片感"
            "overscan_edge_text" to "\"" + esc(stockDisplayName(filmStockId).uppercase()) + "\"",
            "overscan_frame_number" to frameNumber.coerceAtLeast(0).toString(),
        )

    /** 逐帧把帧号填进参数（片框开启时用；解析失败就原样返回，不因它失败整卷）。 */
    fun withFrameNumber(paramsJson: String, frameNumber: Int): String = runCatching {
        org.json.JSONObject(paramsJson).apply { put("overscan_frame_number", frameNumber) }.toString()
    }.getOrDefault(paramsJson)

    /** 成片出口（整卷统一，不按胶卷分）。 */
    fun readOutputStyle(context: Context): OutputStyle =
        OutputStyle.fromPrefs(
            context.getSharedPreferences("filmcam", Context.MODE_PRIVATE)
                .getInt("filmcam_output_style", OutputStyle.PRINT.prefs)
        )

    fun saveOutputStyle(context: Context, style: OutputStyle) {
        context.getSharedPreferences("filmcam", Context.MODE_PRIVATE)
            .edit().putInt("filmcam_output_style", style.prefs).apply()
    }

    /** 出口档位循环：印相 → Frontier → Noritsu → 印相 */
    fun cycleOutputStyle(context: Context): OutputStyle {
        val next = when (readOutputStyle(context)) {
            OutputStyle.PRINT -> OutputStyle.FRONTIER
            OutputStyle.FRONTIER -> OutputStyle.NORITSU
            OutputStyle.NORITSU -> OutputStyle.PRINT
        }
        saveOutputStyle(context, next)
        return next
    }

    /**
     * 规格 §2 冻结模板（无 DevSettings 走默认值，等价于作者原版）。
     * 字段顺序、字段名逐字保持。
     */
    fun buildParamsJson(filmStockId: String, printStockId: String): String {
        return buildParamsJson(filmStockId, printStockId, DevSettings())
    }

    /**
     * 规格 §2 冻结模板，用户可调值替换 print_exposure / grain_amount / halation_amount /
     * glare_amount；print_stock 受 settings.paperOverride 覆盖；
     * settings.outputStyle 决定走印相通路还是扫描通路，并在扫描通路追加扫描仪/宽容度参数。
     *
     * 扫描档仍然下发 print_stock：引擎要求 print_stock 是能加载的合法 profile（反转片也是
     * 这么兜底的），真正的通路切换靠 scan_film —— 放大机整段会被跳过。
     */
    /**
     * 成片「输出风格」标记。
     *
     * 这是**我们自己**的元数据：只写进成片 EXIF（供「拍摄同款」还原），
     * 绝不能发给引擎——引擎没有这个参数，收到就是 `unknown parameter` 整卷失败。
     * 引擎侧的通路切换靠 scan_film 与扫描档附加参数，不看这个字段。
     */
    private const val MARK_OUTPUT_STYLE = "output_style"

    /** EXIF 快照里的输入层曝光补偿（EV）。与 [MARK_OUTPUT_STYLE] 一样只给我们自己读。 */
    const val MARK_EXPOSURE_EV = "exposure_ev"

    /**
     * 引擎参数（严格）。任何只给自己看的标记都不在这里出现；
     * 引擎对未知键会直接报 unknown parameter 并让整帧失败。
     */
    fun buildParamsJson(
        filmStockId: String,
        printStockId: String,
        settings: DevSettings,
    ): String = buildParamsJson(
        filmStockId, printStockId, settings,
        includeStyleMark = false, scanFinishLevel = SCAN_LEVEL_FULL
    )

    /**
     * 写进成片 EXIF 的参数快照 = 引擎参数 + [MARK_OUTPUT_STYLE]。
     * 「拍摄同款」靠它把风格也一起还原。
     */
    fun buildExifParamsJson(
        filmStockId: String,
        printStockId: String,
        settings: DevSettings,
    ): String = buildParamsJson(
        filmStockId, printStockId, settings,
        includeStyleMark = true, scanFinishLevel = SCAN_LEVEL_FULL
    )

    /**
     * @param scanFinishLevel 店扫档的味道强度：[SCAN_LEVEL_FULL] 全上，
     *   [SCAN_LEVEL_SAFE] 只保留真机已验证的那几个键（兜底用）。
     * @param scanExtraOverride 非空时**替换**店扫档的味道参数（键-值对，值已是 JSON 字面量）。
     *   冲洗前会拿它做一遍引擎预检，把引擎不接受的键剔掉再传进来，
     *   这样新参数被拒时只丢这一味，不会整档掉回印相。
     * @param extraEntries 追加的额外参数（片框那类与风格无关的键），同样是 JSON 字面量。
     * @param flashShot 这一帧是不是闪光拍摄（ShotMeta.flash != OFF）。
     *   直闪帧走单独的一套味道：辉光增强 + [flashFinishEntries]（预检过滤后的子集）。
     * @param flashEntries 直闪追加键（DevelopmentService 预检后传入；空表 = 全被拒，
     *   仍保留辉光增强）。null 与 false 等价、不进直闪分支。
     */
    fun buildParamsJson(
        filmStockId: String,
        printStockId: String,
        settings: DevSettings,
        includeStyleMark: Boolean,
        scanFinishLevel: Int = SCAN_LEVEL_FULL,
        scanExtraOverride: List<Pair<String, String>>? = null,
        extraEntries: List<Pair<String, String>>? = null,
        flashShot: Boolean = false,
        flashEntries: List<Pair<String, String>>? = null,
    ): String {
        val effectivePrintStock = settings.paperOverride?.takeIf { it.isNotBlank() } ?: printStockId
        // scan_film 只对反转片成立：正片本身就是可直接观看的正像。
        // 负片一旦走 scan_film，出来的是**未反相的底片**（负像、带橙罩、没有色彩）——
        // 负→正的反相是放大机把底片投到相纸上那一步物理完成的，跳过印相就没有正像。
        // 所以「店扫」档也必须走印相通路，再用扫描仪/宽容度参数塑造店扫味。
        val scan = isPositive(filmStockId)
        val style = settings.outputStyle
        // 直闪帧的辉光增强：高光反射点 + 溢出边缘红晕比自然光帧明显
        val halationAmount =
            if (flashShot) (settings.halationAmount * FLASH_HALATION_BOOST).coerceIn(0f, 2f)
            else settings.halationAmount
        // 放大机曝光**恒定**：片框（片基/齿孔/暗边印刷）是印相阶段一起叠上去的，
        // 这个值一变片框浓度就跟着变。曝光调整全部在输入层（inputEvFor）。
        val effectiveExposure = PRINT_EXPOSURE_LOCKED
        val sb = StringBuilder(256)
        sb.append('{')
          .append("\"film_stock\":\"").append(esc(filmStockId)).append("\",")
          .append("\"print_stock\":\"").append(esc(effectivePrintStock)).append("\",")
          .append("\"input_color_space\":\"sRGB\",")
          .append("\"input_cctf_decoding\":true,")
          .append("\"output_color_space\":\"sRGB\",")
          .append("\"print_exposure\":").append(jsonNum(effectiveExposure)).append(',')
          .append("\"preview_long_edge\":2048,")
          .append("\"grain_amount\":").append(jsonNum(settings.grainAmount)).append(',')
          .append("\"halation_amount\":").append(jsonNum(halationAmount)).append(',')
          .append("\"glare_amount\":").append(jsonNum(settings.glareAmount)).append(',')
          // 镜头层：Sonnar T* 35/2.8 打在底片上的柔度（胶片尺度 μm，36mm 宽）。
          // 手机 HAL 出的数据偏"数码级干净"，一点镜头柔度是胶片味的重要来源。
          // 真机标定（S25U / GOLD 200 / 2048px 对照，1px≈17.6μm）：
          //   4μm 无感（次像素）、8μm=0.93、12μm=0.86（细节在、锋利感钝掉，f/5.6-8 的观感）、
          //   20μm=0.73（明显柔）、35μm=0.56（奶油）。全分辨率 3939px 下 12μm ≈ 1.3px。
          // 引擎区间实测 0~35 都收，50000 被拒（越界由引擎硬校验兜底）。
          // 默认值在工程师「冲洗参数」面板可调（全局键 filmcam_dev_lens_blur），
          // 最终定值以用户在真机上的对比结论为准。
          .append("\"lens_blur_um\":").append(jsonNum(settings.lensBlurUm)).append(',')
          .append("\"film_format_mm\":36.0")
        // 直闪味（预检后的子集；全部被拒时只剩上面的辉光增强）
        if (flashShot) {
            flashEntries?.forEach { (k, v) ->
                sb.append(",\"").append(k).append("\":").append(v)
            }
        }
        if (scan) sb.append(",\"scan_film\":true")
        // 风格标记只在 EXIF 快照里出现；发给引擎会触发 unknown parameter，整卷出不了图
        if (includeStyleMark) {
            sb.append(",\"").append(MARK_OUTPUT_STYLE).append("\":\"").append(settings.outputStyle.wire).append('"')
            // 输入层曝光同样只进 EXIF：引擎侧没有这个键（曝光在 App 里施加），
            // 但「拍摄同款」要能把这张的曝光补偿一起还原。
            sb.append(",\"").append(MARK_EXPOSURE_EV).append("\":").append(jsonNum(settings.exposureEv))
        }
        // 店扫味（走印相通路，靠末端参数拉开与光学印相的差别）。
        // **不叠色彩微调**：两家店的滤色片签名是独立标定过的（OutputStyleTest 守护契约），
        // 全局 +1.0 会把 Noritsu 的 -1.0 暖调正好抵消归零；而且滤色强度增益
        // 会把 ±1.0 的签名放大到失控。色彩微调是「家里暗房」的校色，只属于印相档。
        if (style.isScan) {
            val entries = scanExtraOverride ?: scanFinishEntries(style, scanFinishLevel)
            entries.forEach { (k, v) -> sb.append(",\"").append(k).append("\":").append(v) }
        } else if (settings.ccY != 0f || settings.ccM != 0f) {
            // 印相档的滤色片只由色彩微调产生（默认 = 冻结定值，见 CC_*_DEFAULT）；
            // 用户显式全零 = 主动关掉，一个键都不发，回到不滤色的老成色。
            // 肤色「偏黄不够白」主要就从这里修：ccY 往正（冷/白）拖，必要时配一点负 ccM（洋红）。
            sb.append(",\"y_filter_shift\":").append(jsonNum(settings.ccY.coerceIn(-CC_SHIFT_MAX, CC_SHIFT_MAX)))
            sb.append(",\"m_filter_shift\":").append(jsonNum(settings.ccM.coerceIn(-CC_SHIFT_MAX, CC_SHIFT_MAX)))
            // 滤色强度增益跟着印相档的滤色片走（店扫签名用引擎默认强度）
            settings.ccScale?.let {
                sb.append(",\"filter_shift_scale\":").append(jsonNum(it.coerceIn(0.01f, CC_SCALE_MAX)))
            }
        }
        // 片框（整卷统一档位；帧号由冲洗侧逐帧覆盖）
        // extraEntries 优先：冲洗前的小图自检会挑出真正能出图的版式传进来
        val frameEntries = extraEntries
            ?: settings.frameFormat.overscanFormat?.let { frameBorderEntries(filmStockId, 1, it) }
        frameEntries?.forEach { (k, v) ->
            sb.append(",\"").append(k).append("\":").append(v)
        }
        sb.append('}')
        return sb.toString()
    }

    /**
     * 店扫档的"味道强度"分级。引擎对不认识或越界的键是硬失败，
     * 所以新加的味道参数一律放在 [SCAN_LEVEL_FULL]，一旦被拒就退到
     * [SCAN_LEVEL_SAFE]（真机已验证能出图的那一套），保住店扫的基本差别。
     */
    const val SCAN_LEVEL_FULL = 0
    const val SCAN_LEVEL_SAFE = 1

    /**
     * 镜头柔度默认值：`lens_blur_um`，Sonnar T* 35/2.8 在 36mm 底片上的模糊斑尺度。
     * 这是「镜头层」唯一的引擎参数（暗角在冲洗侧 [DevelopmentService.applySonnarVignette]）。
     * 标定数据见 [buildParamsJson] 里 `lens_blur_um` 那行的注释。
     * 工程师「冲洗参数」面板可全局覆盖（键 `filmcam_dev_lens_blur`）——
     * 用户觉得画质受损时可以自己扫值找最合适的点，定值以真机对比结论为准。
     */
    const val LENS_BLUR_UM = 12.0f

    /**
     * **T3 35mm 视角**：动态裁切比 = 35 / 本机主摄等效焦距。
     *
     * 采集端 JPEG/预览经 `CONTROL_ZOOM_RATIO` 由 HAL 裁；RAW_SENSOR 是全幅 Bayer
     * 不被 HAL 裁（真机实锤 RAW 比 JPEG 广）——RAW 冲洗端
     * `SpektraProcessor.zoomCrop` 用同一值补裁，两条通路视角才能一致。
     *
     * **2026-11-05 起动态化**：不同手机主摄等效焦距不同（23mm/24mm/26mm/28mm…），
     * 硬编码 1.52 只对 23mm 机型正确。现在由 [Camera2Controller] 在 `start()` 时
     * 读 `SENSOR_INFO_PHYSICAL_SIZE` + `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` 算出
     * 本机等效焦距，调 [setT3ZoomRatio] 更新此值。读取不到时保持默认 1.52（23mm），
     * 不会比之前差。
     */
    @Volatile
    var T3_ZOOM_RATIO: Float = 1.52f
        private set

    /** 目标等效焦距（T3 定焦 35mm）。 */
    private const val TARGET_EQUIV_FOCAL_MM = 35f

    /**
     * 由 Camera2Controller 在初始化时调用：根据本机传感器物理尺寸 + 镜头实际焦距
     * 算出等效 35mm 焦距，再算裁切比。
     *
     * 等效焦距 = 实际焦距 × (43.27 / 传感器对角线)，其中 43.27 是全画幅对角线。
     * 裁切比 = 目标等效 / 本机等效。
     *
     * - 本机等效 23mm → 35/23 ≈ 1.52（与旧硬编码一致）
     * - 本机等效 26mm → 35/26 ≈ 1.35
     * - 本机等效 28mm → 35/28 ≈ 1.25
     */
    fun setT3ZoomRatio(sensorPhysicalSizeMm: Float, actualFocalLengthMm: Float) {
        if (sensorPhysicalSizeMm <= 0f || actualFocalLengthMm <= 0f) {
            android.util.Log.w("FilmStockMapper", "setT3ZoomRatio 参数无效: sensor=$sensorPhysicalSizeMm focal=$actualFocalLengthMm，保持默认 $T3_ZOOM_RATIO")
            return
        }
        // 传感器对角线
        // sensorPhysicalSize 是 SizeF（宽×高），传入的是对角线长度
        val diag = sensorPhysicalSizeMm
        val equivFocal = actualFocalLengthMm * (43.27f / diag)
        val ratio = TARGET_EQUIV_FOCAL_MM / equivFocal
        // 合理范围：1.0~2.0（35mm / 主摄等效 18~28mm = 1.25~1.94）。超过 2.0
        // 多半是读到了副摄焦距或异常物理尺寸，会让 RAW 冲洗把 ActiveArea 裁到极小
        // （成片分辨率塌成几百 KB），宁可保持默认值也不冒险。
        if (ratio in 1.0f..2.0f) {
            T3_ZOOM_RATIO = ratio
            android.util.Log.i("FilmStockMapper", "T3 裁切比动态化: sensor=${diag}mm focal=${actualFocalLengthMm}mm equiv=${equivFocal}mm ratio=$ratio")
        } else {
            android.util.Log.w("FilmStockMapper", "T3 裁切比超出合理范围: ratio=$ratio（equiv=${equivFocal}mm），保持 $T3_ZOOM_RATIO")
        }
    }

    /** 镜头柔度上限：引擎真机实测 0~35 都收、50000 被拒，超出区间由引擎硬校验兜底 */
    const val LENS_BLUR_MAX_UM = 35.0f

    /**
     * 滤色片偏移上限：引擎硬区间 [-1,1]（真机报错原文
     * `'y_filter_shift'=12.000000 is outside [-1.000000, 1.000000]` 确认）。
     */
    const val CC_SHIFT_MAX = 1.0f

    /**
     * 滤色强度增益（`filter_shift_scale`）的 UI 上限。**引擎侧值域未标定**——
     * 键本身在引擎里（so 里查得到、白名单已收录），越界的值会在冲洗前预检被剔，
     * 这里只限制面板滑杆能给到多大。
     */
    const val CC_SCALE_MAX = 15.0f

    /**
     * 色彩微调默认值（**2026-11-05 起取消冻结定值**）。
     *
     * 原冻结定值 +1.0/+0.10/15 是 11-04 按「JPEG 通路 + 采集端全关降噪」基线对照
     * T3 实拍选的；该基线已漂移两次（v19 恢复 HAL 默认加工、RAW 通路上线），
     * 继续默认下发等于拿旧基线的补偿叠加新基线双重校色。默认 0/0 = 纯净基线，
     * 一个滤色键都不发；需要校色在面板手动调（调了滤色强度增益才有意义）。
     * 旧的 +1.0/+0.10 历史值留在 git（34978dd / ea883e6）供日后重标参考。
     */
    const val CC_Y_DEFAULT = 0f
    const val CC_M_DEFAULT = 0f
    const val CC_SCALE_DEFAULT = 15.0f

    /** [DevSettings.ccScale] 的 JSON 数值字面量（预检与下发共用，保证字面一致） */
    fun ccScaleJsonLiteral(scale: Float): String = jsonNum(scale.coerceIn(0.01f, CC_SCALE_MAX))

    /**
     * 直闪帧的辉光增强系数。直闪的小灯管在皮肤/镜面上打出集中的高光反射点，
     * 高光溢出边缘的红晕（halation）比自然光帧明显得多。
     * 只对 `halation_amount` 乘系数，仍钳在 0–2 的已验证区间内。
     */
    const val FLASH_HALATION_BOOST = 1.5f

    /**
     * 直闪帧追加的引擎参数（键-值对，值是 JSON 字面量）。
     *
     * 直闪底片和自然光底片在暗房里就是两种东西：反差大、主体密、背景薄，
     * 印相时店员有一套针对性的处理。这里把其中可参数化的部分搬过来：
     *
     *  - `auto_exposure=false`：直闪帧的曝光量在拍摄时就由 EI + 闪光指数定死了
     *    （EXTERNAL 档 HAL 已 `AE_OFF` + 固定 ISO/快门），引擎不该再按画面亮度
     *    评估一次。11-07 真机定量（同参数链只关 AE）：meanL 61.8→92.1、
     *    暗部占比 49.4%→40.4%、高频 0.86→0.94、高光无新增溢出。
     *    之前 center_weighted 把被闪光照亮的底片判成「太亮」压暗，正是
     *    「闪光灯拍了却没有闪光味」的根因。**只作用于直闪帧**，不违反
     *    「别把 auto_exposure=false 全局落默认（白天大光比会爆高光）」的约束。
     *  - `preflash_exposure`：放大机预闪 —— 印正像前给相纸一次均匀的弱曝光，
     *    把黑位抬起来、反差压一点。直闪帧的背景被闪光甩进黑暗，预一点闪
     *    避免印出来背景死黑一片（店扫处理直闪卷的常规操作）。
     *    值域未标定：冲洗前的 [SpektraProcessor.probeRejectedKeys] 预检会真渲一张
     *    小图试这个值，引擎不收就整键剔除，只会丢这一味、不炸整帧。
     */
    fun flashFinishEntries(): List<Pair<String, String>> = listOf(
        "auto_exposure" to "false",
        "preflash_exposure" to "0.08",
    )

    /** 片框版式：135 全画幅（36×24mm，3:2，T3 这一格）—— 画面要先按片门裁成 3:2 */
    const val OVERSCAN_FORMAT_FULL = "135"
    /**
     * 片框版式自检候选：只有 135 一格，所以是固定的一项。
     * 保留这个函数是因为冲洗前要用小图**真跑一遍**确认这机器认不认这一格
     * （引擎的片门校验发生在渲染期，问参数是问不出来的）。
     */
    fun overscanCandidates(preferred: FrameFormat): List<String> =
        listOfNotNull(preferred.overscanFormat ?: OVERSCAN_FORMAT_FULL)

    /**
     * 店扫档追加的参数。都作用在成片链末端，不碰印相反相本身：
     *
     *  - `scanner_lens_blur`：扫描仪自身光学带来的柔化，**单位是像素**（0 = 关）。
     *    Frontier 镜头偏柔，颗粒被抹平；Noritsu 更锐，颗粒粒粒可数。
     *  - `glare_active:false`：glare 是「纸面反射的室内光」，店扫拿到的是屏幕上的数字图，
     *    没有印相表面可反光 —— 关掉它，扫描档的黑位更实。
     *  - `scene_latitude_*`：宽容度模型，高光走肩部压过去而不是硬切。rolloff 越大肩越长
     *    （Frontier 的 Highlight Soft）；max_lift 控制暗部能被抬多少。
     *
     * [SCAN_LEVEL_FULL] 再加三味（用来把两档真正拉开）：
     *  - `y_filter_shift`：放大机黄滤片，决定**冷暖**，但效果很弱。
     *    darktable 文档写的是 Kodak CC 单位，可**引擎实际收的是归一化值，硬区间 [-1,1]**
     *    （真机报错原文：`'y_filter_shift'=12.000000 is outside [-1.000000, 1.000000]`）。
     *    像素统计标定：正 = 冷、负 = 暖，且**拉到边界 ±1.0 也只挪 ~1.6 个 R−B 色阶**
     *    （±0.35 那档实测看不出来）。所以要做冷暖差就得给满，别舍不得。
     *    注意：曾在真机上把「偏暖」误判成这个键造成的，其实是 dir_couplers 掉饱和。
     *  - `m_filter_shift`：放大机品红滤片，决定**洋红 / 青绿**的偏向，和黄滤片一起才像
     *    两家店的颜色签名（Noritsu 偏洋红、Frontier 偏青绿）。同样归一化、同样待标定方向。
     *  - `density_curve_gamma`：密度曲线反差，>1 收紧（Frontier 的"对比强"），<1 放宽（Noritsu 的平）。
     *  - `dir_couplers_amount`：层间效应强度，直接关系负片的色彩浓度。
     *    **上限就是 1.0**（文档：滑杆止于 1.0，因为超过胶片本身的强度后反解曲线就无解了）。
     *
     * 注意：这里**没有** contrast_mask —— 引擎 capabilities 明确列了
     * `unsupported_features:["contrast_mask"]`，且 scale 合法区间只有 [0.002,0.120]。
     */
    fun scanFinishEntries(style: OutputStyle, level: Int): List<Pair<String, String>> {
        val safe = listOf(
            "scanner_lens_blur" to (if (style == OutputStyle.FRONTIER) "2.2" else "0.3"),
            "glare_active" to "false",
            "scene_latitude_active" to "true",
            "scene_latitude_rolloff" to (if (style == OutputStyle.FRONTIER) "2.6" else "1.8"),
            "scene_latitude_max_lift" to (if (style == OutputStyle.FRONTIER) "3.0" else "1.5"),
            "scene_latitude_norm" to "\"power\"",
        )
        if (level != SCAN_LEVEL_FULL || !style.isScan) return safe
        return safe + fullExtraEntries(style)
    }

    /**
     * 完整档比保守档**多出来**的那几味 —— 冲洗前会对它们逐个做引擎预检
     * （见 [SpektraProcessor.probeRejectedKeys]），被拒的剔掉，剩下的才下发。
     */
    fun fullExtraEntries(style: OutputStyle): List<Pair<String, String>> = when (style) {
        // 三个方向都是**真机对照实验**（matrix_out 像素统计）标定的，别按暗房直觉改：
        //   y_filter_shift：正 = 冷、负 = 暖（±1.0 才挪得动 ~1.6 个 R−B 色阶，量级很小，
        //     所以直接给到区间边界；±0.35 那档实测等于没给）
        //   m_filter_shift：正 = 偏青绿、负 = 偏洋红
        //   dir_couplers_amount：**两档都必须保持 1.0**。它是色彩浓度，1.0 = 胶片自身强度
        //     （引擎默认值），往下调会让画面褪色发冷 —— 实测 0.2 时 R−B 掉 8 个色阶，
        //     之前 Noritsu 给 0.6 就是这个把画面褪冷的，看起来像"色温不对"，其实是掉饱和。
        //     浓度属于胶片特性，不该拿来区分两家扫描仪。
        OutputStyle.FRONTIER -> listOf(
            "y_filter_shift" to "1.0",
            "m_filter_shift" to "0.8",
            "density_curve_gamma" to "1.06",
            "dir_couplers_amount" to "1.0",
        )
        OutputStyle.NORITSU -> listOf(
            "y_filter_shift" to "-1.0",
            "m_filter_shift" to "-0.8",
            "density_curve_gamma" to "0.94",
            "dir_couplers_amount" to "1.0",
        )
        else -> emptyList()
    }

    /**
     * 店扫档的曝光倾向，**单位是 EV 偏移**（不是倍率）。
     * 文档：正 EV = 放大机照得更久 = 成片更暗。店员判断不同：
     * Frontier 偏亮（-EV）、Noritsu 略收（+EV）。
     */
    private fun styleExposureOffsetEv(style: OutputStyle): Float = when (style) {
        OutputStyle.FRONTIER -> -0.18f
        OutputStyle.NORITSU -> 0.15f
        else -> 0f
    }

    // ---------- 内部 ----------

    private fun lookup(name: String): Pair<String, String> {
        val key = name.trim().uppercase()
        ROLL_TABLE[key]?.let { return it }
        throw IllegalArgumentException(
            "未知胶卷名: \"$name\"。支持的胶卷: " +
                ROLL_TABLE.keys.joinToString(", ")
        )
    }

    private fun esc(s: String): String {
        // stock id 都是 [a-z0-9_]，不会有需要转义的字符；做一次保守转义即可。
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * JSON 数字字面量：固定两位小数后去尾零，至少保留一位小数。
     * 1.0f → "1.0"；0.75f → "0.75"；3.0f → "3.0"；1.2f → "1.2"。
     * 避免 "1.0f" / "1" 这类非法或与冻结模板风格不一致的输出。
     */
    private fun jsonNum(v: Float): String {
        val raw = String.format(Locale.US, "%.2f", v)
        // raw 形如 "1.00" / "0.75" / "3.00"。
        val dot = raw.indexOf('.')
        if (dot < 0) return "$raw.0"
        var end = raw.length
        while (end > dot + 2 && raw[end - 1] == '0') end--
        // 至少保留一位小数位。
        if (end == dot + 1) end++
        return raw.substring(0, end)
    }
}
