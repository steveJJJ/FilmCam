package com.filmcam.app

import com.filmcam.app.camera.FlashFeedbackLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 方案A：成片反馈闭环的规则与接线契约（11-14 23:2x）。
 *
 * 定量口径来自 11-14 全链路取证（裁边 7%）：
 *  - vc83 定稿 mean 51.8 / p50 26 / 平台 2.2%（背景死黑但主体正常）
 *  - FIMO mean 93.4 / p50 86 / 平台 31.8%（目标方向）
 *  - slow_sync #1 过爆手掌 plateau 41 / mean 105（必须保守回收）
 *  - vc87 实测主体过曝下界 product 5609 ⇒ 修正上限 +0.6EV（3600→5456）
 */
class FlashFeedbackLoopTest {

    @Test
    fun `基线是零修正 首张等于 vc83 定稿行为`() {
        FlashFeedbackLoop.reset()
        assertEquals(0f, FlashFeedbackLoop.correctionEv(), 0.0001f)
        assertEquals(1f, FlashFeedbackLoop.productScale(), 0.0001f)
        assertEquals(0, FlashFeedbackLoop.updates())
    }

    @Test
    fun `背景死黑帧 p50 贴黑电平要上探 0点25EV`() {
        FlashFeedbackLoop.reset()
        val v = FlashFeedbackLoop.onStats(FlashFeedbackLoop.FrameStats(mean = 30f, p50 = 15, plateauPct = 0.5f))
        assertEquals("dark", v)
        assertEquals(0.25f, FlashFeedbackLoop.correctionEv(), 0.0001f)
        assertEquals(1, FlashFeedbackLoop.updates())
    }

    @Test
    fun `整体偏暗帧 mean 低于 45 小幅上探 0点15EV`() {
        FlashFeedbackLoop.reset()
        val v = FlashFeedbackLoop.onStats(FlashFeedbackLoop.FrameStats(mean = 40f, p50 = 40, plateauPct = 3f))
        assertEquals("dim", v)
        assertEquals(0.15f, FlashFeedbackLoop.correctionEv(), 0.0001f)
    }

    @Test
    fun `带内滞回不动 修正是跨张缓变不是逐张重算`() {
        FlashFeedbackLoop.reset()
        val before = FlashFeedbackLoop.correctionEv()
        val v = FlashFeedbackLoop.onStats(FlashFeedbackLoop.FrameStats(mean = 70f, p50 = 50, plateauPct = 10f))
        assertEquals("hold", v)
        assertEquals(before, FlashFeedbackLoop.correctionEv(), 0.0001f)
        assertEquals(0, FlashFeedbackLoop.updates())
    }

    @Test
    fun `偏亮帧要小幅回收 0点15EV`() {
        FlashFeedbackLoop.reset()
        val v = FlashFeedbackLoop.onStats(FlashFeedbackLoop.FrameStats(mean = 105f, p50 = 80, plateauPct = 20f))
        assertEquals("bright", v)
        assertEquals(-0.15f, FlashFeedbackLoop.correctionEv(), 0.0001f)
    }

    @Test
    fun `过曝判定优先于一切 平台加高均值保守回收 0点25EV`() {
        FlashFeedbackLoop.reset()
        // slow_sync #1 过爆手掌的画像：plateau 41 / mean 105
        val v = FlashFeedbackLoop.onStats(FlashFeedbackLoop.FrameStats(mean = 105f, p50 = 100, plateauPct = 41f))
        assertEquals("over", v)
        assertEquals(-0.25f, FlashFeedbackLoop.correctionEv(), 0.0001f)
    }

    @Test
    fun `修正量夹在上限内 product 永不越过 vc87 过曝下界 5609`() {
        FlashFeedbackLoop.reset()
        repeat(10) {
            FlashFeedbackLoop.onStats(FlashFeedbackLoop.FrameStats(mean = 30f, p50 = 15, plateauPct = 0.5f))
        }
        assertEquals(FlashFeedbackLoop.MAX_CORR_EV, FlashFeedbackLoop.correctionEv(), 0.0001f)
        val product = 3600f * FlashFeedbackLoop.productScale()
        assertTrue("修正后 product=$product 必须低于过曝下界 5609", product < 5609f)
    }

