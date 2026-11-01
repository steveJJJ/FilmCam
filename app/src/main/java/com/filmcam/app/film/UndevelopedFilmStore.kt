package com.filmcam.app.film

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.filmcam.app.R
import java.io.File
import java.io.InputStream

/**
 * 未冲洗胶卷对外快照（冲洗侧按 index 取帧；详细帧记录内部保存）。
 * 冻结契约：字段一个不能少、顺序不能变。
 */
data class RollInfo(
    val rollId: String,
    val filmStockId: String,
    val printStockId: String,
    val createdAtMs: Long,
    val status: String,
    val shotCount: Int,
    val frames: List<Int>
)

// 内部记录复用纯数据层 RollRegistry（不触碰 Context，可单测）。
// Kotlin 不允许嵌套 typealias，故定义在文件顶层。
private typealias RollRec = RollRegistry.RollRec
private typealias FrameRec = RollRegistry.FrameRec

/**
 * 未冲洗胶卷存储（拍摄侧实现，冲洗侧只调冻结 API）。
 *
 * 双路径：
 *  - API >= 29：MediaStore Images + IS_PENDING=1（冲洗前不可见），RELATIVE_PATH=DCIM/FilmCam
 *  - API 26..28：直接写文件到 DCIM/.FilmCamUndeveloped/<rollId>/（点目录天然不可见）
 *
 * 卷登记走 SharedPreferences（key=roll_registry_v1），不耦合存储权限。
 * 所有公开函数 synchronized(this)，拍摄线程与冲洗服务可能并发。
 */
object UndevelopedFilmStore {

    private const val TAG = "UndevelopedFilmStore"
    private const val PREFS_NAME = "filmcam_rolls"
    private const val KEY_REGISTRY = "roll_registry_v1"
    private const val DIR_UNDEVELOPED = ".FilmCamUndeveloped"

    // 状态常量与上限统一来自纯数据层 RollRegistry，避免两处定义漂移
    private const val MAX_SHOTS = RollRegistry.MAX_SHOTS
    const val STATUS_SHOOTING = RollRegistry.STATUS_SHOOTING
    const val STATUS_DEVELOPING = RollRegistry.STATUS_DEVELOPING
    const val STATUS_DEVELOPED = RollRegistry.STATUS_DEVELOPED

    @Volatile
    private var appContext: Context? = null

    /** 进程内缓存，避免每次 API 调用都从 SharedPreferences 反序列化 JSON。
     *  冲洗循环每帧调 openFrameStream → loadAll，36 帧 = 36 次解析 → 缓存后仅首次解析。 */
    @Volatile
    private var cachedRolls: MutableList<RollRegistry.RollRec>? = null

    /** 进程启动时由 MainActivity 调用一次，缓存 applicationContext（冻结业务 API 不带 context 参数）。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context =
        appContext ?: throw IllegalStateException("UndevelopedFilmStore.init(context) 尚未调用")

    private fun prefs(): SharedPreferences =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 从进程内缓存读取；首次或缓存被清时从 SharedPreferences 反序列化。 */
    private fun loadAll(): MutableList<RollRec> {
        cachedRolls?.let { return it }
        synchronized(this) {
            cachedRolls?.let { return it }
            val raw = prefs().getString(KEY_REGISTRY, null)
            // 解析容错在 RollRegistry 内部（坏项跳过、整段损坏返回空），这里只留一条现场日志
            val out = RollRegistry.deserialize(raw)
            if (!raw.isNullOrEmpty() && out.isEmpty()) {
                Log.e(TAG, "loadAll 解析结果为空，登记可能已损坏（长度 ${raw.length}）")
            }
            cachedRolls = out
            return out
        }
    }

    /** persist 后同步更新进程内缓存。 */
    private fun persist(rolls: List<RollRec>) {
        cachedRolls = rolls.toMutableList()
        prefs().edit().putString(KEY_REGISTRY, RollRegistry.serialize(rolls)).apply()
    }

    private fun findRec(rolls: MutableList<RollRec>, rollId: String): RollRec =
        rolls.firstOrNull { it.rollId == rollId }
            ?: throw IllegalArgumentException("未知卷: $rollId")

    // ---------- 冻结 API ----------

