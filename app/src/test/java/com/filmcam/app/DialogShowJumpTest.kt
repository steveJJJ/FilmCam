package com.filmcam.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v81 → vc84：设置面板 / 反馈弹窗 / 快速上手卡的「零跳变 + 宽度」契约。
 *
 * v81 根因：`setOnShowListener` 里才 setLayout + 换 panelDrawable —— show()
 * 首帧先按窗口默认宽度（更宽）+ Material 大圆角卡片渲染，第二帧才跳到铭牌形态。
 * 当时把尺寸/背景移到 show() 之前，首帧即最终形态。
 *
 * ⚠️ vc84 真机窗口 dump：Dialog 主题 windowIsFloating=true，show() 时
 * PhoneWindow.generateLayout()（installDecor 内）会 setLayout(WRAP, WRAP)
 * 把 show() 前设的宽度静默重置 —— S25U mAttrs=(wrapxwrap)、
 * Requested w=1024px，0.86→0.78 根本没生效，侧滑起手仍落进设置行。
 * ❌ 否决：固定宽内容容器 → 窗口 1024 / 内容 842 左对齐、右边空 182px。
 * ✅ 定稿：create() 后先取 decorView 触发 installDecor，再 setLayout(w)，
 * 之后 show() 不再重置；窗口即 842px、内容 match_parent 填满，零跳变。
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
        assertTrue("宽度按 78% 屏宽口径（给侧滑留边）", fn.contains("widthPixels * 0.78f"))
        assertTrue("背景要换机加工面板", fn.contains("panelDrawable()"))
        assertTrue("必须先取 decorView 触发 installDecor，再 setLayout 才不会被重置",
            fn.contains("decorView") && fn.contains("setLayout(w,"))
        assertTrue("宽度必须下发给窗口、内容 View 保持 match_parent 填满",
            fn.contains(".setView(view)") && !fn.contains(".setView(panel)"))
        assertTrue("必须真的调用 show()", fn.contains("dialog.show()"))
    }

    /**
     * 11-14 真机反馈：86% 屏宽时每侧只留 29dp，而设置行是
     * match_parent + clickable —— 系统/三星侧滑返回起手稍靠内就落进设置行，
     * 返回手势被消费或误触就地翻转。收到 78%（每侧约 45dp）留足缓冲。
     * ⚠️ MainActivity.kt 是 CRLF 行尾，跨行锚易静默失配，这里只用单行断言。
     */
    @Test
    fun `设置面板必须给侧滑返回留边 宽度不超过 80 屏宽`() {
        val s = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val fn = s.substringAfter("private fun showSettings()", "")
            .substringBefore("\n    }", "")
        assertTrue("设置面板宽度收到 78% 屏宽（每侧约 45dp）",
            fn.contains("widthPixels * 0.78f"))
        assertFalse("不许退回 86% 宽口径（每侧仅 29dp 会误触设置行）",
            fn.contains("widthPixels * 0.86f"))
        assertTrue("横屏仍要保留 400dp 宽度上限",
            fn.contains("400 * dm.density"))
    }

    @Test
    fun `反馈弹窗同款 不得在 OnShowListener 里改布局`() {
        val s = read("java/com/filmcam/app/AboutActivity.kt") ?: return
        assertFalse("AboutActivity 全文不得再用 setOnShowListener 改弹窗布局",
            s.contains("setOnShowListener"))
    }

    /**
     * 11-14：设置面板 86% 屏宽（每侧仅 29dp）导致侧滑返回误触设置行。
     * 三处铭牌弹窗共用同一套宽度口径，必须一起收窄，别只改一处留下同类风险。
     * ⚠️ 源码级契约只用单行断言（MainActivity.kt 是 CRLF，跨行锚会静默失配）。
     */
    @Test
    fun `三处铭牌弹窗统一 78 屏宽 给侧滑返回留缓冲`() {
        val targets = listOf(
            "java/com/filmcam/app/MainActivity.kt" to "设置面板",
            "java/com/filmcam/app/AboutActivity.kt" to "反馈弹窗",
            "java/com/filmcam/app/ui/QuickStartCard.kt" to "快速上手卡",
        )
        targets.forEach { (rel, name) ->
            val s = read(rel) ?: return@forEach
            assertTrue("$name 宽度必须收到 78% 屏宽（每侧约 45dp）",
                s.contains("widthPixels * 0.78f"))
            assertFalse("$name 不许退回 86% 宽口径（每侧仅 29dp 会误触）",
                s.contains("widthPixels * 0.86f"))
            assertTrue("$name 横屏仍要保留 400dp 宽度上限",
                s.contains("400 * dm.density"))
        }
    }

    /**
     * ⚠️ installDecor() 会把 show() 前设的 attrs.width 重置成 wrap_content。
     * 顺序契约：先 `decorView`（触发 installDecor）→ 再 `setLayout(w, ...)`
     * → 才 `show()`；内容 View 保持 setView(view) 由窗口宽度撑满。
     * 不许回到固定宽容器（会右空 182px），也不许只设一次宽度（会被重置）。
     */
    @Test
    fun `三处弹窗必须先触发 installDecor 再下发窗口宽度`() {
        val targets = listOf(
            "java/com/filmcam/app/MainActivity.kt" to "设置面板",
            "java/com/filmcam/app/AboutActivity.kt" to "反馈弹窗",
            "java/com/filmcam/app/ui/QuickStartCard.kt" to "快速上手卡",
        )
        targets.forEach { (rel, name) ->
            val s = read(rel) ?: return@forEach
            assertTrue("$name 必须先取 decorView 触发 installDecor",
                s.contains("decorView"))
            assertTrue("$name 必须下发窗口宽度 w",
                s.contains("setLayout(w,"))
            assertTrue("$name 内容 View 必须保持原始 setView(view)",
                s.contains(".setView(view)"))
            assertFalse("$name 不许回到固定宽容器方案（右边会空 182px）",
                s.contains(".setView(panel)"))
            assertTrue("$name decorView 必须排在 setLayout 之前",
                s.indexOf("decorView") in 0 until s.indexOf("setLayout(w,"))
        }
    }
}
