package com.filmcam.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 外置闪光（氙气灯）拍照帧白平衡契约（11-22 真机根因）。
 *
 * 取证：暖光房间 AWB 实测 CT≈3079K、B 通道增益 2.524；五张外闪成片全部
 * 系统性偏蓝 B−R=+28~+54。成因＝闪光 ~1ms 太快，AWB 来不及为它重测光，
 * 拍照帧套用了"暖光→猛拉蓝"的 AUTO 增益，把中性氙气光补偿成蓝青。
 *
 * 内置闪光早有 applyFlashCaptureTuning 在 AUTO 时钉 DAYLIGHT 防偏色，但外闪
 * 走独立的 applyExternalFlashSync 路径、没经过那笔 —— 本测试锁住对称补齐：
 * 外闪帧在 AWB=AUTO/null 时也必须钉 DAYLIGHT，用户手动预设则尊重不覆盖。
 */
class ExternalFlashAwbContractTest {

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
        File(it, "java/com/filmcam/app/camera/Camera2Controller.kt")
            .takeIf { f -> f.isFile }?.readText()
    }

    /** 只取 applyExternalFlashSync 函数体，避免匹配到内置闪光的同名逻辑。 */
    private fun extFn(s: String): String = s
        .substringAfter("private fun applyExternalFlashSync", "")
        .substringBefore("\n    }", "")

    @Test
    fun `sourceRoot 真的解析到源码 防止空跑假绿`() {
        val s = camera()
        assertTrue("必须读到 Camera2Controller.kt，否则本契约全程空跑", s != null)
        assertTrue("源码里必须存在外闪曝光函数", s!!.contains("private fun applyExternalFlashSync"))
    }

    @Test
    fun `外闪帧在AUTO时钉日光 尊重用户手动预设`() {
        val s = camera() ?: return
        val fn = extFn(s)
        assertTrue("AUTO/null 才钉日光：必须判 currentAwbMode",
            fn.contains("currentAwbMode == null") &&
                fn.contains("CONTROL_AWB_MODE_AUTO"))
        assertTrue("必须写 DAYLIGHT 白平衡（氙气≈5500K）",
            fn.contains("CONTROL_AWB_MODE_DAYLIGHT"))
        assertTrue("必须受开关与 AUTO 双重条件保护，不能无条件覆盖手动预设",
            fn.contains("val awbDaylight = extFlashAwbDaylight &&"))
    }

    @Test
    fun `外闪白平衡锁定默认开 且可热调回退`() {
        val s = camera() ?: return
        assertTrue("热调文件名常量要在",
            s.contains("EXT_FLASH_AWB_DAYLIGHT_HOTFILE = \"flash_awb_daylight.txt\""))
        assertTrue("默认值必须是开（字段初值 true）",
            s.contains("private var extFlashAwbDaylight: Boolean = true"))
        // readFlashSyncConfig 里缺文件=开，显式 0 才关
        val read = s.substringAfter("extFlashAwbDaylight = if (dir == null)", "")
            .substringBefore("\n", "")
        assertTrue("缺文件=开（dir==null 分支 true）", read.contains("true"))
        assertTrue("显式写 0 才关",
            s.contains("f.readText().substringBefore(\"//\").trim() != \"0\"") ||
                read.contains("!= \"0\""))
    }

    @Test
    fun `日志带awbDaylight状态便于真机定量A B`() {
        val s = camera() ?: return
        val fn = extFn(s)
        assertTrue("FlashSync 日志必须带 awbDaylight 状态",
            fn.contains("awbDaylight=\$awbDaylight"))
    }

    @Test
    fun `不许破坏内置闪光的AWB DAYLIGHT分支`() {
        val s = camera() ?: return
        val intFlash = s.substringAfter("private fun applyFlashCaptureTuning", "")
            .substringBefore("\n    }", "")
        assertTrue("内置闪光 DAYLIGHT 分支必须原样保留",
            intFlash.contains("CONTROL_AWB_MODE_DAYLIGHT"))
        // 两处独立：外闪的钉日光不能挪进内置函数导致重复
        assertFalse("外闪钉日光必须在 applyExternalFlashSync 内，不能塞进内置函数",
            intFlash.contains("extFlashAwbDaylight"))
    }
}
