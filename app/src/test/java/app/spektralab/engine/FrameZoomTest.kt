package app.spektralab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧记录变焦比的回落契约：拍摄时把实际 CONTROL_ZOOM_RATIO 随帧落盘，
 * 冲洗按帧取值；老帧 / 坏数据回落到设备默认，不能把底片裁坏。
 */
class FrameZoomTest {

    @Test
    fun `帧记录值优先`() {
        assertEquals(1.35f, SpektraProcessor.effectiveZoomRatio(1.35f), 1e-6f)
        assertEquals(1.0f, SpektraProcessor.effectiveZoomRatio(1.0f), 1e-6f)
        assertEquals(2.0f, SpektraProcessor.effectiveZoomRatio(2.0f), 1e-6f)
    }

    @Test
    fun `老帧零值与坏数据回落设备默认`() {
        val def = FilmStockMapper.T3_ZOOM_RATIO
        assertEquals(def, SpektraProcessor.effectiveZoomRatio(0f), 1e-6f)
        assertEquals(def, SpektraProcessor.effectiveZoomRatio(-1f), 1e-6f)
        assertEquals(def, SpektraProcessor.effectiveZoomRatio(2.01f), 1e-6f)
        // 异常大的值（旧上限 4.0 会采信）必须回落：否则 ActiveArea 被裁到极小，
        // 成片分辨率塌成几百 KB —— 真机反馈「RAW 成片很小」的防线
        assertEquals(def, SpektraProcessor.effectiveZoomRatio(3.0f), 1e-6f)
        assertEquals(def, SpektraProcessor.effectiveZoomRatio(4.01f), 1e-6f)
        assertEquals(def, SpektraProcessor.effectiveZoomRatio(Float.NaN), 1e-6f)
    }

    @Test
    fun `变焦比越大裁得越窄且原点保持偶对齐`() {
        val wide = SpektraProcessor.zoomCrop(4080, 3060, 1.35f)
        val narrow = SpektraProcessor.zoomCrop(4080, 3060, 1.52f)
        assertTrue(wide[2] > narrow[2])
        assertTrue(wide[3] > narrow[3])
        for (z in listOf(wide, narrow)) {
            assertEquals(0, z[0] % 2)   // CFAPattern 从 ActiveArea 左上角起排，偶偏移不串色
            assertEquals(0, z[1] % 2)
        }
    }

    @Test
    fun `zoomCrop 不传帧值时与设备默认一致`() {
        assertEquals(
            SpektraProcessor.zoomCrop(4080, 3060, FilmStockMapper.T3_ZOOM_RATIO).toList(),
            SpektraProcessor.zoomCrop(4080, 3060).toList()
        )
    }
}
