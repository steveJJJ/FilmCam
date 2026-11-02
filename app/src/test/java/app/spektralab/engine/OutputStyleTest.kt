package app.spektralab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.io.File

/**
 * 成片「输出风格」三档（印相 / Frontier 扫描 / Noritsu 扫描）的参数生成。
 *
 * 这里只测三件事，其余交给真机：
 *  1) 通路切换字段对不对 —— scan_film 决定走扫描还是走放大机，写错了整卷味道就错了；
 *  2) 新下发的数值落在引擎硬校验区间内 —— 超出区间 setParams 会直接失败；
 *  3) **每个键都是引擎认识的** —— 引擎对未知键报 unknown parameter 并让整帧失败，
 *     output_style 就曾经这样让整卷一帧都出不来（它只该进 EXIF）。
 */
class OutputStyleTest {

    /** 装真实 catalog，让 isPositive（反转片判定）在测试里也是真的。 */
    @Before
    fun setUp() {
        val f = File("src/main/assets/resources/stock_catalog.json")
        assertTrue("缺少 catalog: ${f.absolutePath}", f.exists())
        FilmStockMapper.installCatalog(
            FilmStockMapper.parseCatalog(f.readText(Charsets.UTF_8))
        )
    }

    private fun json(style: FilmStockMapper.OutputStyle): JSONObject =
        fullJson(style, FilmStockMapper.SCAN_LEVEL_FULL)

    private fun fullJson(style: FilmStockMapper.OutputStyle, level: Int): JSONObject =
        JSONObject(
            FilmStockMapper.buildParamsJson(
                filmStockId = "kodak_portra_400",
                printStockId = "kodak_portra_endura",
                settings = FilmStockMapper.DevSettings(outputStyle = style),
                includeStyleMark = false,
                scanFinishLevel = level,
            )
        )

    private fun exifJson(style: FilmStockMapper.OutputStyle): JSONObject =
        JSONObject(
            FilmStockMapper.buildExifParamsJson(
                filmStockId = "kodak_portra_400",
                printStockId = "kodak_portra_endura",
                settings = FilmStockMapper.DevSettings(outputStyle = style),
            )
        )

    @Test
    fun engineParams_neverCarryOurOwnMarkers() {
        // 引擎遇到不认识的键会报 unknown parameter 并让整帧失败。
        // 风格标记只属于 EXIF 快照（拍摄同款要读），绝不能发给引擎。
        for (style in FilmStockMapper.OutputStyle.values()) {
            val o = json(style)
            assertFalse("$style 不该把 output_style 发给引擎", o.has("output_style"))
            assertEquals(style.wire, exifJson(style).getString("output_style"))
        }
    }

    @Test
    fun everyEngineKeyIsKnownToTheEngine() {
        // 拿引擎二进制里核对过的键名集合逐个校验，杜绝下一个 output_style。
        for (style in FilmStockMapper.OutputStyle.values()) {
            val o = json(style)
            for (k in o.keys()) {
                assertTrue(
                    "「$k」不是引擎参数（${style.wire} 档），引擎会报 unknown parameter",
                    k in FilmStockMapper.ENGINE_KNOWN_KEYS
                )
            }
        }
    }

    @Test
    fun printStyle_isNotScanAndCarriesNoScannerParams() {
        val o = json(FilmStockMapper.OutputStyle.PRINT)
        assertFalse("印相档不该加 scan_film", o.optBoolean("scan_film", false))
        assertFalse(o.has("scanner_lens_blur"))
        assertFalse(o.has("contrast_mask_active"))
        assertFalse(o.has("scene_latitude_active"))
        // 印相档必须带相纸，否则放大机那段没有介质
        assertEquals("kodak_portra_endura", o.getString("print_stock"))
    }

