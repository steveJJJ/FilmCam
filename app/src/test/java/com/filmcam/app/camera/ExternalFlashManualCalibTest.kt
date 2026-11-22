package com.filmcam.app.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 外闪手动标定档契约（11-22）：Pro 模式 + 外闪档下，手动 ISO/快门必须真正
 * 作用到拍照帧（不能被 gain 反算覆盖），且灯侧 TTL 要关掉以免乱改功率。
 */
class ExternalFlashManualCalibTest {

    private fun read(rel: String): String? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val d = dir ?: return null
            val root = File(d, rel)
            if (root.exists()) return root.readText()
            dir = d.parentFile
        }
        return null
    }

    private fun camera() = read("app/src/main/java/com/filmcam/app/camera/Camera2Controller.kt")
    private fun main() = read("app/src/main/java/com/filmcam/app/MainActivity.kt")

    @Test
    fun `sourceRoot 真跑到源码 防假绿`() {
        assertTrue("必须读到 Camera2Controller", camera() != null)
        assertTrue("必须读到 MainActivity", main() != null)
    }

    @Test
    fun `applyExternalFlashSync必须有手动档分支`() {
        val s = camera() ?: return
        assertTrue("必须有手动标定档标志", s.contains("extFlashManualCalib"))
        assertTrue("手动分支必须用 manualIso/manualExposure 判定",
            s.contains("extFlashManualCalib && manualIso != null && manualExposure != null"))
        assertTrue("手动值必须钳到设备区间",
            s.contains("manualIso!!.coerceIn(deviceIsoRange.first, deviceIsoRange.last)"))
        assertTrue("手动快门必须钳到设备区间",
            s.contains("manualExposure!!.coerceIn(minShutterNs, maxShutterNs)"))
        assertTrue("必须构造手动 Result",
            s.contains("FlashExposure.Result(iso = iso, shutterNs = shutter"))
    }

    @Test
    fun `手动标志由Pro加外闪驱动且退出要清`() {
        val m = main() ?: return
        assertTrue("applyExposure 必须按 Pro+外闪设标志",
            m.contains("val manualCalib = proMode && isExt"))
        assertTrue("退出 Pro 必须清标志",
            m.contains("cameraController?.extFlashManualCalib = false"))
        assertTrue("手动档必须关灯侧TTL",
            m.contains("externalTtlEnabled = isExt && !manualCalib"))
    }

    @Test
    fun `HAL延迟实测必须每帧写盘不得攒样本`() {
        val s = camera() ?: return
        // 11-22 缓存恶性循环：原来 size>=2 才写盘，会话重建样本永远=1⇒缓存卡陈旧值
        assertTrue("实测延迟必须无条件写盘", s.contains("saveFlashLatency(flashHalLatencyMs)"))
        assertTrue("实测后标记已校准", s.contains("flashHalLatencyCalibrated = true"))
        assertTrue("不得再攒够2样本才写（缓存修复注释须在）",
            s.contains("缓存恶性循环修复") && !s.contains("if (flashHalLatencySamples.size >= 2) saveFlashLatency"))
        assertTrue("必须有校准来源标记供取证", s.contains("flashHalLatencyCalibrated"))
        assertTrue("触发日志须标明校准来源",
            s.contains("上次实测校准") && s.contains("兜底未校准"))
    }

    @Test
    fun `applyExposure不得冲掉Pro手动值`() {
        val m = main() ?: return
        // 直接对整份源码断言（applyExposure 内有嵌套 try/if，分段截取会提前截断）
        assertTrue("applyExposure 必须按 proMode 设回手动 ISO（A档回落胶卷ISO）",
            m.contains("setManualIso(if (manualHere) proManualIso() else null)"))
        assertTrue("A 档必须有胶卷 ISO 兜底（否则退回 gain 反算）",
            m.contains("private fun proManualIso(): Int = isoSteps[isoIdx] ?: effectiveFilmIso()"))
        assertTrue("applyExposure 必须按 proMode 设回手动快门",
            m.contains("setManualExposure(if (manualHere) shutterSteps[shutterIdx] else null)"))
    }

    @Test
    fun `快门下限1_5档与176ms同步上限（11-23）`() {
        val m = main() ?: return
        assertTrue("旋钮必须有 1/5 档（200ms 请求，HAL 钳到 176ms）",
            m.contains("200_000_000L,     // 1/5"))
        assertTrue("液晶标签必须补 5",
            m.contains("listOf(\"500\", \"250\", \"125\", \"60\", \"30\", \"15\", \"8\", \"5\")"))
        assertTrue("1/5 必须落取景窗 LT 区",
            m.contains("setOf(\"LT\"),            // 1/5"))
        val c = camera() ?: return
        assertTrue("外闪同步上限必须放宽到设备真实上限 176ms",
            c.contains("MAX_SYNC_SHUTTER_NS = 176_000_000L"))
        assertTrue("不得再钳 150ms", !c.contains("MAX_SYNC_SHUTTER_NS = 150_000_000L"))
    }

    @Test
    fun `外闪档三处手动手势锁必须拆除`() {
        val m = main() ?: return
        // 三处手势函数里不再有 isExternalFlash 拦截（按函数分段断言）
        fun body(sig: String) = m.substringAfter(sig, "")
            .substringBefore("\n    }", "")
        assertTrue("onShutterDrag 不得锁外闪", !body("private fun onShutterDrag(").contains("isExternalFlash"))
        assertTrue("stepIso 不得锁外闪", !body("private fun stepIso(").contains("isExternalFlash"))
        assertTrue("onIsoDrag 不得锁外闪", !body("private fun onIsoDrag(").contains("isExternalFlash"))
    }
}
