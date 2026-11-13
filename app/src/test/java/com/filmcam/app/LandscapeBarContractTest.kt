package com.filmcam.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * vc77 横屏右栏版式契约（用户选 B 方案）。
 *
 * JVM 单测拿不到真实窗口 / ViewOutline，所以钉住的是最容易被动掉的几条源码口径：
 *  1. 横屏快门上下间距 56dp 且**相等**（三件才等距）；竖屏水平 56dp 不能被误改；
 *  2. 横屏绝不能把垂直居中的 bottomBar 交给底部 inset 补偿（会把整列上推 40px）；
 *  3. 胶卷圆角必须在 wireChrome 里应用（换朝向会重新 inflate），半径与暗室牌一致。
 */
class LandscapeBarContractTest {

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

    private fun shutterBlock(layout: String): String =
        layout.substringAfter("android:id=\"@+id/btnShutter\"", "")
            .substringBefore("/>", "")

    @Test
    fun `横屏快门与两翼各留 56dp 且上下相等`() {
        assertNotNull("源码扫描必须真的跑到，不能静默跳过", sourceRoot())
        val land = read("res/layout-land/activity_main.xml") ?: return
        val block = shutterBlock(land)
        assertTrue("横屏 marginTop 必须是 56dp",
            block.contains("android:layout_marginTop=\"56dp\""))
        assertTrue("横屏 marginBottom 必须是 56dp（与上方相等才叫等距）",
            block.contains("android:layout_marginBottom=\"56dp\""))
        assertFalse("不能残留旧的 28dp 间距", block.contains("28dp"))
    }

    @Test
    fun `竖屏快门的水平间距没有被误改`() {
        val port = read("res/layout/activity_main.xml") ?: return
        val block = shutterBlock(port)
        assertTrue(block.contains("android:layout_marginStart=\"56dp\""))
        assertTrue(block.contains("android:layout_marginEnd=\"56dp\""))
        assertFalse("竖屏是水平排列，不该被塞进上下 margin",
            block.contains("android:layout_marginTop"))
    }

    @Test
    fun `横屏不把 bottomBar 交给底部 inset 补偿 竖屏仍保留`() {
        val s = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val body = s.substringAfter("private fun setupEdgeToEdge()", "")
            .substringBefore("private fun refitChrome", "")
        assertTrue("必须先判朝向", body.contains("ORIENTATION_LANDSCAPE"))
        assertTrue("横屏必须传 null，否则 center_vertical 会把整列上推",
            body.contains("if (landscape) null else binding.bottomBar"))
        assertTrue("竖屏仍必须补偿 bottomBar", body.contains("binding.bottomBar"))
        assertTrue("补偿口径不变", body.contains("bottomAsMargin = true"))
    }

    @Test
    fun `胶卷圆角在 wireChrome 里幂等应用 且与暗室同半径`() {
        val s = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val wire = s.substringAfter("private fun wireChrome()", "")
            .substringBefore("private fun", "")
        assertTrue("必须在 wireChrome 里应用（换 chrome 后会重跑）",
            wire.contains("FilmIconBadge.apply(binding.tvFilmName)"))

        val badge = read("java/com/filmcam/app/ui/FilmIconBadge.kt") ?: return
        assertTrue("圆角半径必须与 bg_darkroom 一致",
            badge.contains("CORNER_RADIUS_DP = 14f"))
        assertTrue(badge.contains("clipToOutline = true"))
        assertTrue(badge.contains("setRoundRect"))
        assertFalse("不能改成椭圆/全圆，暗室是圆角方牌",
            badge.contains("setOval"))

        val bg = read("res/drawable/bg_darkroom.xml") ?: return
        assertTrue("暗室牌外框圆角必须仍是 14dp", bg.contains("android:radius=\"14dp\""))
    }
}
