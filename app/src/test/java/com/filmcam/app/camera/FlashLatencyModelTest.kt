package com.filmcam.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 曝光时长触发模型契约（11-23）。
 *
 * 根因：旧方案拿「上一帧实测 HAL 延迟」预测本帧触发——换快门档时缓存必过期，
 * 用户实测「闪光时间跟着快门走」。S25U 8 样本拟合出延迟 ≈ max(90ms, 5×曝光ms)
 * （斜率 5.0、截距≈0、±10%），模型直接从本帧曝光算，换档即时正确。
 */
class FlashLatencyModelTest {

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

    @Test
    fun `sourceRoot 防假绿`() {
        assertTrue(read("app/src/main/java/com/filmcam/app/camera/Camera2Controller.kt") != null)
    }

    @Test
    fun `模型数学 地板90ms与5倍曝光`() {
        assertEquals(90L, Camera2Controller.predictFlashLatencyMs(8L))    // 短曝光落地板
        assertEquals(165L, Camera2Controller.predictFlashLatencyMs(33L))  // 1/30
        assertEquals(625L, Camera2Controller.predictFlashLatencyMs(125L)) // 1/8
        assertEquals(880L, Camera2Controller.predictFlashLatencyMs(176L)) // 1/5 实际钳制值
    }

    @Test
    fun `调度点用本帧曝光建模且保留实测缓存做比对`() {
        val s = camera() ?: return
        assertTrue("调度点必须走曝光模型", s.contains("predictFlashLatencyMs(pendingFlashExposureNs"))
        assertTrue("必须窗内瞄准 1/3（起辉波动容差）", s.contains("aimIntoWindowMs"))
        assertTrue("模型必须有热调回退开关", s.contains("FLASH_LATENCY_MODEL_HOTFILE"))
        assertTrue("本帧曝光必须在 applyExternalFlashSync 求解后写入",
            s.contains("pendingFlashExposureNs = solved.shutterNs"))
        assertTrue("实测缓存必须保留并标注仅供比对",
            s.contains("仅供比对") && s.contains("saveFlashLatency(flashHalLatencyMs)"))
        assertTrue("回退分支必须保留旧日志语义",
            s.contains("上次实测校准") && s.contains("兜底未校准"))
    }

    @Test
    fun `纯函数仍保留供旧路径与编排契约使用`() {
        val s = camera() ?: return
        assertTrue(s.contains("fun flashTriggerDelayMs(halLatencyMs: Long, offsetMs: Long): Long"))
        assertTrue(s.contains("fun flashPreRollMs(halLatencyMs: Long, offsetMs: Long): Long"))
    }
}
