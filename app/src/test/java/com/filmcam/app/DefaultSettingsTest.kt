package com.filmcam.app

import app.spektralab.engine.FilmStockMapper
import app.spektralab.engine.JpegNormalizer
import com.filmcam.app.camera.FilmExposure
import com.filmcam.app.develop.ColorStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用户 2026-11-13 指定的七项新装默认档位契约。
 *
 * 默认值是「prefs 里没有该键时」才生效的读时回落，老用户已存过的档位绝不能被覆盖。
 * 所以除了断言常量本身，还要用源码扫描钉住**所有读取点**都引用同一批常量 ——
 * 漏改一处就会出现「设置面板显示淡、冲洗时却按 0.7 标准档」这种最难查的偏差。
 */
class DefaultSettingsTest {

    private fun sourceRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val d = dir ?: return null
            val main = File(d, "app/src/main")
            if (main.isDirectory) return main
            dir = d.parentFile
        }
        return null
    }

    private fun read(rel: String): String? {
        val root = sourceRoot() ?: return null
        return File(root, rel).takeIf { it.isFile }?.readText()
    }

    @Test
    fun `七项默认值与用户指定一致`() {
        assertEquals("白边相框", FilmStockMapper.FrameFormat.WHITE, DefaultSettings.frameFormat)
        assertEquals("Frontier 店扫", FilmStockMapper.OutputStyle.FRONTIER, DefaultSettings.outputStyle)
        assertEquals("慢门自然", FilmExposure.Mode.NATURAL, DefaultSettings.slowMode)
        assertEquals("真实派 + 轻随机", FilmExposure.Trigger.REAL_MIX, DefaultSettings.slowTrigger)
        assertEquals(2, DefaultSettings.SOFT_FOCUS_PROB)
        assertTrue("概率 >0 必须同步开启模拟失焦，否则面板显示 2% 却永不触发",
            DefaultSettings.SOFT_FOCUS_ENABLED)
        assertEquals("色彩浓度淡 = 滑杆满档", 1f, DefaultSettings.colorStrength, 1e-6f)
        assertFalse("日期水印默认关闭（11-13 用户指定）", DefaultSettings.DATE_STAMP_ENABLED)
    }

    @Test
    fun `默认档落在各值域内且没有把标准档吃掉`() {
        // 色彩浓度三档必须还是三个不同值（改默认不能让 STANDARD 与 SOFT 重合）
        assertEquals(JpegNormalizer.DEFAULT_STRENGTH, ColorStrength.STANDARD, 1e-6f)
        assertEquals(JpegNormalizer.MAX_STRENGTH, ColorStrength.SOFT, 1e-6f)
        assertTrue(ColorStrength.STANDARD > ColorStrength.ORIGINAL)
        assertTrue(ColorStrength.SOFT > ColorStrength.STANDARD)
        assertEquals(ColorStrength.Level.SOFT, ColorStrength.levelOf(DefaultSettings.colorStrength))

        // 引擎域常量与聚合表同源
        assertEquals(FilmStockMapper.DEFAULT_FRAME_FORMAT, DefaultSettings.frameFormat)
        assertEquals(FilmStockMapper.DEFAULT_OUTPUT_STYLE, DefaultSettings.outputStyle)
        assertEquals(JpegNormalizer.DEFAULT_LEVEL_STRENGTH, DefaultSettings.colorStrength, 1e-6f)
        assertEquals(FilmExposure.DEFAULT_MODE, DefaultSettings.slowMode)
        assertEquals(FilmExposure.DEFAULT_TRIGGER, DefaultSettings.slowTrigger)
    }

    @Test
    fun `自检摘要七项齐全`() {
        val m = DefaultSettings.summary()
        assertEquals(8, m.size)
        assertEquals("white", m["frameFormat"])
        assertEquals("frontier", m["outputStyle"])
        assertEquals("NATURAL", m["slowMode"])
        assertEquals("REAL_MIX", m["slowTrigger"])
        assertEquals(1f, m["colorStrength"] as Float, 1e-6f)
        assertEquals(2, m["softFocusProb"])
        assertEquals(true, m["softFocusEnabled"])
        assertEquals(false, m["dateStampEnabled"])
    }

    @Test
    fun `所有读取点都引用默认常量 没有残留旧硬编码`() {
        assertNotNull("源码扫描必须真的跑到，不能静默跳过", sourceRoot())
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val about = read("java/com/filmcam/app/AboutActivity.kt") ?: return
        val mapper = read("java/app/spektralab/engine/FilmStockMapper.kt") ?: return
        val cs = read("java/com/filmcam/app/develop/ColorStrength.kt") ?: return
        val dev = read("java/com/filmcam/app/develop/DevelopmentService.kt") ?: return

        // 旧默认：慢门关(0) / 纯概率派 / 失焦概率 40 或 0 / 片框关 / 印相 / 0.7
        for ((name, src) in listOf("MainActivity" to main, "AboutActivity" to about)) {
            assertFalse("$name 仍有 slowShutter 默认 0",
                src.contains("getInt(\"slowShutter\", 0)"))
            assertFalse("$name 仍有 slowTrigger 默认 PROBABILITY",
                src.contains("\"slowTrigger\", com.filmcam.app.camera.FilmExposure.Trigger.PROBABILITY.prefs") &&
                    !src.contains("DefaultSettings.slowTrigger.prefs"))
            assertFalse("$name 仍有 softFocus_prob 默认 40/0",
                src.contains("\"softFocus_prob\", if ("))
            assertFalse("$name 仍有 softFocus 默认 false",
                src.contains("getBoolean(\"softFocus\", false)"))
            assertFalse("$name 仍有 JPEG 归一化默认 0.7",
                src.contains("JpegNormalizer.DEFAULT_STRENGTH") ||
                    src.contains("app.spektralab.engine.JpegNormalizer.DEFAULT_STRENGTH"))
            assertTrue("$name 必须引用 DefaultSettings", src.contains("DefaultSettings."))
        }
        assertFalse("FilmStockMapper 仍有输出风格默认 PRINT",
            mapper.contains("\"filmcam_output_style\", OutputStyle.PRINT.prefs"))
        assertFalse("FilmStockMapper 仍有片框默认 OFF",
            mapper.contains("FrameFormat.FULL else FrameFormat.OFF"))
        assertTrue("FilmStockMapper 必须用 DEFAULT_LEVEL_STRENGTH",
            mapper.contains("\"filmcam_dev_jpeg_normalize\", JpegNormalizer.DEFAULT_LEVEL_STRENGTH"))
        assertTrue("ColorStrength.load 必须回落到 DEFAULT_VALUE",
            cs.contains(".getFloat(PREF, DEFAULT_VALUE)"))

        // 11-13 追加：日期水印默认关，拍摄侧 + 冲洗侧三个读点必须同源
        for ((name, src) in listOf("MainActivity" to main, "DevelopmentService" to dev)) {
            assertFalse("$name 仍有日期水印默认开",
                src.contains("getBoolean(\"dateStamp\", true)"))
            assertTrue("$name 必须引用 DefaultSettings.DATE_STAMP_ENABLED",
                src.contains("DefaultSettings.DATE_STAMP_ENABLED"))
        }
        assertFalse("日期水印默认值不能写死在源码里",
            main.contains("private var dateStampEnabled = false") ||
                main.contains("private var dateStampEnabled = true"))
    }
}
