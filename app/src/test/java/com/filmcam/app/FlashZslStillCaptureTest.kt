package com.filmcam.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v81 断点②：内置直闪成片帧必须关 ZSL。
 *
 * 11-14 S25U 真机取证（raw=true、INT 档、evBias=0 连拍 4 张）：
 * HAL 对 STILL_CAPTURE + ALWAYS_FLASH 走 CheckZslStillCapture —— LED 主闪
 * 序列照跑（led mode 2→0），但输出帧取自主闪前的 ZSL 历史暗帧：4 张 RAW
 * 底片全部贴黑电平（98% 像素暗于 +256/1023），成片参数却是闪光档
 * （iso=23、1/60s、flashState=FIRED）。参数与帧内容错配 = ZSL 挑旧帧。
 * 修复 = 直闪调优里把 CONTROL_ENABLE_ZSL 设 false，强制 HAL 现场拍主闪帧。
 */
class FlashZslStillCaptureTest {

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

    private fun camera(): String? {
        val root = sourceRoot() ?: return null
        return File(root, "java/com/filmcam/app/camera/Camera2Controller.kt")
            .takeIf { it.isFile }?.readText()
    }

    private fun flashTuningFn(s: String): String =
        s.substringAfter("private fun applyFlashCaptureTuning", "")
            .substringBefore("\n    }", "")

    @Test
    fun `直闪调优必须给拍照帧关 ZSL`() {
        val s = camera() ?: return
        val fn = flashTuningFn(s)
        assertTrue("applyFlashCaptureTuning 内必须设 CONTROL_ENABLE_ZSL=false",
            fn.contains("builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)"))
        assertTrue("关 ZSL 前必须有 canDisableZsl 能力守卫（口径与慢门一致）",
            fn.contains("if (canDisableZsl)"))
    }

    @Test
    fun `关 ZSL 的效果要进 FlashTuning 诊断日志`() {
        val s = camera() ?: return
        val fn = flashTuningFn(s)
        assertTrue("FlashTuning capture 行必须带 zslOff 字段，真机验证靠它区分新旧行为",
            fn.contains("zslOff=$") || fn.contains("zslOff=\$zslOff"))
    }
}
