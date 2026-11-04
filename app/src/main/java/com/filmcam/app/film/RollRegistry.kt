package com.filmcam.app.film

import org.json.JSONArray
import org.json.JSONObject

/**
 * 卷登记的**纯数据层**：只负责 JSON 序列化/反序列化与状态规则，
 * 不触碰 Context / SharedPreferences / MediaStore，因此可以在纯 JVM 单测里直接验证。
 *
 * 序列化格式与旧实现逐字段一致（key 名、类型、缺省值都不能变），
 * 否则老用户已拍未冲洗的卷会在升级后读不出来。
 */
object RollRegistry {

    const val MAX_SHOTS = 36

    const val STATUS_SHOOTING = "shooting"
    const val STATUS_DEVELOPING = "developing"
    const val STATUS_DEVELOPED = "developed"

    /** 单帧记录。orientation 缺省 0：旧记录是拍摄侧已物理旋转过的老链路产物。
     *  shot 是拍摄参数 JSON（闪光/白平衡/EV/快门/失焦），旧记录为空串。 */
    data class FrameRec(
        val index: Int,
        val handle: String,
        val takenAtMs: Long,
        val iso: Int?,
        val orientation: Int = 0,
        val shot: String = "",
        /** RAW 底片（DNG）的绝对路径；空串 = 这帧是纯 JPEG 负片。老记录缺此字段按空串读 */
        val raw: String = ""
    )

    data class RollRec(
        val rollId: String,
        val filmStockId: String,
        val printStockId: String,
        val createdAtMs: Long,
        var status: String,
        val frames: MutableList<FrameRec>
    ) {
        fun toInfo(): RollInfo = RollInfo(
            rollId = rollId,
            filmStockId = filmStockId,
            printStockId = printStockId,
            createdAtMs = createdAtMs,
            status = status,
            shotCount = frames.size,
            frames = frames.map { it.index }
        )
    }

    /**
     * 反序列化卷登记。
     * 容错优先：整段 JSON 损坏、或其中某一卷/某一帧字段缺失时，
     * 跳过坏项而不是整体崩溃——用户已拍的卷比严格校验更重要。
     */
    fun deserialize(raw: String?): MutableList<RollRec> {
        val out = mutableListOf<RollRec>()
        if (raw.isNullOrEmpty()) return out
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return out
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rollId = o.optString("rollId", null) ?: continue
            val frames = mutableListOf<FrameRec>()
            val fa = o.optJSONArray("frames") ?: JSONArray()
            for (j in 0 until fa.length()) {
                val f = fa.optJSONObject(j) ?: continue
                val handle = f.optString("handle", null) ?: continue
                frames.add(
                    FrameRec(
                        index = f.optInt("index", frames.size + 1),
                        handle = handle,
                        takenAtMs = f.optLong("takenAtMs", 0L),
                        // iso 可能是 JSONObject.NULL —— 用 isNull 判断，不能用 optInt（会返回 0）
                        iso = if (f.isNull("iso")) null else f.optInt("iso", 0),
                        orientation = f.optInt("orientation", 0),
                        shot = f.optString("shot", ""),
                        raw = f.optString("raw", "")
                    )
                )
            }
            out.add(
                RollRec(
                    rollId = rollId,
                    filmStockId = o.optString("filmStockId", ""),
                    printStockId = o.optString("printStockId", ""),
                    createdAtMs = o.optLong("createdAtMs", 0L),
                    status = o.optString("status", STATUS_SHOOTING),
                    frames = frames
                )
            )
        }
        return out
    }

    fun serialize(rolls: List<RollRec>): String {
        val arr = JSONArray()
        for (r in rolls) {
            val o = JSONObject()
            o.put("rollId", r.rollId)
            o.put("filmStockId", r.filmStockId)
            o.put("printStockId", r.printStockId)
            o.put("createdAtMs", r.createdAtMs)
            o.put("status", r.status)
            val fa = JSONArray()
            for (f in r.frames) {
                val fo = JSONObject()
                fo.put("index", f.index)
                fo.put("handle", f.handle)
                fo.put("takenAtMs", f.takenAtMs)
                if (f.iso == null) fo.put("iso", JSONObject.NULL) else fo.put("iso", f.iso)
                fo.put("orientation", f.orientation)
                if (f.shot.isNotEmpty()) fo.put("shot", f.shot)
                if (f.raw.isNotEmpty()) fo.put("raw", f.raw)
                fa.put(fo)
            }
            o.put("frames", fa)
            arr.put(o)
        }
        return arr.toString()
    }

    /** 状态规则：只有 shooting 卷能继续拍、能被置为 developing。 */
    fun isShooting(roll: RollRec): Boolean = roll.status == STATUS_SHOOTING

    fun isFull(roll: RollRec): Boolean = roll.frames.size >= MAX_SHOTS

    /** 未冲洗 = shooting 或 developing（developed 的卷不再出现在暗房列表）。 */
    fun isUndeveloped(roll: RollRec): Boolean =
        roll.status == STATUS_SHOOTING || roll.status == STATUS_DEVELOPING
}
