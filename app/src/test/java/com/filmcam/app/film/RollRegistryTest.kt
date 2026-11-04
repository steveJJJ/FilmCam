package com.filmcam.app.film

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卷登记纯数据层契约测试。
 *
 * 这些断言守的是**落盘格式**：一旦序列化 key 名/类型/缺省值漂移，
 * 老用户已拍未冲洗的卷会在升级后读不出来，属于静默数据丢失，必须挡在 CI 里。
 */
class RollRegistryTest {

    private fun frame(
        index: Int = 1,
        handle: String = "content://media/x/$index",
        takenAtMs: Long = 1_700_000_000_000L,
        iso: Int? = 400,
        orientation: Int = 90
    ) = RollRegistry.FrameRec(index, handle, takenAtMs, iso, orientation)

    private fun roll(
        rollId: String = "roll_1",
        status: String = RollRegistry.STATUS_SHOOTING,
        frames: MutableList<RollRegistry.FrameRec> = mutableListOf(frame())
    ) = RollRegistry.RollRec(rollId, "kodak_kodachrome_64", "kodak_endura", 1_699_999_999_000L, status, frames)

    // ---------- 序列化往返 ----------

    @Test
    fun `序列化往返保持全部字段`() {
        val src = listOf(roll(frames = mutableListOf(frame(1, iso = 400, orientation = 90), frame(2, iso = null, orientation = 0))))
        val back = RollRegistry.deserialize(RollRegistry.serialize(src))

        assertEquals(1, back.size)
        val r = back[0]
        assertEquals("roll_1", r.rollId)
        assertEquals("kodak_kodachrome_64", r.filmStockId)
        assertEquals("kodak_endura", r.printStockId)
        assertEquals(1_699_999_999_000L, r.createdAtMs)
        assertEquals(RollRegistry.STATUS_SHOOTING, r.status)
        assertEquals(2, r.frames.size)

        assertEquals(1, r.frames[0].index)
        assertEquals("content://media/x/1", r.frames[0].handle)
        assertEquals(1_700_000_000_000L, r.frames[0].takenAtMs)
        assertEquals(400, r.frames[0].iso)
        assertEquals(90, r.frames[0].orientation)

        assertEquals(2, r.frames[1].index)
        assertNull(r.frames[1].iso)
        assertEquals(0, r.frames[1].orientation)
    }

    /** iso 为空必须写成 JSON null 而不是 0——否则冲洗侧会拿 0 当真实 ISO 用。 */
    @Test
    fun `iso 为空序列化为 JSON null 且反序列化回 null`() {
        val json = RollRegistry.serialize(listOf(roll(frames = mutableListOf(frame(iso = null)))))
        val isoField = JSONArray(json).getJSONObject(0).getJSONArray("frames").getJSONObject(0).get("iso")
        assertTrue("iso 必须是 JSONObject.NULL", isoField === JSONObject.NULL)
        assertNull(RollRegistry.deserialize(json)[0].frames[0].iso)
    }

    // ---------- 落盘格式（冻结契约） ----------

    @Test
    fun `序列化包含卷的全部约定字段`() {
        val obj = JSONArray(RollRegistry.serialize(listOf(roll()))).getJSONObject(0)
        for (key in listOf("rollId", "filmStockId", "printStockId", "createdAtMs", "status", "frames")) {
            assertTrue("缺少字段 $key", obj.has(key))
        }
        val f = obj.getJSONArray("frames").getJSONObject(0)
        for (key in listOf("index", "handle", "takenAtMs", "iso", "orientation")) {
            assertTrue("帧缺少字段 $key", f.has(key))
        }
    }

    // ---------- 容错：坏数据不能拖垮整个登记 ----------

    @Test
    fun `损坏 JSON 返回空而不是抛异常`() {
        assertTrue(RollRegistry.deserialize("{ 这不是 json").isEmpty())
        assertTrue(RollRegistry.deserialize("").isEmpty())
        assertTrue(RollRegistry.deserialize(null).isEmpty())
    }

    @Test
    fun `跳过缺 rollId 的坏卷但保留同批正常卷`() {
        val raw = """[{"filmStockId":"x"},{"rollId":"good","status":"shooting","frames":[]}]"""
        val out = RollRegistry.deserialize(raw)
        assertEquals(1, out.size)
        assertEquals("good", out[0].rollId)
    }

