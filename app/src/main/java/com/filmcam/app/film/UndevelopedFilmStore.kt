package com.filmcam.app.film

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
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
    private const val MAX_SHOTS = 36
    private const val DIR_UNDEVELOPED = ".FilmCamUndeveloped"

    const val STATUS_SHOOTING = "shooting"
    const val STATUS_DEVELOPING = "developing"
    const val STATUS_DEVELOPED = "developed"

    @Volatile
    private var appContext: Context? = null

    /** 进程内缓存，避免每次 API 调用都从 SharedPreferences 反序列化 JSON。
     *  冲洗循环每帧调 openFrameStream → loadAll，36 帧 = 36 次解析 → 缓存后仅首次解析。 */
    @Volatile
    private var cachedRolls: MutableList<RollRec>? = null

    /** 进程启动时由 MainActivity 调用一次，缓存 applicationContext（冻结业务 API 不带 context 参数）。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context =
        appContext ?: throw IllegalStateException("UndevelopedFilmStore.init(context) 尚未调用")

    // ---------- 内部记录（含 handle/takenAtMs/iso，不对外暴露） ----------

    private data class FrameRec(
        val index: Int,
        val handle: String,          // MediaStore uri 字符串（API29+）或文件绝对路径（API26-28）
        val takenAtMs: Long,
        val iso: Int?
    )

    private data class RollRec(
        val rollId: String,
        val filmStockId: String,
        val printStockId: String,
        val createdAtMs: Long,
        var status: String,
        val frames: MutableList<FrameRec>
    ) {
        fun toInfo(): RollInfo =
            RollInfo(
                rollId = rollId,
                filmStockId = filmStockId,
                printStockId = printStockId,
                createdAtMs = createdAtMs,
                status = status,
                shotCount = frames.size,
                frames = frames.map { it.index }
            )
    }

    private fun prefs(): SharedPreferences =
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 从进程内缓存读取；首次或缓存被清时从 SharedPreferences 反序列化。 */
    private fun loadAll(): MutableList<RollRec> {
        cachedRolls?.let { return it }
        synchronized(this) {
            cachedRolls?.let { return it }
            val out = mutableListOf<RollRec>()
            val raw = prefs().getString(KEY_REGISTRY, null)
            if (!raw.isNullOrEmpty()) {
                try {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val frames = mutableListOf<FrameRec>()
                        val fa = o.optJSONArray("frames") ?: JSONArray()
                        for (j in 0 until fa.length()) {
                            val f = fa.getJSONObject(j)
                            frames.add(
                                FrameRec(
                                    index = f.getInt("index"),
                                    handle = f.getString("handle"),
                                    takenAtMs = f.getLong("takenAtMs"),
                                    iso = if (f.isNull("iso")) null else f.getInt("iso")
                                )
                            )
                        }
                        out.add(
                            RollRec(
                                rollId = o.getString("rollId"),
                                filmStockId = o.getString("filmStockId"),
                                printStockId = o.getString("printStockId"),
                                createdAtMs = o.getLong("createdAtMs"),
                                status = o.getString("status"),
                                frames = frames
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "loadAll 解析失败", e)
                }
            }
            cachedRolls = out
            return out
        }
    }

    /** persist 后同步更新进程内缓存。 */
    private fun persist(rolls: List<RollRec>) {
        cachedRolls = rolls.toMutableList()
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
                fa.put(fo)
            }
            o.put("frames", fa)
            arr.put(o)
        }
        prefs().edit().putString(KEY_REGISTRY, arr.toString()).apply()
    }

    private fun findRec(rolls: MutableList<RollRec>, rollId: String): RollRec =
        rolls.firstOrNull { it.rollId == rollId }
            ?: throw IllegalArgumentException("未知卷: $rollId")

    // ---------- 冻结 API ----------

    /** status==shooting 的活动卷 */
    fun currentRoll(): RollInfo? = synchronized(this) {
        loadAll().firstOrNull { it.status == STATUS_SHOOTING }?.toInfo()
    }

    /** 开始新卷。已有活动卷时抛 IllegalStateException。 */
    fun startRoll(filmStockId: String, printStockId: String): RollInfo = synchronized(this) {
        val rolls = loadAll()
        if (rolls.any { it.status == STATUS_SHOOTING }) {
            throw IllegalStateException("已有活动卷（shooting），不能同时开第二卷")
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
     */
    fun saveFrame(rollId: String, jpegBytes: ByteArray, takenAtMs: Long, iso: Int?): Int = synchronized(this) {
        val rolls = loadAll()
        val roll = findRec(rolls, rollId)
        check(roll.status == STATUS_SHOOTING) { "卷状态非 shooting: ${roll.status}" }
        if (roll.frames.size >= MAX_SHOTS) {
            throw IllegalStateException("胶卷已拍满 $MAX_SHOTS 张，请先冲洗")
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
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(jpegBytes)
            } ?: throw java.io.IOException("MediaStore openOutputStream 失败: $uri")
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

        roll.frames.add(FrameRec(index = index, handle = handle, takenAtMs = takenAtMs, iso = iso))
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
        loadAll().filter { it.status == STATUS_SHOOTING || it.status == STATUS_DEVELOPING }
            .map { it.toInfo() }
    }

    fun markDeveloping(rollId: String) = synchronized(this) {
        val rolls = loadAll()
        val roll = findRec(rolls, rollId)
        if (roll.status == STATUS_SHOOTING) {
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
