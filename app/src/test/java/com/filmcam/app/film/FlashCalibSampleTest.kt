package com.filmcam.app.film

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外闪手动标定样本契约（11-22）：为「人工标定 → 归纳 TTL」采集的字段
 * 必须能随 ShotMeta 完整序列化/反序列化，否则样本信息在 EXIF 往返中丢失。
 */
class FlashCalibSampleTest {

    @Test
    fun `标定字段往返一致`() {
        val meta = ShotMeta(
            flash = "EXTERNAL",
            calibId = 1795355560930L,
            calibPwr = 10,
            calibDist = 1.75f,
            calibAmbIso = 7186,
            calibAmbSec = 0.04f,
            calibP50 = 123,
            calibManual = true,
        )
        val json = meta.toJson()
        // 短键都要出现
        assertTrue("calibId 键 cid 缺失", json.contains("\"cid\""))
        assertTrue("功率键 cpw 缺失", json.contains("\"cpw\""))
        assertTrue("距离键 cdi 缺失", json.contains("\"cdi\""))
        assertTrue("环境ISO键 cai 缺失", json.contains("\"cai\""))
        assertTrue("环境快门键 cas 缺失", json.contains("\"cas\""))
        assertTrue("p50键 cp5 缺失", json.contains("\"cp5\""))
        assertTrue("手动标志键 cma 缺失", json.contains("\"cma\""))

        val back = ShotMeta.fromJson(json)!!
        assertEquals(meta.calibId, back.calibId)
        assertEquals(meta.calibPwr, back.calibPwr)
        assertEquals(meta.calibDist, back.calibDist, 0.001f)
        assertEquals(meta.calibAmbIso, back.calibAmbIso)
        assertEquals(meta.calibAmbSec, back.calibAmbSec, 0.0001f)
        assertEquals(meta.calibP50, back.calibP50)
        assertEquals(meta.calibManual, back.calibManual)
    }

    @Test
    fun `普通帧不带标定字段 默认值为空`() {
        val meta = ShotMeta(flash = "OFF")
        val json = meta.toJson()
        assertTrue("普通帧不该写 cid", !json.contains("\"cid\""))
        assertTrue("普通帧不该写 cpw", !json.contains("\"cpw\""))
        val back = ShotMeta.fromJson(json)!!
        assertEquals(0L, back.calibId)
        assertEquals(-1, back.calibPwr)
        assertEquals(false, back.calibManual)
    }

    @Test
    fun `老版本JSON缺标定字段要能兼容回落`() {
        val oldJson = """{"flash":"EXTERNAL","wb":1,"ev":0,"siso":25}"""
        val back = ShotMeta.fromJson(oldJson)!!
        assertEquals(0L, back.calibId)
        assertEquals(-1, back.calibPwr)
        assertEquals(false, back.calibManual)
    }
}
