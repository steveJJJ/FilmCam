package com.filmcam.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v78 全局触感契约（用户 11-14 要求：关于页/暗房/照片/拍同款补震动）。
 *
 * JVM 单测震不了马达，钉住的是最容易被悄悄删掉的接线口径：
 *  1. 四个用户点名场景（拍摄同款 / 照片点按 / 关于页滑动 / 暗房胶卷条滑动）必须有触感调用；
 *  2. 共享口径：ui/Haptics.kt 必须读 "filmcam" prefs 的 hapticLevel 且夹到 [24,255]
 *     （与 MainActivity.vibrate 同口径，改一处必须同步另一处）；
 *  3. 滑杆 tick 只能在 fromUser 时触发 —— 程序回填（syncUi/恢复默认）不许震。
 */
class HapticWiringTest {

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
    fun `共享触感工具与机身设置同口径`() {
        assertNotNull("源码扫描必须真的跑到，不能静默跳过", sourceRoot())
        val h = read("java/com/filmcam/app/ui/Haptics.kt") ?: return
        assertTrue("Haptics 必须读 filmcam prefs 的 hapticLevel（随机身三档强度）",
            h.contains("getSharedPreferences(\"filmcam\""))
        assertTrue("Haptics 必须按档位缩放（与 MainActivity.HAPTIC_LEVEL_SCALE 同口径）",
            h.contains("LEVEL_SCALE"))
        assertTrue("振幅必须夹到 [24,255]（太低三星马达不响应）",
            h.contains("coerceIn(24, 255)"))
    }

    @Test
    fun `拍摄同款与照片点按有触感`() {
        val p = read("java/com/filmcam/app/PhotoViewerActivity.kt") ?: return
        assertTrue("拍摄同款按钮必须走 hapticClick（带确认感的重触感）",
            p.contains("btnShootSame).hapticClick"))
        assertTrue("照片点按翻面板必须有触感", p.contains("onPhotoTapped"))
        assertTrue("翻页 tick 必须在 onPageSelected 里",
            p.contains("Haptics.vibrate(this@PhotoViewerActivity"))
    }

    @Test
    fun `关于页滑动与点击有触感`() {
        val a = read("java/com/filmcam/app/AboutActivity.kt") ?: return
        assertTrue("关于页 ScrollView 必须挂滚动限位触感",
            a.contains("attachEdgeHaptics"))
        assertTrue("关于页滑杆必须有拖动 tick（SliderTick）",
            a.contains("SliderTick(this)"))
    }

    @Test
    fun `暗房胶卷条手动拨动有棘轮触感且自动推进不震`() {
        val d = read("java/com/filmcam/app/develop/DevelopmentActivity.kt") ?: return
        assertTrue("胶卷条必须挂 OnScrollListener 给拨动 tick",
            d.contains("addOnScrollListener"))
        assertTrue("必须用 DRAGGING 区分手拨与自动推进（自动推进不许震）",
            d.contains("SCROLL_STATE_DRAGGING"))
        assertTrue("后台冲洗按钮必须有触感",
            d.contains("btnBackground.setOnClickListener { vibrate(20); finish() }"))
    }

    @Test
    fun `滑杆 tick 只在用户拖动时触发`() {
        val devs = listOf(
            "java/com/filmcam/app/develop/DevParamsDialog.kt",
            "java/com/filmcam/app/AboutActivity.kt"
        )
        for (rel in devs) {
            val f = read(rel) ?: continue
            val tickCalls = Regex("sliderTick|ccTick|probTick|\\.onUserProgress\\(\\)")
                .findAll(f).count()
            assertTrue("$rel 里有 SliderTick 实例就必须有 fromUser 守卫的调用", tickCalls > 0)
            assertTrue("$rel 的 tick 必须由 fromUser 守卫（程序回填不震）",
                f.contains("fromUser"))
        }
    }
}
