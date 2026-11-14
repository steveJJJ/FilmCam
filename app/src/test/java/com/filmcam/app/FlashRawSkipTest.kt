package com.filmcam.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v81（11-14 复盘修订）：内置直闪帧是否跳过 RAW 会话必须**按 DNG 实际像素
 * 动态判定**，不得按档位写死。
 *
 * S25U 真机定量（tmp/flash4_raw_1114）：HAL 在闪光拍照中 RAW_SENSOR 流
 * 不吃光——直闪帧 DNG 净信号仅 ≈30/1023（主体区同样贴黑），同快门 HAL
 * JPEG 正常吃光；黑底片叠增益救不回闪光信息（+1EV 背景仍在地板、主体先爆）。
 * 但其他机型 RAW 流多半正常吃光——写死 `flash=="BUILT_IN"` 全跳等于把那些
 * 机器的直闪画质无条件降级为 JPEG（用户点名机型兼容）。
 *
 * 契约：
 *  - BUILT_IN 是先决条件，但**跳过必须再过 [TiffRawFlashStat] 净信号判定**；
 *  - 解析失败 / 净信号正常 → 不跳（误判方向只能「多走 RAW」，不能误降级）；
 *  - 「盲」判定按设备缓存（HAL 行为是设备级属性），正常机型每帧轻量复检；
 *  - EXTERNAL / 普通帧不受影响。
 */
class FlashRawSkipTest {

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

    private fun dev(): String? =
        read("java/com/filmcam/app/develop/DevelopmentService.kt")

    private fun stat(): String? =
        read("java/com/filmcam/app/develop/TiffRawFlashStat.kt")

    /** RAW 通路入口段：从函数签名到「只看 bounds」的 JPEG 解码段。 */
    private fun rawBranch(s: String): String =
        s.substringAfter("P3 RAW 通路：有 DNG 底片的帧走引擎 RAW 会话", "")
            .substringBefore("只看 bounds", "")

    @Test
    fun `内置直闪帧跳过 RAW 必须过净信号动态判定 不得写死全跳`() {
        val s = dev() ?: return
        val fn = rawBranch(s)
        assertTrue("RAW 分支必须先判 BUILT_IN 直闪帧",
            fn.contains("ShotMeta.fromJson(shot)?.flash == \"BUILT_IN\""))
        assertTrue("跳过必须再过 isBuiltInFlashRawBlind 动态判定（机型兼容）",
            fn.contains("isBuiltInFlashRawBlind"))
        assertTrue("RAW 分支入口必须带 rawSkipForBuiltInFlash 守卫",
            fn.contains("!rawSkipForBuiltInFlash"))
        assertTrue("跳过时必须有诊断日志（真机验证靠它区分通路口径）",
            fn.contains("内置直闪帧跳过 RAW 会话"))
    }

    @Test
    fun `动态判定的保守方向 解析失败不跳 只缓存盲判定`() {
        val s = dev() ?: return
        val fn = s.substringAfter("private fun isBuiltInFlashRawBlind", "")
            .substringBefore("private fun processOneFrame", "")
        assertTrue("设备缓存键 flash_raw_blind 必须存在",
            fn.contains("\"flash_raw_blind\""))
        assertTrue("缓存命中直接返回（盲判定按设备记）",
            fn.contains("getBoolean(\"flash_raw_blind\", false)"))
        assertFalse("只允许写 true 进缓存，正常机型必须每帧复检",
            fn.contains("putBoolean(\"flash_raw_blind\", false)"))
        assertTrue("解析失败必须走「不跳」分支（不误降级）",
            fn.contains("不跳 RAW，保持原通路"))
    }

    @Test
    fun `TiffRawFlashStat 读原始像素 门限与保守语义`() {
        val st = stat() ?: return
        assertTrue("门限常量必须存在（S25U 黑帧 2.9%~3.4%，正常闪光帧远高于此）",
            st.contains("BLIND_NET_RATIO = 0.05f"))
        assertTrue("必须读 DNG 中心区原始 Bayer（引擎渲图会被印相归一化骗掉）",
            st.contains("netSignalRatio"))
        assertTrue("BlackLevel 必须按 RATIONAL 取（DngCreator CFA 写法）",
            st.contains("TAG_BLACK_LEVEL = 50714") && st.contains("TAG_WHITE_LEVEL = 50717"))
        assertTrue("结构不认识必须返回 null（调用侧不跳）",
            st.contains("return@runCatching null"))
        assertTrue("只认无压缩 16bit 每行一 strip 的 DngCreator 结构",
            st.contains("bps != 16") && st.contains("rowsPerStrip != 1L"))
    }

    @Test
    fun `EXTERNAL 与普通帧不受影响`() {
        val s = dev() ?: return
        val fn = rawBranch(s)
        assertFalse("判定只精确匹配 BUILT_IN，不许把 EXTERNAL 一起跳过",
            fn.contains("flash != \"OFF\"") || fn.contains("flash != null"))
        assertTrue("回落 JPEG 通路（isFlash 参数链）原样保留",
            s.substringAfter("private fun processOneFrame", "")
                .contains("flashParamsJson ?: paramsJson"))
    }
}
