package app.spektralab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.io.File

/**
 * 胶片相框（引擎 `film_render.overscan.*`）：把片基、齿孔、暗边文字一起扫进成片。
 *
 * 测试点：
 *  1) 开关关着时参数里一个 overscan 键都不许有（引擎不认的键会整卷失败，多一个都不行）；
 *  2) 开着时每个键都必须在引擎白名单里；
 *  3) 帧号逐帧递增（底片暗边本来就印着 1…36，每帧一个数）。
 */
class FrameBorderTest {

    @Before
    fun setUp() {
        val f = File("src/main/assets/resources/stock_catalog.json")
        assertTrue("缺少 catalog: ${f.absolutePath}", f.exists())
        FilmStockMapper.installCatalog(
            FilmStockMapper.parseCatalog(f.readText(Charsets.UTF_8))
        )
    }

    private fun json(frameBorder: Boolean): JSONObject = JSONObject(
        FilmStockMapper.buildParamsJson(
            filmStockId = "kodak_portra_400",
            printStockId = "kodak_portra_endura",
            settings = FilmStockMapper.DevSettings(frameBorder = frameBorder),
            includeStyleMark = false,
        )
    )

    @Test
    fun frameBorderOff_carriesNoOverscanKeys() {
        val o = json(frameBorder = false)
        for (k in o.keys()) {
            assertFalse("相框关着时不该有 $k", k.startsWith("overscan"))
        }
    }

    @Test
    fun frameBorderOn_onlyUsesKeysTheEngineKnows() {
        val o = json(frameBorder = true)
        val overscanKeys = o.keys().asSequence().filter { it.startsWith("overscan") }.toList()
        assertTrue("相框开着就该有 overscan 参数", overscanKeys.isNotEmpty())
        for (k in overscanKeys) {
            assertTrue(
                "「$k」不是引擎参数，引擎会报 unknown parameter",
                k in FilmStockMapper.ENGINE_KNOWN_KEYS
            )
        }
    }

    @Test
    fun frameBorderOn_scansTheWholeFilmBaseWithEdgeText() {
        val o = json(frameBorder = true)
        assertTrue(o.getBoolean("overscan_active"))
        // 取值贴着引擎默认：整条片基 + 135 格式 + 自动片门，别自己发明
        assertEquals("strip", o.getString("overscan_mode"))
        assertEquals("135", o.getString("overscan_format"))
        assertEquals("auto", o.getString("overscan_gate"))
        assertFalse("不双联", o.getBoolean("overscan_pair"))
        assertEquals("不漏光", 0, o.getInt("overscan_leaks"))
        // 暗边印刷字：真卷片基边缘都印着胶卷型号
        assertTrue(
            "暗边该印着胶卷名，留空就没有底片感",
            o.getString("overscan_edge_text").isNotBlank()
        )
    }

    @Test
    fun frameNumberAdvancesPerFrame() {
        val base = FilmStockMapper.buildParamsJson(
            "kodak_portra_400", "kodak_portra_endura",
            FilmStockMapper.DevSettings(frameBorder = true),
            includeStyleMark = false,
        )
        assertTrue("第一帧就该带帧号", JSONObject(base).getInt("overscan_frame_number") >= 1)
        val seventh = JSONObject(FilmStockMapper.withFrameNumber(base, 7))
        assertEquals(7, seventh.getInt("overscan_frame_number"))
        // 覆盖不能把别的参数弄丢
        assertEquals("kodak_portra_400", seventh.getString("film_stock"))
        assertTrue(seventh.getBoolean("overscan_active"))
    }

    @Test
    fun withFrameNumber_keepsJsonIntactWhenItCannotParse() {
        // 解析失败要原样返回：宁可帧号不对，也不能因为一个键让整卷冲不出来
        assertEquals("not json", FilmStockMapper.withFrameNumber("not json", 3))
    }
}
