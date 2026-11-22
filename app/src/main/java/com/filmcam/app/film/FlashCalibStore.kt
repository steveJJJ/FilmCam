package com.filmcam.app.film

import android.content.Context
import java.io.File

/**
 * 外闪手动标定样本的「事后标记」存取（11-22）。
 *
 * 样本本身随 ShotMeta 写进成片 EXIF（见 [ShotMeta.calibId]），天然与成片一一对应。
 * 标记单独存一个轻量文本（不改成片 EXIF，避免损坏照片）：
 *   每行 `calibId=good` 或 `calibId=bad`，最新一条覆盖同 id 的旧值。
 * 归纳算法时：读所有成片 EXIF 的 calibId + 特征，按本文件标记筛出好/差样本。
 */
object FlashCalibStore {

    const val MARK_FILE = "flash_calib_marks.txt"
    const val GOOD = "good"
    const val BAD = "bad"

    private fun markFile(ctx: Context): File = File(ctx.getExternalFilesDir(null), MARK_FILE)

    /** 读某个 calibId 的标记；无标记返回 null。 */
    fun read(ctx: Context, calibId: Long): String? {
        if (calibId <= 0L) return null
        return runCatching {
            val f = markFile(ctx)
            if (!f.exists()) return null
            var found: String? = null
            f.readLines().forEach { line ->
                val parts = line.trim().split('=', limit = 2)
                if (parts.size == 2 && parts[0] == calibId.toString()) {
                    found = parts[1]
                }
            }
            found
        }.getOrNull()
    }

    /** 写标记。mark 传 null = 清除该 id 的标记。 */
    fun write(ctx: Context, calibId: Long, mark: String?) {
        if (calibId <= 0L) return
        runCatching {
            val f = markFile(ctx)
            val lines = if (f.exists()) f.readLines().toMutableList() else mutableListOf()
            // 去掉该 id 的旧行
            lines.removeAll { it.trim().startsWith("$calibId=") }
            if (mark != null) lines.add("$calibId=$mark")
            f.writeText(lines.joinToString("\n") + if (lines.isEmpty()) "" else "\n")
        }
    }

    /** 读全部标记（calibId -> good/bad），归纳时用。 */
    fun readAll(ctx: Context): Map<Long, String> = runCatching {
        val f = markFile(ctx)
        if (!f.exists()) return emptyMap()
        val out = mutableMapOf<Long, String>()
        f.readLines().forEach { line ->
            val parts = line.trim().split('=', limit = 2)
            if (parts.size == 2) {
                parts[0].toLongOrNull()?.let { out[it] = parts[1] }
            }
        }
        out
    }.getOrDefault(emptyMap())
}