    /** status==shooting 的活动卷 */
    fun currentRoll(): RollInfo? = synchronized(this) {
        loadAll().firstOrNull { RollRegistry.isShooting(it) }?.toInfo()
    }

    /** 开始新卷。已有活动卷时抛 IllegalStateException。 */
    fun startRoll(filmStockId: String, printStockId: String): RollInfo = synchronized(this) {
        val rolls = loadAll()
        if (rolls.any { RollRegistry.isShooting(it) }) {
            // 会随「保存失败」提示到用户，故走字符串资源
            throw IllegalStateException(requireContext().getString(R.string.film_roll_already_active))
        }
        val roll = RollRec(
            rollId = "roll_${System.currentTimeMillis()}",
            filmStockId = filmStockId,
            printStockId = printStockId,
            createdAtMs = System.currentTimeMillis(),
            status = STATUS_SHOOTING,
            frames = mutableListOf()
        )
        rolls.add(roll)
        persist(rolls)
        roll.toInfo()
    }

    /**
     * 存一帧原 JPEG（未冲洗、零处理）。返回当前张数（1..36）。
     * 拍满 36 抛 IllegalStateException。
     *
     * @param orientation 像素需顺时针旋转的角度（0/90/180/270），冲洗侧按此旋转
     */
    fun saveFrame(
        rollId: String,
        jpegBytes: ByteArray,
        takenAtMs: Long,
        iso: Int?,
        orientation: Int = 0,
    ): Int = synchronized(this) {
        val rolls = loadAll()
        val roll = findRec(rolls, rollId)
        check(RollRegistry.isShooting(roll)) { "卷状态非 shooting: ${roll.status}" }
        if (RollRegistry.isFull(roll)) {
            throw IllegalStateException(
                requireContext().getString(R.string.film_roll_full, MAX_SHOTS)
            )
        }
        val index = roll.frames.size + 1
        val nn = index.toString().padStart(2, '0')
        val handle: String
        val ctx = requireContext()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+：MediaStore pending 项，冲洗前不可见
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "FilmCam_R${roll.rollId}_$nn.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/FilmCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("MediaStore insert pending 失败")
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(jpegBytes)
                } ?: throw java.io.IOException("MediaStore openOutputStream 失败: $uri")
            } catch (e: Exception) {
                // 写入失败必须回收这条 pending 行：否则残留一条永远不可见、也不会被清理的孤儿记录
                runCatching { ctx.contentResolver.delete(uri, null, null) }
                throw e
            }
            handle = uri.toString()
        } else {
            // API 26..28：点目录直接写文件
            if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("API≤28 需要 WRITE_EXTERNAL_STORAGE 权限（调用方负责请求）")
            }
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "$DIR_UNDEVELOPED/${roll.rollId}"
            )
            if (!dir.exists() && !dir.mkdirs()) {
                throw java.io.IOException("无法创建未冲洗目录: $dir")
            }
            val file = File(dir, "frame_$nn.jpg")
            file.outputStream().use { it.write(jpegBytes) }
            handle = file.absolutePath
        }

        roll.frames.add(
            FrameRec(index = index, handle = handle, takenAtMs = takenAtMs, iso = iso, orientation = orientation)
        )
        persist(rolls)
        roll.frames.size
    }

    fun shotCount(rollId: String): Int = synchronized(this) {
        findRec(loadAll(), rollId).frames.size
    }

    fun frameTakenAt(rollId: String, index: Int): Long = synchronized(this) {
        findRec(loadAll(), rollId).frames.firstOrNull { it.index == index }?.takenAtMs
            ?: throw IllegalArgumentException("帧不存在: $rollId/$index")
    }

    fun frameIso(rollId: String, index: Int): Int? = synchronized(this) {
        findRec(loadAll(), rollId).frames.firstOrNull { it.index == index }?.iso
    }

    /** 该帧像素需顺时针旋转的角度（0/90/180/270）。缺省 0。 */
    fun frameOrientation(rollId: String, index: Int): Int = synchronized(this) {
        findRec(loadAll(), rollId).frames.firstOrNull { it.index == index }?.orientation ?: 0
    }

    /** 冲洗侧解码用：打开原始未冲洗帧的输入流。 */
    fun openFrameStream(rollId: String, index: Int): InputStream? = synchronized(this) {
        val rec = findRec(loadAll(), rollId).frames.firstOrNull { it.index == index }
            ?: return@synchronized null
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { ctx.contentResolver.openInputStream(Uri.parse(rec.handle)) }.getOrNull()
        } else {
            val f = File(rec.handle)
            if (f.exists()) f.inputStream() else null
        }
    }

    /** status in (shooting, developing) 的卷 */
    fun undevelopedRolls(): List<RollInfo> = synchronized(this) {
        loadAll().filter { RollRegistry.isUndeveloped(it) }.map { it.toInfo() }
    }

    fun markDeveloping(rollId: String) = synchronized(this) {
        val rolls = loadAll()
        val roll = findRec(rolls, rollId)
        if (RollRegistry.isShooting(roll)) {
            roll.status = STATUS_DEVELOPING
            persist(rolls)
        }
    }

    /**
     * 写冲洗后的成片，替换/落地未冲洗帧。返回最终可见 uri。
     *  - API29+：覆盖 pending 项内容 → 清 IS_PENDING → 可见
     *  - API26-28：正常插入 MediaStore 可见项 → 删除原始点目录文件
     */
    fun writeDevelopedFrame(rollId: String, index: Int, processedJpeg: java.io.File): Uri? = synchronized(this) {
        val rolls = loadAll()
        val roll = findRec(rolls, rollId)
        val frame = roll.frames.firstOrNull { it.index == index }
            ?: throw IllegalArgumentException("帧不存在: $rollId/$index")
        val ctx = requireContext()
        val nn = index.toString().padStart(2, '0')
        val bytes = processedJpeg.readBytes()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = Uri.parse(frame.handle)
            // "wt" 模式：先清空内容再写新 JPEG
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(bytes)
            } ?: throw java.io.IOException("openOutputStream(wt) 失败: $uri")
            // 清 IS_PENDING → 对相册可见；update 返回行数必须 >0，否则行已失效/未应用清除
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            val updated = ctx.contentResolver.update(uri, values, null, null)
            if (updated <= 0) {
                throw java.io.IOException("IS_PENDING 清除失败（MediaStore 行未更新）: $uri")
            }
            // 通知媒体库刷新观察者，系统相册/应用查询即时可见，防缓存不刷新
            ctx.contentResolver.notifyChange(uri, null)
            uri
        } else {
            // API 26..28：RELATIVE_PATH 被 provider 忽略，必须显式指定 DATA 绝对路径，
            // 先写文件到 DCIM/FilmCam，再 insert 带 DATA 的 MediaStore 行 → 相册可扫到。
            val outDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "FilmCam"
            )
            if (!outDir.exists() && !outDir.mkdirs()) {
                throw java.io.IOException("无法创建成片目录: $outDir")
            }
            val outFile = File(outDir, "FilmCam_R${roll.rollId}_$nn.jpg")
            outFile.outputStream().use { it.write(bytes) }

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, outFile.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATA, outFile.absolutePath)
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("MediaStore insert 成片失败: ${outFile.absolutePath}")
            // 删除原始未冲洗文件
            runCatching { File(frame.handle).delete() }
            uri
        }
    }

    /** 整卷冲洗完成：状态 → developed，清残留。 */
    fun finishRoll(rollId: String) = synchronized(this) {
        val rolls = loadAll()
        val roll = findRec(rolls, rollId)
        roll.status = STATUS_DEVELOPED
        // API26-28：删空的点目录
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "$DIR_UNDEVELOPED/${roll.rollId}"
                ).deleteRecursively()
            }
        }
        persist(rolls)
    }

    /** 弃卷：删残留（pending MediaStore 项或点目录文件）并从登记移除。 */
    fun deleteRoll(rollId: String) = synchronized(this) {
        val rolls = loadAll()
        val roll = rolls.firstOrNull { it.rollId == rollId } ?: return
        val ctx = requireContext()
        for (f in roll.frames) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ctx.contentResolver.delete(Uri.parse(f.handle), null, null)
                } else {
                    File(f.handle).delete()
                }
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "$DIR_UNDEVELOPED/$rollId"
                ).deleteRecursively()
            }
        }
        rolls.remove(roll)
        persist(rolls)
    }
}
