package com.filmcam.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 11-21 统一动效契约（源码扫描，防止后续改动把已验证的手感改回去）。
 *
 * 覆盖：
 *  - 设置面板读数切档滚字：只在 view attach 后滚、首帧零跳变（SettingsScrollContractTest 契约）；
 *  - 按键/LCD 背光：hapticClick 统一 pulse、LCD 自身可点；
 *  - PhotoViewer 面板进出走 Motion token；
 *  - 弹窗退场：关闭字牌与外部/返回键都走 dismissPanel，跳转型保持硬切。
 */
class MotionContractTest {

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

    private fun settingsFn(): String? =
        read("java/com/filmcam/app/MainActivity.kt")
            ?.substringAfter("private fun showSettings()", "")
            ?.substringBefore("\n    }", "")

    @Test
    fun `源码扫描必须真的跑到`() {
        assertNotNull("源码根路径要能解析到 app/src/main", sourceRoot())
        assertTrue("Motion.kt 要存在", read("java/com/filmcam/app/ui/Motion.kt") != null)
    }

    @Test
    fun `设置读数滚字只在 attach 后触发 首帧零跳变`() {
        val m = read("java/com/filmcam/app/ui/Motion.kt") ?: return
        assertTrue("要有 setTextWithRoll 扩展", m.contains("fun android.widget.TextView.setTextWithRoll"))
        assertTrue("必须判断 attach 状态（首次填充在 show 前，不许滚）",
            m.contains("if (!isAttachedToWindow || Motion.reduced(ctx)) return"))
        assertTrue("滚字用 ROLL token", m.contains("Motion.ROLL"))
        val fn = settingsFn() ?: return
        assertTrue("读数要接滚字", fn.contains("value(R.id.rowSetBody).setTextWithRoll"))
        assertTrue("全部读数都要接", fn.contains("value(R.id.rowSetAbout).setTextWithRoll"))
    }

    @Test
    fun `按键与 LCD 背光接线`() {
        val a = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val hc = a.substringAfter("private fun android.view.View.hapticClick", "")
            .substringBefore("\n    }", "")
        assertTrue("hapticClick 统一点亮 LCD", hc.contains("binding.lcdDisplay.pulseBacklight()"))
        assertTrue("LCD 自身可点", a.contains("binding.lcdDisplay.isClickable = true"))
        assertTrue("LCD 点击也 pulse", a.contains("binding.lcdDisplay.pulseBacklight()"))
        val lcd = read("java/com/filmcam/app/ui/T3LcdView.kt") ?: return
        assertTrue("背光时长已缩短到 1.8s", lcd.contains("BACKLIGHT_MS = 1800L"))
        assertTrue("淡出 350ms", lcd.contains("BACKLIGHT_FADE = 350L"))
    }

    @Test
    fun `PhotoViewer 面板进出统一 Motion token`() {
        val p = read("java/com/filmcam/app/PhotoViewerActivity.kt") ?: return
        assertTrue("入场用 PANEL_IN", p.contains("Motion.PANEL_IN"))
        assertTrue("退场用 PANEL_OUT", p.contains("Motion.PANEL_OUT"))
        assertTrue("入场 decelerate", p.contains("setInterpolator(Motion.decelerate)"))
        assertTrue("退场 accelerate", p.contains("setInterpolator(Motion.accelerate)"))
    }

    @Test
    fun `弹窗退场覆盖主动关闭与外部返回键`() {
        val m = read("java/com/filmcam/app/ui/Motion.kt") ?: return
        assertTrue("要有 dismissPanel", m.contains("fun android.app.Dialog.dismissPanel"))
        assertTrue("要有外部/返回键退场", m.contains("fun android.app.Dialog.attachOutsideDismiss"))
        assertTrue("外部触摸要判内容矩形", m.contains("event.rawX >= loc[0]"))
        assertTrue("返回键要拦", m.contains("KEYCODE_BACK"))
        assertTrue("不能改 canceledOnTouchOutside 契约之外的取消路径",
            m.contains("setCanceledOnTouchOutside(false)"))

        val fn = settingsFn() ?: return
        assertTrue("关闭字牌走退场", fn.contains("dialog.dismissPanel(view)"))
        assertTrue("外部/返回键接上", fn.contains("dialog.attachOutsideDismiss(view)"))
        // 跳转型三行必须保持硬切，否则会被切屏打断
        assertTrue("语言切档仍硬切", fn.indexOf("dialog.dismiss()") < fn.indexOf("AppLocale.cycle"))
    }
}