    @Test
    fun frontierStyle_hasSoftShoulderAndSofterScannerOptics() {
        val o = json(FilmStockMapper.OutputStyle.FRONTIER)
        // 负片必须仍走印相（否则出的是底片），店扫味靠末端参数
        assertFalse(o.optBoolean("scan_film", false))
        assertEquals("kodak_portra_endura", o.getString("print_stock"))
        assertTrue(o.getBoolean("scene_latitude_active"))
        assertTrue("Frontier 镜头偏柔，颗粒被轻微抹平", o.getDouble("scanner_lens_blur") > 1.0)
    }

    @Test
    fun noritsuStyle_isHarderAndSharperThanFrontier() {
        val f = json(FilmStockMapper.OutputStyle.FRONTIER)
        val n = json(FilmStockMapper.OutputStyle.NORITSU)
        assertTrue(
            "Noritsu 颗粒更清晰 → 扫描仪柔化更小",
            n.getDouble("scanner_lens_blur") < f.getDouble("scanner_lens_blur")
        )
        assertTrue(
            "Frontier 高光更柔 → 肩部 rolloff 更长",
            f.getDouble("scene_latitude_rolloff") > n.getDouble("scene_latitude_rolloff")
        )
        assertTrue(
            "Frontier 抬暗部更多",
            f.getDouble("scene_latitude_max_lift") > n.getDouble("scene_latitude_max_lift")
        )
    }

    @Test
    fun sceneLatitudeValues_stayInsideEngineLimits() {
        // 引擎硬校验：rolloff ∈ [1,4]、max_lift ∈ [0.25,12]、norm ∈ {power,y,max}
        for (style in listOf(
            FilmStockMapper.OutputStyle.FRONTIER,
            FilmStockMapper.OutputStyle.NORITSU,
        )) {
            val o = json(style)
            val rolloff = o.getDouble("scene_latitude_rolloff")
            val lift = o.getDouble("scene_latitude_max_lift")
            val norm = o.getString("scene_latitude_norm")
            assertTrue("$style rolloff=$rolloff 越界", rolloff >= 1.0 && rolloff <= 4.0)
            assertTrue("$style max_lift=$lift 越界", lift >= 0.25 && lift <= 12.0)
            assertTrue("$style norm=$norm 非法", norm in listOf("power", "y", "max"))
        }
    }

    @Test
    fun styleRoundTripsThroughWireValue() {
        for (style in FilmStockMapper.OutputStyle.values()) {
            assertEquals(style, FilmStockMapper.OutputStyle.fromWire(style.wire))
            assertEquals(style, FilmStockMapper.OutputStyle.fromPrefs(style.prefs))
        }
    }

    @Test
    fun dirtyPrefsAndWire_fallBackToPrint() {
        assertEquals(FilmStockMapper.OutputStyle.PRINT, FilmStockMapper.OutputStyle.fromPrefs(99))
        assertEquals(FilmStockMapper.OutputStyle.PRINT, FilmStockMapper.OutputStyle.fromPrefs(-1))
        assertEquals(FilmStockMapper.OutputStyle.PRINT, FilmStockMapper.OutputStyle.fromWire("sp3000"))
        assertEquals(FilmStockMapper.OutputStyle.PRINT, FilmStockMapper.OutputStyle.fromWire(null))
    }

    @Test
    fun negativeFilm_neverGoesThroughScanPath() {
        // 负片走 scan_film 出来的是未反相的底片（橙罩负像、没有色彩）：
        // 负→正是放大机把底片投到相纸上那一步物理完成的，跳过印相就没有正像。
        // scan_film 只属于反转片。
        for (style in FilmStockMapper.OutputStyle.values()) {
            assertFalse(
                "负片在 ${style.wire} 档不该走 scan_film",
                json(style).optBoolean("scan_film", false)
            )
        }
        // 反转片仍必须直扫
        val slide = JSONObject(
            FilmStockMapper.buildParamsJson(
                "kodak_kodachrome_64", "kodak_endura_premier",
                FilmStockMapper.DevSettings(outputStyle = FilmStockMapper.OutputStyle.FRONTIER)
            )
        )
        assertTrue("反转片必须直扫", slide.getBoolean("scan_film"))
    }

