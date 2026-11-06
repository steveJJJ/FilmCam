package com.filmcam.app.film

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拍摄参数快照（随帧落盘 → 成片 EXIF → 拍摄同款）的编解码契约。
 *
 * 这一层一旦漂移，老照片的「拍摄同款」会静默失效（读不到参数但不报错），
 * 所以键名与缺省值都钉在这里。
 */
class ShotMetaTest {

    @Test
    fun `完整字段往返`() {
        val src = ShotMeta(
            flash = "EXTERNAL", awbMode = 4, ev = -2,
            shutterSec = 1f / 8f, slow = true, defocus = true
        )
        val back = ShotMeta.fromJson(src.toJson())!!
        assertEquals(src.flash, back.flash)
        assertEquals(src.awbMode, back.awbMode)
        assertEquals(src.ev, back.ev)
        assertEquals(src.shutterSec, back.shutterSec, 1e-6f)
        assertTrue(back.slow)
        assertTrue(back.defocus)
    }

    @Test
    fun `常规快门不写入 sec 键`() {
        val json = ShotMeta(flash = "OFF", awbMode = 1, ev = 0).toJson()
        assertFalse(json.contains("\"sec\""))
        assertEquals(0f, ShotMeta.fromJson(json)!!.shutterSec, 0f)
    }

    @Test
    fun `脏数据返回 null 而不是崩`() {
        assertNull(ShotMeta.fromJson(null))
        assertNull(ShotMeta.fromJson(""))
        assertNull(ShotMeta.fromJson("not json"))
    }

    @Test
    fun `缺字段按缺省补齐`() {
        val back = ShotMeta.fromJson("""{"flash":"BUILT_IN"}""")!!
        assertEquals("BUILT_IN", back.flash)
        assertEquals(1, back.awbMode)   // AUTO
        assertEquals(0, back.ev)
        assertFalse(back.slow)
        assertFalse(back.defocus)
    }

    @Test
    fun `变焦比随帧落盘并往返`() {
        // 这一张实际下发的 CONTROL_ZOOM_RATIO：冲洗端按帧补裁 RAW 的 ActiveArea，
        // 不能依赖「相机是否刚在这个进程里初始化过」
        val json = ShotMeta(flash = "OFF", awbMode = 1, zoom = 1.35f).toJson()
        assertTrue(json.contains("\"zoom\""))
        assertEquals(1.35f, ShotMeta.fromJson(json)!!.zoom, 1e-6f)
    }

    @Test
    fun `老帧没有 zoom 键时读成 0 且不写空键`() {
        val legacy = """{"flash":"OFF","wb":1,"ev":0,"slow":false,"defocus":false}"""
        assertEquals(0f, ShotMeta.fromJson(legacy)!!.zoom, 0f)
        // 0 = 未记录：不往新帧里写这个键，省得把「不知道」和「1.0」混淆
        assertFalse(ShotMeta(zoom = 0f).toJson().contains("\"zoom\""))
    }
}
