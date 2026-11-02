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
        "auto_exposure_method", "lens_blur_um",
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
    )

    /** 冲洗（暗房）用户可调参数；未改设置时全部取冻结模板默认值。 */
    data class DevSettings(
        val grainAmount: Float = 1.0f,
        val halationAmount: Float = 1.0f,
        val glareAmount: Float = 3.0f,
        val printExposure: Float = 1.0f,   // 预留：放大机曝光（本轮不暴露 UI，只留读取位）
        val paperOverride: String? = null, // 预留：相纸 id 覆盖（null/空白 = 用胶卷映射的 print_stock）
        val outputStyle: OutputStyle = OutputStyle.PRINT, // 成片出口：印相 / Frontier / Noritsu
    )

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
     *           filmcam_dev_glare_<stockId> / filmcam_dev_print_exposure_<stockId> /
     *           filmcam_dev_paper_<stockId>（stockId 即引擎 film_stock id，[a-z0-9_]）。
     * 范围钳制：grain/halation 0–2，glare 0–6，printExposure 0.25–4。
     * 未写过的卷全部回退到 DevSettings 默认值（1.0/1.0/3.0/1.0/null）。
     */
    fun readDevSettings(context: Context, filmStockId: String): DevSettings {
        val sp = context.getSharedPreferences("filmcam", Context.MODE_PRIVATE)
        val grain = sp.getFloat("filmcam_dev_grain_$filmStockId", 1.0f).coerceIn(0f, 2f)
        val halation = sp.getFloat("filmcam_dev_halation_$filmStockId", 1.0f).coerceIn(0f, 2f)
        val glare = sp.getFloat("filmcam_dev_glare_$filmStockId", 3.0f).coerceIn(0f, 6f)
        val exposure = sp.getFloat("filmcam_dev_print_exposure_$filmStockId", 1.0f).coerceIn(0.25f, 4f)
        val paper = sp.getString("filmcam_dev_paper_$filmStockId", null)?.takeIf { it.isNotBlank() }
        return DevSettings(
            grainAmount = grain,
            halationAmount = halation,
            glareAmount = glare,
            printExposure = exposure,
            paperOverride = paper,
            // 出口是整卷统一的口味，不按胶卷存；脏值回退印相
            outputStyle = OutputStyle.fromPrefs(sp.getInt("filmcam_output_style", OutputStyle.PRINT.prefs)),
        )
    }

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
     */
    fun buildParamsJson(
        filmStockId: String,
        printStockId: String,
        settings: DevSettings,
        includeStyleMark: Boolean,
        scanFinishLevel: Int = SCAN_LEVEL_FULL,
        scanExtraOverride: List<Pair<String, String>>? = null,
    ): String {
        val effectivePrintStock = settings.paperOverride?.takeIf { it.isNotBlank() } ?: printStockId
        // scan_film 只对反转片成立：正片本身就是可直接观看的正像。
        // 负片一旦走 scan_film，出来的是**未反相的底片**（负像、带橙罩、没有色彩）——
        // 负→正的反相是放大机把底片投到相纸上那一步物理完成的，跳过印相就没有正像。
        // 所以「店扫」档也必须走印相通路，再用扫描仪/宽容度参数塑造店扫味。
        val scan = isPositive(filmStockId)
        val style = settings.outputStyle
        // 店扫档的曝光倾向只在完整档生效：保守档要和可出图的基线完全一致
        val effectiveExposure =
            if (style.isScan && scanFinishLevel == SCAN_LEVEL_FULL)
                settings.printExposure + styleExposureOffsetEv(style)
            else settings.printExposure
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
          .append("\"halation_amount\":").append(jsonNum(settings.halationAmount)).append(',')
          .append("\"glare_amount\":").append(jsonNum(settings.glareAmount)).append(',')
          .append("\"film_format_mm\":36.0")
        if (scan) sb.append(",\"scan_film\":true")
        // 风格标记只在 EXIF 快照里出现；发给引擎会触发 unknown parameter，整卷出不了图
        if (includeStyleMark) {
            sb.append(",\"").append(MARK_OUTPUT_STYLE).append("\":\"").append(settings.outputStyle.wire).append('"')
        }
        // 店扫味（走印相通路，靠末端参数拉开与光学印相的差别）
        if (style.isScan) {
            val entries = scanExtraOverride ?: scanFinishEntries(style, scanFinishLevel)
            entries.forEach { (k, v) -> sb.append(",\"").append(k).append("\":").append(v) }
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
