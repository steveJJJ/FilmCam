package app.spektralab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎原生日期印字（`date_imprint_*`）的契约。
 *
 * 背景（11-18）：日期原本在 App 侧用 Canvas 手绘，用户连着两轮反馈「颜色/撇号/段间隙
 * 都不对」「像贴在成片上」。根因是它压根不该手绘——引擎自带 `spk::DateImprintParams`
 * 与 imprint 渲染（自带字体、当额外曝光印进成像链路）。这里锁住参数形状，
 * 防止有人再退回手绘、或写错键名导致引擎整帧硬失败（引擎对未知键是直接报错）。
 */
class DateImprintTest {

    @Test
    fun `参数键名与取值形状必须是引擎认的那一套`() {
        val d = DateImprint(text = "'26 11 18")
        val map = d.jsonEntries().toMap()
        assertEquals("true", map["date_imprint_active"])
        assertEquals("\"'26 11 18\"", map["date_imprint_text"])
        assertEquals("\"lcd\"", map["date_imprint_style"])
        assertEquals("\"frame\"", map["date_imprint_placement"])
        assertEquals("\"br\"", map["date_imprint_corner"])
        assertTrue("字号系数要发数字", map["date_imprint_size"]!!.toFloat() > 0f)
        assertTrue("内缩要发数字（mm）", map["date_imprint_inset_x"]!!.toFloat() >= 0f)
        assertTrue(map["date_imprint_inset_y"]!!.toFloat() >= 0f)
        assertTrue("曝光要发数字", map["date_imprint_ev"]!!.toFloat() > 0f)
    }

    @Test
    fun `注入日期不会破坏原有参数串`() {
        // 不用 org.json 解析：JVM 单测的 android.jar 是 mockable 空壳，org.json 不可用
        val base = """{"film_stock":"kodak_gold_200","print_exposure":0.83}"""
        val out = DateImprint.inject(base, DateImprint.t3Style("'26 11 18"))!!
        assertTrue("原参数必须保留", out.contains(""""film_stock":"kodak_gold_200""""))
        assertTrue("原参数必须保留", out.contains(""""print_exposure":0.83"""))
        assertTrue("必须是合法收尾", out.trimEnd().endsWith("}"))
        assertTrue(out.contains(""""date_imprint_active":true"""))
        assertTrue(out.contains(""""date_imprint_text":"'26 11 18""""))
        // 花括号只应有一对（拼接没把 JSON 弄坏）
        assertEquals("花括号必须配对", 1, out.count { it == '{' })
        assertEquals(1, out.count { it == '}' })
    }

    @Test
    fun `日期关闭时原样返回 不发任何日期键`() {
        val base = """{"film_stock":"x"}"""
        assertEquals(base, DateImprint.inject(base, null))
        assertEquals(null, DateImprint.inject(null, DateImprint.t3Style("'26 1 1")))
    }

    @Test
    fun `机背格式 两位年带撇号 月日不补零`() {
        assertEquals("'26 11 18", DateImprint.textFor(2026, 11, 18))
        assertEquals("'21 5 29", DateImprint.textFor(2021, 5, 29))
        assertEquals("'05 1 1", DateImprint.textFor(2005, 1, 1))
    }

    @Test
    fun `T3 参数落在真机区间 且不能是手绘那套`() {
        val d = DateImprint.t3Style("'21 5 29")
        assertEquals("lcd", d.style)
        assertEquals("frame", d.placement)
        assertEquals("br", d.corner)
        // 字高 = 1.3mm × size；真机实测约 1.0~1.1mm
        val hMm = 1.3f * d.size
        assertTrue("字高应在 0.9~1.2mm，实际 $hMm", hMm in 0.9f..1.2f)
        assertTrue("右内缩应在 2~5mm，实际 ${d.insetXMm}", d.insetXMm in 2f..5f)
        assertTrue("底内缩应在 0.8~3mm，实际 ${d.insetYMm}", d.insetYMm in 0.8f..3f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `未知风格被拒 不能把错误值发给引擎`() {
        DateImprint(text = "'26 1 1", style = "seven-seg")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `未知落位被拒`() {
        DateImprint(text = "'26 1 1", placement = "outside")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `未知角被拒`() {
        DateImprint(text = "'26 1 1", corner = "middle")
    }
}
