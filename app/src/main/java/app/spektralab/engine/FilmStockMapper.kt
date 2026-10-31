package app.spektralab.engine

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * FilmRoll.name → 引擎 film_stock / print_stock id 映射（规格 §1 冻结表）。
 * 并负责组装引擎 setParams 的冻结 JSON 模板（规格 §2）。
 */
object FilmStockMapper {

    /** 冲洗（暗房）用户可调参数；未改设置时全部取冻结模板默认值。 */
    data class DevSettings(
        val grainAmount: Float = 1.0f,
        val halationAmount: Float = 1.0f,
        val glareAmount: Float = 3.0f,
        val printExposure: Float = 1.0f,   // 预留：放大机曝光（本轮不暴露 UI，只留读取位）
        val paperOverride: String? = null, // 预留：相纸 id 覆盖（null/空白 = 用胶卷映射的 print_stock）
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
        "CLASSIC CHROME"   to ("kodak_kodachrome_64"         to "kodak_endura_premier"),
        "ETERNA"           to ("kodak_vision3_250d"          to "kodak_2383"),
        "TRI-X 400"        to ("kodak_tri_x_400"             to "ilford_multigrade_iv_rc"),
        "HP5 PLUS"         to ("ilford_hp5_plus_400"         to "ilford_multigrade_iv_rc"),
        "CN800"            to ("cinestill_800t"               to "kodak_endura_premier"),
        // ---- 2025-11 扩充：引擎 catalog 全量 25 支胶卷 ----
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
     * 读取 assets/resources/stock_catalog.json 的 films[]/prints[]，建立 id→name、
     * id→type 缓存，以及保序的 prints[] 列表。
     * 由 SpektraProcessor.ensureEngine 在拿到 Context 后调用一次。
     */
    fun initialize(context: Context) {
        if (displayNames != null) return
        synchronized(this) {
            if (displayNames != null) return
            val names = HashMap<String, String>(64)
            val types = HashMap<String, String>(32)
            val prints = ArrayList<PrintOption>(16)
            try {
                context.assets.open("resources/stock_catalog.json").use { input ->
                    val text = input.bufferedReader(Charsets.UTF_8).readText()
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
                }
            } catch (_: Throwable) {
                // 读不到 catalog 就退化为返回 id 本身 / 空相纸表。
            }
            displayNames = names
            filmTypes = types
            printOptionsList = prints
        }
    }

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
     * 相纸解析：反转片（type=="positive"）忽略用户选择、强制回退默认映射；
     * 否则选中即用、未选回落默认。
     */
    fun resolvePrintStock(
        filmStockId: String,
        mappedDefault: String,
        selectedPaper: String?,
    ): ResolvedPrint {
        val sel = selectedPaper?.takeIf { it.isNotBlank() }
        return if (filmTypeOf(filmStockId) == "positive" && sel != null) {
            ResolvedPrint(mappedDefault, true)
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
        )
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
     * glare_amount；print_stock 受 settings.paperOverride 覆盖。
     * 字段顺序仍为：film_stock, print_stock, input_color_space, input_cctf_decoding,
     * output_color_space, print_exposure, preview_long_edge, grain_amount,
     * halation_amount, glare_amount, film_format_mm。
     */
    fun buildParamsJson(
        filmStockId: String,
        printStockId: String,
        settings: DevSettings,
    ): String {
        val effectivePrintStock = settings.paperOverride?.takeIf { it.isNotBlank() } ?: printStockId
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
          .append('}')
        return sb.toString()
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
