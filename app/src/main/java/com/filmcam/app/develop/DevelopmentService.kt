package com.filmcam.app.develop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import app.spektralab.engine.EngineResources
import app.spektralab.engine.FilmStockMapper
import app.spektralab.engine.SpektraProcessor
import com.filmcam.app.GalleryActivity
import com.filmcam.app.R
import com.filmcam.app.film.RollInfo
import com.filmcam.app.film.UndevelopedFilmStore
import java.io.File

/**
 * 暗房冲洗前台服务（规格 §6.3）。
 *
 *  - 通道 "development"，常驻进度通知（"显影中 n/36" + 进度条）
 *  - 处理线程：逐帧 decode → processFrame → DateStamp → EXIF → writeDevelopedFrame
 *  - 单帧失败不中断整卷（errorCount++，跳过）
 *  - 卷已是 developing（上次中断残留）：从 index 1 重跑全部帧（writeDevelopedFrame 覆盖写，幂等）
 *  - 完成 → finishRoll → releaseEngine → 通知"冲洗完成，可查看" → stopSelf
 *    （DevelopmentState 保留完成态给活动读）
 *  - 引擎在工作线程创建（ensureEngine 较慢，不放主线程）
 */
class DevelopmentService : Service() {

    companion object {
        private const val TAG = "DevelopmentService"
        private const val CHANNEL_ID = "development"
        private const val NOTIF_ID = 1
        private const val ACTION_START = "com.filmcam.development.START"
        private const val ACTION_STOP = "com.filmcam.development.STOP"

        /** 由 DevelopController 调用：兼容 API 26+ startForegroundService。 */
        fun start(ctx: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var worker: Thread? = null

    /** onStartCommand 传入的真实 startId，用于 stopSelf(currentStartId)。 */
    @Volatile
    private var currentStartId: Int = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rollId = intent?.getStringExtra(DevelopController.EXTRA_ROLL_ID)
            ?: return START_NOT_STICKY
        currentStartId = startId

        // 立即挂前台通知（API 34 必须带 type）
        startForegroundWithType(
            buildProgressNotification(0, 0, getString(R.string.dev_notif_preparing))
        )

        // 防重入：
        //  - 同一卷重复请求 → 直接忽略（正在跑的 worker 会持续刷新进度通知）
        //  - 另一卷正在冲洗 → 拒绝并发。单帧峰值内存上百 MB，两卷并发必 OOM，
        //    且两者会争用同一个引擎实例与同一块临时文件。
        val running = worker
        if (running?.isAlive == true) {
            if (DevelopmentState.rollId == rollId) {
                return START_NOT_STICKY
            }
            Log.w(TAG, "已有卷在冲洗（${DevelopmentState.rollId}），拒绝并发请求: $rollId")
            updateProgressNotification(
                DevelopmentState.done,
                DevelopmentState.total,
                getString(R.string.dev_notif_busy)
            )
            return START_NOT_STICKY
        }

        worker = Thread({ runDevelopment(rollId) }, "DevWorker-$rollId").also { it.start() }
        return START_NOT_STICKY
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.dev_darkroom),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.dev_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    // ---------- 主冲洗循环 ----------

    private fun runDevelopment(rollId: String) {
        try {
            // 1) 定位卷（status 会自动变 developing）
            val roll: RollInfo = UndevelopedFilmStore.undevelopedRolls()
                .firstOrNull { it.rollId == rollId }
                ?: run {
                    Log.w(TAG, "未找到卷 $rollId（可能已冲洗），退出")
                    finishWithError(getString(R.string.dev_error_roll_missing))
                    return
                }

            // 幂等：卷已是 developing 也从 index 1 重跑（writeDevelopedFrame 覆盖写）
            UndevelopedFilmStore.markDeveloping(rollId)

            // 2) 初始化共享状态
            DevelopmentState.reset()
            DevelopmentState.rollId = roll.rollId
            DevelopmentState.total = roll.shotCount
            DevelopmentState.done = 0
            DevelopmentState.currentIndex = 0
            DevelopmentState.errorCount = 0
            // 先进入「准备」阶段：首次冲洗要把 30MB 引擎资源从 assets 拷到 filesDir
            // 并创建引擎（约 10s），此前这段静默进行，UI 停在「显影中 0/36」，
            // 用户会以为 App 卡死。
            DevelopmentState.preparingFirstTime = !EngineResources.isPrepared(this)
            DevelopmentState.phase = DevelopmentState.PHASE_PREPARING
            notifyStateToMain()

            // 3) 在工作线程创建引擎（ensureEngine 较慢）
            try {
                SpektraProcessor.ensureEngine(applicationContext)
            } catch (e: Throwable) {
                Log.e(TAG, "引擎初始化失败", e)
                finishWithError(getString(R.string.dev_error_engine_init, e.message))
                return
            }

            DevelopmentState.phase = DevelopmentState.PHASE_DEVELOPING
            notifyStateToMain()

            // 冲洗开始时读一次设置；帧循环内不做动态重发（grain/halation/glare 是普通参数字段）
            val devSettings = FilmStockMapper.readDevSettings(this, roll.filmStockId)
            // 旧记录可能存了空相纸 id：冲洗时兜底到合法占位（反转片 scan_film 模式下不参与成像）
            val mappedPrint = roll.printStockId.ifBlank { FilmStockMapper.DEFAULT_PRINT_FALLBACK }
            val resolved = FilmStockMapper.resolvePrintStock(
                roll.filmStockId, mappedPrint, devSettings.paperOverride
            )
            if (resolved.fellBack) {
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.dev_toast_slide_no_paper),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            val effective = devSettings.copy(paperOverride = resolved.paperId)
            val paramsJson = FilmStockMapper.buildParamsJson(roll.filmStockId, mappedPrint, effective)
            val filmDisplayName = FilmStockMapper.stockDisplayName(roll.filmStockId)
            // 临时文件名带 rollId：即使并发（例如被系统重新拉起）也不会互相覆写同一个文件
            val tmpJpeg = File(cacheDir, "develop_${roll.rollId}.jpg")

            // 4) 逐帧
            var firstError: String? = null
            for (index in 1..roll.shotCount) {
                if (Thread.currentThread().isInterrupted) {
                    Log.w(TAG, "冲洗被中断: $rollId")
                    return
                }

                DevelopmentState.currentIndex = index
                updateProgressNotification(
                    index - 1, roll.shotCount, progressText(index - 1, roll.shotCount)
                )
                notifyStateToMain()

                val frameStart = android.os.SystemClock.elapsedRealtime()
                try {
                    processOneFrame(roll, index, paramsJson, filmDisplayName, tmpJpeg)
                    DevelopmentState.done = index
                    // 单帧耗时走 EMA（0.7/0.3）：首帧含引擎预热偏慢，直接整体平均会把
                    // 预估拉得很长，EMA 让它几帧之内收敛到稳态速度。
                    val cost = (android.os.SystemClock.elapsedRealtime() - frameStart).toFloat()
                    DevelopmentState.frameMsEma =
                        if (DevelopmentState.frameMsEma <= 0f) cost
                        else DevelopmentState.frameMsEma * 0.7f + cost * 0.3f
                } catch (e: Throwable) {
                    Log.e(TAG, "帧 $index 失败，跳过", e)
                    DevelopmentState.errorCount++
                    if (firstError == null) {
                        firstError = e.message ?: e.javaClass.simpleName
                    }
                }
                notifyStateToMain()
            }

            // 5) 收尾：有任何帧失败就走错误分支，绝不静默报"完成"
            // 延迟释放引擎：用户可能马上又冲洗下一卷，30 秒内复用避免重新初始化
            SpektraProcessor.scheduleReleaseEngine()
            val nm = getSystemService(NotificationManager::class.java)
            if (DevelopmentState.errorCount > 0) {
                DevelopmentState.phase = DevelopmentState.PHASE_ERROR
                DevelopmentState.lastError = firstError
                // 不调 finishRoll：卷保持 undeveloped/developing，用户可再次冲洗重试
                nm.notify(
                    NOTIF_ID,
                    buildErrorNotification(
                        getString(
                            R.string.dev_error_frames_detail,
                            DevelopmentState.errorCount, roll.shotCount,
                            firstError?.take(120) ?: getString(R.string.dev_error_unknown)
                        )
                    )
                )
            } else {
                UndevelopedFilmStore.finishRoll(rollId)
                DevelopmentState.phase = DevelopmentState.PHASE_DONE
                nm.notify(NOTIF_ID, buildDoneNotification(roll))
            }
            notifyStateToMain()
            stopSelf(currentStartId)
        } catch (e: Throwable) {
            Log.e(TAG, "冲洗整体异常", e)
            finishWithError(e.message ?: getString(R.string.dev_error_unknown))
        }
    }

    /**
     * 进度文案：有样本之后就带上预估剩余时间。
     * 通知栏和暗房进度条共用同一套措辞（UI 那边算 ETA 的方式也一致）。
     */
    private fun progressText(done: Int, total: Int): String {
        val eta = DevelopmentState.etaSeconds()
        return if (eta > 0) getString(R.string.dev_progress_eta, done, total, formatEta(eta))
        else getString(R.string.dev_progress, done, total)
    }

    /** 秒 → "40 秒"（英文 "40s"）；超过一分钟拆成分 + 秒。 */
    private fun formatEta(seconds: Int): String =
        if (seconds < 60) getString(R.string.dev_eta_sec, seconds)
        else getString(R.string.dev_eta_min, seconds / 60, seconds % 60)

    private fun processOneFrame(
        roll: RollInfo,
        index: Int,
        paramsJson: String,
        filmDisplayName: String,
        tmpJpeg: File,
    ) {
        // openFrameStream → 全分辨率 decode
        val stream = UndevelopedFilmStore.openFrameStream(roll.rollId, index)
            ?: throw IllegalStateException("openFrameStream 返回 null: $roll/$index")
        // ARGB_8888：8bit/通道。早前用 RGB_565「省内存」其实更费：bitmapToFloatRgb 转引擎格式时
        // 会再 copy 一份 8888，峰值 264MB 高于直接 8888 解码的 240MB，还白丢 2-3bit 色深
        // （冲洗强拉对比后暗部/天空会出现色带）。inMutable 供日期戳就地绘制。
        val decodeOpts = BitmapFactory.Options().apply {
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            inMutable = true
        }
        var bmp = stream.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            ?: throw IllegalStateException("decodeStream 失败: $roll/$index")

        // 方向：拍摄侧直传字节不旋转像素，这里按帧记录的角度旋转
        // （90° 整数倍 + filter=false → 纯像素搬运，无插值无色差）
        val rotation = UndevelopedFilmStore.frameOrientation(roll.rollId, index)
        if (rotation != 0) {
            val rotated = rotateBitmap(bmp, rotation)
            bmp.recycle()   // 旧位图立即回收，否则 48MB 挂到 GC 才释放
            bmp = rotated
        }

        var writtenOk = false
        try {
            val takenAt = try {
                UndevelopedFilmStore.frameTakenAt(roll.rollId, index)
            } catch (_: Throwable) {
                System.currentTimeMillis()
            }
            val iso = UndevelopedFilmStore.frameIso(roll.rollId, index)

            // 日期戳：直接画在进引擎的位图上，省掉一次全尺寸 decode + encode；
            // 水印会随 LUT 一起调色（等同底片曝光日期）。失败不影响整卷冲洗。
            if (isDateStampEnabled()) {
                try {
                    DateStampRenderer.drawOnBitmap(applicationContext, bmp, takenAt)
                } catch (e: Throwable) {
                    Log.e(TAG, "日期戳绘制失败（跳过）: ${e.message}", e)
                }
            }

            // 引擎冲洗到临时 JPEG（全分辨率）
            // recycleInput=true：像素转入 float 缓冲后立即回收位图，把 48MB 堆让给 solve 阶段
            val result = SpektraProcessor.processFrame(
                applicationContext, bmp, paramsJson, tmpJpeg.absolutePath,
                recycleInput = true
            )
            if (!result.ok) {
                throw IllegalStateException("processFrame 失败: ${result.message}")
            }

            // EXIF（用引擎实际输出尺寸；DateTimeOriginal 按帧拍摄时刻 + 胶卷元数据）
            ContaxExif.write(
                tmpJpeg, iso, result.width, result.height, takenAt,
                filmDisplayName = filmDisplayName,
                filmStockId = roll.filmStockId,
                printStockId = roll.printStockId,
                rollId = roll.rollId,
                paramsJson = paramsJson,
            )

            // 落盘前校验临时 JPEG 非空
            check(tmpJpeg.exists() && tmpJpeg.length() > 0) {
                "冲洗后临时 JPEG 为空: ${tmpJpeg.absolutePath}"
            }

            // 落地为成片
            val uri = UndevelopedFilmStore.writeDevelopedFrame(roll.rollId, index, tmpJpeg)
            Log.d(TAG, "帧 $index 落盘完成 uri=$uri")
            writtenOk = true
        } finally {
            bmp.recycle()
            // 只有成功落地后才删 tmpJpeg；失败时保留以便诊断或手动重试
            if (writtenOk) {
                tmpJpeg.delete()
            }
        }
    }

    /** 按 90° 整数倍旋转位图。filter=false 保证纯像素搬运（无插值），旋转后立即回收原图。 */
    private fun rotateBitmap(src: android.graphics.Bitmap, degrees: Int): android.graphics.Bitmap {
        val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return try {
            android.graphics.Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
        } finally {
            src.recycle()
        }
    }

    /** 日期水印开关（与拍摄侧共用 "filmcam" 偏好；默认开） */
    private fun isDateStampEnabled(): Boolean =
        getSharedPreferences("filmcam", MODE_PRIVATE).getBoolean("dateStamp", true)

    private fun finishWithError(msg: String) {
        DevelopmentState.phase = DevelopmentState.PHASE_ERROR
        DevelopmentState.lastError = msg
        notifyStateToMain()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildErrorNotification(msg.take(500)))
        // 错误路径立即释放引擎（可能是引擎初始化失败，保留无意义）
        try { SpektraProcessor.releaseEngine() } catch (_: Throwable) {}
        stopSelf(currentStartId)
    }

    private fun notifyStateToMain() {
        mainHandler.post { DevelopmentState.notifyListeners() }
    }

    // ---------- 通知 ----------

    private fun buildProgressNotification(done: Int, total: Int, text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, GalleryActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b
            .setContentTitle(getString(R.string.dev_darkroom))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_develop)
            .setOngoing(true)
            .setProgress(if (total > 0) total else 1, done, total <= 0)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun updateProgressNotification(done: Int, total: Int, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildProgressNotification(done, total, text))
    }

    private fun buildDoneNotification(roll: RollInfo): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, GalleryActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val name = runCatching { FilmStockMapper.stockDisplayName(roll.filmStockId) }
            .getOrDefault(roll.filmStockId)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b
            .setContentTitle(getString(R.string.dev_notif_done_title))
            .setContentText(getString(R.string.dev_notif_done_text, name, roll.shotCount))
            .setSmallIcon(R.drawable.ic_develop_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun buildErrorNotification(msg: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b
            .setContentTitle(getString(R.string.dev_failed_title))
            .setContentText(msg)
            .setSmallIcon(R.drawable.ic_develop_error)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
    }

    override fun onDestroy() {
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }
}
