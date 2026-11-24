package com.filmcam.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置闪光人像肤色 A/B 契约（11-23）。
 *
 * 真机同场景定量（tmp/skin_yellow_1123/）：GOLD/PORTRA 开闪光肤色色相 18.6°/21.7°、
 * 亮度 0.37，vs 系统相机 13.4°/0.64 → 换卷仍黄、且整体偏暗，是闪光帧参数组合问题，
 * 与胶卷无关。本组开关让人像提亮/去灰，但**默认全关**，定稿行为零改变：
 *  - flash_portrait_ev.txt = EV 倍率（抬 ISO 只亮人脸、不亮背景，保 T3 签名）；
 *  - flash_portrait_tonemap.txt=1 = 关线性 tonemap（肤色不再被压灰）。
 * 只作用于内置闪光自动档；手动档/慢门/外闪一律不碰。
 */
class FlashPortraitAbContractTest {

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

    private fun camera(): String? = sourceRoot()?.let {
        File(it, "java/com/filmcam/app/camera/Camera2Controller.kt").takeIf { f -> f.isFile }?.readText()
    }

    private fun fn(s: String): String = s.substringAfter("private fun applyFlashCaptureTuning", "")
        .substringBefore("\n    }", "")

    @Test
    fun `两个AB开关默认关 定稿行为零改变`() {
        val s = camera() ?: return
        assertTrue("曝光倍率默认 0f", s.contains("flashPortraitEv: Float = 0f"))
        assertTrue("tonemap 开关默认 false（保持线性定稿）",
            s.contains("flashPortraitNoLinearTonemap: Boolean = false"))
        // 定稿常量原样保留
        assertTrue("FLASH_LOCK_EV_BIAS 定稿 -0.7 不变", s.contains("const val FLASH_LOCK_EV_BIAS = -0.7f"))
        assertTrue("ISO 上限 250 定稿不变", s.contains("const val FLASH_MAX_FLASH_ISO = 250"))
        assertTrue("总曝光量 3600 定稿不变", s.contains("const val FLASH_MAX_EXPOSURE_PRODUCT = 3600f"))
    }

    @Test
    fun `热调文件缺文件为关 EV夹在负2到正2`() {
        val s = camera() ?: return
        assertTrue("EV 热调文件名", s.contains("FLASH_PORTRAIT_EV_HOTFILE = \"flash_portrait_ev.txt\""))
        assertTrue("tonemap 热调文件名",
            s.contains("FLASH_PORTRAIT_TONEMAP_HOTFILE = \"flash_portrait_tonemap.txt\""))
        assertTrue("EV 缺文件回落 0", s.contains("if (!f.exists()) 0f"))
        assertTrue("EV 夹在 -2..+2", s.contains("coerceIn(-2f, 2f)"))
        assertTrue("tonemap 缺文件/非法=保持线性", s.contains("flashPortraitNoLinearTonemap = if (dir == null) false"))
    }

    @Test
    fun `提亮只抬ISO不碰背景product 保T3签名`() {
        val s = camera() ?: return
        val f = fn(s)
        assertTrue("保留定稿 ISO 计算行（ev=0 逐字相等）",
            f.contains("Math.min(Math.round(lockIso * isoScale), FLASH_MAX_FLASH_ISO)"))
        assertTrue("A/B 只在 ev 非零时生效", f.contains("if (flashPortraitEv != 0f)"))
        assertTrue("抬 ISO 走 2^EV 倍率", f.contains("Math.pow(2.0, flashPortraitEv.toDouble())"))
        assertTrue("抬 ISO 要同时放宽 ISO 硬帽", f.contains("FLASH_MAX_FLASH_ISO * portraitIsoMul"))
        // 背景曝光量 targetProduct 的钳制必须原样保留（不被 A/B 污染）
        assertTrue("背景 product 仍钳原上限（T3 背景压暗签名）",
            f.contains("Math.min(rawProduct, effMaxProduct)"))
        assertFalse("不得用 A/B 倍率去改目标曝光量（那会连背景一起提亮）",
            f.substringAfter("val targetProduct =", "").substringBefore("\n")
                .contains("flashPortraitEv"))
    }

    @Test
    fun `tonemap开关只跳过线性曲线 不动其他调优`() {
        val s = camera() ?: return
        val f = fn(s)
        assertTrue("线性 tonemap 的门控加了 A/B 开关",
            f.contains("canLinearTonemap && !flashPortraitNoLinearTonemap"))
        assertTrue("AWB 钉日光不受影响", f.contains("CONTROL_AWB_MODE_DAYLIGHT"))
        assertTrue("ZSL off 不受影响", f.contains("CONTROL_ENABLE_ZSL"))
        assertTrue("只作用内置闪光自动档：手动档/慢门仍早退",
            f.contains("if (slow || manualIso != null || manualExposure != null) return"))
    }

    @Test
    fun `日志带AB状态便于真机取证`() {
        val s = camera() ?: return
        assertTrue("FlashTuning 日志要打曝光倍率与 tonemap 开关",
            s.contains("portraitEv=\$flashPortraitEv") &&
                s.contains("portraitNoLinearTonemap=\$flashPortraitNoLinearTonemap"))
    }
}
