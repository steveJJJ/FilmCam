package com.filmcam.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 预闪测光可行性探针（flash_meter_probe.txt）契约。
 *
 * 探针的唯一职责：在**不改任何拍照/触发/调功率行为**的前提下，把每张相机直出
 * JPEG 的中心 p50/p90 + 触发时真实功率字节打进日志，用来定量回答「相机读数
 * 能否看见灯的功率/距离差」——这是预闪 TTL 能不能落地的地基。
 *
 * 死锁三条：
 *  1. 默认关闭：缺文件不打印，线上零影响；
 *  2. 只读：不得出现在 takePicture / fireExternalFlashOnce / flashPowerAdjust
 *     等改变行为的调用路径里，不得 setManualIso / invoke(onFlashPowerRequest)；
 *  3. 独立：打印块不嵌在 ttlHotOff 守卫内，关掉 TTL 闭环时仍能读到原始值。
 */
class MeterProbeContractTest {

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

    private fun cam(): String = read("java/com/filmcam/app/camera/Camera2Controller.kt")
        ?: throw AssertionError("Camera2Controller.kt 未找到（契约退化：sourceRoot 没真跑到）")

    @Test
    fun `探针热调默认关闭 缺文件不打印`() {
        val src = cam()
        assertTrue("必须定义 METER_PROBE_HOTFILE", src.contains("METER_PROBE_HOTFILE"))
        assertTrue("热调文件名必须是 flash_meter_probe.txt",
            src.contains("flash_meter_probe.txt"))
        assertTrue("缺文件时必须回落 false", src.contains("getOrDefault(false)"))
    }

    @Test
    fun `探针只读 不改变拍照触发调功率行为`() {
        val src = cam()
        val probeBlock = src.substringAfter("if (meterProbe) {")
            .substringBefore("\n            }")
        assertTrue("探针块必须打出原始 p50", probeBlock.contains("p50="))
        assertTrue("探针块必须打出功率字节做定量对应", probeBlock.contains("lastSentPowerByte"))
        assertFalse("探针不得改 ISO", probeBlock.contains("setManualIso"))
        assertFalse("探针不得下发功率", probeBlock.contains("onFlashPowerRequest"))
        assertFalse("探针不得触发拍照", probeBlock.contains("takePicture"))
    }

    @Test
    fun `关TTL时必须连拍前两段式测光帧一起关 不留闪两次的暗门`() {
        val src = cam()
        val gate = src.substringAfter(
            "if (EXTERNAL_TTL_ENABLED && externalFlashSync && externalTtlEnabled && !slow &&"
        ).substringBefore(") {")
        assertTrue("ttlHotOff 必须进入两段式测光帧的守卫", gate.contains("!ttlHotOff"))
        // 关 TTL 却仍拍 ISO100 测光帧 = 一次快门闪两次、成片黑，是本次真机定位到的根因
        assertTrue("两段式守卫文本必须保留 !orchestratedFlashSync",
            gate.contains("!orchestratedFlashSync"))
    }

    @Test
    fun `探针独立于TTL闭环 关掉闭环仍能读原始值`() {
        val src = cam()
        val ttlBlock = src.substringAfter("if (externalTtlEnabled && externalFlashSync && !ttlHotOff) {")
            .substringBefore("// 预闪测光可行性探针")
        assertFalse("探针块不得嵌在 ttlHotOff 守卫内", ttlBlock.contains("if (meterProbe)"))
        assertTrue("探针块必须在 TTL 闭环之后独立存在",
            src.indexOf("if (meterProbe)") > src.indexOf("if (externalTtlEnabled && externalFlashSync && !ttlHotOff)"))
    }
}
