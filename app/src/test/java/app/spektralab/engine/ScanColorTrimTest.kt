package app.spektralab.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 「店扫校色」= 店扫档专属的色彩微调，叠加在店扫自家滤色片签名上。
 *
 * 契约（2026-11-05 改版）：
 *  1) 默认 0/0 → 签名**一个数都不改**（保持出厂标定的店扫味）；
 *  2) 偏移叠在签名上，并钳回引擎硬区间 [-1,1]；
 *  3) 印相档完全不看这两个键（印相校色是冻结定值 ccY/ccM）；
 *  4) 店扫档的校色只挂在店扫出口，不会漏到印相。
 *
 * 边界现实：两家店的黄滤片签名顶在 ±1.0（真机标定：±1.0 才挪得动 ~1.6 个 R−B
 * 色阶，小值等于没给），所以 Frontier 只能往暖调、Noritsu 只能往冷调。
 */
class ScanColorTrimTest {

    @Before
    fun setUp() {
        val f = File("src/main/assets/resources/stock_catalog.json")
        assertTrue("缺少 catalog: ${f.absolutePath}", f.exists())
        FilmStockMapper.installCatalog(FilmStockMapper.parseCatalog(f.readText(Charsets.UTF_8)))
    }

    private fun json(style: FilmStockMapper.OutputStyle): JSONObject =
        json(FilmStockMapper.DevSettings(outputStyle = style))

    private fun json(settings: FilmStockMapper.DevSettings): JSONObject = JSONObject(
        FilmStockMapper.buildParamsJson(
            filmStockId = "kodak_portra_400",
            printStockId = "kodak_portra_endura",
            settings = settings,
            includeStyleMark = false,
            scanFinishLevel = FilmStockMapper.SCAN_LEVEL_FULL,
        )
    )

    private fun ds(
        style: FilmStockMapper.OutputStyle,
        dy: Float = 0f,
        dm: Float = 0f,
    ): FilmStockMapper.DevSettings =
        FilmStockMapper.DevSettings(outputStyle = style, scanCcY = dy, scanCcM = dm)

    @Test
    fun `默认零偏移不改动店扫签名`() {
        val base = json(FilmStockMapper.OutputStyle.FRONTIER)
        val same = json(ds(FilmStockMapper.OutputStyle.FRONTIER, 0f, 0f))
        assertEquals(base.getDouble("y_filter_shift"), same.getDouble("y_filter_shift"), 1e-9)
        assertEquals(base.getDouble("m_filter_shift"), same.getDouble("m_filter_shift"), 1e-9)
        // 签名原值：Frontier 黄滤片 1.0 / 品红 0.8
        assertEquals(1.0, base.getDouble("y_filter_shift"), 1e-9)
        assertEquals(0.8, base.getDouble("m_filter_shift"), 1e-9)
    }

    @Test
    fun `负偏移把 Frontier 往暖拉`() {
        val o = json(ds(FilmStockMapper.OutputStyle.FRONTIER, dy = -0.3f))
        assertEquals(0.7, o.getDouble("y_filter_shift"), 1e-6)
    }

    @Test
    fun `Frontier 往冷的方向已顶到引擎上限`() {
        val o = json(ds(FilmStockMapper.OutputStyle.FRONTIER, dy = 0.3f))
        assertEquals("签名 1.0 + 0.3 应钳回 1.0", 1.0, o.getDouble("y_filter_shift"), 1e-6)
    }

    @Test
    fun `正偏移把 Noritsu 往冷拉`() {
        val o = json(ds(FilmStockMapper.OutputStyle.NORITSU, dy = 0.3f))
        assertEquals(-0.7, o.getDouble("y_filter_shift"), 1e-6)
    }

    @Test
    fun `洋红偏移同样叠加并钳区间`() {
        val o = json(ds(FilmStockMapper.OutputStyle.NORITSU, dm = 0.5f))
        assertEquals("签名 -0.8 + 0.5", -0.3, o.getDouble("m_filter_shift"), 1e-6)
        val pinned = json(ds(FilmStockMapper.OutputStyle.FRONTIER, dm = 0.9f))
        assertEquals("0.8 + 0.9 应钳回 1.0", 1.0, pinned.getDouble("m_filter_shift"), 1e-6)
    }

    @Test
    fun `印相档完全不看店扫校色`() {
        val plain = json(FilmStockMapper.DevSettings(outputStyle = FilmStockMapper.OutputStyle.PRINT))
        val withScanCc = json(
            FilmStockMapper.DevSettings(
                outputStyle = FilmStockMapper.OutputStyle.PRINT,
                scanCcY = 0.8f,
                scanCcM = -0.8f,
            )
        )
        // 印相档的滤色片来自冻结定值 ccY/ccM，店扫校色不该插进来
        assertEquals(plain.getDouble("y_filter_shift"), withScanCc.getDouble("y_filter_shift"), 1e-9)
        assertEquals(plain.getDouble("m_filter_shift"), withScanCc.getDouble("m_filter_shift"), 1e-9)
    }

    @Test
    fun `保守档不带滤色片时偏移非零才插入键`() {
        val safePlain = JSONObject(
            FilmStockMapper.buildParamsJson(
                filmStockId = "kodak_portra_400",
                printStockId = "kodak_portra_endura",
                settings = FilmStockMapper.DevSettings(outputStyle = FilmStockMapper.OutputStyle.FRONTIER),
                includeStyleMark = false,
                scanFinishLevel = FilmStockMapper.SCAN_LEVEL_SAFE,
            )
        )
        assertFalse("保守档默认不带滤色片", safePlain.has("y_filter_shift"))
        val tuned = FilmStockMapper.applyScanColorTrim(
            FilmStockMapper.scanFinishEntries(
                FilmStockMapper.OutputStyle.FRONTIER, FilmStockMapper.SCAN_LEVEL_SAFE
            ),
            dy = -0.2f,
            dm = 0f,
        )
        val y = tuned.first { it.first == "y_filter_shift" }.second.toDouble()
        assertEquals(-0.2, y, 1e-6)
    }
}