    @Test
    fun `修正量夹在下限内 亮场景有退暗空间`() {
        FlashFeedbackLoop.reset()
        repeat(10) {
            FlashFeedbackLoop.onStats(FlashFeedbackLoop.FrameStats(mean = 116f, p50 = 110, plateauPct = 47f))
        }
        assertEquals(FlashFeedbackLoop.MIN_CORR_EV, FlashFeedbackLoop.correctionEv(), 0.0001f)
        assertTrue(FlashFeedbackLoop.productScale() < 1f)
    }

    @Test
    fun `暗场景连拍逐张逼近 FIMO 不一帧跳到极值`() {
        FlashFeedbackLoop.reset()
        // vc83 观感（p50 26 / mean 52）其实落在 dark 与 hold 边界附近：
        // p50<=25 判 dark。真实演进应是每张 +0.25EV 缓步收敛
        FlashFeedbackLoop.onStats(FlashFeedbackLoop.FrameStats(mean = 51.8f, p50 = 26, plateauPct = 2.2f))
        val step1 = FlashFeedbackLoop.correctionEv()
        FlashFeedbackLoop.onStats(FlashFeedbackLoop.FrameStats(mean = 55f, p50 = 30, plateauPct = 8f))
        assertEquals(step1, FlashFeedbackLoop.correctionEv(), 0.0001f) // 带内 hold
        FlashFeedbackLoop.onStats(FlashFeedbackLoop.FrameStats(mean = 58f, p50 = 24, plateauPct = 12f))
        assertTrue(FlashFeedbackLoop.correctionEv() > step1)
    }

    // ---------- 源码接线契约 ----------

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

    private fun read(rel: String): String =
        sourceRoot()?.let { File(it, rel).takeIf { f -> f.isFile }?.readText() } ?: ""

    @Test
    fun `开关与乘子接线 修正只乘在总曝光量安全帽上`() {
        val s = read("java/com/filmcam/app/camera/Camera2Controller.kt")
        assertTrue("单一实验开关", s.contains("const val FLASH_FEEDBACK_LOOP_EXPERIMENT = true"))
        val fn = s.substringAfter("private fun applyFlashCaptureTuning", "")
            .substringBefore("\n    }", "")
        assertTrue("反馈乘子必须只乘 FLASH_MAX_EXPOSURE_PRODUCT（ISO/EV 钳制不动）",
            fn.contains("FLASH_MAX_EXPOSURE_PRODUCT * fbScale"))
        assertTrue("与 unclamp 互斥不叠加", fn.contains("FLASH_FEEDBACK_LOOP_EXPERIMENT && !unclamp"))
        assertTrue("updates=0 时 scale 必须是 1（首张=vc83 基线）",
            fn.contains("if (fbActive) FlashFeedbackLoop.productScale() else 1f"))
        assertTrue("拍摄日志要带回路状态便于定量复标",
            fn.contains("fbCorr=") && fn.contains("fbScale=") && fn.contains("fbVerdict="))
    }

    @Test
    fun `冲洗端两条收尾路径都要取样 且在 tmpJpeg 删除前`() {
        val s = read("java/com/filmcam/app/develop/DevelopmentService.kt")
        val calls = Regex("FlashFeedbackLoop\\.onDevelopedFrame\\(tmpJpeg, shotJson\\)").findAll(s).count()
        assertTrue("JPEG 与 RAW 两条收尾路径都要接（实际 $calls 处）", calls == 2)
        assertTrue("取样必须包 runCatching，不许影响冲洗落盘",
            Regex("runCatching \\{\\s*\\n\\s*com\\.filmcam\\.app\\.camera\\.FlashFeedbackLoop\\.onDevelopedFrame")
                .findAll(s).count() == 2)
    }

    @Test
    fun `回路对象零引擎依赖 只吃自己包与 ShotMeta`() {
        val s = read("java/com/filmcam/app/camera/FlashFeedbackLoop.kt")
        assertTrue("必须有安全上限常量并引用 vc87 定量",
            s.contains("const val MAX_CORR_EV") && s.contains("5609"))
        assertEquals("不许 import 引擎层（ spektra / Spektra / FilmStockMapper）",
            0, Regex("^import .*(pektra|ilmStockMapper)", RegexOption.MULTILINE).findAll(s).count())
    }
}
