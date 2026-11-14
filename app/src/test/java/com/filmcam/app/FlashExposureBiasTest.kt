package com.filmcam.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v81：内置直闪成片「过暗」的曝光偏置契约。
 *
 * 11-14 真机同场景 A/B（关闪 vs INT，相隔 4s）：
 *   HAL `ON_ALWAYS_FLASH` 重测光 iso 202/1:23 → 23/1:60，环境光掉 ≈4.5 EV；
 *   成片均值 89.8 → 27.0、暗像素 46% → 83%、四角 49 → 10。
 * 主因是 HAL 测光；App 额外再压 -2/3 EV 属于过冲。用户拍板 A 方案，把
 * [Camera2Controller.FLASH_EV_BIAS] 归零，只去掉这一层，保留直闪味。
 */
class FlashExposureBiasTest {

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

    private fun camera(): String? = read("java/com/filmcam/app/camera/Camera2Controller.kt")

    @Test
    fun `直闪曝光偏置归零 不许退回负 EV`() {
        val s = camera() ?: return
        assertTrue("FLASH_EV_BIAS 必须是 0f", s.contains("const val FLASH_EV_BIAS = 0f"))
        assertFalse("不许再出现旧的 -2/3 常量",
            s.contains("const val FLASH_EV_BIAS = -2f / 3f"))
        // 注释里的历史定量可以提负数，但常量定义只能有一个
        val defs = Regex("const val FLASH_EV_BIAS\\s*=").findAll(s).count()
        assertTrue("偏置常量只能定义一次，实际 $defs 处", defs == 1)
    }

    @Test
    fun `按 AE 步长换算下发的能力要留着 且零偏置时不写 EV 补偿`() {
        val s = camera() ?: return
        val fn = s.substringAfter("private fun applyFlashCaptureTuning", "")
            .substringBefore("\n    }", "")
        assertTrue("仍按设备 AE 步长换算整档",
            fn.contains("Math.round(FLASH_EV_BIAS / stepEv)"))
        assertTrue("非零档才写 AE_EXPOSURE_COMPENSATION",
            fn.contains("if (evBiasSteps != 0)"))
        assertTrue("写的是用户 EV + 偏置档数",
            fn.contains("(exposureComp + evBiasSteps)"))
        assertTrue("仍要夹在设备真实 AE 范围内",
            fn.contains("coerceIn(aeCompRange.first, aeCompRange.last)"))
    }

    @Test
    fun `归零只去偏置 直闪另外三层调优一字不动`() {
        val s = camera() ?: return
        assertTrue("AWB 钉日光还在", fnHas(s, "CONTROL_AWB_MODE_DAYLIGHT"))
        assertTrue("线性 tonemap 还在", fnHas(s, "TONEMAP_MODE_CONTRAST_CURVE"))
        assertTrue("拍照帧仍挂 applyFlashCaptureTuning",
            s.contains("applyFlashCaptureTuning(this, slow)"))
        assertTrue("拍照帧仍挂 ALWAYS_FLASH",
            s.contains("applyStillFlashSettings(this)"))
        assertTrue("守卫不变：手动曝光/慢门不调优",
            s.contains("if (slow || manualIso != null || manualExposure != null) return"))
    }

    private fun fnHas(s: String, key: String): Boolean =
        s.substringAfter("private fun applyFlashCaptureTuning", "")
            .substringBefore("\n    }", "").contains(key)
}
