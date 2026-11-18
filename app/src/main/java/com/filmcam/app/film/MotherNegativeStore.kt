package com.filmcam.app.film

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 母片存储（v2 方案）：母片 = 原始输入引用 + 参数配方 + 指纹，**不复制像素**。
 *
 * 原始 JPEG 帧仍在 UndevelopedFilmStore 的 frame.handle（pending 项 / 点目录文件），
 * DNG 在 frame.raw。冲洗后这些原始输入**不再被写成片覆盖**，专供编辑时重渲。
 * 本 store 只维护一张独立的母片登记（SharedPreferences，key=mother_registry_v1），
 * 与 RollRegistry 解耦，老用户没有这个文件 → 当成空。
 *
 * 所有公开函数 synchronized，冲洗线程与编辑/导出线程可能并发。
 */
object MotherNegativeStore {

    private const val TAG = "MotherNegativeStore"
    private const val PREFS_NAME = "filmcam_rolls"
    private const val KEY_MOTHERS = "mother_registry_v1"

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var cached: MutableList<MotherFilmRegistry.MotherRec>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context =
        appContext ?: throw IllegalStateException("MotherNegativeStore.init(context) 尚未调用")

    private fun prefs(): SharedPreferences =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadAll(): MutableList<MotherFilmRegistry.MotherRec> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val out = MotherFilmRegistry.deserialize(prefs().getString(KEY_MOTHERS, null))
            cached = out
            return out
        }
    }

    private fun persist(recs: List<MotherFilmRegistry.MotherRec>) {
        cached = recs.toMutableList()
        prefs().edit().putString(KEY_MOTHERS, MotherFilmRegistry.serialize(recs)).apply()
    }

    /** 计算源指纹：读原始输入字节的 SHA-256（JPEG handle 或 DNG raw），失败回落到元数据串。 */
    private fun sourceFingerprint(sourceHandle: String, rawPath: String, index: Int, rollId: String): String {
        val path: String? = when {
            rawPath.isNotEmpty() && File(rawPath).exists() -> rawPath
            sourceHandle.startsWith("content://") -> null // MediaStore 走 resolver，见下
            File(sourceHandle).exists() -> sourceHandle
            else -> null
        }
        if (path != null) {
            runCatching {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                File(path).inputStream().use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n > 0) md.update(buf, 0, n)
                    }
                }
                return md.digest().joinToString("") { "%02x".format(it) }
            }
        }
        if (sourceHandle.startsWith("content://")) {
            runCatching {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                requireContext().contentResolver.openInputStream(Uri.parse(sourceHandle))?.use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (n > 0) md.update(buf, 0, n)
                    }
                }
                return md.digest().joinToString("") { "%02x".format(it) }
            }
        }
        // 兜底：文件读不到时用稳定元数据串，保证同一帧多次算出的指纹一致
        return MotherFilmRegistry.keyOf(rollId, index).let { key ->
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * 冲洗落盘改为调用本方法：登记一帧母片。
     * @param sourceHandle 原始 JPEG 帧 handle（来自 FrameRec.handle，不再是成片）
     * @param rawPath 原始 DNG 路径（FrameRec.raw），空 = 纯 JPEG
     * @param recipeJson 纯色彩参数配方（不含片框/日期/白边）
     */
    fun addMother(
        rollId: String,
        index: Int,
        sourceHandle: String,
        rawPath: String,
        recipeJson: String,
        shotJson: String,
        inputEv: Float = 0f,
        normK: Float = 0f,
        rawGainEv: Float = 0f,
        rawParamsJson: String = "",
    ): MotherFilmRegistry.MotherRec = synchronized(this) {
        val recs = loadAll()
        val fp = sourceFingerprint(sourceHandle, rawPath, index, rollId)
        // 幂等：同 key 已存在则更新配方/指纹，保留已导出指纹集合
        val existing = recs.firstOrNull { it.rollId == rollId && it.index == index }
        val rec = MotherFilmRegistry.MotherRec(
            rollId = rollId, index = index,
            sourceHandle = sourceHandle, rawPath = rawPath,
            recipeJson = recipeJson, shotJson = shotJson,
            fingerprint = fp,
            inputEv = inputEv, normK = normK,
            rawGainEv = rawGainEv, rawParamsJson = rawParamsJson,
            exportedFingerprints = existing?.exportedFingerprints?.toMutableList() ?: mutableListOf()
        )
        if (existing != null) recs[recs.indexOf(existing)] = rec else recs.add(rec)
        persist(recs)
        rec
    }

    /** 预览图目录（app 私有，相册不可见）：filesDir/mothers/<rollId>/NN.jpg */
    private fun previewDir(rollId: String): File =
        File(requireContext().filesDir, "mothers/$rollId")

    fun previewFile(rollId: String, index: Int): File =
        File(previewDir(rollId), "${index.toString().padStart(2, '0')}.jpg")

    /**
     * 全分辨率纯色彩母片（编辑导出的 fast-path 源）：冲洗时引擎已输出的全尺寸
     * 纯色彩 JPEG 直接拷一份进私有目录。白边/无框档导出时本地叠加即可出片
     * （亚秒级），只有片边档才需要回 DNG 重渲引擎（注入 overscan 片框）。
     */
    fun fullFile(rollId: String, index: Int): File =
        File(previewDir(rollId), "${index.toString().padStart(2, '0')}_full.jpg")

    fun saveFull(rollId: String, index: Int, sourceJpeg: File): File {
        val out = fullFile(rollId, index)
        sourceJpeg.inputStream().use { ins -> out.outputStream().use { os -> ins.copyTo(os) } }
        return out
    }

    fun hasFull(rollId: String, index: Int): Boolean = fullFile(rollId, index).exists()

    /**
     * 片边档预渲染成片（冲洗时趁热渲好、导出直接取）：全分辨率、带引擎齿孔/片框、
     * 无日期/无白边/无 EXIF。编辑导出片边档直接拷贝它叠日期入册，省掉 5~8s 引擎重渲。
     */
    fun framedFile(rollId: String, index: Int): File =
        File(previewDir(rollId), "${index.toString().padStart(2, '0')}_framed.jpg")

    fun saveFramed(rollId: String, index: Int, sourceJpeg: File): File {
        val out = framedFile(rollId, index)
        sourceJpeg.inputStream().use { ins -> out.outputStream().use { os -> ins.copyTo(os) } }
        return out
    }

    fun hasFramed(rollId: String, index: Int): Boolean = framedFile(rollId, index).exists()

    /**
     * 该卷是否所有母片帧都至少导出过一次（任一参数指纹即可）。
     * 导出服务用它决定「编辑完成」：全部帧都进过相册 → 卷从暗房列表移除（→ developed）。
     * 只导出了部分帧（"仅此张"）时返回 false，卷继续留在暗房待编辑。
     */
    fun allExported(rollId: String): Boolean = synchronized(this) {
        val recs = mothersOf(rollId)
        recs.isNotEmpty() && recs.all { it.exportedFingerprints.isNotEmpty() }
    }

    /**
     * A 方案：冲洗时把纯色彩母片缩成一张小预览图存私有目录，供暗房网格/详情显示。
     * 长边不超过 [maxEdge]（默认 720px），质量 88。返回预览文件，失败抛异常由调用方决定降级。
     */
    fun savePreview(rollId: String, index: Int, sourceJpeg: File, maxEdge: Int = 720): File {
        val dir = previewDir(rollId)
        if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("无法创建母片预览目录: $dir")
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(sourceJpeg.absolutePath, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decode = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = android.graphics.BitmapFactory.decodeFile(sourceJpeg.absolutePath, decode)
            ?: throw java.io.IOException("母片预览解码失败: $sourceJpeg")
        val out = previewFile(rollId, index)
        try {
            java.io.FileOutputStream(out).use { stream ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, stream)
            }
        } finally {
            bmp.recycle()
        }
        return out
    }

    fun hasPreview(rollId: String, index: Int): Boolean = previewFile(rollId, index).exists()

    fun mother(rollId: String, index: Int): MotherFilmRegistry.MotherRec? = synchronized(this) {
        loadAll().firstOrNull { it.rollId == rollId && it.index == index }
    }

    fun mothersOf(rollId: String): List<MotherFilmRegistry.MotherRec> = synchronized(this) {
        loadAll().filter { it.rollId == rollId }.sortedBy { it.index }
    }

    fun hasMothers(rollId: String): Boolean = synchronized(this) {
        loadAll().any { it.rollId == rollId }
    }

    fun markExported(rollId: String, index: Int, fingerprint: String) = synchronized(this) {
        val recs = loadAll()
        val rec = recs.firstOrNull { it.rollId == rollId && it.index == index } ?: return
        MotherFilmRegistry.markExported(rec, fingerprint)
        persist(recs)
    }

    /**
     * 删母片（仅删登记，不动原始输入源文件——它们归卷/底片目录管，避免误删 DNG 影响其它帧）。
     * 卷壳与已导出成片（相册里）都不碰。
     * @return 实际删除的条数
     */
    fun deleteMothers(rollId: String, indices: Collection<Int>): Int = synchronized(this) {
        if (indices.isEmpty()) return 0
        val recs = loadAll()
        val before = recs.size
        recs.removeAll { it.rollId == rollId && it.index in indices }
        val removed = before - recs.size
        if (removed > 0) {
            indices.forEach {
                runCatching { previewFile(rollId, it).delete() }
                runCatching { fullFile(rollId, it).delete() }
                runCatching { framedFile(rollId, it).delete() }
            }
            persist(recs)
        }
        removed
    }

    fun deleteRollMothers(rollId: String): Int = synchronized(this) {
        val recs = loadAll()
        val before = recs.size
        recs.removeAll { it.rollId == rollId }
        val removed = before - recs.size
        if (removed > 0) {
            runCatching { previewDir(rollId).deleteRecursively() }
            persist(recs)
        }
        removed
    }
}
