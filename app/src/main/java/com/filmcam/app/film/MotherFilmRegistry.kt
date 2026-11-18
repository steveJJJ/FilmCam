package com.filmcam.app.film

import org.json.JSONArray
import org.json.JSONObject

/**
 * 母片登记的**纯数据层**：只做母片记录的 JSON 序列化/反序列化、状态与指纹规则，
 * 不碰 Context / SharedPreferences / 文件，因此可在纯 JVM 单测里验证。
 *
 * 设计要点（v2 方案）：母片**不复制像素**。原始输入仍在原帧的 handle（JPEG）
 * 与 raw（DNG）里，母片只是一条"这帧已冲洗成母片 + 参数配方 + 指纹"的索引。
 * 这样零额外存储、零二次损失，编辑导出时从原始输入重渲。
 *
 * 与 RollRegistry 完全独立的一套 JSON（key=mother_registry_v1），老用户没有这个文件 → 空。
 */
object MotherFilmRegistry {

    /** 单帧母片记录。sourceKind: "jpeg" | "dng"（有 DNG 时优先 dng）。 */
    data class MotherRec(
        val rollId: String,
        val index: Int,
        /** 原始 JPEG 帧 handle（MediaStore uri 或点目录路径），冲洗后不再被覆盖 */
        val sourceHandle: String,
        /** 原始 DNG 绝对路径；空 = 纯 JPEG 帧 */
        val rawPath: String,
        /** 色彩参数配方 JSON（不含片框/日期/白边），供导出时重渲 */
        val recipeJson: String,
        /** ShotMeta JSON（含 gate/闪光等），透传 EXIF */
        val shotJson: String,
        /** 源内容指纹（幂等键的一部分） */
        val fingerprint: String,
        /** 已导出的成片指纹集合（同一参数重复点不重复入库） */
        val exportedFingerprints: MutableList<String> = mutableListOf(),
        /**
         * JPEG 帧冲洗时施加的**输入层曝光 EV**（含 JPEG_INPUT_BASE_EV）。片边档重渲必须复刻，
         * 否则与母片（已归一化）亮度不一致。RAW 帧为 0（不走这条）。
         */
        val inputEv: Float = 0f,
        /**
         * JPEG 帧冲洗时施加的**反 ISP 归一化有效强度 k**（= suggestStrength 整卷基准 × 滑杆）。
         * 片边档从原始 HAL JPEG 重渲时要用同一个 k 复刻 JpegNormalizer，否则颜色比母片浓。
         * 0 = 未归一化（老卷/fast-path 不受影响）。
         */
        val normK: Float = 0f,
        /**
         * RAW 帧冲洗时由 ISP 引导 + 用户补偿算出的**输入层曝光增益**（EV）。
         * 片边档从 DNG 重渲时必须复刻同一 gainEv，否则与母片亮度不一致
         * （11-18 用户：「RAW 白边和原片一样，片边会变暗」）。JPEG 帧为 0。
         */
        val rawGainEv: Float = 0f,
        /**
         * RAW 帧冲洗实际使用的参数串（含 raw exposure method + fixed print exposure）。
         * 片边重渲在该串上叠加片框键即可，保证 print_exposure 与母片完全一致。
         * 空 = 老卷/失败回落，用导出侧自构参数。
         */
        val rawParamsJson: String = "",
    )

    /**
     * 计算编辑指纹：源指纹 + frame(0/1/2) + dateOn + 引擎版本。
     * 纯函数、无 android 依赖，可在 JVM 单测断言。
     */
    fun editFingerprint(
        sourceFingerprint: String,
        frame: Int,
        dateOn: Boolean,
        engineVersion: Int,
    ): String {
        val raw = "$sourceFingerprint|f$frame|d${if (dateOn) 1 else 0}|e$engineVersion"
        return raw.sha256Hex()
    }

    /** 该指纹是否已导出过（幂等）。 */
    fun isExported(rec: MotherRec, fingerprint: String): Boolean =
        rec.exportedFingerprints.contains(fingerprint)

    /** 标记指纹已导出（幂等：已存在不重复加）。 */
    fun markExported(rec: MotherRec, fingerprint: String) {
        if (!rec.exportedFingerprints.contains(fingerprint)) {
            rec.exportedFingerprints.add(fingerprint)
        }
    }

    fun deserialize(raw: String?): MutableList<MotherRec> {
        val out = mutableListOf<MotherRec>()
        if (raw.isNullOrEmpty()) return out
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return out
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rollId = o.optString("rollId", null) ?: continue
            val sourceHandle = o.optString("sourceHandle", null) ?: continue
            val exported = mutableListOf<String>()
            val ea = o.optJSONArray("exportedFingerprints") ?: JSONArray()
            for (k in 0 until ea.length()) {
                ea.optString(k, null)?.let { exported.add(it) }
            }
            out.add(
                MotherRec(
                    rollId = rollId,
                    index = o.optInt("index", out.size + 1),
                    sourceHandle = sourceHandle,
                    rawPath = o.optString("rawPath", ""),
                    recipeJson = o.optString("recipeJson", ""),
                    shotJson = o.optString("shotJson", ""),
                    fingerprint = o.optString("fingerprint", ""),
                    inputEv = o.optDouble("inputEv", 0.0).toFloat(),
                    normK = o.optDouble("normK", 0.0).toFloat(),
                    rawGainEv = o.optDouble("rawGainEv", 0.0).toFloat(),
                    rawParamsJson = o.optString("rawParamsJson", ""),
                    exportedFingerprints = exported
                )
            )
        }
        return out
    }

    fun serialize(recs: List<MotherRec>): String {
        val arr = JSONArray()
        for (r in recs) {
            val o = JSONObject()
            o.put("rollId", r.rollId)
            o.put("index", r.index)
            o.put("sourceHandle", r.sourceHandle)
            o.put("rawPath", r.rawPath)
            o.put("recipeJson", r.recipeJson)
            o.put("shotJson", r.shotJson)
            o.put("fingerprint", r.fingerprint)
            o.put("inputEv", r.inputEv.toDouble())
            o.put("normK", r.normK.toDouble())
            o.put("rawGainEv", r.rawGainEv.toDouble())
            o.put("rawParamsJson", r.rawParamsJson)
            val ea = JSONArray()
            r.exportedFingerprints.forEach { ea.put(it) }
            o.put("exportedFingerprints", ea)
            arr.put(o)
        }
        return arr.toString()
    }

    /** 负责任的源指纹：优先读 DNG 文件属性，否则读 handle 内容字节。纯路径计算留给 store 层。 */
    fun keyOf(rollId: String, index: Int): String = "$rollId#$index"

    private fun String.sha256Hex(): String {
        val bytes = toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
