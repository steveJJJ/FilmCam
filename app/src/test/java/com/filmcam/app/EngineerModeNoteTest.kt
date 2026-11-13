package com.filmcam.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 11-14 用户「工程师模式下面的说明关掉」的源码契约。
 *
 * 只关**顶部「工程师模式」开关正下方那一行灰字**（`eng_mode_note`），
 * 不是关掉整个控制台、也不是关掉其它设置项的 hint。所以除了断言它消失，
 * 还要反过来钉住开关本身与其余说明都还在 —— 避免误删扩大。
 */
class EngineerModeNoteTest {

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
    fun `顶部开关下的 eng_mode_note 说明已关掉`() {
        assertNotNull("源码扫描必须真的跑到，不能静默跳过", sourceRoot())
        val layout = read("res/layout/dialog_hidden_settings.xml") ?: return
        assertFalse("布局里不能再引用 @string/eng_mode_note",
            layout.contains("@string/eng_mode_note"))
        val zh = read("res/values/strings.xml") ?: return
        val en = read("res/values-en/strings.xml") ?: return
        assertFalse("中文文案要一起删（中英同步）", zh.contains("name=\"eng_mode_note\""))
        assertFalse("英文文案要一起删（中英同步）", en.contains("name=\"eng_mode_note\""))
    }

    @Test
    fun `工程师模式开关本身与其它说明都保留`() {
        val layout = read("res/layout/dialog_hidden_settings.xml") ?: return
        assertTrue("开关必须还在", layout.contains("android:id=\"@+id/swEngineerMode\""))
        assertTrue("开关标题必须还在", layout.contains("@string/eng_mode_title"))
        assertTrue("说明消失后要补底部留白，别让分隔线贴住开关",
            layout.contains("android:paddingBottom=\"12dp\""))
        // 其它说明：RAW 负片 / 开发调试 / 设置行 hint（在 AboutActivity 里绑定）
        assertTrue(layout.contains("@string/eng_raw_hint"))
        assertTrue(layout.contains("@string/main_debug_note"))
        val zh = read("res/values/strings.xml") ?: return
        for (key in listOf("eng_slow_hint", "eng_style_hint", "eng_dev_hint",
            "eng_lens_hint", "eng_cc_hint", "eng_jpeg_norm_hint",
            "eng_raw_hint", "main_debug_note", "eng_mode_title",
            "eng_mode_on_toast", "eng_mode_off_toast")) {
            assertTrue("不该顺手删掉 $key", zh.contains("name=\"$key\""))
        }
        val en = read("res/values-en/strings.xml") ?: return
        for (key in listOf("eng_slow_hint", "eng_raw_hint", "main_debug_note",
            "eng_mode_title", "eng_mode_on_toast", "eng_mode_off_toast")) {
            assertTrue("英文不该顺手删掉 $key", en.contains("name=\"$key\""))
        }
    }

    @Test
    fun `工程师模式控制台入口与行 hint 绑定没有被撤掉`() {
        val about = read("java/com/filmcam/app/AboutActivity.kt") ?: return
        assertTrue("控制台入口必须在", about.contains("showEngineerConsole()"))
        assertTrue("6 行设置项说明仍要绑定", about.contains("tvSetHint"))
        assertTrue("布局仍要 inflate", about.contains("R.layout.dialog_hidden_settings"))
    }
}
