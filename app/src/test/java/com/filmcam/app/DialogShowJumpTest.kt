package com.filmcam.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v81：设置面板 / 反馈弹窗「点开跳一下」修复契约（11-14 S25U 真机反馈）。
 *
 * 根因：`setOnShowListener` 里才 `setLayout(0.86 屏宽)` + 换 `panelDrawable()` ——
 * show() 首帧先按窗口默认宽度（更宽）+ Material 大圆角卡片渲染，第二帧才跳到
 * 铭牌形态。修法：create() 后 window 已存在，把尺寸/背景设定移到 show() 之前，
 * 首帧即最终形态。
 *
 * 守护两条：①这两处不得再出现 setOnShowListener 改布局的写法；
 * ②setLayout 必须排在 dialog.show() 之前。
 */
class DialogShowJumpTest {

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
    fun `设置面板尺寸背景必须在 show 之前定死`() {
        val s = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val fn = s.substringAfter("private fun showSettings()", "")
            .substringBefore("\n    }", "")
        assertTrue("设置面板函数要存在", fn.isNotBlank())
        assertFalse("设置面板不得在 OnShowListener 里改布局（首帧按默认宽度渲染一帧再跳变）",
            fn.contains("setOnShowListener"))
        assertTrue("宽度 0.86 屏宽口径要在", fn.contains("widthPixels * 0.86f"))
        assertTrue("背景要换机加工面板", fn.contains("panelDrawable()"))
        val setIdx = fn.indexOf("setLayout(")
        val showIdx = fn.indexOf("dialog.show()")
        assertTrue("必须真的调用 show()", showIdx > 0)
        assertTrue("setLayout 必须在 show() 之前（实际 set=$setIdx show=$showIdx）",
            setIdx in 0 until showIdx)
    }

    @Test
    fun `反馈弹窗同款 不得在 OnShowListener 里改布局`() {
        val s = read("java/com/filmcam/app/AboutActivity.kt") ?: return
        assertFalse("AboutActivity 全文不得再用 setOnShowListener 改弹窗布局",
            s.contains("setOnShowListener"))
    }
}
