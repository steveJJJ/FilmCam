package com.filmcam.app

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.spektralab.engine.FilmStockMapper
import com.filmcam.app.develop.DevelopController
import com.filmcam.app.film.RollInfo
import com.filmcam.app.film.UndevelopedFilmStore
import coil.load

class GalleryActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "GalleryActivity"
        /** 「冲洗完成」这条停留多久自己收起来（毫秒）：够看清，又不占着屏幕 */
        const val DEV_BAR_AUTO_HIDE_MS = 4000L
        /** 一卷 36 张（与 RollRegistry.MAX_SHOTS 一致），导入超了就截断 */
        const val MAX_IMPORT_FRAMES = 36
        /** 导入照片的像素上限：超过就降采样，别拿 50MP 去喂引擎 */
        const val MAX_IMPORT_PIXELS = 24_000_000
    }

    private lateinit var rvGallery: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var layoutUndeveloped: LinearLayout
    private lateinit var rvUndeveloped: RecyclerView
    private lateinit var devProgressCard: View
    private lateinit var tvDevProgress: TextView
    private lateinit var pbDevProgress: android.widget.ProgressBar

    /**
     * 后台冲洗时回到暗房，进度条是唯一能看见进展的地方 —— 服务每写完一帧
     * 都会回调这里（DevelopmentState 的 listener，服务走主线程 post）。
     * 完成那一帧还要顺手把未冲洗列表与相册网格刷一遍，否则用户看到的是旧数据。
     */
    private val devStateListener: () -> Unit = { runOnUiThread { renderDevProgress() } }

    /** 上一次渲染到的阶段：用来识别「刚从进行中变成完成」这一刻，只在那时刷列表 */
    private var lastDevPhase: String? = null

    /**
     * 本页亲眼看着洗过的那一卷。DevelopmentState 是进程级单例，上一次冲洗的完成态
     * 会一直留着 —— 没有这个标记，用户隔天进暗房还会看到一条陈旧的完成条；
     * 反过来说，只要这一卷是本页看着它从「显影中」走到「完成」的，就必须认账：
     * 弹完成条 + 刷列表相册 + Toast。
     */
    private var devTrackedRollId: String? = null

    /** 陈旧完成态（上一轮留下的）只要静默对齐一次数据，不必反复刷 */
    private var staleDoneRefreshed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        // 边到边：整页顶部/底部让出状态栏与导航栏
        val gRoot = com.filmcam.app.ui.EdgeToEdge.contentRoot(this)
        com.filmcam.app.ui.EdgeToEdge.setup(this, gRoot, gRoot)

        rvGallery = findViewById(R.id.rvGallery)
        tvEmpty = findViewById(R.id.tvEmpty)
        layoutUndeveloped = findViewById(R.id.layoutUndeveloped)
        rvUndeveloped = findViewById(R.id.rvUndeveloped)
        devProgressCard = findViewById(R.id.devProgressCard)
        tvDevProgress = findViewById(R.id.tvDevProgress)
        pbDevProgress = findViewById(R.id.pbDevProgress)

        // 列数按屏宽自适应：每格最小约 104dp，手机 3 列、大屏/折叠屏展开可到 6 列。
        // 原来固定 3 列 + 400px 行高，在 2K 屏上是细扁条，在折叠屏上又过大。
        val dm = resources.displayMetrics
        val minItemPx = (104f * dm.density).toInt().coerceAtLeast(1)
        val spanCount = (dm.widthPixels / minItemPx).coerceIn(3, 6)
        rvGallery.layoutManager = GridLayoutManager(this, spanCount)
        rvUndeveloped.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        // 暗房不跟随机身主题：机身配色是相机外壳的事，暗房是红灯下的工作环境，
        // 全黑底才能看片（银底会把成片的暗部压掉，也会破坏暗房氛围）。
        // 这里刻意不做 BodyTheme 重映射，保持原来的暗房配色。

        btnImport = findViewById(R.id.btnImport)
        btnImport.setOnClickListener { pickPhotos.launch(arrayOf("image/*")) }
    }

    // ---------- 导入本地照片冲洗 ----------

    private lateinit var btnImport: TextView

    /**
     * 从相册挑已有的照片来冲。走 SAF（无需存储权限），选完再问装哪卷胶卷 ——
     * 这些照片本来就是别人拿数码拍的，装进哪一卷，出片就是哪一卷的味道。
     */
    private val pickPhotos = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        askFilmAndImport(uris)
    }

    private fun askFilmAndImport(uris: List<Uri>) {
        val rolls = com.filmcam.app.filter.FilmRollRepository.rolls
        val ids = rolls.map { runCatching { FilmStockMapper.filmStockIdFor(it.name) }.getOrNull() }
        val names = rolls.map { runCatching { FilmStockMapper.stockDisplayName(FilmStockMapper.filmStockIdFor(it.name)) }.getOrDefault(it.name) }
        androidx.appcompat.app.AlertDialog.Builder(this, com.filmcam.app.ui.BodyTheme.dialogTheme)
            .setTitle(getString(R.string.gallery_import_pick_film))
            .setItems(names.toTypedArray()) { d, which ->
                d.dismiss()
                val id = ids[which]
                if (id == null) {
                    Toast.makeText(this, getString(R.string.gallery_import_failed, names[which]), Toast.LENGTH_SHORT).show()
                } else {
                    importAsRoll(uris, id)
                }
            }
            .setNegativeButton(getString(R.string.main_cancel), null)
            .show()
    }

    private fun importAsRoll(uris: List<Uri>, stockId: String) {
        // 一卷只能装一卷：还有没冲的卷就别混进来，否则两种胶卷的味道会串在同一卷里
        val active = UndevelopedFilmStore.currentRoll()
        if (active != null && UndevelopedFilmStore.shotCount(active.rollId) > 0) {
            Toast.makeText(this, getString(R.string.gallery_import_busy), Toast.LENGTH_LONG).show()
            return
        }
        val take = uris.take(MAX_IMPORT_FRAMES)
        if (uris.size > MAX_IMPORT_FRAMES) {
            Toast.makeText(this, getString(R.string.gallery_import_too_many), Toast.LENGTH_LONG).show()
        }
        // 装卷要解码 + 转码，几十兆一张的图不能占着主线程
        lifecycleScope.launch(Dispatchers.IO) {
            val outcome = runCatching { loadIntoFreshRoll(take, stockId) }
            withContext(Dispatchers.Main) {
                outcome.onSuccess { (rollId, count) ->
                    if (count <= 0) {
                        Toast.makeText(this@GalleryActivity, getString(R.string.gallery_import_none), Toast.LENGTH_SHORT).show()
                    } else {
                        // 直接进冲洗界面：那上面本来就有张数和进度，中间再弹一次是重复播报
                        loadUndevelopedRolls()
                        openDevelopmentScreen(rollId)
                    }
                }.onFailure { e ->
                    Log.w(TAG, "导入冲洗失败", e)
                    Toast.makeText(
                        this@GalleryActivity,
                        getString(R.string.gallery_import_failed, e.message ?: ""), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** 新建一卷，把选中的照片按帧写进去，返回 (rollId, 成功张数)。 */
    private fun loadIntoFreshRoll(uris: List<Uri>, stockId: String): Pair<String, Int> {
        UndevelopedFilmStore.init(this)
        val printId = FilmStockMapper.rollTable().values.firstOrNull { it.first == stockId }?.second
            ?: FilmStockMapper.rollTable().values.first().second
        val roll = UndevelopedFilmStore.startRoll(stockId, printId)
        var count = 0
        for (uri in uris) {
            val frame = readPhotoAsFrame(uri, stockId, printId) ?: continue
            UndevelopedFilmStore.saveFrame(
                roll.rollId, frame.bytes, frame.takenAtMs, null, frame.orientation,
                rawDng = frame.rawFile,
            )
            count++
        }
        if (count == 0) {
            // 一张都没读出来就别留个空卷占位：它会挡住下次导入，也会出现在暗房列表里
            UndevelopedFilmStore.deleteRoll(roll.rollId)
        }
        return roll.rollId to count
    }

    private class ImportedFrame(
        val bytes: ByteArray,
        val takenAtMs: Long,
        val orientation: Int,
        /** RAW 底片临时文件（引擎/相机侧产出的 DNG/RAW）；null = 纯 JPEG 帧 */
        val rawFile: java.io.File? = null,
    )

    /**
     * 读一张本地照片入卷。两条路：
     * - 压缩图（JPEG/PNG/HEIC…）：BitmapFactory 降采样后重压 JPEG；
     * - RAW（DNG/ARW/NEF…）：BitmapFactory 解不了传感器原始数据，拷成临时文件后
     *   交给引擎 RAW 会话渲染一张**兜底 JPEG**入卷（顺带验证引擎解得开这张 RAW），
     *   RAW 本体作为「底片」随帧入卷 —— 冲洗时优先走 RAW 通路（P3），失败落回兜底 JPEG。
     *
     * RAW 判定按扩展名优先（SAF 对 RAW 的 mime 常常是 octet-stream）；扩展名/mime
     * 都认不出但 BitmapFactory 又探不出尺寸的，最后也按 RAW 试一次——解不开自然跳过。
     */
    private fun readPhotoAsFrame(uri: Uri, stockId: String, printId: String): ImportedFrame? {
        val cr = contentResolver
        val displayName = queryDisplayName(uri)
        val ext = displayName?.substringAfterLast('.', "")?.lowercase() ?: ""
        if (com.filmcam.app.develop.PhotoImportDecoder.isRawFileName(displayName) ||
            com.filmcam.app.develop.PhotoImportDecoder.isRawMime(runCatching { cr.getType(uri) }.getOrNull())
        ) {
            return readRawAsFrame(uri, stockId, printId, ext.ifBlank { "dng" })
        }

        val bmp = com.filmcam.app.develop.PhotoImportDecoder.decodeSampled(
            openStream = { runCatching { cr.openInputStream(uri) }.getOrNull() },
            probe = { s ->
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                // 注意：探测模式下 decodeStream 必定返回 null，不能用它的返回值判成败，
                // 成败只看尺寸有没有被填出来（早先就是这里写成 ?: return null，导致导入永远 0 张）
                android.graphics.BitmapFactory.decodeStream(s, null, o)
                if (o.outWidth <= 0 || o.outHeight <= 0) null
                else com.filmcam.app.develop.PhotoImportDecoder.Size(o.outWidth, o.outHeight)
            },
            decode = { s, sample ->
                android.graphics.BitmapFactory.decodeStream(
                    s, null,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                )
            },
            maxPixels = MAX_IMPORT_PIXELS.toLong(),
        )
        if (bmp == null) {
            // BitmapFactory 探不出尺寸 ≠ 坏文件：RAW 就是探不出来的。最后按 RAW 试一次
            Log.i(TAG, "导入探测不到尺寸，按 RAW 再试一次：$uri")
            return readRawAsFrame(uri, stockId, printId, ext.ifBlank { "dng" })
        }
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            out.toByteArray()
        }
        bmp.recycle()

        val (orientation, takenAtMs) = readExifMeta(uri)
        return ImportedFrame(bytes, takenAtMs, orientation)
    }

    /**
     * 读一张 RAW 入卷：临时文件 → 引擎渲染兜底 JPEG（验证可解码）→ 方向归一化。
     * 任何一步失败返回 null 并清理临时文件。
     */
    private fun readRawAsFrame(uri: Uri, stockId: String, printId: String, ext: String): ImportedFrame? {
        // 1. 引擎 RAW 会话只认文件路径：先拷到 cache
        val tmpRaw = java.io.File(cacheDir, "import_raw_${System.currentTimeMillis()}.$ext")
        val copied = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                tmpRaw.outputStream().use { input.copyTo(it) }
            } != null && tmpRaw.length() > 0
        }.getOrDefault(false)
        if (!copied) {
            Log.w(TAG, "导入 RAW 拷贝失败，跳过：$uri")
            runCatching { tmpRaw.delete() }
            return null
        }

        // 2. 引擎渲染兜底 JPEG（限制长边求快）；渲染失败 = 引擎解不开这张 RAW
        val tmpJpeg = java.io.File(cacheDir, "import_preview_${System.currentTimeMillis()}.jpg")
        val params = runCatching {
            FilmStockMapper.buildParamsJson(stockId, printId)
        }.getOrNull()
        val dims = if (params != null) {
            app.spektralab.engine.SpektraProcessor.renderRawPreview(
                applicationContext, tmpRaw.absolutePath, params, tmpJpeg.absolutePath
            )
        } else null
        if (dims == null) {
            Log.w(TAG, "导入 RAW 引擎解不开（或参数构建失败），跳过：$uri")
            runCatching { tmpRaw.delete() }; runCatching { tmpJpeg.delete() }
            return null
        }

        // 3. EXIF 方向原样记录、兜底 JPEG 原样存（不归一化）：RAW 冲洗通路的旋转启发式
        //    和竖片门版式选择都靠这个记录（与相机 DNG 帧同一语义）；
        //    JPEG 兜底通路按记录旋转 —— 引擎若已应用方向标签，双重失败兜底时可能多转一次，
        //    但那是「RAW 冲洗失败 + 引擎转过了」双重小概率，帧不丢即可
        val (orientation, takenAtMs) = readExifMeta(uri)
        val bmp = android.graphics.BitmapFactory.decodeFile(tmpJpeg.absolutePath)
        if (bmp != null) {
            val bytes = java.io.ByteArrayOutputStream().use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                out.toByteArray()
            }
            bmp.recycle()
            runCatching { tmpJpeg.delete() }
            Log.i(TAG, "导入 RAW 成功 ${dims.first}x${dims.second} 底片=${tmpRaw.length() / 1024}KB：$uri")
            return ImportedFrame(bytes, takenAtMs, orientation, rawFile = tmpRaw)
        }
        // 兜底 JPEG 解不开（理论不该发生）：底片照留，用 1x1 占位——帧记录必须有 JPEG handle
        Log.w(TAG, "导入 RAW 的兜底 JPEG 解码失败：$uri")
        runCatching { tmpJpeg.delete() }
        val placeholder = java.io.ByteArrayOutputStream().use { out ->
            android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                .compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
        return ImportedFrame(placeholder, takenAtMs, orientation, rawFile = tmpRaw)
    }

    /** 从 uri 读 EXIF 方向（0/90/180/270）与拍摄时刻（读不到用当前时间）。 */
    private fun readExifMeta(uri: Uri): Pair<Int, Long> {
        var orientation = 0
        var takenAtMs = System.currentTimeMillis()
        runCatching {
            contentResolver.openInputStream(uri)?.use { s ->
                val exif = androidx.exifinterface.media.ExifInterface(s)
                // androidx ExifInterface 支持 DNG/CR2/NEF/NRW/ARW/RW2/ORF/PEF/SRW/RAF 等
                // TIFF 系 RAW 的读取，方向与拍摄时刻照抄——日期戳要的是拍摄那天，不是导入当天
                orientation = when (
                    exif.getAttributeInt(
                        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
                val dt = exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME)
                if (!dt.isNullOrBlank()) {
                    val parsed = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).parse(dt)
                    if (parsed != null) takenAtMs = parsed.time
                }
            }
        }
        return orientation to takenAtMs
    }

    /** SAF uri 的显示名（判扩展名用） */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    override fun onStart() {
        super.onStart()
        com.filmcam.app.develop.DevelopmentState.addListener(devStateListener)
        renderDevProgress()
    }

    override fun onStop() {
        com.filmcam.app.develop.DevelopmentState.removeListener(devStateListener)
        cancelDevBarAutoHide()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 顺手把"因为没填拍摄时间而沉在相册历史里"的成片捞回来（导入旧照片时踩过）
        lifecycleScope.launch(Dispatchers.IO) {
            val fixed = UndevelopedFilmStore.repairMissingDateTaken()
            if (fixed > 0) {
                withContext(Dispatchers.Main) { loadPhotos() }
            }
        }
        loadUndevelopedRolls()
        loadPhotos()
        renderDevProgress()
    }

    // ---------- 后台冲洗进度条 ----------

    /**
     * 按 DevelopmentState 渲染顶部进度条。
     *  - 进行中：显影中 n/36 + 进度条，点进去看冲洗页（带水波盘那页）
     *  - 完成：切成完成文案，并刷新列表与相册（服务已经 finishRoll 落盘）
     *  - 失败：切成重试文案，点进去走冲洗页的重试
     *  - 没有卷在冲洗：整条隐藏
     */
    private fun renderDevProgress() {
        val st = com.filmcam.app.develop.DevelopmentState
        val rollId = st.rollId
        if (rollId == null) {
            devProgressCard.visibility = View.GONE
            lastDevPhase = null
            return
        }
        devProgressCard.visibility = View.VISIBLE
        // 只要这卷还在洗（准备/显影/失败），就记住它 —— 等它跨到 DONE 时这条路认得出
        if (st.phase != com.filmcam.app.develop.DevelopmentState.PHASE_DONE) {
            devTrackedRollId = rollId
        }
        when (st.phase) {
            com.filmcam.app.develop.DevelopmentState.PHASE_DONE -> {
                if (devTrackedRollId != rollId) {
                    // 这不是本页看着洗完的卷，是进程里留着的旧完成态（用户可能是隔天进来、
                    // 或者从冲洗页之外的地方回到暗房）→ 不打扰：不弹完成条、不 Toast。
                    // 但数据必须静默对齐一次，否则列表/相册会停在上一次的旧画面上。
                    devProgressCard.visibility = View.GONE
                    lastDevPhase = st.phase
                    if (!staleDoneRefreshed) {
                        staleDoneRefreshed = true
                        loadUndevelopedRolls()
                        loadPhotos()
                    }
                    return
                }
                tvDevProgress.text = getString(R.string.pf_dev_bar_done, st.done)
                pbDevProgress.progress = 100
                devProgressCard.setOnClickListener {
                    // 点完成条 = 看刚出片的结果：刷新后隐藏
                    devProgressCard.visibility = View.GONE
                    cancelDevBarAutoHide()
                    loadUndevelopedRolls()
                    loadPhotos()
                }
                if (lastDevPhase != st.phase) {
                    // 刚完成：立即把成片与未冲洗列表刷成最新
                    // （刚完成的这一路一定要刷 —— 用户从冲洗页点「后台冲洗」回到暗房后，
                    //  完成回调是从状态监听来的，不走 onResume，漏了这里列表就一直挂着老画面）
                    loadUndevelopedRolls()
                    loadPhotos()
                    // 注意：这里刻意不弹 Toast。这条状态汇报归机顶 LCD ——
                    // 屏幕中下方浮一块 Android 吐司跟机身没任何关系；用户回到机身时
                    // LCD 会通报「冲洗完成 出片 N 张」（见 MainActivity.announceDevelopedOnLcd）。
                    // 完成条不必等用户来点：几秒后自己收起来，并再刷一次列表与相册。
                    // 出现过「卡片没了、下面还挂着刚冲完那卷」，多刷一拍兜住写盘/索引的最后一帧。
                    scheduleDevBarAutoHide()
                }
            }
            com.filmcam.app.develop.DevelopmentState.PHASE_ERROR -> {
                tvDevProgress.text = getString(R.string.pf_dev_bar_failed)
                pbDevProgress.progress =
                    if (st.total > 0) (st.done * 100 / st.total) else 0
                devProgressCard.setOnClickListener { openDevelopmentScreen(rollId) }
                if (lastDevPhase != st.phase) loadUndevelopedRolls()
            }
            else -> {
                // preparing / developing
                val done = st.done.coerceAtMost(st.total)
                val eta = st.etaSeconds()
                tvDevProgress.text =
                    if (st.phase == com.filmcam.app.develop.DevelopmentState.PHASE_PREPARING ||
                        st.total <= 0
                    ) {
                        // 准备阶段（首次要把引擎资源从 assets 拷出来）先说明在干什么，
                        // 不然那十几秒像是卡住了
                        if (st.preparingFirstTime) getString(R.string.dev_preparing_first)
                        else getString(R.string.dev_notif_preparing)
                    } else if (eta > 0) {
                        // 有速度样本之后给预估：让用户知道还要等多久
                        getString(R.string.dev_progress_eta, done, st.total, formatEta(eta))
                    } else {
                        getString(R.string.dev_progress, done, st.total)
                    }
                pbDevProgress.progress =
                    if (st.total > 0) (done * 100 / st.total) else 0
                devProgressCard.setOnClickListener { openDevelopmentScreen(rollId) }
            }
        }
        lastDevPhase = st.phase
    }

    /** 秒 → "40 秒"（英文 "40s"）；超过一分钟拆成分 + 秒。与通知栏同一套措辞。 */
    private fun formatEta(seconds: Int): String =
        if (seconds < 60) getString(R.string.dev_eta_sec, seconds)
        else getString(R.string.dev_eta_min, seconds / 60, seconds % 60)

    /** 完成条自动收起：顺手再把列表与相册刷一遍 */
    private val devBarAutoHide = Runnable {
        if (com.filmcam.app.develop.DevelopmentState.phase ==
            com.filmcam.app.develop.DevelopmentState.PHASE_DONE
        ) {
            devProgressCard.visibility = View.GONE
            loadUndevelopedRolls()
            loadPhotos()
        }
    }

    private fun scheduleDevBarAutoHide() {
        devProgressCard.removeCallbacks(devBarAutoHide)
        devProgressCard.postDelayed(devBarAutoHide, DEV_BAR_AUTO_HIDE_MS)
    }

    private fun cancelDevBarAutoHide() {
        devProgressCard.removeCallbacks(devBarAutoHide)
    }

    private fun openDevelopmentScreen(rollId: String) {
        startActivity(
            android.content.Intent(this, com.filmcam.app.develop.DevelopmentActivity::class.java)
                .putExtra(com.filmcam.app.develop.DevelopController.EXTRA_ROLL_ID, rollId)
        )
    }

    // ---------- 未冲洗胶卷区 ----------

    private fun loadUndevelopedRolls() {
        // 刚冲完的那一卷：登记里的状态有可能还没回落（写盘与索引都有一点延迟），
        // 但用户已经看到"冲洗完成"，下面还挂着一张"未冲洗"的卡就自相矛盾。
        // 这里按进度状态兜一道——UI 与用户看到的事实保持一致。
        val st = com.filmcam.app.develop.DevelopmentState
        val doneRollId =
            if (st.phase == com.filmcam.app.develop.DevelopmentState.PHASE_DONE) st.rollId else null
        val rolls = UndevelopedFilmStore.undevelopedRolls().filter { it.rollId != doneRollId }
        if (rolls.isEmpty()) {
            layoutUndeveloped.visibility = View.GONE
            return
        }
        layoutUndeveloped.visibility = View.VISIBLE
        rvUndeveloped.adapter = UndevelopedAdapter(rolls)
    }

    inner class UndevelopedAdapter(private val items: List<RollInfo>) :
        RecyclerView.Adapter<UndevelopedAdapter.VH>() {

        inner class VH(val card: View) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            // 简单在代码里拼一个横向卡片，避免新增布局文件
            val ctx = parent.context
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 18, 24, 18)
                setBackgroundColor(0xFF2A0808.toInt())
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 12 }
            }
            val nameTv = TextView(ctx).apply {
                setTextColor(0xFFFFD7C4.toInt())
                textSize = 14f
                tag = "name"
            }
            val countTv = TextView(ctx).apply {
                setTextColor(0xFFFF9A86.toInt())
                textSize = 12f
                tag = "count"
            }
            val btn = Button(ctx).apply {
                text = ctx.getString(R.string.pf_develop)
                setTextColor(0xFFFFFFFF.toInt())
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF8B1A1A.toInt())
                tag = "btn"
            }
            card.addView(nameTv)
            card.addView(countTv)
            card.addView(btn)
            return VH(card)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val roll = items[position]
            val ctx = holder.card.context
            (holder.card.findViewWithTag<TextView>("name")).text =
                FilmStockMapper.stockDisplayName(roll.filmStockId)
            (holder.card.findViewWithTag<TextView>("count")).text = "${roll.shotCount}/36"
            val btn = holder.card.findViewWithTag<Button>("btn")
            if (roll.status == UndevelopedFilmStore.STATUS_DEVELOPING) {
                // 这一卷已经在冲洗了：再点「冲洗」只会被服务防重入拒绝（什么也不发生），
                // 按钮直接改成看进度，用户才知道去哪儿看它洗到哪了。
                btn.text = ctx.getString(R.string.pf_dev_view_progress)
                btn.setOnClickListener { openDevelopmentScreen(roll.rollId) }
            } else {
                btn.text = ctx.getString(R.string.pf_develop)
                btn.setOnClickListener { DevelopController.startDevelopment(ctx, roll.rollId) }
            }
        }

        override fun getItemCount() = items.size
    }

    // ---------- 已冲洗相册网格 ----------

    private fun loadPhotos() {
        val photos = mutableListOf<Uri>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID)

        // API 29+：RELATIVE_PATH 精确定位 DCIM/FilmCam/，并显式排除 IS_PENDING=1 的未冲洗帧。
        //   pending 项对本应用自身是可见的，若不排除，用户会在冲洗前就在相册里看到原片，
        //   破坏"拍完不可见、冲洗才出片"的核心体验。
        // API 26-28：没有 RELATIVE_PATH / IS_PENDING 这两列，查它会抛
        //   SQLiteException(no such column)，改用 DATA 路径前缀匹配。
        val selection: String
        val selectionArgs: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                "${MediaStore.Images.Media.IS_PENDING} = 0"
            selectionArgs = arrayOf("DCIM/FilmCam/")
        } else {
            selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            selectionArgs = arrayOf("%/DCIM/FilmCam/%")
        }
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
                ?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        photos.add(Uri.withAppendedPath(collection, id.toString()))
                    }
                }
        } catch (e: Exception) {
            // 查询失败不能连累整个页面：降级为空态，并提示用户
            Log.e(TAG, "查询相册失败", e)
            Toast.makeText(this, getString(R.string.pf_gallery_read_failed), Toast.LENGTH_SHORT).show()
        }

        if (photos.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvGallery.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvGallery.visibility = View.VISIBLE
            rvGallery.adapter = GalleryAdapter(photos)
        }
    }

    inner class GalleryAdapter(private val items: List<Uri>) :
        RecyclerView.Adapter<GalleryAdapter.ViewHolder>() {

        inner class ViewHolder(val iv: ImageView) : RecyclerView.ViewHolder(iv)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            // 正方形容器：宽度由 GridLayoutManager 均分，高度自动等于宽度。
            // 原先写死 400px，在 2K 屏上缩成细扁条、在折叠屏上过大。
            val gap = (2 * parent.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val iv = com.filmcam.app.ui.SquareImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(gap, gap, gap, gap)
            }
            val holder = ViewHolder(iv)
            // 点击事件只设置一次，位置取 holder 的 bindingAdapterPosition 避免复用错乱
            iv.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val intent = android.content.Intent(this@GalleryActivity, PhotoViewerActivity::class.java).apply {
                        putStringArrayListExtra(PhotoViewerActivity.EXTRA_URIS, ArrayList(items.map { it.toString() }))
                        putExtra(PhotoViewerActivity.EXTRA_POSITION, pos)
                    }
                    startActivity(intent)
                }
            }
            return holder
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            // Coil：自动内存/磁盘缓存 + 按 View 尺寸采样解码，滚动时自动取消复用项的旧请求
            holder.iv.load(items[position]) {
                crossfade(true)
            }
        }

        override fun getItemCount() = items.size
    }
}
