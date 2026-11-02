package com.filmcam.app.film

import org.json.JSONObject

/**
 * 单帧的「拍摄参数」快照，跟着未冲洗帧一起落盘，冲洗时写进成片 EXIF。
 *
 * 为什么需要它：胶卷与冲洗参数已经能复现了，但「拍摄同款」还缺一半 ——
 * 闪光灯开没开、白平衡是哪一片、曝光补偿推了几档，这些同样决定成片长什么样。
 * 而它们以前只活在机身内存里，照片一冲洗就丢了。
 *
 * 慢门与失焦也记下来，但**只作为「这张为什么糊」的注解**，不参与拍摄同款套用：
 * 它们是概率事件，不是配方。真机也不会因为你上一张手抖，下一张就强制手抖。
 */
data class ShotMeta(
    /** 闪光模式名：OFF / BUILT_IN / EXTERNAL */
    val flash: String = "OFF",
    /** 白平衡的 CONTROL_AWB_MODE_* 常量（比数组下标稳，将来加档位不会串） */
    val awbMode: Int = 1,
    /** 曝光补偿档位（-3..3） */
    val ev: Int = 0,
    /** 这一张的实际快门（秒）；0 = 未记录（常规 AE 快门不由我们决定） */
    val shutterSec: Float = 0f,
    /** 这一张是否走了胶片慢门 */
    val slow: Boolean = false,
    /** 这一张是否故意失焦 */
    val defocus: Boolean = false,
) {

    fun toJson(): String = JSONObject().apply {
        put("flash", flash)
        put("wb", awbMode)
        put("ev", ev)
        if (shutterSec > 0f) put("sec", shutterSec.toDouble())
        put("slow", slow)
        put("defocus", defocus)
    }.toString()

    companion object {
        fun fromJson(raw: String?): ShotMeta? {
            if (raw.isNullOrBlank()) return null
            return try {
                val o = JSONObject(raw)
                ShotMeta(
                    flash = o.optString("flash", "OFF"),
                    awbMode = o.optInt("wb", 1),
                    ev = o.optInt("ev", 0),
                    shutterSec = o.optDouble("sec", 0.0).toFloat(),
                    slow = o.optBoolean("slow", false),
                    defocus = o.optBoolean("defocus", false),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
