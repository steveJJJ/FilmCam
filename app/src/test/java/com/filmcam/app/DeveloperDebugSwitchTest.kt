package com.filmcam.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * vc72：守护「调试：跳过冲洗立刻出片」已彻底移除。
 *
 * 这个开关历史上只在 MainActivity.onCreate 读一次、onResume 不重读，且 onPause 的
 * saveSettings() 会把内存旧值回写覆盖工程师控制台刚写的新值，所以打开后永远不生效。
 * 用户拍板删除；这两条契约测试防止有人把它原样加回来，也防止误删暗房「后台冲洗」
 * 按钮共用的 DevelopController 入口。
 */
class DeveloperDebugSwitchTest {

    /** Gradle test 的工作目录通常是 app/；向上找带 app/src/main 的项目根。 */
    private fun sourceRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val d = dir ?: return null
            val main = File(d, "app/src/main")
            if (main.isDirectory) return main
            dir = d.parentFile
        }
        return null
    }

    private fun sourceFiles(root: File): Sequence<File> =
        root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }

    @Test
    fun `调试即时出片开关的四个标识都不再出现在主源码与资源里`() {
        val root = sourceRoot() ?: return   // 工作目录不含源码时跳过
        val forbidden = listOf(
            "debug_instant_develop",
            "debugInstantDevelop",
            "swSkipDevelop",
            "main_debug_skip_develop",
        )
        val hits = sourceFiles(root).flatMap { f ->
            val text = f.readText()
            forbidden.filter { text.contains(it) }.map { "${f.name}: $it" }
        }.toList()
        assertTrue("死开关残留：$hits", hits.isEmpty())
    }

    @Test
    fun `暗房后台冲洗共用入口仍在`() {
        val root = sourceRoot() ?: return
        val controller = File(root, "java/com/filmcam/app/develop/DevelopController.kt")
        assertTrue(
            "DevelopController.developRollInBackground 是暗房「后台冲洗」按钮的入口，不能删",
            controller.readText().contains("fun developRollInBackground")
        )
    }
}
