package app.spektralab.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * FilmStockMapper 契约测试。
 *
 * 重点防的是刚踩过的坑：UI 胶卷名与引擎 profile 脱节（曾出现
 * "CLASSIC CHROME" 实际印 kodak_kodachrome_64、"CN800" 实际是 cinestill_800t）。
 * 因此这里用真实的 assets/resources/stock_catalog.json 做三方交叉校验：
 *   ROLL_TABLE（映射） × FilmRollRepository（UI 列表） × catalog（引擎真值）
 */
class FilmStockMapperTest {

    private lateinit var catalog: FilmStockMapper.CatalogSnapshot
    private lateinit var catalogJson: JSONObject

    private fun assetsFile(rel: String): File {
        // 单元测试工作目录 = app/（Gradle 默认）
        val f = File("src/main/assets/$rel")
        assertTrue("缺少 assets 文件: ${f.absolutePath}", f.exists())
        return f
    }

    @Before
    fun setUp() {
        val text = assetsFile("resources/stock_catalog.json").readText(Charsets.UTF_8)
        catalogJson = JSONObject(text)
        catalog = FilmStockMapper.parseCatalog(text)
        FilmStockMapper.installCatalog(catalog)
    }

    // ---------- catalog 解析 ----------

    @Test
    fun `catalog 解析出 25 支胶卷与 9 款相纸`() {
        assertEquals(25, catalogJson.getJSONArray("films").length())
        assertEquals(9, catalogJson.getJSONArray("prints").length())
        assertEquals(25, catalog.types.size)
        assertEquals(9, catalog.prints.size)
        // 相纸顺序必须与 JSON 一致（UI 下拉依赖保序）
        val expected = catalogJson.getJSONArray("prints").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
        }
        assertEquals(expected, catalog.prints.map { it.id })
    }

    @Test
    fun `非法 JSON 解析为空快照而不崩溃`() {
        val snap = FilmStockMapper.parseCatalog("{ not json")
        assertTrue(snap.types.isEmpty())
        assertTrue(snap.prints.isEmpty())
    }

    // ---------- 映射表 × UI 列表 × catalog 三方一致性 ----------

    @Test
    fun `ROLL_TABLE 的 film 与 print id 全部存在于 catalog`() {
        FilmStockMapper.rollTable().forEach { (rollName, pair) ->
            val (filmId, printId) = pair
            assertNotNull("$rollName 的 film_stock 不在 catalog: $filmId", catalog.types[filmId])
            assertNotNull("$rollName 的 print_stock 不在 catalog: $printId", catalog.names[printId])
        }
    }

    @Test
    fun `UI 胶卷列表每一项都能解析为引擎 stock`() {
        com.filmcam.app.filter.FilmRollRepository.rolls.forEach { roll ->
            val filmId = FilmStockMapper.filmStockIdFor(roll.name)
            assertTrue("${roll.name} -> 空 film id", filmId.isNotBlank())
            assertNotNull("${roll.name} -> $filmId 不在 catalog", catalog.types[filmId])
        }
    }

    @Test
    fun `UI 胶卷数量与 catalog 一致且 film id 一一对应`() {
        val rolls = com.filmcam.app.filter.FilmRollRepository.rolls
        assertEquals("UI 胶卷数应等于 catalog films 数", catalog.types.size, rolls.size)

        val mapped = rolls.map { FilmStockMapper.filmStockIdFor(it.name) }
        assertEquals("film id 有重复：$mapped", mapped.size, mapped.toSet().size)
        assertEquals("未覆盖 catalog 全部胶卷", catalog.types.keys, mapped.toSet())
    }

    @Test
    fun `UI 胶卷名唯一且描述与标签非空`() {
        val rolls = com.filmcam.app.filter.FilmRollRepository.rolls
        val names = rolls.map { it.name }
        assertEquals("胶卷名重复：$names", names.size, names.toSet().size)
        val validTags = setOf("彩色", "黑白", "电影", "反转")
        rolls.forEach {
            assertTrue("${it.name} 描述为空", it.description.isNotBlank())
            assertTrue("${it.name} 标签非法: ${it.tag}", it.tag in validTags)
        }
    }

    @Test
    fun `每支反转片都标记为 positive`() {
        val positives = catalog.types.filterValues { it == "positive" }.keys
        assertEquals("catalog 里应有 4 支反转片", 4, positives.size)
        positives.forEach {
            assertTrue("$it 应判定为反转片", FilmStockMapper.isPositive(it))
        }
    }

    @Test
    fun `负片不会误判为反转片`() {
        catalog.types.filterValues { it == "negative" }.keys.forEach {
            assertFalse("$it 被误判为反转片", FilmStockMapper.isPositive(it))
        }
    }

    // ---------- buildParamsJson 冻结模板 ----------

    @Test
    fun `负片参数不含 scan_film 且字段顺序固定`() {
        val json = FilmStockMapper.buildParamsJson("kodak_portra_400", "kodak_portra_endura")
        assertFalse("负片不应带 scan_film", json.contains("scan_film"))

        val order = listOf(
            "\"film_stock\"", "\"print_stock\"", "\"input_color_space\"",
            "\"input_cctf_decoding\"", "\"output_color_space\"", "\"print_exposure\"",
            "\"preview_long_edge\"", "\"grain_amount\"", "\"halation_amount\"",
            "\"glare_amount\"", "\"lens_blur_um\"", "\"film_format_mm\""
        )
        var cursor = -1
        order.forEach { key ->
            val at = json.indexOf(key)
            assertTrue("缺少字段 $key: $json", at >= 0)
            assertTrue("字段顺序被打乱（$key）: $json", at > cursor)
            cursor = at
        }
    }

    @Test
    fun `镜头层带标定过的 lens_blur_um`() {
        // Sonnar 在底片上的柔度，真机 matrix 扫描标定（0~35μm 全收，12μm=f/5.6-8 观感）。
        // 默认 12，工程师「冲洗参数」面板可全局覆盖（键 filmcam_dev_lens_blur）。
        val obj = JSONObject(FilmStockMapper.buildParamsJson("kodak_portra_400", "kodak_portra_endura"))
        assertEquals(12.0, obj.optDouble("lens_blur_um"), 1e-6)
        // 常量与白名单自洽：这个键必须真的在引擎白名单里
        assertTrue(FilmStockMapper.ENGINE_KNOWN_KEYS.contains("lens_blur_um"))
    }

    @Test
    fun `镜头柔度可被工程师面板全局覆盖`() {
        // DevSettings 传入 0（关）和 35（真机验证过的上限），必须原样进 JSON
        val off = JSONObject(
            FilmStockMapper.buildParamsJson(
                "kodak_portra_400", "kodak_portra_endura",
                FilmStockMapper.DevSettings(lensBlurUm = 0f)
            )
        )
        assertEquals(0.0, off.optDouble("lens_blur_um"), 1e-6)
        val max = JSONObject(
            FilmStockMapper.buildParamsJson(
                "kodak_portra_400", "kodak_portra_endura",
                FilmStockMapper.DevSettings(lensBlurUm = FilmStockMapper.LENS_BLUR_MAX_UM)
            )
        )
        assertEquals(35.0, max.optDouble("lens_blur_um"), 1e-6)
    }

    @Test
    fun `反转片参数带 scan_film true`() {
        val json = FilmStockMapper.buildParamsJson("kodak_kodachrome_64", "kodak_endura_premier")
        val obj = JSONObject(json)
        assertTrue("反转片必须 scan_film=true: $json", obj.optBoolean("scan_film", false))
        assertEquals("kodak_kodachrome_64", obj.getString("film_stock"))
    }

    @Test
    fun `直闪帧带辉光增强与预闪参数`() {
        // 自然光帧（基线）：不带预闪，辉光 = 用户设置原值
        val plain = JSONObject(
            FilmStockMapper.buildParamsJson("kodak_portra_400", "kodak_portra_endura")
        )
        assertFalse("非闪光帧不应带 preflash_exposure", plain.has("preflash_exposure"))
        assertEquals(1.0, plain.optDouble("halation_amount"), 1e-6)

        // 直闪帧：辉光 ×1.5，追加预闪（暗房处理直闪底片的常规操作）
        val flash = JSONObject(
            FilmStockMapper.buildParamsJson(
                "kodak_portra_400", "kodak_portra_endura",
                FilmStockMapper.DevSettings(),
                includeStyleMark = false,
                flashShot = true,
                flashEntries = FilmStockMapper.flashFinishEntries(),
            )
        )
        assertEquals(1.5, flash.optDouble("halation_amount"), 1e-6)
        assertEquals(0.08, flash.optDouble("preflash_exposure"), 1e-9)
        // 直闪键必须在引擎白名单里（否则预检必拒，白搭）
        FilmStockMapper.flashFinishEntries().forEach { (k, _) ->
            assertTrue("直闪键 $k 不在引擎白名单", FilmStockMapper.ENGINE_KNOWN_KEYS.contains(k))
        }
    }

    @Test
    fun `直闪辉光增强钳在已验证区间`() {
        // 基线 2.0（顶格）× 1.5 = 3.0，必须钳回 2.0 —— 引擎区间 0~2 是真机验过的
        val flash = JSONObject(
            FilmStockMapper.buildParamsJson(
                "kodak_portra_400", "kodak_portra_endura",
                FilmStockMapper.DevSettings(halationAmount = 2.0f),
                includeStyleMark = false,
                flashShot = true,
                flashEntries = emptyList(),
            )
        )
        assertEquals(2.0, flash.optDouble("halation_amount"), 1e-6)
        // 直闪键全被预检拒掉时（空表）：辉光增强仍然生效
        assertTrue(!flash.has("preflash_exposure"))
    }

    @Test
    fun `参数默认值与冻结模板一致`() {
        val obj = JSONObject(FilmStockMapper.buildParamsJson("kodak_portra_400", "kodak_portra_endura"))
        assertEquals(1.0, obj.getDouble("grain_amount"), 1e-6)
        assertEquals(1.0, obj.getDouble("halation_amount"), 1e-6)
        assertEquals(3.0, obj.getDouble("glare_amount"), 1e-6)
        assertEquals(1.0, obj.getDouble("print_exposure"), 1e-6)
        assertEquals(36.0, obj.getDouble("film_format_mm"), 1e-6)
        assertEquals(2048, obj.getInt("preview_long_edge"))
        assertEquals("sRGB", obj.getString("input_color_space"))
    }

    @Test
    fun `用户覆盖相纸与曝光后按覆盖值输出`() {
        val settings = FilmStockMapper.DevSettings(
            grainAmount = 1.5f,
            printExposure = 0.75f,
            paperOverride = "kodak_supra_endura",
        )
        val obj = JSONObject(
            FilmStockMapper.buildParamsJson("kodak_portra_400", "kodak_portra_endura", settings)
        )
        assertEquals("kodak_supra_endura", obj.getString("print_stock"))
        assertEquals(0.75, obj.getDouble("print_exposure"), 1e-6)
        assertEquals(1.5, obj.getDouble("grain_amount"), 1e-6)
    }

    @Test
    fun `数值格式化保留至少一位小数且不出现浮点尾巴`() {
        val cases = listOf(1.0f to "1.0", 0.75f to "0.75", 3.0f to "3.0", 1.2f to "1.2", 2.0f to "2.0")
        cases.forEach { (v, expect) ->
            val json = FilmStockMapper.buildParamsJson(
                "kodak_portra_400", "kodak_portra_endura",
                FilmStockMapper.DevSettings(grainAmount = v)
            )
            // 直接断言序列化文本：避免 org.json 把数字读成 Double 后丢失原始字面量格式
            assertTrue("$v 应序列化为 $expect，实际: $json", json.contains("\"grain_amount\":$expect,"))
            assertEquals(expect.toDouble(), JSONObject(json).getDouble("grain_amount"), 1e-6)
        }
    }

    // ---------- 相纸解析规则 ----------

    @Test
    fun `负片尊重用户选择的相纸`() {
        val r = FilmStockMapper.resolvePrintStock("kodak_portra_400", "kodak_portra_endura", "kodak_2383")
        assertEquals("kodak_2383", r.paperId)
        assertFalse(r.fellBack)
    }

    @Test
    fun `负片未选相纸时回退映射默认`() {
        val r = FilmStockMapper.resolvePrintStock("kodak_portra_400", "kodak_portra_endura", null)
        assertEquals("kodak_portra_endura", r.paperId)
        assertFalse(r.fellBack)

        val blank = FilmStockMapper.resolvePrintStock("kodak_portra_400", "kodak_portra_endura", "  ")
        assertEquals("kodak_portra_endura", blank.paperId)
    }

    @Test
    fun `反转片忽略用户相纸并标记回退`() {
        val r = FilmStockMapper.resolvePrintStock("kodak_kodachrome_64", "kodak_endura_premier", "kodak_2383")
        assertEquals("反转片应使用映射占位相纸", "kodak_endura_premier", r.paperId)
        assertTrue("用户选纸被忽略应标记 fellBack", r.fellBack)
    }

    @Test
    fun `反转片未选相纸不算回退`() {
        val r = FilmStockMapper.resolvePrintStock("kodak_kodachrome_64", "kodak_endura_premier", null)
        assertEquals("kodak_endura_premier", r.paperId)
        assertFalse(r.fellBack)
    }

    // ---------- 兼容别名 ----------

    @Test
    fun `历史胶卷名仍可解析（兼容旧记录）`() {
        val aliases = mapOf(
            "CLASSIC CHROME" to "kodak_kodachrome_64",
            "ETERNA" to "kodak_vision3_250d",
            "CN800" to "cinestill_800t",
        )
        aliases.forEach { (name, expect) ->
            assertEquals("旧名 $name 兼容失效", expect, FilmStockMapper.filmStockIdFor(name))
        }
    }

    @Test
    fun `大小写与首尾空格不影响解析`() {
        assertEquals(
            FilmStockMapper.filmStockIdFor("PORTRA 400"),
            FilmStockMapper.filmStockIdFor("  portra 400 ")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `未知胶卷名抛异常而不是静默返回空`() {
        FilmStockMapper.filmStockIdFor("NO SUCH FILM 999")
    }

    // ---------- 色彩微调（放大机滤色片 + 滤色强度） ----------

    @Test
    fun `色彩微调默认不发滤色片键`() {
        // 印相档基线就是「不发」，默认设置下参数集与旧版完全一致
        val obj = JSONObject(
            FilmStockMapper.buildParamsJson("kodak_gold_200", "kodak_endura_premier")
        )
        assertFalse("默认不该有 y_filter_shift: $obj", obj.has("y_filter_shift"))
        assertFalse("默认不该有 m_filter_shift: $obj", obj.has("m_filter_shift"))
        assertFalse("默认不该有 filter_shift_scale: $obj", obj.has("filter_shift_scale"))
    }

    @Test
    fun `色彩微调印相档非零直发并钳制`() {
        val s = FilmStockMapper.DevSettings(ccY = 0.6f, ccM = -0.3f, ccScale = 6f)
        val obj = JSONObject(
            FilmStockMapper.buildParamsJson("kodak_gold_200", "kodak_endura_premier", s)
        )
        assertEquals(0.6, obj.optDouble("y_filter_shift"), 1e-3)
        assertEquals(-0.3, obj.optDouble("m_filter_shift"), 1e-3)
        assertEquals(6.0, obj.optDouble("filter_shift_scale"), 1e-3)
        // 越界值钳回引擎硬区间 [-1,1]（真机报错确认过的区间）
        val over = FilmStockMapper.DevSettings(ccY = 5f, ccM = -5f)
        val o2 = JSONObject(
            FilmStockMapper.buildParamsJson("kodak_gold_200", "kodak_endura_premier", over)
        )
        assertEquals(1.0, o2.optDouble("y_filter_shift"), 1e-3)
        assertEquals(-1.0, o2.optDouble("m_filter_shift"), 1e-3)
    }

    @Test
    fun `色彩微调叠加店扫档签名值并钳制`() {
        // FRONTIER 签名 y=+1.0（冷）：用户偏 -0.5 → 0.5
        val front = FilmStockMapper.DevSettings(
            outputStyle = FilmStockMapper.OutputStyle.FRONTIER, ccY = -0.5f
        )
        val fo = JSONObject(
            FilmStockMapper.buildParamsJson("kodak_gold_200", "kodak_endura_premier", front)
        )
        assertEquals(0.5, fo.optDouble("y_filter_shift"), 1e-3)
        // NORITSU 签名 y=-1.0（暖）：偏 -0.5 钳在 -1.0；m 签名 -0.8 偏 +0.45 → -0.35
        val nori = FilmStockMapper.DevSettings(
            outputStyle = FilmStockMapper.OutputStyle.NORITSU, ccY = -0.5f, ccM = 0.45f
        )
        val no = JSONObject(
            FilmStockMapper.buildParamsJson("kodak_gold_200", "kodak_endura_premier", nori)
        )
        assertEquals(-1.0, no.optDouble("y_filter_shift"), 1e-3)
        assertEquals(-0.35, no.optDouble("m_filter_shift"), 1e-2)
    }

    @Test
    fun `色彩微调三键与白名单自洽`() {
        assertTrue(FilmStockMapper.ENGINE_KNOWN_KEYS.contains("y_filter_shift"))
        assertTrue(FilmStockMapper.ENGINE_KNOWN_KEYS.contains("m_filter_shift"))
        assertTrue(FilmStockMapper.ENGINE_KNOWN_KEYS.contains("filter_shift_scale"))
    }
}