    @Test
    fun fullLevelAddsTasteParamsAndSafeLevelIsExactlyTheVerifiedSet() {
        // 完整档比保守档多出滤色片 / gamma / 层间效应；保守档必须只有真机验过的那几个键
        for (style in listOf(
            FilmStockMapper.OutputStyle.FRONTIER,
            FilmStockMapper.OutputStyle.NORITSU,
        )) {
            val full = fullJson(style, FilmStockMapper.SCAN_LEVEL_FULL)
            val safe = fullJson(style, FilmStockMapper.SCAN_LEVEL_SAFE)
            assertTrue("$style 完整档应带滤色片", full.has("y_filter_shift"))
            assertTrue("$style 完整档应带 gamma", full.has("density_curve_gamma"))
            assertTrue("$style 完整档应带层间效应", full.has("dir_couplers_amount"))
            assertFalse("$style 保守档不带滤色片", safe.has("y_filter_shift"))
            assertFalse("$style 保守档不带 gamma", safe.has("density_curve_gamma"))
            // 保守档的键 = 印相档 + 扫描仪模糊 / glare / 宽容度
            assertTrue(safe.has("scanner_lens_blur"))
            assertTrue(safe.has("scene_latitude_active"))
        }
    }

    @Test
    fun frontierAndNoritsuPushColourTemperatureOppositeWays() {
        // y_filter_shift 是放大机黄滤片（CC）：加黄 = 到相纸的蓝光变少 = 成片偏冷
        val f = fullJson(FilmStockMapper.OutputStyle.FRONTIER, FilmStockMapper.SCAN_LEVEL_FULL)
        val n = fullJson(FilmStockMapper.OutputStyle.NORITSU, FilmStockMapper.SCAN_LEVEL_FULL)
        assertTrue("Frontier 偏冷 → 加黄", f.getDouble("y_filter_shift") > 0)
        assertTrue("Noritsu 偏暖 → 减黄", n.getDouble("y_filter_shift") < 0)
        assertTrue(
            "Frontier 对比更强 → gamma 更大",
            f.getDouble("density_curve_gamma") > n.getDouble("density_curve_gamma")
        )
        assertTrue(
            "Frontier 色彩更浓 → 层间效应更强",
            f.getDouble("dir_couplers_amount") > n.getDouble("dir_couplers_amount")
        )
        assertTrue(
            "Frontier 曝光更亮",
            f.getDouble("print_exposure") > n.getDouble("print_exposure")
        )
    }

    @Test
    fun scanStylesDropPaperGlareAndVaryScannerBlur() {
        // glare 是纸面反射的环境光；店扫拿的是数字图，没有印相表面
        val f = json(FilmStockMapper.OutputStyle.FRONTIER)
        val n = json(FilmStockMapper.OutputStyle.NORITSU)
        assertFalse(f.getBoolean("glare_active"))
        assertFalse(n.getBoolean("glare_active"))
        // scanner_lens_blur 单位是像素：Frontier 镜头柔（抹平颗粒），Noritsu 锐（颗粒清晰）
        assertTrue(
            "Frontier 应比 Noritsu 更柔",
            f.getDouble("scanner_lens_blur") > n.getDouble("scanner_lens_blur")
        )
    }

    @Test
    fun scanStylesStillShipAValidPrintStock() {
        // 引擎要求 print_stock 是能加载的合法 profile（反转片也是这么兜底的），
        // 扫描档靠 scan_film 跳过放大机，不能靠把 print_stock 留空来切换通路。
        for (style in FilmStockMapper.OutputStyle.values()) {
            assertEquals(style.wire, "kodak_portra_endura", json(style).getString("print_stock"))
        }
    }
}
