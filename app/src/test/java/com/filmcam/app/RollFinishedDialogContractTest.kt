package com.filmcam.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 胶卷拍满「去冲洗」弹窗契约（2026-11-22 用户反馈系统 AlertDialog「比较丑」后重做）。
 *
 * 锁死：机身铭牌布局（不再用系统标题+消息+按钮）、36 格曝光条、
 * 入场/退场动效接线、跳转型主键硬切、宽度 0.78 口径。
 */
class RollFinishedDialogContractTest {

    @Test
    fun `拍满弹窗是机身铭牌布局 带胶卷条与动效`() {
        val layout = read("res/layout/dialog_roll_finished.xml") ?: return
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val zh = read("res/values/strings.xml") ?: return
        val en = read("res/values-en/strings.xml") ?: return

        assertTrue(
            "布局必须有 T3 字标 + ROLL FULL 蚀刻码 + 36 格曝光条容器 + 双 pill 按钮",
            layout.contains("@+id/ivRollFullLogo") &&
                layout.contains("@string/main_roll_finished_code") &&
                layout.contains("@+id/rollStripFrames") &&
                layout.contains("@+id/tvRollDevelop") &&
                layout.contains("@+id/tvRollLater") &&
                layout.contains("36 格曝光窗由代码填充")
        )
        assertTrue(
            "按钮复用铭牌 pill 底（灰键 + 暖色主键），不是系统按钮",
            layout.contains("@drawable/bg_nameplate_chip") &&
                layout.contains("@drawable/bg_edit_choice")
        )
        assertTrue(
            "中英都要有 ROLL FULL 蚀刻码文案",
            zh.contains("name=\"main_roll_finished_code\"") &&
                en.contains("name=\"main_roll_finished_code\"")
        )
        assertTrue(
            "MainActivity 必须用自定义布局并填充 36 格曝光窗（repeat(36)）",
            main.contains("R.layout.dialog_roll_finished") &&
                main.contains("R.id.rollStripFrames") &&
                main.contains("repeat(36)")
        )
        assertTrue(
            "不得再用系统 setTitle/setMessage 组装这个弹窗",
            !Regex("AlertDialog[\\s\\S]{0,400}main_roll_finished_title\\)\\)\\s*\\n\\s*\\.setMessage")
                .containsMatchIn(main)
        )
    }

    @Test
    fun `拍满弹窗动效接线 入场退场齐全 主键硬切`() {
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val start = main.indexOf("private fun promptDevelopFullRoll()")
        assertTrue("函数必须存在", start >= 0)
        val end = main.indexOf("private fun", start + 10)
        val body = main.substring(start, if (end > start) end else main.length)

        assertTrue(
            "入场 animatePanelIn；稍后 dismissPanel 退场；点外部/返回 attachOutsideDismiss",
            body.contains("view.animatePanelIn()") &&
                body.contains("dialog.dismissPanel(view)") &&
                body.contains("dialog.attachOutsideDismiss(view)")
        )
        assertTrue(
            "「去冲洗」是跳转型（startDevelopment），必须 dialog.dismiss() 硬切，不走 dismissPanel",
            body.contains("DevelopController.startDevelopment") &&
                Regex("tvRollDevelop[\\s\\S]{0,300}dialog\\.dismiss\\(\\)").containsMatchIn(body)
        )
        assertTrue(
            "守卫 guardDialog 保留（触摸守卫单槽）",
            body.contains("dialog.guardDialog()")
        )
        assertTrue(
            "宽度口径 0.78×屏宽 ≤400dp（弹窗宽度契约）",
            body.contains("dm.widthPixels * 0.78f") && body.contains("400 * dm.density")
        )
    }

    private fun read(rel: String): String? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val d = dir ?: return null
            val main = File(d, "app/src/main")
            if (main.isDirectory) {
                val f = File(main, rel)
                if (f.isFile) return f.readText()
            }
            dir = d.parentFile
        }
        return null
    }
}
