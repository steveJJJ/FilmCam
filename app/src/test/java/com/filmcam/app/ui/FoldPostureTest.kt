package com.filmcam.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 腰平取景 P0 骨架：分区求解定量 + 「只改显示、不碰成像」的源码契约。
 *
 * 真机形态：折成 L 形后带镜头的 A 半屏竖起、B 半屏平放朝上当毛玻璃。
 * Android 仍把两块当一个 Display，所以 solve() 只需在窗口里切出
 * finder（B）与 mask（A）两块矩形；相机请求、冲洗、EXIF、成片方向全部不动。
 */
class FoldPostureTest {

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

    private fun input(
        w: Int = 1080, h: Int = 2340,
        hinge: FoldPosture.Hinge = FoldPosture.Hinge.NONE,
        manual: Boolean = true,
        flip: Boolean = false,
        displayW: Int = w, displayH: Int = h,
        boundsLeft: Int = 0, boundsTop: Int = 0,
        boundsRight: Int = 0, boundsBottom: Int = 0,
        halfOpened: Boolean = false,
        gateStyle: FoldPosture.GateStyle = FoldPosture.GateStyle.AUTO,
    ) = FoldPosture.Input(
        windowW = w, windowH = h,
        displayW = displayW, displayH = displayH,
        hinge = hinge, halfOpened = halfOpened,
        boundsLeft = boundsLeft, boundsTop = boundsTop,
        boundsRight = boundsRight, boundsBottom = boundsBottom,
        manual = manual, flip = flip,
        gateStyle = gateStyle,
    )

    @Test
    fun `直板机手动开关按 1-2 平分 取景窗在下半屏`() {
        val r = FoldPosture.solve(input())
        assertEquals(FoldPosture.Mode.WAIST, r.mode)
        assertEquals(FoldPosture.Region(0, 1170, 1080, 2340), r.finder)
        assertEquals(FoldPosture.Region(0, 0, 1080, 1170), r.mask)
        assertEquals(1080, r.finder.width)
        assertEquals(1170, r.finder.height)
        assertEquals("hinge-horizontal", r.reason)
    }

    @Test
    fun `水平折痕按 FoldingFeature bounds 中线切 不用死写 1-2`() {
        val r = FoldPosture.solve(
            input(
                w = 1080, h = 2340,
                hinge = FoldPosture.Hinge.HORIZONTAL,
                boundsTop = 1100, boundsBottom = 1140,
            )
        )
        assertEquals(FoldPosture.Mode.WAIST, r.mode)
        assertEquals(1120, r.finder.top)
        assertEquals(1120, r.mask.bottom)
        assertEquals(1220, r.finder.height)
    }

    @Test
    fun `竖直折痕切左右 书本式 Fold 通用`() {
        val r = FoldPosture.solve(
            input(
                w = 1812, h = 2176,
                hinge = FoldPosture.Hinge.VERTICAL,
                boundsLeft = 880, boundsRight = 920,
            )
        )
        assertEquals(FoldPosture.Mode.WAIST, r.mode)
        assertEquals("hinge-vertical", r.reason)
        assertEquals(FoldPosture.Region(900, 0, 1812, 2176), r.finder)
        assertEquals(FoldPosture.Region(0, 0, 900, 2176), r.mask)
    }

    @Test
    fun `翻转取景窗把 A-B 互换 覆盖摄像头在另一半的机型`() {
        val normal = FoldPosture.solve(input(w = 1080, h = 2340))
        val flipped = FoldPosture.solve(input(w = 1080, h = 2340, flip = true))
        assertEquals(normal.mask, flipped.finder)
        assertEquals(normal.finder, flipped.mask)

        val v = FoldPosture.solve(
            input(w = 1812, h = 2176, hinge = FoldPosture.Hinge.VERTICAL,
                boundsLeft = 900, boundsRight = 912, flip = true)
        )
        assertEquals(906, v.finder.right)
        assertEquals(906, v.mask.left)
    }

