package app.spektralab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

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

    private fun json(style: FilmStockMapper.OutputStyle): JSONObject =
        JSONObject(
            FilmStockMapper.buildParamsJson(
                filmStockId = "kodak_portra_400",
                printStockId = "kodak_portra_endura",
                settings = FilmStockMapper.DevSettings(outputStyle = style),
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
    fun frontierStyle_enablesScanPathAndSoftScannerOptics() {
        val o = json(FilmStockMapper.OutputStyle.FRONTIER)
        assertTrue("扫描档必须走 scan_film", o.getBoolean("scan_film"))
        assertTrue(o.getBoolean("contrast_mask_active"))
        assertEquals("gaussian", o.getString("contrast_mask_scheme"))
        assertTrue("Frontier 镜头偏柔，颗粒被轻微抹平", o.getDouble("scanner_lens_blur") > 0.5)
        assertTrue(o.getBoolean("scene_latitude_active"))
    }

    @Test
    fun noritsuStyle_isFlatterAndSharperThanFrontier() {
        val f = json(FilmStockMapper.OutputStyle.FRONTIER)
        val n = json(FilmStockMapper.OutputStyle.NORITSU)
        assertTrue(n.getBoolean("scan_film"))
        assertFalse("Noritsu 更平，不加局部反差", n.getBoolean("contrast_mask_active"))
        assertTrue(
            "Noritsu 颗粒更清晰 → 扫描镜头柔化更小",
            n.getDouble("scanner_lens_blur") < f.getDouble("scanner_lens_blur")
        )
        assertTrue(
            "Frontier 高光更柔 → 肩部 rolloff 更长",
            f.getDouble("scene_latitude_rolloff") > n.getDouble("scene_latitude_rolloff")
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
    fun scanStylesStillShipAValidPrintStock() {
        // 引擎要求 print_stock 是能加载的合法 profile（反转片也是这么兜底的），
        // 扫描档靠 scan_film 跳过放大机，不能靠把 print_stock 留空来切换通路。
        for (style in FilmStockMapper.OutputStyle.values()) {
            assertEquals(style.wire, "kodak_portra_endura", json(style).getString("print_stock"))
        }
    }
}
