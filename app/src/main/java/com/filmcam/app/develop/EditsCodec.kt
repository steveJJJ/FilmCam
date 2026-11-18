package com.filmcam.app.develop

import org.json.JSONArray
import org.json.JSONObject

/**
 * 编辑参数的**纯数据层**（v2 方案）：每卷一个 edits json，帧级 {frame,date}。
 * 借鉴参照 App 的 Look 配方思路：配方与像素解耦、JSON 序列化、深拷贝。
 * 不碰 Context / 文件，可在纯 JVM 单测里验证。
 *
 * 相框三档：0=无(OFF) / 1=片边(FULL,引擎overscan) / 2=白边(WHITE,本地合成)。
 */
object EditsCodec {

    const val FRAME_NONE = 0
    const val FRAME_FILM = 1
    const val FRAME_WHITE = 2

    data class FrameEdit(
        val frame: Int = FRAME_WHITE,
        val dateOn: Boolean = false,
    ) {
        init {
            require(frame in FRAME_NONE..FRAME_WHITE) { "非法相框档: $frame" }
        }
    }

    /** 一卷的编辑配方：rollId -> 帧号 -> 编辑项。 */
    data class RollEdits(
        val rollId: String,
        val frames: MutableMap<Int, FrameEdit> = mutableListOf<Int>().associateWith { FrameEdit() }.toMutableMap()
    )

    fun validFrame(frame: Int): Boolean = frame in FRAME_NONE..FRAME_WHITE

    /** 深拷贝（借鉴 copyLook）：走序列化往返，避免共享可变 map。 */
    fun deepCopy(src: RollEdits): RollEdits = fromJson(rollId = src.rollId, json = toJson(src))

    fun toJson(edits: RollEdits): String {
        val root = JSONObject()
        root.put("rollId", edits.rollId)
        val arr = JSONArray()
        for ((idx, e) in edits.frames.toSortedMap()) {
            val o = JSONObject()
            o.put("index", idx)
            o.put("frame", e.frame)
            o.put("dateOn", e.dateOn)
            arr.put(o)
        }
        root.put("frames", arr)
        return root.toString()
    }

    fun fromJson(rollId: String, json: String?): RollEdits {
        val out = RollEdits(rollId = rollId, frames = mutableMapOf())
        if (json.isNullOrEmpty()) return out
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return out
        }
        val arr = root.optJSONArray("frames") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val idx = o.optInt("index", -1)
            if (idx <= 0) continue
            val frame = o.optInt("frame", FRAME_WHITE)
            out.frames[idx] = FrameEdit(
                frame = if (validFrame(frame)) frame else FRAME_WHITE,
                dateOn = o.optBoolean("dateOn", false)
            )
        }
        return out
    }

    /** 取某帧编辑项，缺省 = 白边 + 日期关（与默认出片口径一致）。 */
    fun frameOrDefault(edits: RollEdits, index: Int): FrameEdit =
        edits.frames[index] ?: FrameEdit()

    /** 是否值得持久化：与缺省不同才写，减小 json。 */
    fun isDefault(e: FrameEdit): Boolean = e.frame == FRAME_WHITE && !e.dateOn

    /** 纯决策：该档是否走引擎片框重渲（片边=FILM 才走；白边/无框都不走）。 */
    fun needsEngineRerender(frame: Int): Boolean = frame == FRAME_FILM

    /** 纯决策：该档是否走 App 侧白边外扩。 */
    fun needsWhiteBorder(frame: Int): Boolean = frame == FRAME_WHITE
}
