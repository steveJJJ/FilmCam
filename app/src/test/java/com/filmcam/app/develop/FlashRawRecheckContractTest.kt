package com.filmcam.app.develop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置闪光 RAW 盲判定「逐帧化」契约（11-24 二次修复）。
 *
 * 历史「黑底片」按全帧均值 ≈3% 判盲（闪光照片背景本就贴黑），判定器量中心 128²
 * 真机 7/8 帧达 0.05~0.38 正常吃光；一度改 v2 键 + sticky 缓存后，一张 0.048
 * 欠曝帧写死缓存、连坐跳过后续 0.237/0.380 正常帧。最终修复：**去缓存、逐帧判定**。
 * 代价 ~10ms（冲洗一帧 5s，可忽略），误判方向仍朝「多走 RAW」不误降级。
 */
class FlashRawRecheckContractTest {

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

    private fun develop(): String? = sourceRoot()?.let {
        File(it, "java/com/filmcam/app/develop/DevelopmentService.kt").takeIf { f -> f.isFile }?.readText()
    }

    private fun fn(s: String): String = s.substringAfter("private fun isBuiltInFlashRawBlind", "")
        .substringBefore("\n    }", "")

    @Test
    fun `历史误判缓存双键清除 改为逐帧判定`() {
        val s = develop() ?: return
        val f = fn(s)
        assertTrue("旧 flash_raw_blind（全帧3%口径）必须清除",
            f.contains("remove(\"flash_raw_blind\")"))
        assertTrue("v2 sticky 键也必须清除（连坐误杀已废）",
            f.contains("remove(\"flash_raw_blind_v2\")"))
        assertTrue("清除后每帧读 DNG 中心区净信号",
            f.contains("TiffRawFlashStat.netSignalRatio(dng)"))
        assertTrue("清除日志便于取证", f.contains("清除历史误判缓存"))
    }

    @Test
    fun `无设备级缓存 判据仍是中心区净信号 保守方向不变`() {
        val s = develop() ?: return
        val f = fn(s)
        assertFalse("不得写任何 sticky 缓存",
            f.contains("putBoolean(") || f.contains("getBoolean("))
        assertTrue("门限仍是 5%", f.contains("TiffRawFlashStat.BLIND_NET_RATIO"))
        assertTrue("解析失败不跳 RAW（不误降级）", f.contains("不跳 RAW，保持原通路"))
        assertTrue("本帧判盲必须注明逐帧语义（不影响后续帧）",
            f.contains("逐帧判定，不影响后续帧"))
        assertFalse("不得按 BUILT_IN 写死全跳（别的机型 RAW 正常）",
            f.contains("flash == \"BUILT_IN\"") && f.contains("return true"))
    }

    @Test
    fun `不再残留一次性复检开关`() {
        val s = develop() ?: return
        assertFalse("复检开关已完成使命，不应残留在正式代码里",
            fn(s).contains("flash_raw_recheck.txt"))
    }
}
