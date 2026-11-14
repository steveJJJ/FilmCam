package com.filmcam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v81：RAW 底片开关对外开放（从工程师控制台搬到设置面板）的接线契约。
 *
 * 交互口径与「冲洗画质 / 色彩浓度」同款：就地翻转、面板不关；
 * 与工程师控制台共用同一个 prefs 键 filmcam_dev_raw，哪里拨都生效；
 * 不支持 RAW_SENSOR 的机型不翻转、从 LCD 告知（lcd_notice_raw_unavailable）。
 * 生效时机 = 立即下发：rawImageReader 在会话建立时常建（与开关无关），
 * 开关只决定拍照请求挂不挂 RAW 目标，所以不需要重启相机。
 */
class SettingsRawRowTest {

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
    fun `布局行存在且顺序在色彩浓度与关于之间`() {
        val xml = read("res/layout/dialog_settings.xml") ?: return
        assertTrue("rowSetRaw 行要在", xml.contains("@+id/rowSetRaw"))
        val idxColor = xml.indexOf("@+id/rowSetColor")
        val idxRaw = xml.indexOf("@+id/rowSetRaw")
        val idxAbout = xml.indexOf("@+id/rowSetAbout")
        assertTrue("rowSetRaw 必须排在 rowSetColor 之后 rowSetAbout 之前", idxColor in 0 until idxRaw && idxRaw in 0 until idxAbout)
    }

    @Test
    fun `showSettings 的 rows 数组与 set_labels 顺序对齐`() {
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val rowsFn = main.substringAfter("val rows = intArrayOf(", "")
            .substringBefore(")", "")
        val rowOrder = listOf(
            "rowSetBody", "rowSetDatestamp", "rowSetHaptic", "rowSetWb", "rowSetLanguage",
            "rowSetQuality", "rowSetFrame", "rowSetColor", "rowSetRaw", "rowSetAbout"
        )
        rowOrder.forEach { id ->
            assertTrue("rows 数组缺 $id", rowsFn.contains("R.id.$id"))
        }
        val idxRaw = rowsFn.indexOf("rowSetRaw")
        val idxAbout = rowsFn.indexOf("rowSetAbout")
        assertTrue("rows 数组里 rowSetRaw 要在 rowSetAbout 之前", idxRaw in 0 until idxAbout)

        val zh = read("res/values/strings.xml") ?: return
        val en = read("res/values-en/strings.xml") ?: return
        assertEquals("中英 set_labels 元素数必须一致（rows 按 index 对齐 labels）",
            Regex("<item>@string/").findAll(zh.substringAfter("set_labels", "").substringBefore("</string-array>")).count(),
            Regex("<item>@string/").findAll(en.substringAfter("set_labels", "").substringBefore("</string-array>")).count())
        assertTrue("中文 set_raw 文案要在", zh.contains("name=\"set_raw\""))
        assertTrue("英文 set_raw 文案要在", en.contains("name=\"set_raw\""))
    }

    @Test
    fun `点击就地翻转 写 filmcam_dev_raw 并立即下发 不支持不翻`() {
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val handler = main.substringAfter("row(R.id.rowSetRaw).hapticClick {", "")
            .substringBefore("row(R.id.rowSetAbout).hapticClick", "")
        assertTrue("rowSetRaw 点击处理器要在", handler.isNotBlank())
        assertTrue("不支持机型必须先守 rawSensorAvailable 再翻",
            handler.indexOf("rawSensorAvailable()") in 0 until handler.indexOf("rawNegativeEnabled = !rawNegativeEnabled"))
        assertTrue("不支持的提示必须走 LCD（与 onResume 同口径）",
            handler.contains("lcd_notice_raw_unavailable"))
        assertTrue("翻转要写回工程师控制台同一个 prefs 键",
            handler.contains("putBoolean(\"filmcam_dev_raw\""))
        assertTrue("要立即下发 cameraController（rawImageReader 常建，无需重启相机）",
            handler.contains("cameraController?.rawNegativeEnabled = rawNegativeEnabled"))
        assertTrue("翻转后要 refreshValues（就地翻转口径）", handler.contains("refreshValues()"))
    }

    @Test
    fun `读数口径 不支持刻不支持 开关刻开或关`() {
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val fn = main.substringAfter("private fun rawSettingName()", "")
            .substringBefore("\n    }", "")
        assertTrue("不支持要如实刻（raw_unavailable）", fn.contains("raw_unavailable"))
        assertTrue("支持时按开关刻开/关", fn.contains("common_on") && fn.contains("common_off"))
        assertTrue("能力探测要与工程师控制台同口径（RAW_SENSOR 输出档）",
            main.contains("getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)"))
    }
}
