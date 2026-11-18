package com.filmcam.app.develop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import app.spektralab.engine.FilmStockMapper
import app.spektralab.engine.SpektraProcessor
import com.filmcam.app.GalleryActivity
import com.filmcam.app.R
import com.filmcam.app.film.MotherFilmRegistry
import com.filmcam.app.film.MotherNegativeStore
import com.filmcam.app.film.RollInfo
import com.filmcam.app.film.ShotMeta
import com.filmcam.app.film.UndevelopedFilmStore
import java.io.File

/**
 * v2 暗房编辑导出前台服务：把编辑态母片渲染成最终成片并写入相册。
 *
 * 流程（逐张，串行）：
 *  母片(原始输入引用 + 色彩配方)
 *   → 重建参数（filmStock/printStock/devSettings + 可选 overscan 片框）
 *   → 从原始输入重渲（有 DNG 走 RAW，否则 JPEG）成全分辨率纯色彩图
 *   → 日期戳（可选）→ 白边（可选，本地外扩，最后一步）
 *   → ContaxExif → NCC/XY 导出验真（不合格自动重导一次）
 *   → writeDevelopedFrame 入相册 → markExported 幂等
 *
 * 色彩一致性：重建参数用与冲洗完全相同的 FilmStockMapper.readDevSettings + buildParamsJson，
 * 所以编辑导出的色彩与母片一致；片框档额外注入 frameBorderEntries（引擎合成齿孔）。
 */
class EditExportService : Service() {

