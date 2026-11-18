package com.filmcam.app.develop

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * 编辑参数存储（v2 方案）：每卷 edits/<rollId>.json。
 * 纯 JSONCodec 在 EditsCodec（可单测），本类只做 Context/文件 IO。
 *
 * 用 app 私有 filesDir/edits/，不建 MediaStore 行 → 相册不可见。
 */
object EditsStore {

    private const val PREFS_NAME = "filmcam_edits"
    private const val DIR_EDITS = "edits"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context =
        appContext ?: throw IllegalStateException("EditsStore.init(context) 尚未调用")

    private fun prefs(): SharedPreferences =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun fileFor(rollId: String): File =
        File(requireContext().filesDir, "$DIR_EDITS/$rollId.json")

    fun load(rollId: String): EditsCodec.RollEdits {
        val f = fileFor(rollId)
        if (!f.exists()) return EditsCodec.RollEdits(rollId)
        val json = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
        return EditsCodec.fromJson(rollId, json)
    }

    fun save(edits: EditsCodec.RollEdits) {
        val f = fileFor(edits.rollId)
        f.parentFile?.mkdirs()
        f.writeText(EditsCodec.toJson(edits), Charsets.UTF_8)
    }

    /** 设置某帧编辑项（缺省项写进去也无害，读时按值处理）。 */
    fun setFrame(rollId: String, index: Int, edit: EditsCodec.FrameEdit) {
        val edits = load(rollId)
        edits.frames[index] = edit
        save(edits)
    }

    /** 应用到多帧（借鉴"一键套用"：只改配方，不做任何渲染）。 */
    fun applyTo(rollId: String, indices: Collection<Int>, edit: EditsCodec.FrameEdit) {
        val edits = load(rollId)
        indices.forEach { edits.frames[it] = edit.copy() }
        save(edits)
    }

    fun delete(rollId: String) {
        runCatching { fileFor(rollId).delete() }
        prefs().edit().remove(rollId).apply()
    }
}
