package com.filmcam.app.flash

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 外闪连接看门狗 + EXT 取证契约。
 *
 * 真机反馈（小米 17 Pro / HyperOS）：配对向导里试闪都亮，回到取景器按快门不闪。
 * 根因之一是连接状态机可以把 `connecting` 永久卡在 true —— connectGatt 的回调在
 * 部分 ROM 上会静默丢失，之后 `scheduleReconnect` 的 `if (connecting) return`
 * 把重连全挡掉，`writeChar` 永远是 null，trigger() 每次都走 NOT_READY 分支。
 * 向导页用的是它自己那条 GATT，所以「试闪能亮」。
 *
 * 另一个问题是取证缺失：报告里只有内置闪光的 precapture 计数，看不出外接灯的
 * 指令发没发、发在曝光前多少毫秒。现在两个驱动都挂看门狗，相机侧把触发
 * 时机写进诊断报告。
 */
class FlashReconnectContractTest {

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
    fun `两个驱动都必须有连接看门狗 且在连接时武装 回调时撤掉`() {
        for (rel in listOf(
            "java/com/filmcam/app/flash/LearnedProfileDriver.kt",
            "java/com/filmcam/app/flash/FotorGearFlash.kt"
        )) {
            val src = read(rel) ?: return
            assertTrue("$rel 要有 connectWatchdog", src.contains("connectWatchdog"))
            assertTrue("$rel 要在连接时 postDelayed 武装看门狗",
                src.contains("postDelayed(connectWatchdog"))
            assertTrue("$rel 的回调里要撤掉看门狗，避免误杀正常连接",
                src.contains("removeCallbacks(connectWatchdog)"))
            assertTrue("$rel 的看门狗要复位 connecting 再重连",
                src.contains("connecting = false") && src.contains("scheduleReconnect(300L)"))
        }
    }

    @Test
    fun `trigger 未就绪时不能把 connecting 卡死`() {
        for (rel in listOf(
            "java/com/filmcam/app/flash/LearnedProfileDriver.kt",
            "java/com/filmcam/app/flash/FotorGearFlash.kt"
        )) {
            val src = read(rel) ?: return
            val idx = src.indexOf("TRIGGER_SKIP NOT_READY")
            assertTrue("$rel 要有 NOT_READY 分支", idx > 0)
            val tail = src.substring(idx, minOf(src.length, idx + 400))
            assertTrue("$rel 的 NOT_READY 分支要先复位 connecting 才能重连",
                tail.contains("if (gatt == null) connecting = false"))
        }
    }

    @Test
    fun `EXT 触发时机要进诊断报告`() {
        val cam = read("java/com/filmcam/app/camera/Camera2Controller.kt") ?: return
        assertTrue("要记录触发次数", cam.contains("\"flash.extTriggers\""))
        assertTrue("要记录触发通路（快门前置 / 曝光开始 / 预触发）",
            cam.contains("\"flash.extLastTriggerWhere\""))
        assertTrue("要记录指令→曝光开始的间隔", cam.contains("\"flash.extTriggerToExposureMs\""))
        assertTrue("要记录本帧前置量", cam.contains("\"flash.extLastPreRollMs\""))
        assertTrue("要记录 EXT 同步是否开着", cam.contains("\"flash.extSync\""))

        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        assertTrue("外闪状态串（TRIGGER_SENT / FAIL / SKIP）要进报告",
            main.contains("DeviceGeometryReport.put(\"flash.extStatus\""))
    }
}
