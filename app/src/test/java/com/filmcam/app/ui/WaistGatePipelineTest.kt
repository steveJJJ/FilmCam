package com.filmcam.app.ui

import app.spektralab.engine.FilmStockMapper
import com.filmcam.app.develop.WhiteBorderRenderer
import com.filmcam.app.film.ShotMeta
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 腰平画幅（135 / 120 6×6）从拍摄快照到成片的定量契约。
 *
 * 纯 JVM：裁切几何、参数 JSON、画幅快照、白边外扩都是纯 Kotlin，
 * 不碰 Android Canvas / 引擎 session（那些留在真机成片里验）。
 */
class WaistGatePipelineTest {

    @Test
    fun `片门比例 135 随朝向 6x6 恒 1比1`() {
        assertEquals(1.5f, FilmStockMapper.gateAspectOf(FilmStockMapper.FilmGate.F135, false), 1e-6f)
        assertEquals(2f / 3f, FilmStockMapper.gateAspectOf(FilmStockMapper.FilmGate.F135, true), 1e-6f)
        assertEquals(1f, FilmStockMapper.gateAspectOf(FilmStockMapper.FilmGate.MF6x6, false), 1e-6f)
        assertEquals(1f, FilmStockMapper.gateAspectOf(FilmStockMapper.FilmGate.MF6x6, true), 1e-6f)
        assertEquals("120_6x6", FilmStockMapper.FilmGate.MF6x6.overscanFormat)
        assertTrue(FilmStockMapper.FilmGate.MF6x6.isMediumFormat)
        assertFalse(FilmStockMapper.FilmGate.F135.isMediumFormat)
    }

    @Test
    fun `老帧没有 gate 字段时回落 135`() {
        assertEquals(FilmStockMapper.FilmGate.F135, FilmStockMapper.FilmGate.fromKey(null))
        assertEquals(FilmStockMapper.FilmGate.F135, FilmStockMapper.FilmGate.fromKey(""))
        assertEquals(FilmStockMapper.FilmGate.MF6x6, FilmStockMapper.FilmGate.fromKey("6x6"))
        assertEquals("135", ShotMeta.fromJson("""{"flash":"OFF"}""")?.gate)
        assertNull(ShotMeta.fromJson(null))
    }

    @Test
    fun `逐帧画幅随快照读回 不串帧`() {
        val square = ShotMeta(gate = "6x6", flash = "EXTERNAL").toJson()
        assertEquals("6x6", ShotMeta.fromJson(square)?.gate)
        assertEquals("EXTERNAL", ShotMeta.fromJson(square)?.flash)
        assertEquals("135", ShotMeta(gate = "135").toJson().let {
            ShotMeta.fromJson(it)?.gate
        })
        // 135 是默认值，不写进 JSON（老解析器看到的字段集不变）
        assertFalse(JSONObject(ShotMeta(gate = "135").toJson()).has("gate"))
        assertTrue(JSONObject(square).has("gate"))
    }

    @Test
    fun `4比3 传感器裁成 1比1 是居中正方形`() {
        val c = FilmStockMapper.gateCrop(4000, 3000, 1f)
        assertTrue(c.cropped)
        assertEquals(3000, c.w)
        assertEquals(3000, c.h)
        assertEquals(500, c.x)
        assertEquals(0, c.y)
        // 已经是正方形就一格都不裁
        val same = FilmStockMapper.gateCrop(3000, 3000, 1f)
        assertFalse(same.cropped)
    }

    @Test
    fun `120 片框参数带 120_6x6 版式与 56mm 幅宽`() {
        val entries = FilmStockMapper.frameBorderEntries(
            "kodak_gold_200", 1, FilmStockMapper.FilmGate.MF6x6.overscanFormat
        )
        val json = FilmStockMapper.buildParamsJson(
            "kodak_gold_200", "kodak_portra_endura",
            FilmStockMapper.DevSettings(frameFormat = FilmStockMapper.FrameFormat.FULL),
            includeStyleMark = false,
            extraEntries = entries,
            filmFormatMm = 56f,
        )
        val o = JSONObject(json)
        assertEquals("120_6x6", o.getString("overscan_format"))
        assertTrue(o.getBoolean("overscan_active"))
        assertEquals(56.0, o.getDouble("film_format_mm"), 1e-6)
        // 默认（135 老路径）必须保持 36.0，一个字节都不能变
        val legacy = JSONObject(
            FilmStockMapper.buildParamsJson(
                "kodak_gold_200", "kodak_portra_endura"
            )
        )
        assertEquals(36.0, legacy.getDouble("film_format_mm"), 1e-6)
    }

    @Test
    fun `白边对 1比1 成片仍是四边等宽`() {
        val g = WhiteBorderRenderer.geometry(2000, 2000)
        assertEquals(120, g.margin)
        assertEquals(2240, g.outW)
        assertEquals(2240, g.outH)
        // 短边比例对任意画幅都成立，6×6 / 3:2 都不需要改渲染器
        val wide = WhiteBorderRenderer.geometry(3000, 2000)
        assertEquals(120, wide.margin)
        assertEquals(3240, wide.outW)
        assertEquals(2240, wide.outH)
    }
}
