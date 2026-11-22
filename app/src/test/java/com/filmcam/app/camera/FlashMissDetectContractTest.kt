package com.filmcam.app.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 外闪黑片侦测契约（11-22 真机根因）。
 *
 * 取证：A2 没电时 BLE 指令照发、灯不闪，直出中心 p50=1/1/1/1；
 * 满电正常张 p50≥91。中间有巨大分界，p50≤5 可可靠识别「灯没闪」。
 * 用户拍板：LCD 提示 + 照常落卷（不拦快门、不丢张，自己决定删留）。
 */
class FlashMissDetectContractTest {

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

    private fun read(rel: String): String? = sourceRoot()?.let {
        File(it, rel).takeIf { f -> f.isFile }?.readText()
    }

    private fun camera(): String? = read("java/com/filmcam/app/camera/Camera2Controller.kt")

    @Test
    fun `sourceRoot 真的解析到源码 防止空跑假绿`() {
        val s = camera()
        assertTrue("必须读到 Camera2Controller.kt", s != null)
        assertTrue("源码里必须有黑片侦测阈值", s!!.contains("FLASH_MISS_P50_MAX"))
    }

    @Test
    fun `黑片阈值锁在5 守住正常张与黑片的巨大分界`() {
        val s = camera() ?: return
        assertTrue("阈值必须=5（黑片p50=1，正常张≥91）",
            s.contains("const val FLASH_MISS_P50_MAX = 5"))
    }

    @Test
    fun `回调存在且有守卫 只在外闪非慢门非测光帧判定`() {
        val s = camera() ?: return
        assertTrue("必须有黑片侦测回调", s.contains("var onFlashMissDetected"))
        // 判定条件：外闪档 + 非慢门 + 非TTL测光帧 + p50<=阈值
        val guard = s.substringAfter("// 外闪黑片侦测（11-22）", "")
            .substringBefore("\n            }", "")
        assertTrue("必须用本次外闪正式帧标记守卫", guard.contains("flashFrameArmed"))
        assertTrue("takePicture 必须按外闪且非慢门置位",
            s.contains("flashFrameArmed = externalFlashSync && !slow"))
        assertTrue("必须排除TTL测光帧", guard.contains("!ttlMetering"))
        assertTrue("必须用中心p50与阈值比较", guard.contains("centerLuma.p50 <= FLASH_MISS_P50_MAX"))
    }

    @Test
    fun `亮度只解码一次 探针与黑片侦测共用`() {
        val s = camera() ?: return
        assertTrue("中心亮度必须只解一次码复用",
            s.contains("val centerLuma = if (meterProbe || externalFlashSync) analyzeMeteringLuma(bytes) else null"))
        assertTrue("探针块必须保持 if (meterProbe) 字面量（旧契约依赖）",
            s.contains("if (meterProbe) {"))
    }

    @Test
    fun `UI绑定LCD提示且照常落卷不拦截`() {
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        assertTrue("MainActivity 必须绑定黑片回调", main.contains("onFlashMissDetected ="))
        assertTrue("必须用 LCD showMessage 提示",
            main.contains("binding.lcdDisplay.showMessage(getString(R.string.main_flash_miss_notice)")
        )
        val str = read("res/values/strings.xml") ?: return
        assertTrue("提示字符串必须存在",
            str.contains("name=\"main_flash_miss_notice\""))
    }
}
