package com.filmcam.app.film

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 母片纯数据层契约（v2 方案）。不依赖 Context，纯 JVM 可跑。 */
class MotherFilmRegistryTest {

    private fun rec(fps: MutableList<String> = mutableListOf()) = MotherFilmRegistry.MotherRec(
        rollId = "roll_1", index = 1,
        sourceHandle = "content://media/1", rawPath = "/neg/frame_01.dng",
        recipeJson = "{\"film\":\"gold\"}", shotJson = "{\"gate\":\"6x6\"}",
        fingerprint = "fp_abc", exportedFingerprints = fps,
        inputEv = 0.5f, normK = 0.7f,
        rawGainEv = 1.3f, rawParamsJson = "{\"auto_exposure\":\"false\"}"
    )

    @Test
    fun `序列化反序列化 保留全部字段 含已导出指纹集合`() {
        val rec = rec(mutableListOf("h1", "h2"))
        val json = MotherFilmRegistry.serialize(listOf(rec))
        val out = MotherFilmRegistry.deserialize(json)
        assertEquals(1, out.size)
        assertEquals("roll_1", out[0].rollId)
        assertEquals(1, out[0].index)
        assertEquals("content://media/1", out[0].sourceHandle)
        assertEquals("/neg/frame_01.dng", out[0].rawPath)
        assertEquals("{\"film\":\"gold\"}", out[0].recipeJson)
        assertEquals("{\"gate\":\"6x6\"}", out[0].shotJson)
        assertEquals("fp_abc", out[0].fingerprint)
        assertEquals(0.5f, out[0].inputEv, 0.001f)
        assertEquals(0.7f, out[0].normK, 0.001f)
        assertEquals(1.3f, out[0].rawGainEv, 0.001f)
        assertEquals("{\"auto_exposure\":\"false\"}", out[0].rawParamsJson)
        assertEquals(listOf("h1", "h2"), out[0].exportedFingerprints)
    }

    @Test
    fun `空或损坏 JSON 返回空列表 容错不崩`() {
        assertTrue(MotherFilmRegistry.deserialize(null).isEmpty())
        assertTrue(MotherFilmRegistry.deserialize("").isEmpty())
        assertTrue(MotherFilmRegistry.deserialize("{broken").isEmpty())
        assertTrue(MotherFilmRegistry.deserialize("[]").isEmpty())
    }

    @Test
    fun `指纹 随源指纹相框日期引擎版本变化而变化`() {
        val base = MotherFilmRegistry.editFingerprint("fp", 0, false, 1)
        assertFalse(base == MotherFilmRegistry.editFingerprint("fp2", 0, false, 1))
        assertFalse(base == MotherFilmRegistry.editFingerprint("fp", 1, false, 1))
        assertFalse(base == MotherFilmRegistry.editFingerprint("fp", 0, true, 1))
        assertFalse(base == MotherFilmRegistry.editFingerprint("fp", 0, false, 2))
        // 同参数必须稳定
        assertEquals(base, MotherFilmRegistry.editFingerprint("fp", 0, false, 1))
    }

    @Test
    fun `指纹是64位hex`() {
        val fp = MotherFilmRegistry.editFingerprint("fp", 2, true, 385)
        assertEquals(64, fp.length)
        assertTrue(fp.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `幂等 已导出指纹不重复添加`() {
        val rec = rec()
        assertFalse(MotherFilmRegistry.isExported(rec, "h1"))
        MotherFilmRegistry.markExported(rec, "h1")
        assertTrue(MotherFilmRegistry.isExported(rec, "h1"))
        MotherFilmRegistry.markExported(rec, "h1")
        assertEquals(1, rec.exportedFingerprints.size)
    }

    @Test
    fun `keyOf 对同帧稳定 对不同帧不同`() {
        assertEquals(MotherFilmRegistry.keyOf("r", 3), MotherFilmRegistry.keyOf("r", 3))
        assertTrue(MotherFilmRegistry.keyOf("r", 3) != MotherFilmRegistry.keyOf("r", 4))
    }
}
