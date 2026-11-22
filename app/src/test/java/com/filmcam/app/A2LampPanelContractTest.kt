package com.filmcam.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A2 造型灯控制弹层契约（2026-11-22 用户拍板：从工程控制台迁出，点 LCD 调出）。
 *
 * 锁死：LCD 钩子只在 A2 会话时弹层、弹层控件与下发行为、工程控制台卡片拆除、
 * 复位提示文案必须写 0c 00（实际下发的帧，不是老的错文案 0c 50）、
 * FlashLampControl 能力面不动（0x0c 永不由 UI 直接碰）。
 */
class A2LampPanelContractTest {

    @Test
    fun `A2 灯控弹层布局与文案齐全`() {
        val layout = read("res/layout/dialog_a2_lamp.xml") ?: return
        val zh = read("res/values/strings.xml") ?: return
        val en = read("res/values-en/strings.xml") ?: return
        assertTrue(
            "布局必须有 T3 字标 + 蚀刻码 + 状态行 + 亮度/色温滑杆 + 复位/关闭按钮",
            layout.contains("@+id/ivA2LampLogo") &&
                layout.contains("@string/a2_lamp_code") &&
                layout.contains("@+id/tvA2LampStatus") &&
                layout.contains("@+id/sbA2Bright") &&
                layout.contains("@+id/sbA2Cct") &&
                layout.contains("@+id/btnA2LampReset") &&
                layout.contains("@+id/tvA2LampClose")
        )
        assertTrue(
            "中英都要有 a2_lamp_* 全套文案，且不得再有 eng_a2_* 残留",
            zh.contains("name=\"a2_lamp_code\"") &&
                zh.contains("name=\"a2_lamp_reset_done\"") &&
                en.contains("name=\"a2_lamp_code\"") &&
                !zh.contains("eng_a2_") && !en.contains("eng_a2_")
        )
    }

    @Test
    fun `LCD 钩子只在 A2 会话弹层 弹层下发行为完整`() {
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        assertTrue(
            "LCD 点击必须挂 maybeShowA2Lamp（背光自检照常先播）",
            main.contains("binding.lcdDisplay.pulseBacklight()") &&
                Regex("pulseBacklight\\(\\)\\s*\\n[^}]*maybeShowA2Lamp\\(\\)").containsMatchIn(main)
        )
        val start = main.indexOf("private fun maybeShowA2Lamp()")
        assertTrue("钩子函数必须存在", start >= 0)
        val hookBody = main.substring(start, main.indexOf("private fun showA2LampPanel", start))
        assertTrue(
            "识别口径必须走 FlashProfile.isA2Device + FlashController.currentLightName（单一口径）",
            hookBody.contains("FlashController.active?.currentLightName()") &&
                hookBody.contains("FlashProfile.isA2Device(")
        )
        val body = main.substring(main.indexOf("private fun showA2LampPanel"))
        assertTrue(
            "弹层必须发亮度/色温/复位三能力，进面板先 setLed(false) 保护连拍",
            body.contains("lamp.setBrightnessPercent(sbBright.progress)") &&
                body.contains("lamp.setColorTemp(sbCct.progress)") &&
                body.contains("lamp?.resetFlashMode()") &&
                Regex("setLed\\(false\\)[\\s\\S]{0,200}sbBright\\.setOnSeekBarChangeListener")
                    .containsMatchIn(body)
        )
        assertTrue(
            "复位提示必须写实际下发的 0c 00，不得回退到错的 0c 50",
            main.contains("R.string.a2_lamp_reset_done") && !main.contains("0c 50")
        )
        assertTrue(
            "动效接线：入场 animatePanelIn + 关闭 dismissPanel + 外部/返回 attachOutsideDismiss",
            body.contains("view.animatePanelIn()") &&
                body.contains("dialog.attachOutsideDismiss(view)") &&
                Regex("tvA2LampClose[\\s\\S]{0,200}dialog\\.dismissPanel\\(view\\)").containsMatchIn(body)
        )
    }

    @Test
    fun `工程控制台 A2 卡片已拆除 能力面保留`() {
        val console = read("java/com/filmcam/app/AboutActivity.kt") ?: return
        val hidden = read("res/layout/dialog_hidden_settings.xml") ?: return
        val lamp = read("java/com/filmcam/app/flash/FlashLampControl.kt") ?: return
        assertTrue(
            "工程控制台不得再有 A2 卡片（groupEngA2 / tvA2Status / sbA2Bright）",
            !hidden.contains("groupEngA2") &&
                !console.contains("tvA2Status") &&
                !console.contains("sbA2Bright") &&
                !console.contains("sbA2Power") &&
                !console.contains("groupEngA2")
        )
        assertTrue(
            "FlashLampControl 能力面不动：亮度/色温/复位/功率组帧知识保留（0x0c 仍不由 UI 碰）",
            lamp.contains("fun setBrightnessPercent(pct: Int): Boolean") &&
                lamp.contains("fun setColorTemp(pos: Int): Boolean") &&
                lamp.contains("fun resetFlashMode(): Boolean") &&
                lamp.contains("fun setFlashPowerLevel(powerByte: Int): Boolean = false")
        )
    }

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
}
