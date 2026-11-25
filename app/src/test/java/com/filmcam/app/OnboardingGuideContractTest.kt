package com.filmcam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首次使用引导接线契约。
 *
 * 首启直接进 LCD 八步实操（11-25 去掉开头说明书卡，卡片改为关于页「重看」入口）：
 * 选卷 / 进Pro / ISO / 快门 / 退Pro / 对焦 / EV / 首张落卷，各检测点做对自动前进，
 * 15s 超时跳过不卡人。段 3 首卷护栏：第 1 / 12 张提醒冲洗，首卷冲洗完成出贺词后归档。
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
        assertEquals("段2完成档位(八步→9)", 9, Regex("STAGE_DONE = (\\d+)").find(s)?.groupValues?.get(1)?.toInt())
        assertEquals("超时15s", "15_000L", Regex("HINT_TIMEOUT_MS = ([0-9_L]+)").find(s)?.groupValues?.get(1))
        assertEquals("护栏点1", 1, Regex("GUARD_FRAME_FIRST = (\\d+)").find(s)?.groupValues?.get(1)?.toInt())
        assertEquals("护栏点12", 12, Regex("GUARD_FRAME_THIRD = (\\d+)").find(s)?.groupValues?.get(1)?.toInt())
        assertEquals("暗房引导60s自收", "60_000L",
            Regex("DARKROOM_GUIDE_TIMEOUT_MS = ([0-9_L]+)").find(s)?.groupValues?.get(1))
        assertFalse("暗房页二次提醒已移除（11-25 用户拍板）", s.contains("EXTRA_FIRST_ROLL_GUIDE"))
        // 八步检测点：选卷 / 进Pro / ISO / 快门 / 退Pro / 对焦 / 曝光 / 落卷
        assertTrue("选卷检测点", s.contains("fun onRollPicked"))
        assertTrue("进Pro检测点", s.contains("fun onProEntered"))
        assertTrue("ISO检测点", s.contains("fun onIsoChanged"))
        assertTrue("快门检测点", s.contains("fun onShutterChanged"))
        assertTrue("退Pro检测点", s.contains("fun onProExited"))
        assertTrue("对焦检测点要在", s.contains("fun onFocusDone"))
        assertTrue("EV 检测点要在", s.contains("fun onEvChanged"))
        assertTrue("落卷检测点要在", s.contains("fun onShotTaken"))
        assertTrue("护栏命中要在", s.contains("fun guardFrameHit"))
        // 八步 stage 常量顺序锁死
        assertEquals("选卷=1", 1, Regex("STAGE_PICK_ROLL = (\\d+)").find(s)?.groupValues?.get(1)?.toInt())
        assertEquals("进Pro=2", 2, Regex("STAGE_ENTER_PRO = (\\d+)").find(s)?.groupValues?.get(1)?.toInt())
        assertEquals("ISO=3", 3, Regex("STAGE_ISO = (\\d+)").find(s)?.groupValues?.get(1)?.toInt())
        assertEquals("快门=4", 4, Regex("STAGE_SHUTTER = (\\d+)").find(s)?.groupValues?.get(1)?.toInt())
        assertEquals("退Pro=5", 5, Regex("STAGE_EXIT_PRO = (\\d+)").find(s)?.groupValues?.get(1)?.toInt())
        assertEquals("对焦=6", 6, Regex("STAGE_FOCUS = (\\d+)").find(s)?.groupValues?.get(1)?.toInt())
        assertEquals("曝光=7", 7, Regex("STAGE_EV = (\\d+)").find(s)?.groupValues?.get(1)?.toInt())
        assertEquals("快门=8", 8, Regex("STAGE_SHOT = (\\d+)").find(s)?.groupValues?.get(1)?.toInt())
    }

    @Test
    fun `首启先弹玩法卡 关掉后接八步 重玩不弹卡 老用户不重播`() {
        val s = main() ?: return
        // 函数里有多个嵌套 if 块，不能截到第一个 }；截到段 2 分区注释才是完整函数体
        val fn = s.substringAfter("private fun maybeShowFirstRunGuide()", "")
            .substringBefore("// ===== v82 段 2", "")
        assertTrue("maybeShowFirstRunGuide 要在", fn.isNotBlank())
        assertTrue("老用户 guide_shown 守卫保留", fn.contains("guide_shown"))
        assertTrue("首启要弹玩法说明书卡（11-25 二版恢复）", fn.contains("QuickStartCard.show"))
        assertTrue("关卡后才进八步实操",
            fn.contains("onDismissed =") && fn.contains("showOnboardingHint()"))
        assertTrue("重玩分支只重播八步、不再弹卡", fn.contains("if (replay)"))
        assertTrue("段2启动 = stage 0→1", fn.contains("OnboardingGuide.setStage(this, 1)"))
    }

    @Test
    fun `段2 八步检测点接线且超时可跳过`() {
        val s = main() ?: return
        // 单行断言（MainActivity.kt 是 CRLF 行尾，跨行锚会因 \r 对不上）
        val picker = s.substringAfter("private fun showFilmRollPicker()", "")
            .substringBefore("\n    }", "")
        assertTrue("打开换卷弹层即推进首步（不能等真的换卷）",
            picker.contains("OnboardingGuide.onRollPicked(this)") &&
                picker.contains("showOnboardingHint()"))
        assertTrue("进/出Pro要挂段2检测",
            s.contains("OnboardingGuide.onProEntered(this)") && s.contains("OnboardingGuide.onProExited(this)"))
        assertTrue("ISO调节要挂段2检测", s.contains("OnboardingGuide.onIsoChanged(this)"))
        assertTrue("快门调节要挂段2检测", s.contains("OnboardingGuide.onShutterChanged(this)"))
        assertTrue("对焦回调要挂段2检测",
            s.contains("if (OnboardingGuide.onFocusDone(this@MainActivity)) showOnboardingHint()"))
        val evBlock = s.substringAfter("setEv(newEv, persist = false)", "")
            .substringBefore("android.view.MotionEvent.ACTION_UP", "")
        assertTrue("EV 手势要挂段2检测", evBlock.contains("OnboardingGuide.onEvChanged(this)"))
        val shot = s.substringAfter("if (OnboardingGuide.onShotTaken(this)) {", "")
            .substringBefore("if (count >= FILM_MAX)", "")
        assertTrue("首张落卷要停超时轮转", shot.contains("stopOnboardingTimeout()"))
        assertTrue("引导最后一拍直接接力暗房指引（不依赖帧号）",
            shot.contains("showDarkroomGuide()"))
        assertTrue("段3护栏提示要在落卷处", shot.contains("guardFrameHit"))
        val hint = s.substringAfter("private fun showOnboardingHint()", "")
            .substringBefore("\n    }", "")
        assertTrue("超时15s要挂轮转", hint.contains("HINT_TIMEOUT_MS"))
        assertTrue("超时跳过不卡人", hint.contains("OnboardingGuide.setStage(this, cur + 1)"))
        assertTrue("onResume 要续播当前段位",
            s.contains("OnboardingGuide.stage(this) in 1 until OnboardingGuide.STAGE_DONE"))
    }

    @Test
    fun `段2 八步LCD文案映射中英齐`() {
        val s = main() ?: return
        val hint = s.substringAfter("private fun showOnboardingHint()", "")
            .substringBefore("\n    }", "")
        listOf("guide_roll", "guide_pro_enter", "guide_iso", "guide_shutter_speed",
            "guide_pro_exit", "guide_focus", "guide_ev", "guide_shoot",
            "guide_tag_roll", "guide_tag_pro_enter", "guide_tag_iso", "guide_tag_shutter",
            "guide_tag_pro_exit", "guide_tag_focus", "guide_tag_ev", "guide_tag_shoot").forEach { key ->
            assertTrue("LCD/胶囊映射缺 $key", hint.contains("R.string.$key"))
        }
        val zh = read("res/values/strings.xml") ?: return
        val en = read("res/values-en/strings.xml") ?: return
        listOf("guide_roll", "guide_pro_enter", "guide_iso", "guide_shutter_speed",
            "guide_pro_exit", "guide_focus", "guide_ev", "guide_shoot").forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
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
    fun `关于页重玩入口重播实操引导 不再弹说明书卡`() {
        val about = read("java/com/filmcam/app/AboutActivity.kt") ?: return
        assertTrue("关于页要绑 rowQuickStart", about.contains("rowQuickStart"))
        assertTrue("重玩写 replay 文件", about.contains("onboarding_replay.txt"))
        assertTrue("重玩 CLEAR_TOP 回主页重播", about.contains("FLAG_ACTIVITY_CLEAR_TOP"))
        assertFalse("重玩不得再弹说明书卡", about.contains("QuickStartCard.show"))
    }

    @Test
    fun `点击位置高亮框只框不拦 且引导结束清除`() {
        val main = main() ?: return
        assertTrue("引导要挂点击框 View", main.contains("GuideHighlightView"))
        assertTrue("高亮框不拦触摸", main.contains("isClickable = false"))
        val hint = main.substringAfter("private fun showOnboardingHint()", "")
            .substringBefore("\n    }", "")
        assertTrue("每步要框出对应控件并带步骤胶囊",
            hint.contains("setTargetLater(highlightTargetForStage(s), s, total, tag)"))
        assertTrue("铭牌只框 CONTAX T3 字牌，不框 match_parent 整条（会盖住胶卷/暗房）",
            main.contains("OnboardingGuide.STAGE_EXIT_PRO -> binding.brandLogo"))
        val stop = main.substringAfter("private fun stopOnboardingTimeout()", "")
            .substringBefore("\n    }", "")
        assertTrue("引导完成要撤框", stop.contains("guideHighlight?.clear()"))
        // 高亮 View 自身：不 consume 触摸、有脉冲、相对 root 定位
        val hv = read("java/com/filmcam/app/ui/GuideHighlightView.kt") ?: return
        assertTrue("框用 getLocationOnScreen 相对定位", hv.contains("getLocationOnScreen"))
        assertTrue("框有呼吸脉冲引起注意", hv.contains("ValueAnimator"))
        assertFalse("框不得拦截触摸事件", hv.contains("onTouchEvent"))
    }

    @Test
    fun `引导提示常驻不闪 高亮框聚光且带步骤`() {
        val main = main() ?: return
        val hint = main.substringAfter("private fun showOnboardingHint()", "")
            .substringBefore("\n    }", "")
        assertTrue("引导 LCD 必须走常驻口径（3.2s 机身提示用户来不及看）",
            hint.contains("binding.lcdDisplay.showGuideMessage("))
        assertFalse("引导不得再走会自收回的普通机身提示",
            hint.contains("showLcdNotice("))
        assertTrue("快门步骤要精确框刻度列，不是整个取景窗",
            hint.contains("shutterScaleHitRect()"))
        val stop = main.substringAfter("private fun stopOnboardingTimeout()", "")
            .substringBefore("\n    }", "")
        assertTrue("引导完成要清常驻 LCD 提示", stop.contains("clearGuideMessage()"))

        val lcd = read("java/com/filmcam/app/ui/T3LcdView.kt") ?: return
        assertTrue("LCD 要有引导常驻方法", lcd.contains("fun showGuideMessage"))
        assertTrue("引导提示不闪烁", lcd.contains("!guideMessage && (SystemClock.uptimeMillis() / 350L)"))
        assertTrue("常驻提示不能把 tick 钉死在 80ms", lcd.contains("(!guideMessage && now < messageUntil)"))

        val hv = read("java/com/filmcam/app/ui/GuideHighlightView.kt") ?: return
        assertTrue("聚光：四周压暗 + 目标挖空", hv.contains("Path.Op.DIFFERENCE"))
        assertTrue("步骤胶囊要画在框上", hv.contains("drawText(caption"))
        assertTrue("支持框控件内部子区域（快门刻度列）", hv.contains("fun setTargetRegion"))
    }

    @Test
    fun `首张落卷引导去暗房冲洗 暗房页不再二次提醒`() {
        val main = main() ?: return
        val shot = main.substringAfter("val forceDarkroomGuide =", "")
            .substringBefore("if (count >= FILM_MAX)", "")
        assertTrue("第 1 张要框暗房字牌带去冲洗", shot.contains("showDarkroomGuide()"))
        assertTrue("引导最后一拍已挂过就不重复重置", shot.contains("if (!darkroomGuideActive)"))
        assertTrue("第 12 张保留普通提示", shot.contains("R.string.guide_third_frame"))
        val galleryClick = main.substringAfter("binding.btnGallery.hapticClick {", "")
            .substringBefore("binding.btnGallery.attachPressFeedback", "")
        assertTrue("点暗房只收引导、不带额外提醒",
            galleryClick.contains("clearDarkroomGuide()") &&
                !galleryClick.contains("EXTRA_FIRST_ROLL_GUIDE"))
        val hv = read("java/com/filmcam/app/ui/GuideHighlightView.kt") ?: return
        assertTrue("无序号的跨页引导胶囊只显示动作短句", hv.contains("step <= 0 -> label"))
        val gal = read("java/com/filmcam/app/GalleryActivity.kt") ?: return
        assertTrue("暗房页不得再弹「点冲洗」二次提醒（11-25 用户拍板）",
            !gal.contains("gallery_first_develop_hint"))
    }

    @Test
    fun `关于页入口与中英文案齐`() {
        val zh = read("res/values/strings.xml") ?: return
        val en = read("res/values-en/strings.xml") ?: return
        listOf("qs_title_1", "qs_body_1", "qs_title_2", "qs_body_2", "qs_title_3", "qs_body_3",
            "guide_roll", "guide_pro_enter", "guide_iso", "guide_shutter_speed",
            "guide_pro_exit", "guide_focus", "guide_ev", "guide_shoot",
            "guide_tag_roll", "guide_tag_pro_enter", "guide_tag_iso", "guide_tag_shutter",
            "guide_tag_pro_exit", "guide_tag_focus", "guide_tag_ev", "guide_tag_shoot",
            "guide_darkroom", "guide_tag_darkroom",
            "guide_first_frame", "guide_third_frame", "about_spec_quickstart",
            "about_quickstart_action").forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
        assertFalse("旧单段引导文案应已删除", zh.contains("main_guide_body"))
        assertFalse("英文旧文案也应删", en.contains("main_guide_body"))
    }
}
