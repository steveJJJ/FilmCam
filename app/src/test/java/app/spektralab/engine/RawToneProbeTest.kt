package app.spektralab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RAW 影调 A/B 探针的参数合并契约。
 *
 * 关键坑：基线里已有的同名键必须被**替换**，直接字符串追加会写出重复键，
 * 引擎取第一个 → 那组实验等于没跑（矩阵实验里 max_lift / print_exposure
 * 就是这么白测的，出图和 base 字节级相同）。
 */
class RawToneProbeTest {

    private val base = """{"film_stock":"kodak_gold_200",""" +
        """"print_exposure":1.0,"grain_amount":1.0,"input_cctf_decoding":true}"""

    @Test
    fun `同名键替换且只出现一次`() {
        val out = SpektraProcessor.mergeParams(base, mapOf("print_exposure" to "0.6"))
        val o = org.json.JSONObject(out)
        assertEquals(0.6, o.getDouble("print_exposure"), 1e-9)
        assertEquals(1.0, o.getDouble("grain_amount"), 1e-9)
        assertEquals(1, Regex("\"print_exposure\"").findAll(out).count())
    }

    @Test
    fun `新增键与布尔数字类型都能吃`() {
        val out = SpektraProcessor.mergeParams(
            base,
            mapOf("auto_exposure" to "false", "exposure_compensation_ev" to "1.0")
        )
        val o = org.json.JSONObject(out)
        assertFalse(o.getBoolean("auto_exposure"))
        assertEquals(1.0, o.getDouble("exposure_compensation_ev"), 1e-9)
        assertEquals("kodak_gold_200", o.getString("film_stock"))
    }

    @Test
    fun `空 extra 内容等价`() {
        val out = org.json.JSONObject(SpektraProcessor.mergeParams(base, emptyMap()))
        assertEquals(1.0, out.getDouble("print_exposure"), 1e-9)
        assertTrue(out.getBoolean("input_cctf_decoding"))
    }

    @Test
    fun `坏 JSON 不抛且原样返回`() {
        val bad = "{not json"
        assertEquals(bad, SpektraProcessor.mergeParams(bad, mapOf("x" to "1")))
    }

    @Test
    fun `探针合并后仍按 RAW 线性输入`() {
        // renderRawProbeFrame 会在合并结果上再套 asLinearInput；
        // 合并不能把 input_cctf_decoding 弄丢，否则整组实验又是二次反 gamma
        val merged = SpektraProcessor.mergeParams(base, mapOf("print_exposure" to "0.8"))
        val linear = org.json.JSONObject(SpektraProcessor.asLinearInput(merged))
        assertFalse(linear.getBoolean("input_cctf_decoding"))
        assertEquals(0.8, linear.getDouble("print_exposure"), 1e-9)
    }

    @Test
    fun `RAW 默认测光方式只发给非直闪帧`() {
        val out = org.json.JSONObject(SpektraProcessor.withRawExposureMethod(base, false))
        assertEquals("balanced", out.getString("auto_exposure_method"))
        assertEquals("kodak_gold_200", out.getString("film_stock"))
        assertTrue(FilmStockMapper.ENGINE_KNOWN_KEYS.contains("auto_exposure_method"))
        // 直闪帧已 auto_exposure=false，曝光量拍摄时定死，不该再发测光方式
        assertEquals(base, SpektraProcessor.withRawExposureMethod(base, true))
    }

    @Test
    fun `RAW 默认测光方式重复注入不产生重复键`() {
        val once = SpektraProcessor.withRawExposureMethod(base, false)
        val twice = SpektraProcessor.withRawExposureMethod(once, false)
        assertEquals(1, Regex("\"auto_exposure_method\"").findAll(twice).count())
        assertEquals("balanced", org.json.JSONObject(twice).getString("auto_exposure_method"))
    }

    @Test
    fun `JPEG 共用的基础参数不带 RAW 专属测光方式`() {
        // v32 的 whole point：只改 RAW 分支参数链，不能动 buildParamsJson 基础串，
        // 否则 JPEG 通路观感会被一起改掉，违反「JPEG 回归旧水平」定调
        val json = FilmStockMapper.buildParamsJson("kodak_gold_200", "kodak_endura_premier")
        val o = org.json.JSONObject(json)
        assertFalse(o.has("auto_exposure_method"))
        assertFalse(o.has("auto_exposure"))
    }
}
