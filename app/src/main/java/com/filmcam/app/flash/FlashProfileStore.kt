package com.filmcam.app.flash

import android.content.Context
import java.io.File

/**
 * 档案持久化：`filesDir/flash_profiles/<MAC 去冒号>.txt`，一灯一档。
 *
 * 放**私有目录**（不是旧的 getExternalFilesDir）：档案是「学会了怎么点这盏灯」
 * 的核心能力，不能落在清数据 / 卸载就没了的地方 —— 这正是 v60 修
 * `flash_trigger.hex` 时的教训（点灯能力静默失效）。
 *
 * 注册表联动：[ExternalFlashRegistry.syncLearned] 在建会话前把在档档案
 * 注册成驱动条目（priority=1），最近配对者优先于内置 FotorGear（priority=0）。
 */
class FlashProfileStore(private val context: Context) {

    private fun dir(): File =
        File(context.filesDir, "flash_profiles").apply { if (!isDirectory) mkdirs() }

    private fun fileFor(address: String): File =
        File(dir(), address.replace(":", "").lowercase() + ".txt")

    fun save(profile: FlashProfile): Boolean {
        if (profile.triggerBytes() == null) return false
        return runCatching {
            fileFor(profile.address).writeText(FlashProfileCodec.encode(profile))
        }.isSuccess
    }

    /** 全部在档档案（读失败的单个文件跳过，不连坐）。 */
    fun list(): List<FlashProfile> =
        dir().listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            .orEmpty()
            .mapNotNull { f -> runCatching { FlashProfileCodec.decode(f.readText()) }.getOrNull() }

    fun find(address: String?): FlashProfile? =
        if (address.isNullOrBlank()) null else list().firstOrNull { it.address == address.uppercase() }

    /** 最近配对的档案（多条时取 pairedAt 最大；0 视为无时间，排最后）。 */
    fun latest(): FlashProfile? = list().maxByOrNull { it.pairedAt }

    fun delete(address: String): Boolean = runCatching {
        fileFor(address).delete()
    }.getOrDefault(false)
}
