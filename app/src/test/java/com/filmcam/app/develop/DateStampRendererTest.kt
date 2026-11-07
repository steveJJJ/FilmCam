package com.filmcam.app.develop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v36：日期戳落点。只测纯函数，不碰 Android Canvas / Typeface。
 * 守护的核心是「带框成片时日期戳必须落回画面区，不能印到片基印刷带上」。
 */
class DateStampRendererTest {

    @Test
    fun `裸画面按整图定位`() {
        val m = DateStampRenderer.contentMetrics(4000, 3000, null)
        assertArrayEquals(floatArrayOf(0f, 0f, 4000f, 3000f, 1f, 0.032f), m, 0f)
        val p = DateStampRenderer.place(m, 600f, 20f)
        assertEquals(4000f - 4000f * 0.032f - 600f, p.x, 0.01f)
        assertEquals(3000f - 4000f * 0.032f - 20f, p.baseline, 0.01f)
        assertEquals(4000f * 0.036f, p.textSize, 0.01f)
    }

    @Test
    fun `纯画面比例一律不传内容区`() {
        assertNull(DateStampRenderer.contentForAspect(3000, 2000))  // 3:2 片门
        assertNull(DateStampRenderer.contentForAspect(2000, 3000))  // 2:3 竖片门
        assertNull(DateStampRenderer.contentForAspect(4000, 3000))  // 4:3 gateCrop
        assertNull(DateStampRenderer.contentForAspect(3000, 4000))  // 3:4
        assertNull(DateStampRenderer.contentForAspect(0, 100))
    }

    @Test
    fun `带框横拍识别为横置内容区`() {
        // 真机带框成片 2710x2503 ≈ 1.083，不是任何裸画面比例
        assertEquals(
            DateStampRenderer.CONTENT_LANDSCAPE,
            DateStampRenderer.contentForAspect(2710, 2503),
        )
    }

    @Test
    fun `带框横拍日期戳落回画面区`() {
        val c = DateStampRenderer.contentForAspect(2710, 2503)
        assertNotNull(c)
        val m = DateStampRenderer.contentMetrics(2710, 2503, c)
        val p = DateStampRenderer.place(m, 700f, 24f)
        val relY = p.baseline / 2503f
        // 画面区 y∈[0.141,0.866]，旧实现落在 0.95 的片基印刷带
        assertTrue("日期戳应落在画面区内，实际 $relY", relY > 0.70f && relY < 0.866f)
        assertTrue("不应再落到底部齿孔/印刷带", relY < 0.90f)
        assertTrue("文字右边缘应在内容区内", p.x + 700f < 2710f * 0.99f)
    }

    @Test
    fun `带框竖拍识别为竖置内容区`() {
        // 真机带框竖拍 2503x2710 ≈ 0.924
        assertEquals(
            DateStampRenderer.CONTENT_PORTRAIT,
            DateStampRenderer.contentForAspect(2503, 2710),
        )
    }

    @Test
    fun `带框竖拍日期戳落在画面内且避开两侧齿孔带`() {
        val c = DateStampRenderer.contentForAspect(2503, 2710)
        assertNotNull(c)
        val m = DateStampRenderer.contentMetrics(2503, 2710, c)
        val p = DateStampRenderer.place(m, 700f, 24f)
        val relY = p.baseline / 2710f
        // 竖拍画面列内 y 0.05~0.95 一直是影像，底部没有独立齿孔/印刷带。
        // 旧值 bottom=0.83 把日期戳抬到 y≈0.80 悬在画面中间（用户 16:11 报「位置不对」）。
        assertTrue("竖拍日期戳应贴近画面底部，实际 $relY", relY > 0.88f && relY < 0.95f)
        // v41：17:35 真机逐列精扫，影像一直到 x 0.84，x 0.85 才是右片基、
        // x 0.87~0.92 才是齿孔列。旧值 right=0.77 是把暗场景误判成片基。
        val right = (p.x + 700f) / 2503f
        // v43：水平内缩 0.065×cw → 右端 0.805，离 x=0.85 的右片基留 0.045w
        // （v41 0.028w 太贴 = 压框，v42 0.073w 太远）
        assertTrue("右边缘不应压到右片基，实际 $right", right <= 0.82f)
        assertTrue("应比 v42 的 0.782 更靠右，实际 $right", right > 0.79f)
        assertTrue("左边缘不应进入左侧齿孔带", p.x / 2503f > 0.17f)
    }

