package app.spektralab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RAW 通路的输入传输函数修正：DNG 解码出来是**线性**场景光，而共享参数里的
 * `input_cctf_decoding=true` 是给 JPEG 通路（sRGB 编码）写的。对线性数据再反一次
 * gamma，中灰 0.18 → 0.027（≈ -2.7EV），真机观感就是「RAW 成片明显偏暗」。
 *
 * 契约：RAW 会话必须把该键改成 false，其余键一个都不能动（多一个未知键整档出不了图）。
 */
class RawLinearInputTest {

    @Test
    fun `RAW 输入关闭 cctf 解码且其余键原样保留`() {
        val src = """{"film_stock":"kodak_gold_200","print_stock":"kodak_endura",""" +
            """"input_color_space":"sRGB","input_cctf_decoding":true,""" +
            """"output_color_space":"sRGB","print_exposure":1.0,"overscan_active":true}"""
        val out = SpektraProcessor.asLinearInput(src)
        val o = org.json.JSONObject(out)
        assertFalse("RAW 必须按线性输入（否则二次反 gamma）", o.getBoolean("input_cctf_decoding"))
        // 其余键逐个核对，防止改写时误伤
        assertEquals("kodak_gold_200", o.getString("film_stock"))
        assertEquals("kodak_endura", o.getString("print_stock"))
        assertEquals("sRGB", o.getString("input_color_space"))
        assertEquals("sRGB", o.getString("output_color_space"))
        assertEquals(1.0, o.getDouble("print_exposure"), 1e-9)
        assertTrue(o.getBoolean("overscan_active"))
    }

    @Test
    fun `已是线性输入时不重复改写`() {
        val src = """{"input_cctf_decoding":false,"print_exposure":1.0}"""
        assertEquals(src, SpektraProcessor.asLinearInput(src))
    }

    @Test
    fun `没有该键时原样返回`() {
        val src = """{"film_stock":"kodak_gold_200","print_exposure":1.0}"""
        assertEquals(src, SpektraProcessor.asLinearInput(src))
    }

    @Test
    fun `坏 JSON 原样返回不抛`() {
        val src = "{not json"
        assertEquals(src, SpektraProcessor.asLinearInput(src))
    }
}