    @Test
    fun `系统已把窗口缩到一半时不再切分`() {
        val r = FoldPosture.solve(
            input(w = 1080, h = 1080, displayW = 1080, displayH = 2176,
                hinge = FoldPosture.Hinge.HORIZONTAL)
        )
        assertEquals(FoldPosture.Mode.WINDOW_HALF, r.mode)
        assertEquals("system-split-vertical", r.reason)

        val v = FoldPosture.solve(
            input(w = 900, h = 2176, displayW = 1812, displayH = 2176,
                hinge = FoldPosture.Hinge.VERTICAL)
        )
        assertEquals(FoldPosture.Mode.WINDOW_HALF, v.mode)
        assertEquals("system-split-horizontal", v.reason)
    }

    @Test
    fun `没开手动也没半开时保持常规 半开自动进入留给 P1`() {
        assertEquals(
            FoldPosture.Mode.NORMAL,
            FoldPosture.solve(input(manual = false)).mode
        )
        assertEquals(
            FoldPosture.Mode.WAIST,
            FoldPosture.solve(input(manual = false, halfOpened = true)).mode
        )
        assertEquals(
            FoldPosture.Mode.NORMAL,
            FoldPosture.solve(input(w = 0, h = 0)).mode
        )
    }

    @Test
    fun `折痕 bounds 越界时钳住 不产生零面积区域`() {
        val r = FoldPosture.solve(
            input(hinge = FoldPosture.Hinge.HORIZONTAL,
                boundsTop = -500, boundsBottom = 99999)
        )
        assertEquals(FoldPosture.Mode.WAIST, r.mode)
        assertEquals(2339, r.mask.height)
        assertEquals(1, r.finder.height)
        assertFalse(r.finder.isEmpty())
        assertFalse(r.mask.isEmpty())
    }

    @Test
    fun `求解器是纯 Kotlin 不带 Android 依赖`() {
        val src = read("java/com/filmcam/app/ui/FoldPosture.kt") ?: return
        assertFalse("分区求解必须能在 JVM 单测里真跑", src.contains("import android."))
        assertTrue("要同时支持水平与竖直折痕", src.contains("HORIZONTAL") && src.contains("VERTICAL"))
        assertTrue("要有 A/B 互换开关", src.contains("val flip: Boolean"))
        assertTrue("系统已缩窗时不能重复切分", src.contains("WINDOW_HALF"))
    }

    @Test
    fun `腰平只改取景显示 不碰相机与冲洗链路`() {
        val chrome = read("java/com/filmcam/app/ui/WaistLevelChrome.kt") ?: return
        assertFalse("腰平不能改相机请求", chrome.contains("Camera2Controller"))
        assertFalse("腰平不能改冲洗", chrome.contains("DevelopmentService"))
        assertFalse("腰平不能改 EXIF", chrome.contains("ExifInterface"))
        assertTrue("A 半屏必须是纯黑遮罩", chrome.contains("Gravity.TOP or Gravity.START"))
        assertTrue("B 半屏控件条贴在近端", chrome.contains("Gravity.BOTTOM or Gravity.START"))
        assertTrue("换朝向重建 chrome 后要幂等", chrome.contains("originalVisibility"))
    }

    @Test
    fun `MainActivity 接线与布局契约`() {
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        // 腰平总闸搬到工程模式：三态 prefs（off / auto / simulate），设置面板不再有这一行
        assertTrue("总闸没从 prefs 读", main.contains("prefs.getString(\"waistEgg\", \"auto\")"))
        assertTrue("没有真折痕监听", main.contains("WindowInfoTracker.getOrCreate"))
        assertTrue("半开判定没接 FoldingFeature", main.contains("FoldingFeature.State.HALF_OPENED"))
        assertTrue("没要求折痕分离", main.contains("foldSeparating = f.isSeparating"))
        assertTrue("没用整块屏尺寸判缩窗", main.contains("displayWidthPx()"))
        assertTrue("退出后没抑制本次半开", main.contains("waistDismissed = true"))
        assertTrue("展平没复位抑制", main.contains("if (wasHalf && !foldHalfOpened) waistDismissed = false"))
        assertTrue("refitChrome 在腰平期间必须跳过", main.contains("if (waistApplied) return"))
        assertTrue("分区要经纯函数求解", main.contains("FoldPosture.solve("))
        assertTrue("要幂等应用分区", main.contains("WaistLevelChrome.apply("))
        assertTrue("腰平快门要接原有快门链路", main.contains("binding.waistShutter.hapticClick { triggerShutter() }"))
        assertTrue("设置面板不该再引用腰平行", !main.contains("R.id.rowSetWaist"))
        assertTrue("换朝向后要重应用分区", main.contains("binding.root.post { applyWaistLevel() }"))

        val layout = read("res/layout/activity_main.xml") ?: return
        assertTrue("缺纯黑遮罩", layout.contains("android:id=\"@+id/waistMask\""))
        assertTrue("残缺取景控件条", layout.contains("android:id=\"@+id/waistBar\""))
        assertTrue("缺腰平快门", layout.contains("android:id=\"@+id/waistShutter\""))
        val settings = read("res/layout/dialog_settings.xml") ?: return
        assertTrue("设置面板该移除腰平行", !settings.contains("android:id=\"@+id/rowSetWaist\""))
        val hidden = read("res/layout/dialog_hidden_settings.xml") ?: return
        assertTrue("工程模式缺腰平彩蛋行", hidden.contains("android:id=\"@+id/rowEngWaist\""))
        val about = read("java/com/filmcam/app/AboutActivity.kt") ?: return
        assertTrue("工程模式没读三态", about.contains("prefs.getString(\"waistEgg\", \"auto\")"))
        assertTrue("三态没写回 prefs", about.contains("prefs.edit().putString(\"waistEgg\", mode)"))
    }

