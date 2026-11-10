package com.filmcam.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v68 P0 的机型契约：预览尺寸必须收敛到 4:3，且**基准机型 S25U 零变化**。
 *
 * 候选表全部来自真机 `filmcam_device_report.txt`（不是编造的）：
 *  - vivo X300 Ultra V2547A，2026-11-10 21:43，vc65，1260×2772 @3.5
 *  - vivo V2227A，2026-11-10 13:55，vc65，1440×3200 @4.0
 *  - 三星 S25U（基准机）：最大面积候选本来就是 1920×1440
 */
class PreviewSizePolicyTest {

    private fun pick(sizes: List<Pair<Int, Int>>, maxLongEdge: Int = 1920) =
        PreviewSizePolicy.choose(sizes, maxLongEdge)

    /** X300 Ultra 完整候选表：1:1 / 1.185 / 1.6 / 2.4 的面积都比 4:3 大。 */
    private fun x300Ultra() = listOf(
        1920 to 1920, 1920 to 1620, 1920 to 1440, 1920 to 1200,
        1920 to 1080, 1632 to 1224, 1600 to 1200, 1920 to 864,
        1440 to 1080, 1920 to 800, 1216 to 1080, 1440 to 900
    )

    private fun v2227a() = listOf(
        1920 to 1920, 1920 to 1440, 1920 to 1080,
        1632 to 1224, 1600 to 1200, 1440 to 1080, 1280 to 960
    )

    private fun s25u() = listOf(
        1920 to 1440, 1920 to 1080, 1440 to 1080, 1280 to 960, 1280 to 720
    )

    @Test
    fun `vivo X300 Ultra 必须跳过面积更大的 1920x1920 选 1920x1440`() {
        assertEquals(1920 to 1440, pick(x300Ultra()))
    }

    @Test
    fun `vivo V2227A 同一根因同样收敛到 1920x1440`() {
        assertEquals(1920 to 1440, pick(v2227a()))
    }

    @Test
    fun `基准机 S25U 逐像素等价旧行为`() {
        assertEquals(1920 to 1440, pick(s25u()))
        // 旧逻辑 = 面积最大；新策略在基准机上不能改变任何结果
        assertEquals(
            s25u().maxByOrNull { it.first * it.second },
            pick(s25u())
        )
    }

    @Test
    fun `无 4x3 候选时严格回退旧面积最大行为`() {
        // 只有 16:9：仍取面积最大
        assertEquals(1920 to 1080, pick(listOf(1920 to 1080, 1280 to 720, 1024 to 576)))
        // 含 1:1 且面积更大：回退后依旧选 1:1（不擅自替 HAL 改比例，留给 P1）
        assertEquals(1920 to 1920, pick(listOf(1920 to 1920, 1920 to 1080)))
    }

    @Test
    fun `竖置尺寸按 max 比 min 归一化后也算 4x3`() {
        // 1080×1440 面积 1.555M < 1920×1080 的 2.074M，但它是 4:3 → 必须优先
        assertEquals(1080 to 1440, pick(listOf(1920 to 1080, 1080 to 1440)))
        assertTrue(PreviewSizePolicy.isTargetAspect(1440, 1920))
        assertTrue(PreviewSizePolicy.isTargetAspect(1920, 1440))
        assertEquals(4f / 3f, PreviewSizePolicy.normalizedAspect(1080, 1440), 0.0001f)
    }

    @Test
    fun `近似 4x3 在容差内而 16x10 之类不计入`() {
        assertTrue(PreviewSizePolicy.isTargetAspect(1920, 1448))   // 1.3260，差 0.0074
        assertFalse(PreviewSizePolicy.isTargetAspect(1920, 1600))  // 1.2000
        assertEquals(
            1920 to 1448,
            pick(listOf(1920 to 1600, 1920 to 1448))  // 面积更小的近似 4:3 胜出
        )
    }

    @Test
    fun `长边上限过滤 12MP 全尺寸预览`() {
        // 4080×3072 私有流会让 Adreno 的 EGLImage 绑定失败（bindTextureImage 0x502）
        val sizes = listOf(4080 to 3072, 1920 to 1920, 1920 to 1440, 1920 to 1080)
        assertEquals(1920 to 1440, pick(sizes, maxLongEdge = 1920))
        assertEquals(4080 to 3072, pick(sizes, maxLongEdge = Int.MAX_VALUE))
    }

    @Test
    fun `空表与非法尺寸返回 null 交给调用方兜底`() {
        assertNull(pick(emptyList()))
        assertNull(pick(listOf(0 to 0, -1 to 1080, 1920 to 0)))
        assertNull(pick(listOf(4080 to 3072), maxLongEdge = 1920))
    }

    @Test
    fun `比例偏差量化 1x1 等于 25 个百分点且报警`() {
        assertEquals(0.25f, PreviewSizePolicy.aspectErrorFraction(1920, 1920), 0.0001f)
        assertEquals(0f, PreviewSizePolicy.aspectErrorFraction(1920, 1440), 0.0001f)
        assertTrue(PreviewSizePolicy.aspectErrorFraction(1920, 1920) > PreviewSizePolicy.ASPECT_TOLERANCE)
        assertFalse(PreviewSizePolicy.isTargetAspect(1920, 1920))
    }

    @Test
    fun `结果与候选表输入顺序无关`() {
        assertEquals(pick(x300Ultra()), pick(x300Ultra().reversed()))
        assertEquals(pick(v2227a()), pick(v2227a().shuffled(java.util.Random(7))))
    }
}
