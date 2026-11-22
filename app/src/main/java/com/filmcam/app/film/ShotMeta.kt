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
    /**
     * 这一张实际下发给 HAL 的 CONTROL_ZOOM_RATIO（T3 35mm 视角裁切比）。
     *
     * **冲洗端按帧取它补裁 RAW 的 ActiveArea**：zoom 是按机型传感器算出来的，
     * 只存在内存里的话，冲洗在另一个进程 / 冷启动后直接进暗房 / 换设备导入时
     * 就会读到错的默认值，RAW 与 JPEG 视角又会对不上。
     * 0 = 未记录（升级前拍的老帧），冲洗端回落到当前设备的默认裁切比。
     */
    val zoom: Float = 0f,
    /** EXT 档的闪光灯细档补偿（EV，0.5 步进）；非 EXT 档为 0。 */
    val flashCompEv: Float = 0f,
    /**
     * 这一帧**传感器实际**感光度（ISO）。与 EXIF 里模拟的胶卷 ISO（400/200…）
     * 不是一回事：外闪固定曝光时它可能是 228 或 81。0 = 升级前老帧未记录。
     */
    val sensorIso: Int = 0,
    /**
     * 这一帧**传感器实际**快门（秒）。与上面的 [shutterSec]（胶片慢门秒数，
     * 概率事件、不参与拍摄同款）刻意分开：EXT 档恒 0.1s 但并没有走慢门。
     * 0 = 老帧未记录。
     */
    val sensorShutterSec: Float = 0f,
    /** 外置灯显示名（仅 EXTERNAL 档；空 = 未记录 / 无档案）。 */
    val flashDevice: String = "",
    /** 外置灯 MAC（档案主键，复盘时认灯用）。 */
    val flashAddress: String = "",
    /** 触发通道（可写特征 UUID 短码）。 */
    val flashChannel: String = "",
    /** 触发指令 hex 文本（如 "05 01"）。 */
    val flashTriggerHex: String = "",
    /** 这一帧镜头的**真实**光圈（f-number）；0 = 老帧 / 设备未上报。 */
    val aperture: Float = 0f,
    /** 这一帧镜头的**真实**物理焦距（mm）；0 = 老帧 / 设备未上报。 */
    val focalMm: Float = 0f,
    /**
     * 这一帧的成片画幅："135"（全幅 3:2，整屏与默认）/ "6x6"（120 中画幅 1:1，
     * 方形取景窗的腰平拍）。「旧帧未记录」回落 "135"，与升级前行为一致。
     * 冲洗端按帧裁片门、选片框版式，腰平方窗拍的就是 6×6 成片。
     */
    val gate: String = "135",
    // ===== 外闪手动标定样本（2026-11-22）=====
    // 为「人工标定 → 归纳 TTL 算法」采集：每帧把闪光功率/距离/环境基线/成片亮度
    // 刻进 ShotMeta，随帧落盘、冲洗写进 EXIF，看图页按 calibId 事后标记好坏。
    // 全部走 ShotMeta 现成通路，样本天然与成片一一对应，零匹配风险。

    /** 标定样本唯一 id（拍摄时刻 epoch ms）；0 = 非标定帧。 */
    val calibId: Long = 0L,
    /** 下发给灯的功率字节 arg（0~70，1/1~1/128）；-1 = 未记录。 */
    val calibPwr: Int = -1,
    /** AF 测得主体距离（米）；0 = 未测到。 */
    val calibDist: Float = 0f,
    /** 闪光前环境光基线 ISO（AE 收敛值）；0 = 未记录。 */
    val calibAmbIso: Int = 0,
    /** 闪光前环境光基线快门（秒）；0 = 未记录。 */
    val calibAmbSec: Float = 0f,
    /** 成片中心亮度 p50（落卷时尚无，冲洗回写前为 0）。 */
    val calibP50: Int = 0,
    /** 是否 Pro 手动档拍摄（手动 ISO/快门接管）。 */
    val calibManual: Boolean = false,
) {

    fun toJson(): String = JSONObject().apply {
        put("flash", flash)
        put("wb", awbMode)
        put("ev", ev)
        if (shutterSec > 0f) put("sec", shutterSec.toDouble())
        put("slow", slow)
        put("defocus", defocus)
        if (zoom > 0f) put("zoom", zoom.toDouble())
        if (flashCompEv != 0f) put("fev", flashCompEv.toDouble())
        if (sensorIso > 0) put("siso", sensorIso)
        if (sensorShutterSec > 0f) put("ssec", sensorShutterSec.toDouble())
        if (flashDevice.isNotBlank()) put("fdev", flashDevice)
        if (flashAddress.isNotBlank()) put("fmac", flashAddress)
        if (flashChannel.isNotBlank()) put("fchan", flashChannel)
        if (flashTriggerHex.isNotBlank()) put("ftrig", flashTriggerHex)
        if (aperture > 0f) put("ap", aperture.toDouble())
        if (focalMm > 0f) put("fl", focalMm.toDouble())
        if (gate != "135") put("gate", gate)
        // 外闪标定样本字段（短键，沿用 ShotMeta 的紧凑风格）
        if (calibId > 0L) put("cid", calibId)
        if (calibPwr >= 0) put("cpw", calibPwr)
        if (calibDist > 0f) put("cdi", calibDist.toDouble())
        if (calibAmbIso > 0) put("cai", calibAmbIso)
        if (calibAmbSec > 0f) put("cas", calibAmbSec.toDouble())
        if (calibP50 > 0) put("cp5", calibP50)
        if (calibManual) put("cma", true)
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
                    zoom = o.optDouble("zoom", 0.0).toFloat(),
                    flashCompEv = o.optDouble("fev", 0.0).toFloat(),
                    sensorIso = o.optInt("siso", 0),
                    sensorShutterSec = o.optDouble("ssec", 0.0).toFloat(),
                    flashDevice = o.optString("fdev", ""),
                    flashAddress = o.optString("fmac", ""),
                    flashChannel = o.optString("fchan", ""),
                    flashTriggerHex = o.optString("ftrig", ""),
                    aperture = o.optDouble("ap", 0.0).toFloat(),
                    focalMm = o.optDouble("fl", 0.0).toFloat(),
                    gate = o.optString("gate", "135"),
                    calibId = o.optLong("cid", 0L),
                    calibPwr = o.optInt("cpw", -1),
                    calibDist = o.optDouble("cdi", 0.0).toFloat(),
                    calibAmbIso = o.optInt("cai", 0),
                    calibAmbSec = o.optDouble("cas", 0.0).toFloat(),
                    calibP50 = o.optInt("cp5", 0),
                    calibManual = o.optBoolean("cma", false),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
