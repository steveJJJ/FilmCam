package com.filmcam.app.develop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 编辑参数纯数据层契约（v2 方案）。 */
class EditsCodecTest {

    @Test
    fun `相框三档合法 越界非法`() {
        assertTrue(EditsCodec.validFrame(EditsCodec.FRAME_NONE))
        assertTrue(EditsCodec.validFrame(EditsCodec.FRAME_FILM))
        assertTrue(EditsCodec.validFrame(EditsCodec.FRAME_WHITE))
        assertFalse(EditsCodec.validFrame(-1))
        assertFalse(EditsCodec.validFrame(3))
    }

    @Test
    fun `FrameEdit 越界构造抛异常`() {
        try {
            EditsCodec.FrameEdit(frame = 9)
            throw AssertionError("应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("非法相框档"))
        }
    }

    @Test
    fun `序列化反序列化 保留帧编辑项`() {
        val edits = EditsCodec.RollEdits("roll_1")
        edits.frames[1] = EditsCodec.FrameEdit(EditsCodec.FRAME_FILM, true)
        edits.frames[3] = EditsCodec.FrameEdit(EditsCodec.FRAME_NONE, false)
        val json = EditsCodec.toJson(edits)
        val out = EditsCodec.fromJson("roll_1", json)
        assertEquals("roll_1", out.rollId)
        assertEquals(EditsCodec.FRAME_FILM, out.frames[1]!!.frame)
        assertTrue(out.frames[1]!!.dateOn)
        assertEquals(EditsCodec.FRAME_NONE, out.frames[3]!!.frame)
        assertFalse(out.frames[3]!!.dateOn)
    }

    @Test
    fun `深拷贝互不影响`() {
        val edits = EditsCodec.RollEdits("roll_1")
        edits.frames[1] = EditsCodec.FrameEdit(EditsCodec.FRAME_FILM, true)
        val copy = EditsCodec.deepCopy(edits)
        copy.frames[1] = EditsCodec.FrameEdit(EditsCodec.FRAME_NONE, false)
        assertEquals(EditsCodec.FRAME_FILM, edits.frames[1]!!.frame)
        assertTrue(edits.frames[1]!!.dateOn)
    }

    @Test
    fun `空或损坏 json 返回空配方`() {
        assertTrue(EditsCodec.fromJson("r", null).frames.isEmpty())
        assertTrue(EditsCodec.fromJson("r", "{bad").frames.isEmpty())
        assertTrue(EditsCodec.fromJson("r", "{}").frames.isEmpty())
    }

    @Test
    fun `坏档位回落到白边 不崩`() {
        val json = """{"rollId":"r","frames":[{"index":1,"frame":99,"dateOn":true}]}"""
        val out = EditsCodec.fromJson("r", json)
        assertEquals(EditsCodec.FRAME_WHITE, out.frames[1]!!.frame)
        assertTrue(out.frames[1]!!.dateOn)
    }

    @Test
    fun `缺省帧是白边加日期关`() {
        val e = EditsCodec.frameOrDefault(EditsCodec.RollEdits("r"), 7)
        assertEquals(EditsCodec.FRAME_WHITE, e.frame)
        assertFalse(e.dateOn)
        assertTrue(EditsCodec.isDefault(e))
    }

    @Test
    fun `非法 index 被跳过`() {
        val json = """{"rollId":"r","frames":[{"index":0,"frame":1},{"index":2,"frame":2}]}"""
        val out = EditsCodec.fromJson("r", json)
        assertFalse(out.frames.containsKey(0))
        assertTrue(out.frames.containsKey(2))
    }

    @Test
    fun `渲染决策 只有片边档走引擎 只有白边档走本地外扩`() {
        assertTrue(EditsCodec.needsEngineRerender(EditsCodec.FRAME_FILM))
        assertFalse(EditsCodec.needsEngineRerender(EditsCodec.FRAME_WHITE))
        assertFalse(EditsCodec.needsEngineRerender(EditsCodec.FRAME_NONE))
        assertTrue(EditsCodec.needsWhiteBorder(EditsCodec.FRAME_WHITE))
        assertFalse(EditsCodec.needsWhiteBorder(EditsCodec.FRAME_FILM))
        assertFalse(EditsCodec.needsWhiteBorder(EditsCodec.FRAME_NONE))
    }
}
