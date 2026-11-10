package com.filmcam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackMailTest {

    private val template = "遇到的问题：\n（简单描述）\n\n—— 以下信息自动生成 ——\nApp 版本：v1.0.0 (67)\n"
    private val header = "—— 设备诊断报告（自动生成）——"
    private val report = "[device]\ndevice.model = SM-S9380\n\n[window]\nwindow.density = 2.625"

    @Test
    fun `模板与诊断报告都在正文里且顺序正确`() {
        val body = FeedbackMail.composeBody(template, header, report)
        assertTrue(body.startsWith("遇到的问题："))
        assertTrue(body.contains("App 版本：v1.0.0 (67)"))
        assertTrue(body.contains(header))
        assertTrue(body.endsWith("window.density = 2.625"))
        assertTrue(body.indexOf("App 版本") < body.indexOf("[device]"))
        assertTrue(body.indexOf(header) < body.indexOf("[device]"))
    }

    @Test
    fun `模板尾部换行归一化不出现三重空行`() {
        val body = FeedbackMail.composeBody(template + "\n\n\n", header, report)
        assertFalse(body.contains("\n\n\n"))
        assertTrue(body.contains("App 版本：v1.0.0 (67)\n\n$header\n[device]"))
    }

    @Test
    fun `报告内部换行与分组标题原样保留`() {
        val body = FeedbackMail.composeBody(template, header, report)
        assertTrue(body.contains("device.model = SM-S9380\n\n[window]"))
    }

    @Test
    fun `报告为空或全空白时不追加段落`() {
        assertEquals(template.trimEnd(), FeedbackMail.composeBody(template, header, ""))
        assertEquals(template.trimEnd(), FeedbackMail.composeBody(template, header, "  \n \t "))
        assertFalse(FeedbackMail.composeBody(template, header, "").contains(header))
    }

    @Test
    fun `标题为空时只留一个空行分隔`() {
        val body = FeedbackMail.composeBody(template, "", report)
        assertTrue(body.contains("App 版本：v1.0.0 (67)\n\n[device]"))
        assertFalse(body.contains("—— 设备诊断报告"))
    }

    @Test
    fun `报告首尾空白被裁掉但内容不变`() {
        val body = FeedbackMail.composeBody(template, header, "\n\n  $report  \n\n")
        assertTrue(body.endsWith("window.density = 2.625"))
        assertTrue(body.contains("[device]\ndevice.model"))
    }
}
