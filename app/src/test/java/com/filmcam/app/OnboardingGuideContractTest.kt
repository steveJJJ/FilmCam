package com.filmcam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v82 首次使用引导（三段式）接线契约。
 *
 * 段 1 说明书卡 [com.filmcam.app.ui.QuickStartCard]（三屏翻页，show 前定尺寸）；
 * 段 2 LCD 实操引导：对焦 / EV / 首张落卷三个检测点做对自动前进，15s 超时跳过；
 * 段 3 首卷护栏：第 1 / 12 张提醒冲洗，首卷冲洗完成出贺词后归档。
 * 状态机 [OnboardingGuide] 持久化进 prefs，重进 App 不回退、老用户不重播。
 */
class OnboardingGuideContractTest {

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

    private fun main(): String? = read("java/com/filmcam/app/MainActivity.kt")

    @Test
    fun `状态机常量与检测点锁死`() {
        val s = read("java/com/filmcam/app/OnboardingGuide.kt") ?: return
        assertEquals("stage 键", "onboarding_stage", Regex("\"(onboarding_stage)\"").find(s)?.groupValues?.get(1))
        assertEquals("首卷护栏键", "first_roll_guarded", Regex("\"(first_roll_guarded)\"").find(s)?.groupValues?.get(1))
        assertEquals("段2完成档位", 4, Regex("STAGE_DONE = (\\d+)").find(s)?.groupValues?.get(1)?.toInt())
        assertEquals("超时15s", "15_000L", Regex("HINT_TIMEOUT_MS = ([0-9_L]+)").find(s)?.groupValues?.get(1))
        assertEquals("护栏点1", 1, Regex("GUARD_FRAME_FIRST = (\\d+)").find(s)?.groupValues?.get(1)?.toInt())
        assertEquals("护栏点12", 12, Regex("GUARD_FRAME_THIRD = (\\d+)").find(s)?.groupValues?.get(1)?.toInt())
        assertTrue("对焦检测点要在", s.contains("fun onFocusDone"))
        assertTrue("EV 检测点要在", s.contains("fun onEvChanged"))
        assertTrue("落卷检测点要在", s.contains("fun onShotTaken"))
        assertTrue("护栏命中要在", s.contains("fun guardFrameHit"))
    }

    @Test
    fun `段1 说明书卡老用户不重播 播完进段2`() {
        val s = main() ?: return
        val fn = s.substringAfter("private fun maybeShowFirstRunGuide()", "")
            .substringBefore("\n    }", "")
        assertTrue("maybeShowFirstRunGuide 要在", fn.isNotBlank())
        assertTrue("老用户 guide_shown 守卫保留", fn.contains("guide_shown"))
        assertTrue("说明书卡走 QuickStartCard", fn.contains("QuickStartCard.show"))
        assertTrue("段2启动 = stage 0→1", fn.contains("OnboardingGuide.setStage(this, 1)"))
        assertTrue("播完 LCD 接力第一条", fn.contains("showOnboardingHint()"))
    }

    @Test
    fun `段2 三个检测点接线且超时可跳过`() {
        val s = main() ?: return
        // 单行断言（MainActivity.kt 是 CRLF 行尾，跨行锚会因 \r 对不上）
        assertTrue("对焦回调要挂段2检测",
            s.contains("if (OnboardingGuide.onFocusDone(this@MainActivity)) showOnboardingHint()"))
        val evBlock = s.substringAfter("setEv(newEv, persist = false)", "")
            .substringBefore("android.view.MotionEvent.ACTION_UP", "")
        assertTrue("EV 手势要挂段2检测", evBlock.contains("OnboardingGuide.onEvChanged(this)"))
        val shot = s.substringAfter("if (OnboardingGuide.onShotTaken(this))", "")
            .substringBefore("if (count >= FILM_MAX)", "")
        assertTrue("首张落卷要停超时轮转", shot.contains("stopOnboardingTimeout()"))
        assertTrue("段3护栏提示要在落卷处", shot.contains("guardFrameHit"))
        val hint = s.substringAfter("private fun showOnboardingHint()", "")
            .substringBefore("\n    }", "")
        assertTrue("超时15s要挂轮转", hint.contains("HINT_TIMEOUT_MS"))
        assertTrue("超时跳过不卡人", hint.contains("OnboardingGuide.setStage(this, cur + 1)"))
        assertTrue("onResume 要续播当前段位",
            s.contains("OnboardingGuide.stage(this) in 1 until OnboardingGuide.STAGE_DONE"))
    }

    @Test
    fun `段3 首卷护栏与贺词收口`() {
        val s = main() ?: return
        val announce = s.substringAfter("private fun announceDevelopedOnLcd()", "")
            .substringBefore("\n    }", "")
        assertTrue("首卷判定要在", announce.contains("OnboardingGuide.firstRollDone(this)"))
        assertTrue("首卷贺词要在", announce.contains("lcd_notice_first_dev_done"))
        assertTrue("贺词出完要归档", announce.contains("OnboardingGuide.markFirstRollDone(this)"))
        assertTrue("常规贺词保留", announce.contains("lcd_notice_dev_done"))
    }

    @Test
    fun `快速上手卡 show 前定尺寸 三页翻页`() {
        val s = read("java/com/filmcam/app/ui/QuickStartCard.kt") ?: return
        // vc84：show() 时 installDecor() 会重置 attrs.width；必须先取 decorView
        // 触发 installDecor，再 setLayout(w) 才能保住宽度且零跳变。
        val widthIdx = s.indexOf("widthPixels * 0.78f")
        val decorIdx = s.indexOf("decorView")
        val setIdx = s.indexOf("setLayout(w,")
        val showIdx = s.indexOf("dialog.show()")
        assertTrue("78% 屏宽口径必须在 show 前算好", widthIdx in 0 until showIdx)
        assertTrue("decorView 必须先于 setLayout 触发（否则宽度被重置）", decorIdx in 0 until setIdx)
        assertTrue("宽度的确定必须在 show 之前（零跳变）", setIdx in 0 until showIdx)
        assertTrue("背景必须 show 前换铭牌", s.indexOf("panelDrawable()") in 0 until showIdx)
        assertEquals("三屏", 3, Regex("R\\.string\\.qs_title_").findAll(s).count())
        assertTrue("重看不驱动 onboarding（guard 由调用方织入 dismiss）",
            s.contains("guard(dialog)"))
    }

    @Test
    fun `关于页重看入口与中英文案齐`() {
        val about = read("java/com/filmcam/app/AboutActivity.kt") ?: return
        assertTrue("关于页要绑 rowQuickStart", about.contains("rowQuickStart"))
        assertTrue("重看走 QuickStartCard", about.contains("QuickStartCard.show"))
        val zh = read("res/values/strings.xml") ?: return
        val en = read("res/values-en/strings.xml") ?: return
        listOf("qs_title_1", "qs_body_1", "qs_title_2", "qs_body_2", "qs_title_3", "qs_body_3",
            "guide_focus", "guide_ev", "guide_shoot", "guide_first_frame", "guide_third_frame",
            "lcd_notice_first_dev_done", "about_spec_quickstart").forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
        assertFalse("旧单段引导文案应已删除", zh.contains("main_guide_body"))
        assertFalse("英文旧文案也应删", en.contains("main_guide_body"))
    }
}
