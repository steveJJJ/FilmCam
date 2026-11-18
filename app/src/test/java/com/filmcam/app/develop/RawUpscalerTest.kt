package com.filmcam.app.develop

import org.junit.Assert.*
import org.junit.Test

/**
 * RAW 裁后放大纯函数契约。目标：把 T3 视角裁切后的 2596×1947（5MP）按同一
 * ratio 放大回接近传感器原始 4080×3060，构图/比例不变；JPEG 帧不放大。
 */
class RawUpscalerTest {

    @Test
    fun `放大倍率精确补回传感器尺寸`() {
        // 真机 11-18：源 4080x3060 → 裁后 2596x1947（ratio≈1.5715）
        val (w, h) = RawUpscaler.targetSize(2596, 1947, 1.5715276f)
        assertTrue("宽应接近 4080，实际 $w", w in 4070..4090)
        assertTrue("高应接近 3060，实际 $h", h in 3050..3070)
        // 比例基本不变（4:3）
        val ar = w.toFloat() / h
        assertTrue("宽高比保持≈4:3，实际 $ar", kotlin.math.abs(ar - 4f / 3f) < 0.02f)
    }

    @Test
    fun `竖幅裁后放大保持3比4`() {
        // 竖拍裁后 1947x2596（3:4，T3 等比中心裁不改比例）→ 放大回 ≈3060x4080
        val (w, h) = RawUpscaler.targetSize(1947, 2596, 1.5715276f)
        assertTrue("竖幅宽≈3060，实际 $w", w in 3050..3070)
        assertTrue("竖幅高≈4080，实际 $h", h in 4070..4090)
        assertTrue("竖幅比例≈3:4（等比放大不改比例）", kotlin.math.abs(w.toFloat() / h - 3f / 4f) < 0.02f)
    }

    @Test
    fun `长边硬上限防OOM`() {
        val (w, h) = RawUpscaler.targetSize(2596, 1947, 1.9f)
        assertTrue("长边不超 4608，实际 ${maxOf(w, h)}", maxOf(w, h) <= 4608)
    }

    @Test
    fun `zoom为0的JPEG帧不放大`() {
        // shouldUpscale 只看 zoom>1；JPEG 帧 zoom=0 返回 false
        assertFalse("zoom=0 不放大", RawUpscaler.shouldUpscale("""{"zoom":0.0}"""))
        assertFalse("无 zoom 字段不放大", RawUpscaler.shouldUpscale("""{}"""))
        assertFalse("空 shotJson 不放大", RawUpscaler.shouldUpscale(null))
        assertTrue("RAW zoom>1 放大", RawUpscaler.shouldUpscale("""{"zoom":1.5715276}"""))
    }

    @Test
    fun `无效尺寸原样返回`() {
        assertEquals(0 to 0, RawUpscaler.targetSize(0, 0, 1.57f))
        assertEquals(-1 to 5, RawUpscaler.targetSize(-1, 5, 1.57f))
    }
}
