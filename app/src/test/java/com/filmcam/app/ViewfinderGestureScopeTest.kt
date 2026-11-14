package com.filmcam.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 取景器手势作用域契约（用户 11-14：「滑动调 EV / 白平衡只限取景框，其他位置不需要」）。
 *
 * 手势 Listener 挂在全屏 glSurfaceView 上，之前只有点屏对焦做了 contentRect 判定，
 * 顶栏 / 机顶 LCD / 底栏快门条 / letterbox 黑边上上下滑照样改 EV，横滑照样换白平衡。
 * 这里钉住「起手点判定 + 整条序列守卫」这条最小修法，防止以后接线时被删掉。
 */
class ViewfinderGestureScopeTest {

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

    @Test
    fun `DOWN 必须按取景框判定起手点且框外直接丢弃序列`() {
        assertNotNull("源码扫描必须真的跑到，不能静默跳过", sourceRoot())
        val s = File(sourceRoot()!!, "java/com/filmcam/app/MainActivity.kt").readText()
        val down = s.substringAfter("MotionEvent.ACTION_DOWN -> {", "")
            .substringBefore("MotionEvent.ACTION_MOVE -> {", "")
        assertTrue("DOWN 必须用 viewfinderFrame.contentRect() 判起手点（与点屏对焦同一口径）",
            down.contains("binding.viewfinderFrame.contentRect()"))
        assertTrue("起手点判定要用相对 View 的 event.x / event.y，不是 raw 坐标",
            down.contains("contains(event.x, event.y)"))
        assertTrue("框外起手必须直接 return，不能进入 touchActive 序列",
            down.indexOf("if (!gestureInContent) return@setOnTouchListener true") > 0 &&
                down.indexOf("if (!gestureInContent)") < down.indexOf("touchActive = true"))
    }

    @Test
    fun `MOVE 与抬手都必须带取景框守卫`() {
        val s = File(sourceRoot()!!, "java/com/filmcam/app/MainActivity.kt").readText()
        val guard = "if (!touchActive || !gestureInContent) return@setOnTouchListener true"
        val hits = Regex(Regex.escape(guard)).findAll(s).count()
        assertTrue("MOVE 与 UP/CANCEL 两处都必须守 gestureInContent，实际 $hits 处", hits == 2)
        // 守卫必须排在真正的 EV / WB 计算之前（不能只是写了没用）
        assertTrue("MOVE 守卫必须在 EV 计算之前",
            s.indexOf(guard) < s.indexOf("val delta = (touchStartY - event.rawY) / 100f"))
        assertTrue("MOVE 守卫必须在 WB 判定之前",
            s.indexOf(guard) < s.indexOf("if (!wbDragging && Math.abs(dx) > 32f"))
    }

    @Test
    fun `点屏对焦的取景框判定仍在`() {
        val s = File(sourceRoot()!!, "java/com/filmcam/app/MainActivity.kt").readText()
        assertTrue("点屏对焦仍用 contentRect 判定（与手势起手点同一口径）",
            s.contains("binding.viewfinderFrame.contentRect().contains(fx, fy)"))
    }
}