    @Test
    fun `v42 竖拍字号与横拍齐平且明显大于 v40`() {
        val pm = DateStampRenderer.contentMetrics(2503, 2710, DateStampRenderer.CONTENT_PORTRAIT)
        val lm = DateStampRenderer.contentMetrics(2710, 2503, DateStampRenderer.CONTENT_LANDSCAPE)
        val portrait = DateStampRenderer.place(pm, 700f, 24f).textSize
        val landscape = DateStampRenderer.place(lm, 700f, 24f).textSize
        assertEquals(pm[2] * 0.036f * 1.60f, portrait, 0.01f)
        assertEquals(lm[2] * 0.036f, landscape, 0.01f)
        // v40 竖拍 54px、v41 80px、v42 约 98px（与横拍 96px 齐平）
        assertTrue("竖拍应明显放大，实际 $portrait", portrait > 2503f * 0.60f * 0.036f * 1.7f)
        assertTrue("应与横拍字高相当，实际 $portrait / $landscape",
            portrait > landscape * 0.95f && portrait < landscape * 1.10f)
    }

    @Test
    fun `竖拍裸画面带框时套用等效内容区且底部不抬高`() {
        assertNull(DateStampRenderer.portraitFramedSource(4000, 3000, true))   // 横拍
        assertNull(DateStampRenderer.portraitFramedSource(3000, 4000, false))  // 片框关
        val c = DateStampRenderer.portraitFramedSource(3000, 4000, true)
        assertNotNull(c)
        val m = DateStampRenderer.contentMetrics(3000, 4000, c)
        assertArrayEquals(floatArrayOf(0f, 0f, 3000f, 4000f, 1.40f, 0.065f), m, 0.01f)
        val p = DateStampRenderer.place(m, 900f, 30f)
        // 裸右端 0.932 → 引擎映射 0.17+0.932*0.68 = 0.805，离片基 0.85 有 0.045w
        val sourceRight = (p.x + 900f) / 3000f
        assertTrue("裸右端应收回到 0.93，实际 $sourceRight",
            sourceRight > 0.92f && sourceRight < 0.95f)
        // 垂直内缩仍是 0.032×cw，字变大也不会把底边一起抬高
        assertTrue("底边不应上移，实际 ${p.baseline / 4000f}", p.baseline / 4000f > 0.96f)
        assertEquals(3000f * 0.036f * 1.40f, p.textSize, 0.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `水平内缩越界被拒`() {
        DateStampRenderer.ContentRect(0f, 0f, 1f, 1f, marginXFraction = 0.6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非正字号补偿被拒`() {
        DateStampRenderer.ContentRect(0.17f, 0.02f, 0.85f, 0.95f, textScale = 0f)
    }

    @Test
    fun `旧整图定位确实落在印刷带（回归对照）`() {
        val m = DateStampRenderer.contentMetrics(2503, 2710, null)
        val p = DateStampRenderer.place(m, 700f, 24f)
        assertTrue(
            "旧行为 baseline 应在 0.93 以上，实际 ${p.baseline / 2710f}",
            p.baseline / 2710f > 0.93f,
        )
    }

    @Test
    fun `内容区缩放后字高按内容区宽度而非整图宽度`() {
        val m = DateStampRenderer.contentMetrics(2710, 2503, DateStampRenderer.CONTENT_LANDSCAPE)
        val p = DateStampRenderer.place(m, 700f, 24f)
        assertEquals(m[2] * 0.036f, p.textSize, 0.01f)
        // 内容区宽 0.98*2710，比整图略窄，字也应略小
        assertTrue(p.textSize < 2710f * 0.036f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非法内容区被拒`() {
        DateStampRenderer.ContentRect(0.9f, 0f, 0.1f, 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `越界内容区被拒`() {
        DateStampRenderer.ContentRect(0f, 0f, 1.2f, 1f)
    }
}