    @Test
    fun `跳过缺 handle 的坏帧但保留同卷正常帧`() {
        val raw = """[{"rollId":"r","status":"shooting","frames":[
            {"index":1,"takenAtMs":5},{"index":2,"handle":"h2","takenAtMs":6}]}]"""
        val out = RollRegistry.deserialize(raw)
        assertEquals(1, out[0].frames.size)
        assertEquals(2, out[0].frames[0].index)
    }

    /** 老版本登记没有 orientation 字段，必须按 0 读（旧链路已物理旋转过），不能丢帧。 */
    @Test
    fun `旧记录缺 orientation 缺省为 0`() {
        val raw = """[{"rollId":"r","status":"shooting","frames":[{"index":1,"handle":"h","takenAtMs":5,"iso":100}]}]"""
        val out = RollRegistry.deserialize(raw)
        assertEquals(0, out[0].frames[0].orientation)
        assertEquals(100, out[0].frames[0].iso)
    }

    // ---------- 拍摄参数快照 ----------

    @Test
    fun `shot 字段往返保持`() {
        val shot = ShotMeta(
            flash = "BUILT_IN", awbMode = 6, ev = 1,
            shutterSec = 0.125f, slow = true, defocus = true
        ).toJson()
        val src = listOf(roll(frames = mutableListOf(frame(1).copy(shot = shot))))
        val back = RollRegistry.deserialize(RollRegistry.serialize(src))
        assertEquals(shot, back[0].frames[0].shot)
        assertEquals(6, ShotMeta.fromJson(back[0].frames[0].shot)!!.awbMode)
    }

    @Test
    fun `旧记录没有 shot 键时读出空串`() {
        val raw = """[{"rollId":"r","status":"shooting","frames":[{"index":1,"handle":"h","takenAtMs":5,"iso":100}]}]"""
        val out = RollRegistry.deserialize(raw)
        assertEquals("", out[0].frames[0].shot)
        assertNull(ShotMeta.fromJson(out[0].frames[0].shot))
    }

    // ---------- RAW 底片（P3） ----------

    @Test
    fun `raw 字段往返保持`() {
        val src = listOf(roll(frames = mutableListOf(frame(1).copy(raw = "/x/negatives/r/frame_01.dng"))))
        val back = RollRegistry.deserialize(RollRegistry.serialize(src))
        assertEquals("/x/negatives/r/frame_01.dng", back[0].frames[0].raw)
    }

    @Test
    fun `旧记录没有 raw 键时读出空串且纯 JPEG 帧不写 raw 键`() {
        // 旧记录（升级前的卷）缺 raw 键 → 空串，走 JPEG 通路
        val raw = """[{"rollId":"r","status":"shooting","frames":[{"index":1,"handle":"h","takenAtMs":5,"iso":100}]}]"""
        val out = RollRegistry.deserialize(raw)
        assertEquals("", out[0].frames[0].raw)
        // 纯 JPEG 帧序列化时不带 raw 键（保持旧格式逐字节兼容）
        val src = listOf(roll(frames = mutableListOf(frame(1))))
        assertFalse(RollRegistry.serialize(src).contains("\"raw\""))
    }

    // ---------- 状态规则 ----------

    @Test
    fun `一卷上限 36 张且边界正确`() {
        assertEquals(36, RollRegistry.MAX_SHOTS)
        val almost = roll(frames = (1..35).map { frame(it) }.toMutableList())
        assertFalse(RollRegistry.isFull(almost))
        almost.frames.add(frame(36))
        assertTrue(RollRegistry.isFull(almost))
    }

    @Test
    fun `状态判定规则`() {
        assertTrue(RollRegistry.isShooting(roll(status = "shooting")))
        assertFalse(RollRegistry.isShooting(roll(status = "developing")))
        assertFalse(RollRegistry.isShooting(roll(status = "developed")))

        assertTrue(RollRegistry.isUndeveloped(roll(status = "shooting")))
        assertTrue(RollRegistry.isUndeveloped(roll(status = "developing")))
        assertFalse(RollRegistry.isUndeveloped(roll(status = "developed")))
    }

    // ---------- 对外快照契约 ----------

    @Test
    fun `toInfo 暴露张数与帧序号且不含内部 handle`() {
        val info = roll(frames = mutableListOf(frame(1), frame(2), frame(3))).toInfo()
        assertEquals(3, info.shotCount)
        assertEquals(listOf(1, 2, 3), info.frames)
        assertEquals("roll_1", info.rollId)
        assertEquals("kodak_kodachrome_64", info.filmStockId)
    }
}
