package com.filmcam.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v87 设置面板「限高 + 滚动 + 触感」契约（用户 11-16 反馈：点设置后弹出页面太长）。
 *
 * 根因：11 行「名称 + 说明 + 读数」自然高 ≈1800px，S25U 可用高约 2100px，
 * ScrollView 却是 wrap_content、窗口也是 WRAP_CONTENT → 列表拿不到高度上限，
 * 滚动实际上不生效，面板成了一条又窄又长的铭牌；且滚动区没挂任何触感。
 *
 * 定稿口径：
 *  1. show() 前用 UNSPECIFIED 量自然高，把 setScroll 钳到屏高 58% 以内；
 *     窗口高度仍是 WRAP_CONTENT（78% 屏宽 + installDecor 顺序契约不许动）；
 *  2. 铭牌头留在 ScrollView 之外固定；底沿渐隐 + 琥珀刻线常驻占位，
 *     只在「下面还有行」时点亮，滚到底收起；
 *  3. 一条 OnScrollChangeListener 同时负责棘轮 tick、到顶/到底限位震、底沿提示，
 *     不许挂两条（后一条 setOnScrollChangeListener 会静默覆盖前一条）。
 */
class SettingsScrollContractTest {

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

    private fun settingsFn(): String? {
        val s = read("java/com/filmcam/app/MainActivity.kt") ?: return null
        return s.substringAfter("private fun showSettings()", "")
            .substringBefore("\n    }", "")
    }

    @Test
    fun `源码扫描必须真的跑到 不能静默跳过`() {
        assertNotNull("源码根路径要能解析到 app/src/main", sourceRoot())
        assertTrue("设置面板布局要存在", read("res/layout/dialog_settings.xml") != null)
    }

    @Test
    fun `限高必须在 show 之前按 58 屏高量自然高再钳列表`() {
        val fn = settingsFn() ?: return
        assertTrue("面板总高上限按屏高 58%", fn.contains("dm.heightPixels * 0.58f"))
        assertTrue("必须用 UNSPECIFIED 量自然高（EXACTLY 会把列表压成 0）",
            fn.contains("UNSPECIFIED"))
        assertTrue("必须真的调用 view.measure", fn.contains("view.measure("))
        assertTrue("必须把超出量从 setScroll 的高度里减掉",
            fn.contains("lp.height = (setScroll.measuredHeight - (naturalH - maxPanelH))"))
        assertTrue("限高计算必须排在 dialog.show() 之前（首帧即最终形态、零跳变）",
            fn.indexOf("view.measure(") in 0 until fn.indexOf("dialog.show()"))
        assertFalse("不许退回 setOnShowListener 里改布局的老路",
            fn.contains("setOnShowListener"))
    }

    @Test
    fun `限高不许破坏 78 屏宽与 WRAP_CONTENT 高度的窗口契约`() {
        val fn = settingsFn() ?: return
        assertTrue("宽度仍是 78% 屏宽 + 400dp 上限",
            fn.contains("widthPixels * 0.78f") && fn.contains("400 * dm.density"))
        assertTrue("窗口高度保持 WRAP_CONTENT —— 限高由内容自身承担，不下发给窗口",
            fn.contains("setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)"))
        assertTrue("仍要先取 decorView 触发 installDecor 再 setLayout",
            fn.indexOf("decorView") in 0 until fn.indexOf("setLayout(w,"))
        assertTrue("内容 View 仍是最原始的 setView(view)",
            fn.contains(".setView(view)"))
    }

    @Test
    fun `顶部铭牌头固定在滚动区之外 底沿提示不随列表滚动`() {
        val xml = read("res/layout/dialog_settings.xml") ?: return
        assertTrue("列表要有 id 供代码钳高", xml.contains("@+id/setScroll"))
        assertTrue("铭牌头必须排在滚动区之前（滚动时不动）",
            xml.indexOf("ivSetLogo") in 0 until xml.indexOf("@+id/setScroll"))
        assertTrue("底沿刻线窗必须排在 FrameLayout 之外（不随列表滚动）",
            xml.indexOf("@+id/setMoreHint") > xml.indexOf("</FrameLayout>"))
        assertFalse("底沿提示不许 GONE（会改高度、限高计算跳变）",
            xml.substringAfter("@+id/setMoreHint", "").substringBefore("</LinearLayout>")
                .contains("android:visibility=\"gone\""))
        assertTrue("要关掉三星的拉伸回弹（铭牌不发光）",
            xml.contains("android:overScrollMode=\"never\""))
    }

    @Test
    fun `底沿渐隐与刻线必须跟着机身主题走且随滚动收起`() {
        val fn = settingsFn() ?: return
        assertTrue("渐隐要按 panelBg 现画（系统 fadingEdge 在浅钛面板上是脏边）",
            fn.contains("theme.panelBg") && fn.contains("GradientDrawable.Orientation.BOTTOM_TOP"))
        assertTrue("刻线亮＝还能往下滚，到底变轨道色",
            fn.contains("theme.accent") && fn.contains("theme.trackBg"))
        assertTrue("切机身色时重画渐隐（paintPanel 里接）",
            fn.contains("paintFade()"))
        assertTrue("首帧后按真实 layout 再校正一次提示",
            fn.contains("view.post { updateMoreHint() }"))
    }

    @Test
    fun `棘轮与限位震必须挂在同一条滚动监听上`() {
        val fn = settingsFn() ?: return
        assertTrue("设置面板要挂滚动触感", fn.contains("attachEdgeHaptics(detents = detents)"))
        assertTrue("棘轮步长按一行 44dp", fn.contains("ScrollDetents(this, (44 * dm.density).toInt())"))
        assertTrue("同一条 listener 顺带驱动底沿提示",
            fn.contains("updateMoreHint(canScrollDown)"))
        assertFalse("不许再单独 setOnScrollChangeListener（会覆盖限位震）",
            fn.contains("setOnScrollChangeListener"))
    }

    @Test
    fun `共享触感工具要支持棘轮且关于页默认行为不变`() {
        val h = read("java/com/filmcam/app/ui/Haptics.kt") ?: return
        assertTrue("要有按距离分档的 ScrollDetents", h.contains("class ScrollDetents("))
        assertTrue("要有时间节流（快滑不糊震）", h.contains("gapMs: Long = 55"))
        assertTrue("attachEdgeHaptics 要接受 detents 与 onScroll 回调",
            h.contains("detents: ScrollDetents? = null") && h.contains("onScroll: ((canScrollDown: Boolean)"))
        assertTrue("listener 里要同时驱动棘轮与限位",
            h.contains("detents?.onScroll(scrollY)") && h.contains("onScroll?.invoke(!bottomNow)"))
        val about = read("java/com/filmcam/app/AboutActivity.kt") ?: return
        assertTrue("关于页仍用无参默认调用（行为与 v78 一致）",
            about.contains("attachEdgeHaptics()"))
    }
}
