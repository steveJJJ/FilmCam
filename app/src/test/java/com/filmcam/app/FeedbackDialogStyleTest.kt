package com.filmcam.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v81：问题反馈弹窗的「设计语言统一」契约。
 *
 * 背景：以前 showFeedbackOptions() 用系统 AlertDialog 的 setItems 纯文字列表，
 * 和设置面板 / 关于页的机身铭牌风格（2dp 圆角外框、1dp 蚀刻线、等宽读数、
 * 副蚀刻小字、BodyTheme 主题映射）完全脱节，显得简陋。
 * 这里钉住「换成自定义铭牌面板」这件事，防回退。
 */
class FeedbackDialogStyleTest {

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
    fun `反馈弹窗不再是系统纯文字列表`() {
        val about = read("java/com/filmcam/app/AboutActivity.kt") ?: return
        val body = about.substringAfter("private fun showFeedbackOptions() {", "")
            .substringBefore("/** v67", "")
        assertFalse("不得再用 setItems 纯文字列表", body.contains(".setItems("))
        assertFalse("不得再用系统默认标题栏", body.contains(".setTitle("))
        assertTrue("必须 inflate 铭牌面板", body.contains("R.layout.dialog_feedback"))
        assertTrue("必须走机身弹窗主题", body.contains("theme.dialogTheme"))
        assertTrue("必须换掉 Material 大圆角外框", body.contains("theme.panelDrawable()"))
        assertTrue("必须做整棵 View 树的主题重映射", body.contains("theme.applyToViewTree(view)"))
    }

    @Test
    fun `两个渠道仍是原来的邮件与微信且各带触感`() {
        val about = read("java/com/filmcam/app/AboutActivity.kt") ?: return
        val body = about.substringAfter("private fun showFeedbackOptions() {", "")
            .substringBefore("/** v67", "")
        assertTrue("邮件渠道还在", body.contains("R.string.about_feedback_email_channel"))
        assertTrue("微信渠道还在", body.contains("R.string.about_feedback_wechat_channel"))
        assertTrue("点邮件仍拉起 sendFeedback", body.contains("sendFeedback()"))
        assertTrue("点微信仍复制微信号", body.contains("copyWechatId()"))
        assertTrue("两行都要触感", Regex("tick\\(\\)").findAll(body).count() >= 3)
    }

    @Test
    fun `铭牌面板沿用设置面板的行样式与蚀刻线`() {
        val xml = read("res/layout/dialog_feedback.xml") ?: return
        assertTrue("面板底必须参与主题映射", xml.contains("android:background=\"#1A1A1A\""))
        val rows = Regex("layout=\"@layout/item_setting_row\"").findAll(xml).count()
        assertTrue("两行渠道必须复用 item_setting_row，实际 $rows 行", rows == 2)
        assertTrue("必须有 1dp 蚀刻分隔线", xml.contains("android:background=\"#26262A\""))
        assertFalse("铭牌面板不许出现系统列表控件",
            xml.contains("ListView") || xml.contains("RecyclerView"))
        assertTrue("要有铭牌头字标", xml.contains("@drawable/ic_t3_logo"))
        assertTrue("要有英文副题（等宽小字）", xml.contains("@string/about_feedback_subtitle"))
        assertTrue("要有副蚀刻说明", xml.contains("@string/about_feedback_hint"))
        assertTrue("要有底部诊断说明", xml.contains("@string/about_feedback_note"))
    }

    @Test
    fun `中英文案齐备且旧渠道名去掉了括号说明`() {
        val keys = listOf(
            "about_feedback_subtitle", "about_feedback_hint",
            "about_feedback_mail_hint", "about_feedback_wechat_hint", "about_feedback_note",
        )
        listOf("values/strings.xml", "values-en/strings.xml").forEach { f ->
            val s = read("res/$f") ?: return
            keys.forEach { k ->
                assertTrue("$f 缺 $k", s.contains("<string name=\"$k\">"))
            }
            assertTrue("$f 的邮件渠道名不该再带括号说明",
                !Regex("about_feedback_email_channel\">[^<]*[（(]").containsMatchIn(s))
        }
    }
}
