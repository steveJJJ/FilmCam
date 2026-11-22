package com.filmcam.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * 多帧覆盖转正契约。
 *
 * A2 灯触发后约 440ms 出主闪，但本机相机「提交→真实曝光」延迟抖动
 * 238~539ms、单帧窗硬上限 150ms，任何预测提前量都猜不准。一次快门连拍 18 帧
 * （40ms 间隔让窗连续覆盖），触发延时发出，成片按 SENSOR_TIMESTAMP 配对三路碎片、
 * 选最亮那帧写 DNG 母片交付。转正默认开启，热调文件可覆盖（0/1 关闭做 A/B）。
 */
class BurstCoverageContractTest {

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
        ?: throw AssertionError("Camera2Controller.kt 未找到（契约退化）")

    @Test
    fun `多帧覆盖默认开启18帧 热调可覆盖关闭`() {
        val src = cam()
        assertTrue("必须定义 BURST_HOTFILE", src.contains("BURST_HOTFILE"))
        assertTrue("热调文件名必须是 flash_burst.txt", src.contains("flash_burst.txt"))
        assertTrue("必须有默认帧数常量", src.contains("BURST_DEFAULT_FRAMES = 18"))
        assertTrue("必须有固定帧间隔常量（≤AE窗宽40ms）", src.contains("BURST_STEP_MS = 40L"))
        assertTrue("热调缺文件必须回落默认帧数",
            src.contains("if (!f.exists()) BURST_DEFAULT_FRAMES"))
        assertTrue("帧数必须钳到 20 上限", src.contains("coerceIn(0, 20)"))
        assertTrue("小于 2 帧必须归零关闭", src.contains("if (it < 2) 0 else it"))
    }

    @Test
    fun `burst前置拦截先于快门前置 触发延时单次不依赖halLatency预测`() {
        val src = cam()
        val guardToken = "if (burstFrames >= 2 && externalFlashSync && !slowShutter) {"
        val burstGuard = src.indexOf(guardToken)  // 首次出现＝takePicture 里的前置拦截
        val preRollGuard = src.indexOf("if (externalFlashSync && !slowShutter && handler != null) {")
        assertTrue("必须存在 burst 前置拦截分支", burstGuard >= 0)
        assertTrue("burst 前置拦截必须在快门前置之前", burstGuard < preRollGuard)
        val firstBlock = src.substring(burstGuard).substringBefore("\n        }")
        assertTrue("burst 前置拦截必须直接提交并 return",
            firstBlock.contains("submitStillCaptureNow(captureBuilder, slowShutter)") &&
                firstBlock.contains("return"))

        val block = src.substringAfter("// 多帧覆盖验证（外闪档 + flash_burst.txt=N）")
            .substringBefore("// 慢门已把 AE 关掉")
        assertTrue("必须用独立 session.capture 连拍（不用 captureBurst 被 HAL 重测）",
            block.contains("session.capture(captureBuilder.build()"))
        assertFalse("不得再用 captureBurst 调用", block.contains("session.captureBurst("))
        assertTrue("各帧必须间隔提交铺开覆盖带", block.contains("i * stepMs"))
        assertTrue("帧间隔必须用固定 40ms 而非 shutterNs（HAL 压窗）",
            block.contains("val stepMs = BURST_STEP_MS"))
        assertTrue("触发必须延时发出", block.contains("BURST_TRIGGER_DELAY_MS"))
        assertTrue("触发必须走 fireExternalFlashOnce 防重单发",
            block.contains("fireExternalFlashOnce(\"多帧覆盖\")"))
        assertFalse("burst 分支不得调用预测提前量 scheduleFlashPreTrigger",
            block.contains("scheduleFlashPreTrigger"))
        assertFalse("burst 构建块不得写 CONTROL_MODE_OFF（三星 HAL 会忽略手动曝光）",
            src.contains("burst 帧锁 CONTROL_MODE=OFF"))
    }

    @Test
    fun `三路靠SENSOR_TIMESTAMP配对 最亮帧写DNG母片`() {
        val src = cam()
        val jpegBlock = src.substringAfter("imageReader?.setOnImageAvailableListener")
            .substringBefore("if (ttlMetering) {")
        assertTrue("JPEG 必须在 close 前抓 SENSOR_TIMESTAMP", jpegBlock.contains("val ts = image.timestamp"))
        assertTrue("JPEG 必须归入 burstMap", jpegBlock.contains("burstMap.getOrPut(ts)"))
        assertTrue("必须按 p50 选最亮帧", jpegBlock.contains("reading.p50 > burstBestP50"))
        assertTrue("必须记录最亮帧 timestamp", jpegBlock.contains("burstPickedTs = ts"))

        val rawBlock = src.substringAfter("rawImageReader?.setOnImageAvailableListener")
            .substringBefore("android.util.Log.i(TAG, \"RAW 负片可用")
        assertTrue("RAW 必须按 timestamp 归入 burstMap", rawBlock.contains("burstMap.getOrPut(ts)"))
        assertFalse("burst 的 RAW 不得无条件丢弃",
            rawBlock.contains("if (burstActive) {\n                    image.close()"))

        assertTrue("result 必须读 SENSOR_TIMESTAMP",
            src.contains("CaptureResult.SENSOR_TIMESTAMP"))
        assertTrue("burst result 必须归入 burstMap", src.contains("bf.result = result"))

        assertTrue("必须写 DNG 母片", src.contains("raw_burst_"))
        assertTrue("交付必须带 dng 文件", src.contains("onPhotoCaptured?.invoke(jpeg, bf.rotation, dng)"))
        assertTrue("配对失败必须降级纯 JPEG", src.contains("按纯 JPEG 落卷"))
    }

    @Test
    fun `burst帧必须收RAW且缓冲支撑18帧连拍`() {
        val src = cam()
        assertFalse("不得再有排除 burst 的 RAW 判断", src.contains("rawNegativeEnabled && !burstMode"))
        assertTrue("captureBuilder 仍要给 RAW 加 target",
            src.contains("rawImageReader?.surface?.let { addTarget(it) }"))
        assertTrue("JPEG 缓冲必须 >=18 支撑连拍不丢帧",
            src.contains("ImageFormat.JPEG, 20"))
        assertTrue("RAW 缓冲必须 >=18 支撑连拍不丢帧",
            src.contains("ImageFormat.RAW_SENSOR, 20"))
    }

    @Test
    fun `burst三路没齐有超时兜底不卡死`() {
        val src = cam()
        assertTrue("必须有配对超时兜底", src.contains("三路配对超时"))
        assertTrue("超时交付必须降级纯 JPEG", src.contains("deliverBurstBest(BurstFrame()"))
        assertTrue("收尾必须关掉残余 RAW 防泄漏", src.contains("for (bf in burstMap.values)"))
    }
}