    @Test
    fun `画幅按取景窗几何分类 方窗 6x6 宽窗 3比2 竖窗 2比3`() {
        // S25U 下半屏 1080×1170 ≈ 0.923（直板模拟 / 竖折 / 阔折的方窗）
        assertEquals(1f, FoldPosture.classifyGateAspect(1080, 1170, FoldPosture.GateStyle.AUTO), 1e-6f)
        // 传统横折：下半屏明显横向宽 → 135 横置 3:2
        assertEquals(3f / 2f, FoldPosture.classifyGateAspect(1080, 640, FoldPosture.GateStyle.AUTO), 1e-6f)
        // 竖长取景窗兜底 → 135 竖置 2:3
        assertEquals(2f / 3f, FoldPosture.classifyGateAspect(540, 1170, FoldPosture.GateStyle.AUTO), 1e-6f)
        // 带宽两端（0.8 / 1.25）都归方窗
        assertEquals(1f, FoldPosture.classifyGateAspect(800, 1000, FoldPosture.GateStyle.AUTO), 1e-6f)
        assertEquals(1f, FoldPosture.classifyGateAspect(1250, 1000, FoldPosture.GateStyle.AUTO), 1e-6f)
    }

    @Test
    fun `手动覆盖档不受取景窗形状影响`() {
        assertEquals(1f, FoldPosture.classifyGateAspect(1080, 640, FoldPosture.GateStyle.SQUARE), 1e-6f)
        assertEquals(1f, FoldPosture.classifyGateAspect(540, 1170, FoldPosture.GateStyle.SQUARE), 1e-6f)
        assertEquals(3f / 2f, FoldPosture.classifyGateAspect(1080, 640, FoldPosture.GateStyle.STANDARD), 1e-6f)
        assertEquals(2f / 3f, FoldPosture.classifyGateAspect(540, 1170, FoldPosture.GateStyle.STANDARD), 1e-6f)
        assertEquals(3f / 2f, FoldPosture.classifyGateAspect(0, 0, FoldPosture.GateStyle.AUTO), 1e-6f)
    }

    @Test
    fun `求解器把画幅比例带进 Result`() {
        val auto = FoldPosture.solve(input(w = 1080, h = 2340, gateStyle = FoldPosture.GateStyle.AUTO))
        // 下半屏 1080×1170 → 方窗 → 1:1
        assertEquals(1f, auto.gateAspect, 1e-6f)
        val forced = FoldPosture.solve(input(w = 1080, h = 2340, gateStyle = FoldPosture.GateStyle.STANDARD))
        // 覆盖 135 档仍按取景窗横竖取向：这个窗是竖的 → 竖置片门 2:3
        assertEquals(2f / 3f, forced.gateAspect, 1e-6f)
        val wide = FoldPosture.solve(input(w = 2340, h = 1080, gateStyle = FoldPosture.GateStyle.STANDARD))
        assertEquals(3f / 2f, wide.gateAspect, 1e-6f)
    }
}
