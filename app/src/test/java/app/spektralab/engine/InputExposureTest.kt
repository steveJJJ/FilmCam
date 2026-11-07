package app.spektralab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v46 输入层曝光：曝光补偿从印相层（`print_exposure`，会让片框一起冲浓）
 * 搬到引擎前的像素增益。这里守住量纲 —— 增益必须在**线性域**施加。
 */
class InputExposureTest {

    @Test
    fun `0EV 是恒等映射`() {
        val t = InputExposure.lut(0.0)
        for (v in 0..255) {
            assertEquals("码值 $v 应原样返回", v, t[v])
        }
    }

    @Test
    fun `码值是单调不减的`() {
        for (ev in listOf(-1.5, -0.6, 0.0, 0.6, 1.5)) {
            val t = InputExposure.lut(ev)
            for (v in 1..255) {
                assertTrue("EV=$ev：$v 处出现反转", t[v] >= t[v - 1])
            }
        }
    }

    @Test
    fun `正 EV 提亮 负 EV 压暗`() {
        val plus = InputExposure.lut(0.6)
        val minus = InputExposure.lut(-0.6)
        // 中灰附近取样，避开两端截断
        for (v in listOf(64, 96, 128, 160, 192)) {
            assertTrue("+0.6EV 应更亮：$v → ${plus[v]}", plus[v] > v)
            assertTrue("-0.6EV 应更暗：$v → ${minus[v]}", minus[v] < v)
        }
    }

    @Test
    fun `增益落在线性域而不是码值域`() {
        // 这是 v39 在 RAW 通路验证过的量纲：+1EV 是线性光 ×2。
        // 若误写成码值乘 2（gamma 域），中灰 118 会变成 236 而不是 ~156。
        val one = InputExposure.lut(1.0)
        val lin = InputExposure.srgbToLinear(118) * 2.0
        val expect = InputExposure.linearToSrgb(lin)
        assertEquals(expect, one[118])
        assertTrue("+1EV 中灰应落在 150~165（实际 ${one[118]}）", one[118] in 150..165)
    }

    @Test
    fun `端点不越界`() {
        // 正 EV：白已经顶格，保持 255；负 EV：黑保持 0。另一端本来就会被压缩，不要求还原。
        for (ev in listOf(-2.0, -1.5, 1.5, 3.0)) {
            val t = InputExposure.lut(ev)
            assertTrue("表内不得越界（EV=$ev）", t.all { it in 0..255 })
        }
        for (ev in listOf(0.6, 1.5, 3.0)) {
            assertEquals("正 EV 白仍为白", 255, InputExposure.lut(ev)[255])
        }
        for (ev in listOf(-0.6, -1.5, -2.0)) {
            assertEquals("负 EV 黑仍为黑", 0, InputExposure.lut(ev)[0])
        }
    }

    @Test
    fun `sRGB 编解码往返`() {
        for (v in 0..255) {
            val back = InputExposure.linearToSrgb(InputExposure.srgbToLinear(v))
            assertTrue("$v → $back 往返误差应 ≤1", kotlin.math.abs(back - v) <= 1)
        }
    }

    @Test
    fun `店扫曝光倾向搬到输入层且取反`() {
        // 旧的 print_exposure 是「正 = 更暗」；输入 EV 是「正 = 更亮」，所以必须取反。
        val frontier = FilmStockMapper.inputEvFor(
            FilmStockMapper.DevSettings(outputStyle = FilmStockMapper.OutputStyle.FRONTIER)
        )
        val print = FilmStockMapper.inputEvFor(
            FilmStockMapper.DevSettings(outputStyle = FilmStockMapper.OutputStyle.PRINT)
        )
        val noritsu = FilmStockMapper.inputEvFor(
            FilmStockMapper.DevSettings(outputStyle = FilmStockMapper.OutputStyle.NORITSU)
        )
        assertTrue("Frontier 偏亮 → 输入 EV 为正（$frontier）", frontier > print)
        assertTrue("Noritsu 略收 → 输入 EV 为负（$noritsu）", noritsu < print)
    }

    @Test
    fun `用户补偿被钳在允许区间`() {
        val hi = FilmStockMapper.inputEvFor(FilmStockMapper.DevSettings(exposureEv = 9f))
        val lo = FilmStockMapper.inputEvFor(FilmStockMapper.DevSettings(exposureEv = -9f))
        assertEquals(FilmStockMapper.INPUT_EV_MAX + 0.3f, hi, 1e-6f)
        assertEquals(FilmStockMapper.INPUT_EV_MIN - 0.3f, lo, 1e-6f)
    }
}