    companion object {
        private const val TAG = "EditExportService"
        const val EXTRA_ROLL_ID = "edit_roll_id"
        const val EXTRA_INDICES = "edit_indices"
        private const val CHANNEL = "edit_export"
        private const val NOTIF_ID = 81001
        const val ACTION_EXPORT_DONE = "com.filmcam.app.edit.export_done"
        const val EXTRA_FAILED = "edit_export_failed"
        const val EXTRA_ROLL_DONE = "edit_export_roll_done"

        fun start(context: Context, rollId: String, indices: IntArray) {
            val i = Intent(context, EditExportService::class.java).apply {
                putExtra(EXTRA_ROLL_ID, rollId)
                putExtra(EXTRA_INDICES, indices)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }

    @Volatile
    private var running = false
    private val mainHandler = Handler(Looper.getMainLooper())
    /** 片框版式真跑探测缓存（key=胶片|印相|format|朝向 → 错误原文；空串=通过）。 */
    private val frameProbeCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) {
            Log.w(TAG, "导出进行中，忽略重复请求")
            return START_NOT_STICKY
        }
        val rollId = intent?.getStringExtra(EXTRA_ROLL_ID)
        val indices = intent?.getIntArrayExtra(EXTRA_INDICES)
        if (rollId.isNullOrEmpty() || indices == null || indices.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        startForeground(NOTIF_ID, buildNotification(0, indices.size))
        Thread({ runExport(rollId, indices.toList()) }, "edit-export").start()
        return START_NOT_STICKY
    }

    private fun runExport(rollId: String, indices: List<Int>) {
        var failed = 0
        var rollDone = false
        try {
            SpektraProcessor.ensureEngine(applicationContext)
            indices.forEachIndexed { k, index ->
                updateProgress(k, indices.size, index)
                try {
                    exportOne(rollId, index)
                } catch (e: Throwable) {
                    failed++
                    Log.e(TAG, "导出帧 $index 失败", e)
                    mainHandler.post {
                        android.widget.Toast.makeText(
                            applicationContext, "第 $index 张导出失败: ${e.message?.take(60)}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            updateProgress(indices.size, indices.size, -1)
            // 全部帧都导出过（含本次）→ 卷编辑完成，从暗房列表移除。
            // 只导出部分帧（"仅此张"且还有别的没导）时不标记，卷继续留着待编辑。
            val done = runCatching {
                MotherNegativeStore.allExported(rollId) &&
                    UndevelopedFilmStore.markDevelopedFromEdit(rollId)
            }.getOrDefault(false)
            rollDone = done
            if (done) Log.i(TAG, "卷 $rollId 全部帧已导出，编辑完成（→ developed）")
        } catch (e: Throwable) {
            failed += indices.size
            Log.e(TAG, "导出流程异常中断", e)
            mainHandler.post {
                android.widget.Toast.makeText(
                    applicationContext, "导出失败: ${e.message?.take(60)}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } finally {
            // 🔴完成广播必须无条件发：之前 sendBroadcast 在 try 内，任何异常（含引擎
            // 报错）跳过它 → EditActivity 的 receiver 收不到 → 按钮永久卡"正在导出中"。
            sendBroadcast(Intent(ACTION_EXPORT_DONE).apply {
                setPackage(packageName) // 显式包，规避隐式广播在部分 ROM 上的限制
                putExtra(EXTRA_FAILED, failed)
                putExtra(EXTRA_ROLL_DONE, rollDone)
            })
            SpektraProcessor.scheduleReleaseEngine()
            running = false
            stopForeground(true)
            stopSelf()
        }
    }

    /** 导出单帧。返回是否成功入册。 */
    private fun exportOne(rollId: String, index: Int): Boolean {
        val mother = MotherNegativeStore.mother(rollId, index)
            ?: throw IllegalStateException("母片不存在: $rollId/$index")
        val edit = EditsStore.load(rollId).frames[index] ?: EditsCodec.FrameEdit()

        // 参数指纹幂等：同一套参数重复点不重复入库
        val engineVer = currentEngineVersion()
        val fp = MotherFilmRegistry.editFingerprint(mother.fingerprint, edit.frame, edit.dateOn, engineVer)
        if (MotherFilmRegistry.isExported(mother, fp)) {
            Log.i(TAG, "帧 $index 指纹 $fp 已导出过，跳过")
            return true
        }

        // 重建 devSettings（与冲洗同源）
        val rollInfo = rollInfoOf(rollId)
        val dev = FilmStockMapper.readDevSettings(applicationContext, rollInfo.filmStockId)
        val mappedPrint = rollInfo.printStockId.ifBlank { FilmStockMapper.DEFAULT_PRINT_FALLBACK }
        val resolved = FilmStockMapper.resolvePrintStock(rollInfo.filmStockId, mappedPrint, dev.paperOverride)
        val effective = dev.copy(paperOverride = resolved.paperId)

        // 片边档：与冲洗**同源**构建片框参数 + 真跑验证。
        // 🔴关键教训（JPG 真机反复报）：只做 setParams 预检（probeRejectedKeys）不够——
        // 片框版式匹配是在 solve 渲染期才校验的（引擎原文 "does not match this pipeline's
        // overscan layout"）。冲洗侧用 probeRenderOk 小图真跑挑版式，导出侧必须同样真跑，
        // 否则参数"预检通过"却在真正渲染时被拒 → 掉兜底 → 用户看不到片框、还慢。
        var params = mother.recipeJson.ifBlank {
            FilmStockMapper.buildParamsJson(rollInfo.filmStockId, mappedPrint, effective)
        }
        // 日期印字：交给**引擎**（`date_imprint_*`），不是 App 补画。
        // 🔴这决定了导出路径：引擎印字必须在渲染时发生，所以「日期开启」一律不能走
        // fast-path（拷 full.jpg）或 framed 预渲染（冲洗时没印日期），必须回原始输入重渲。
        // 代价是每帧多 ~2-3s；换来的是日期真的融在成像里（吃镜头柔度/颗粒/印相曲线）。
        val dateImprint: app.spektralab.engine.DateImprint? = if (edit.dateOn) {
            val at = runCatching { UndevelopedFilmStore.frameTakenAt(rollId, index) }
                .getOrDefault(System.currentTimeMillis())
            app.spektralab.engine.DateImprint.t3Style(
                app.spektralab.engine.DateImprint.textForMillis(at),
                portrait = EditRenderCore.isPortrait(rollId, index),
            )
        } else null
        val needsEngine = EditsCodec.needsEngineRerender(edit.frame) || dateImprint != null

        // v2 加速：片边档若在冲洗时已预渲染好带框成片，直接拿来用（跳过 probeRenderOk
        // 真跑 + 引擎重渲，~5-8s → 0）。预渲染图与下方即时重渲同源（同一套参数构建/
        // gate 裁切/兜底链，见 FramedPreRenderer），所以视觉逐像素一致。
        // 但预渲染图**没有日期**，日期开启时不能用它。
        val framedPre = MotherNegativeStore.framedFile(rollId, index)
        val useFramedPre = EditsCodec.needsEngineRerender(edit.frame) &&
            dateImprint == null &&
            framedPre.exists() && framedPre.length() > 0
        if (!useFramedPre && EditsCodec.needsEngineRerender(edit.frame)) {
            val gate = runCatching { ShotMeta.fromJson(mother.shotJson)?.gate }.getOrDefault(null)
            val medium = gate == "6x6"
            val format = if (medium) FilmStockMapper.FilmGate.MF6x6.overscanFormat
                else FilmStockMapper.OVERSCAN_FORMAT_FULL
            val portrait = runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                val src = if (mother.rawPath.isNotEmpty() && File(mother.rawPath).exists()) {
                    // DNG 帧：用母片全分辨率图判朝向（已按 gate 裁好）
                    MotherNegativeStore.fullFile(rollId, index)
                } else null
                if (src != null && src.exists()) {
                    BitmapFactory.decodeFile(src.absolutePath, o)
                    o.outWidth < o.outHeight
                } else {
                    UndevelopedFilmStore.frameOrientation(rollId, index).let { it == 90 || it == 270 }
                }
            }.getOrDefault(false)
            val entries = FilmStockMapper.frameBorderEntries(rollInfo.filmStockId, index, format)
            // 与冲洗 probeGate 同构：buildParamsJson(extraEntries) + 按真实画幅比例小图真跑。
            // 日期键一起带上：引擎对不认识/越界的键是硬失败，若 `date_imprint_*` 有问题
            // 这里就能提前发现，而不是等到真正重渲才炸掉整帧。
            val framed = app.spektralab.engine.DateImprint.inject(
                FilmStockMapper.buildParamsJson(
                    rollInfo.filmStockId, mappedPrint, effective,
                    includeStyleMark = false,
                    extraEntries = entries,
                ),
                dateImprint,
            )!!
            val aspect = if (medium) 1f else if (portrait) 2f / 3f else 3f / 2f
            val w = if (portrait) (96 * aspect).toInt().coerceAtLeast(1) else 96
            val h = if (portrait) 96 else (96 / aspect).toInt().coerceAtLeast(1)
            // 探测结果缓存：同一卷同版式同朝向只真跑一次（每次 ~1-2s），逐帧复用。
            // key 含 frameNumber 无关项（帧号只是印刷字，不影响版式能否出图）。
            val probeKey = "${rollInfo.filmStockId}|$mappedPrint|$format|$portrait"
            val probeErr = frameProbeCache.getOrPut(probeKey) {
                val err = runCatching { SpektraProcessor.probeRenderOk(applicationContext, framed, w, h) }
                    .onFailure { Log.w(TAG, "帧 $index 片框真跑预检异常: ${it.message}") }
                    .getOrNull() ?: ""   // "" = 通过
                Log.i(TAG, "片框版式真跑探测 key=$probeKey → ${if (err.isEmpty()) "通过" else err}")
                err
            }.let { it.ifEmpty { null } }
            if (probeErr == null) {
                params = framed // 验证通过，用带片框参数
                Log.i(TAG, "帧 $index 片框版式 $format ${if (portrait) "竖" else "横"} 真跑通过，带片框出图")
            } else {
                // 该版式这一朝向引擎不认 —— 不带框，至少保住画面（与冲洗 side 的 frameOkPortrait 同策略）
                Log.w(TAG, "帧 $index 片框版式 $format 真跑被拒: $probeErr → 本帧不带片框")
            }
        }

        val tmpOut = File(cacheDir, "edit_export_${rollId}_${index}.jpg")
        // fast-path：白边/无框档不重渲引擎 —— 母片已是引擎输出的纯色彩全分辨率图，
        // 直接拷来本地叠加日期+白边即可（亚秒级）。只有片边档才回原始输入重渲引擎，
        // 因为齿孔/片框是 overscan_* 参数在渲染期合成的，无法本地画。
        val full = MotherNegativeStore.fullFile(rollId, index)
        val fastPath = !needsEngine && full.exists()
        // 日期键在**调用时**合成：`params` / 兜底配方都是这卷共用的，逐帧日期不同。
        // 提到这里是为了让「验真重导」分支也用同一份，否则重导会把日期弄丢。
        val paramsWithDate = app.spektralab.engine.DateImprint.inject(params, dateImprint) ?: params
        val fallbackWithDate = app.spektralab.engine.DateImprint.inject(mother.recipeJson, dateImprint)
            ?: mother.recipeJson
        Log.i(TAG, "帧 $index 导出路径=${if (useFramedPre) "framed(冲洗预渲染·秒取)" else if (fastPath) "fast(本地全分辨率母片)" else "engine(重渲${if (dateImprint != null) "·引擎印日期" else "·片边"})"}")
        if (useFramedPre) {
            framedPre.inputStream().use { ins -> tmpOut.outputStream().use { os -> ins.copyTo(os) } }
        } else if (fastPath) {
            full.inputStream().use { ins -> tmpOut.outputStream().use { os -> ins.copyTo(os) } }
        } else {
            val result = rerender(mother, paramsWithDate, tmpOut, fallbackJson = fallbackWithDate)
            if (!result.ok) {
                // 保险：引擎若因日期参数（或其它原因）整帧失败，至少保住画面 ——
                // 一张没有日期但成色正常的成片，远好过让用户丢掉这张底片。
                if (dateImprint != null) {
                    Log.w(TAG, "帧 $index 带日期重渲失败（${result.message}），退回不带日期重试")
                    val plain = rerender(mother, params, tmpOut, fallbackJson = mother.recipeJson)
                    if (!plain.ok) throw IllegalStateException("重渲失败: ${plain.message}")
                } else {
                    throw IllegalStateException("重渲失败: ${result.message}")
                }
            }
        }

        // 白边：最后一步
        val finalSize = if (EditsCodec.needsWhiteBorder(edit.frame)) {
            WhiteBorderRenderer.applyToJpeg(tmpOut)
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmpOut.absolutePath, o)
            WhiteBorderRenderer.Size(o.outWidth, o.outHeight)
        }

        // EXIF（真实拍摄参数）
        val iso = runCatching { UndevelopedFilmStore.frameIso(rollId, index) }.getOrNull()
        ContaxExif.write(
            tmpOut, iso, finalSize.width, finalSize.height,
            runCatching { UndevelopedFilmStore.frameTakenAt(rollId, index) }
                .getOrDefault(System.currentTimeMillis()),
            metadataAtMs = System.currentTimeMillis(),
            filmDisplayName = FilmStockMapper.stockDisplayName(rollInfo.filmStockId),
            filmStockId = rollInfo.filmStockId, printStockId = rollInfo.printStockId,
            rollId = rollId, paramsJson = params, shotJson = mother.shotJson,
        )

        // 导出验真：不合格自动重导一次。
        // 🔴片边档跳过 X/Y 对比度验真：引擎齿孔/片基本来就是合法的 X 方向高频
        // （上下齿孔条行数远少于画面行，dx/dy 被抬高超上限 3.0），会被误判成花屏 →
        // 每帧白重渲一次（~3s）—— 这正是「导出太慢」的主因。片框是引擎真跑出来的，
        // 且上面 probeRenderOk 已验证过版式，不需要再拿验真兜一遍。
        val needVerify = !EditsCodec.needsEngineRerender(edit.frame)
        val verdict = if (needVerify) BitmapVerifierAdapter.contrastVerdict(tmpOut.absolutePath) else null
        if (verdict != null) {
            Log.w(TAG, "帧 $index 导出验真异常: $verdict，准备重导一次")
            if (fastPath && full.exists()) {
                full.inputStream().use { ins -> tmpOut.outputStream().use { os -> ins.copyTo(os) } }
                // fast-path 只在「日期关闭 + 白边/无框」时成立，所以这里不用再叠日期
                if (EditsCodec.needsWhiteBorder(edit.frame)) WhiteBorderRenderer.applyToJpeg(tmpOut)
            } else {
                val retry = rerender(mother, paramsWithDate, tmpOut, fallbackJson = fallbackWithDate)
                if (!retry.ok) throw IllegalStateException("重导失败: ${retry.message}")
            }
            // 11-17 真机：重导后仍不合格绝不入册——空白底片重导一万次还是噪点，
            // 按导出失败处理（上方 catch 已有 Toast），废片留在暗房由用户删除。
            val verdict2 = BitmapVerifierAdapter.contrastVerdict(tmpOut.absolutePath)
            if (verdict2 != null) {
                throw IllegalStateException("验真重导仍不合格: $verdict2（疑似废片底片）")
            }
        }

        UndevelopedFilmStore.writeDevelopedFrame(rollId, index, tmpOut)
        MotherNegativeStore.markExported(rollId, index, fp)
        tmpOut.delete()
        Log.i(TAG, "帧 $index 导出入相册成功（档=${edit.frame} 日期=${edit.dateOn}）")
        return true
    }

    /**
     * 从原始输入重渲 —— 实现已抽到 [EditRenderCore]，编辑页预览与导出共用同一份，
     * 避免「预览和成片不一样」。
     */
    private fun rerender(
        mother: MotherFilmRegistry.MotherRec,
        params: String,
        out: File,
        fallbackJson: String = "",
    ): SpektraProcessor.ProcessResult =
        EditRenderCore.rerender(applicationContext, mother, params, out, fallbackJson)


    private fun rollInfoOf(rollId: String): RollInfo =
        UndevelopedFilmStore.rollInfo(rollId)

    private fun appendEntries(paramsJson: String, extras: List<Pair<String, String>>): String {
        val o = org.json.JSONObject(paramsJson)
        extras.forEach { (k, v) ->
            runCatching {
                val parsed = when {
                    v == "true" || v == "false" -> v.toBoolean()
                    v.toIntOrNull() != null -> v.toInt()
                    v.toFloatOrNull() != null -> v.toFloat()
                    v.startsWith("\"") -> v.trim('"')
                    else -> v
                }
                o.put(k, parsed)
            }
        }
        return o.toString()
    }

    private fun currentEngineVersion(): Int = runCatching {
        SpektraProcessor.ensureEngine(applicationContext).let { 1 }
    }.getOrDefault(1)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "暗房导出", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(done: Int, total: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, GalleryActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("正在导出成片")
            .setContentText("$done / $total")
            .setSmallIcon(R.drawable.ic_develop)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateProgress(done: Int, total: Int, index: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(done, total))
    }
}
