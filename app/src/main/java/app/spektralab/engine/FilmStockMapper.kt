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
        "grain_amount", "halation_amount", "glare_amount",
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
    ): String = buildParamsJson(filmStockId, printStockId, settings, includeStyleMark = false)

    /**
     * 写进成片 EXIF 的参数快照 = 引擎参数 + [MARK_OUTPUT_STYLE]。
     * 「拍摄同款」靠它把风格也一起还原。
     */
    fun buildExifParamsJson(
        filmStockId: String,
        printStockId: String,
        settings: DevSettings,
    ): String = buildParamsJson(filmStockId, printStockId, settings, includeStyleMark = true)

    fun buildParamsJson(
        filmStockId: String,
        printStockId: String,
        settings: DevSettings,
        includeStyleMark: Boolean,
    ): String {
        val effectivePrintStock = settings.paperOverride?.takeIf { it.isNotBlank() } ?: printStockId
        // 反转片不走放大机/相纸：加 scan_film=true，引擎按「直接扫描正片」出图
        val scan = isPositive(filmStockId) || settings.outputStyle.isScan
        val sb = StringBuilder(256)
        sb.append('{')
          .append("\"film_stock\":\"").append(esc(filmStockId)).append("\",")
          .append("\"print_stock\":\"").append(esc(effectivePrintStock)).append("\",")
          .append("\"input_color_space\":\"sRGB\",")
          .append("\"input_cctf_decoding\":true,")
          .append("\"output_color_space\":\"sRGB\",")
          .append("\"print_exposure\":").append(jsonNum(settings.printExposure)).append(',')
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
        // 扫描通路专属：扫描仪镜头、局部反差、场景宽容度
        if (settings.outputStyle.isScan) sb.append(scanFinishParams(settings.outputStyle))
        sb.append('}')
        return sb.toString()
    }

    /**
     * 扫描档追加的参数。三组分别对应：
     *  - scanner_lens_blur：扫描仪自身镜头的柔化（像素）。Frontier 的镜头偏柔，
     *    颗粒会被轻微抹平；Noritsu 更锐，颗粒粒粒可数。
     *  - contrast_mask_*：成片阶段的局部反差，也就是"蔡司立体感"在数码端最接近的对应物。
     *    核心/暗部/高光分开给，暗部给得比高光少，才不会把暗部噪声一起放大。
     *  - scene_latitude_*：负片的宽容度模型 —— 高光走肩部压过去而不是硬切，
     *    暗部有趾部兜底。rolloff 越大肩部越长（Frontier 的 Highlight Soft）。
     *    rolloff ∈ [1,4]、max_lift ∈ [0.25,12]、norm ∈ {power,y,max} 是引擎的硬校验区间，
     *    膝点一律交给引擎自己解（我们只给形状，不给位置）。
     */
    private fun scanFinishParams(style: OutputStyle): String = when (style) {
        OutputStyle.FRONTIER ->
            ",\"scanner_lens_blur\":0.9" +
            ",\"contrast_mask_active\":true" +
            ",\"contrast_mask_scheme\":\"gaussian\"" +
            ",\"contrast_mask_scale\":0.35" +
            ",\"contrast_mask_core\":0.55" +
            ",\"contrast_mask_shadows\":0.30" +
            ",\"contrast_mask_highlights\":0.45" +
            ",\"scene_latitude_active\":true" +
            ",\"scene_latitude_rolloff\":2.6" +
            ",\"scene_latitude_max_lift\":1.6" +
            ",\"scene_latitude_norm\":\"power\""
        OutputStyle.NORITSU ->
            ",\"scanner_lens_blur\":0.35" +
            ",\"contrast_mask_active\":false" +
            ",\"scene_latitude_active\":true" +
            ",\"scene_latitude_rolloff\":1.8" +
            ",\"scene_latitude_max_lift\":1.0" +
            ",\"scene_latitude_norm\":\"power\""
        else -> ""
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
