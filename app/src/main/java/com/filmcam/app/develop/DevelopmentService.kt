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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
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

            // 引擎能力 only once：顺便开一份诊断记录（版本、Vulkan/OpenMP 能力、机型）
            val enginePixelLimit = SpektraProcessor.framePixelLimit(applicationContext)
            // 用户在设置里选的「冲洗画质」：标准档把进引擎的像素压到约 1200 万，
            // 慢机（如麒麟 + 3540 万像素主摄）上单帧从 ~88s 降到 ~30s。
            val framePixelLimit = DevQuality.framePixelCap(this, enginePixelLimit)
            DevDiagnostics.begin(
                runCatching { app.spektralab.engine.SpektraLabEngine.buildInfo() }.getOrDefault("?"),
                runCatching {
                    SpektraProcessor.capabilities(applicationContext).replace('\n', ' ') +
                        " · maxFramePixels=$framePixelLimit" +
                        " · quality=${if (DevQuality.load(this@DevelopmentService) == DevQuality.STANDARD) "standard" else "full"}"
                }.getOrDefault("?")
            )

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
            var effective = devSettings.copy(paperOverride = resolved.paperId)
            // 交给引擎的参数：只包含引擎认识的键。
            // （曾把 output_style 这个自定义标记塞进去，引擎报 unknown parameter，整卷 0 帧出图。）
            val paramsJson = FilmStockMapper.buildParamsJson(roll.filmStockId, mappedPrint, effective)
            // 写进 EXIF 的快照：引擎参数 + 风格标记，「拍摄同款」靠它还原档位
            val exifParamsJson = FilmStockMapper.buildExifParamsJson(roll.filmStockId, mappedPrint, effective)
            // 兜底链：扫描档下发了一批引擎新参数，机型/引擎版本认不全就整卷冲不出来。
            // 逐级退到更保守的印相档（即上一版已验证能出图的参数集），至少保证出图。
            // 一旦确认某档不被接受，后续帧直接从兜底档起跑，不再每帧白试一次。
            // 兜底链：店扫档的完整味道里有一批引擎还没验过的键（滤色片/gamma/层间效应），
            // 机型或引擎版本不认就整卷冲不出来。逐级退：
            //   完整店扫 → 保守店扫（真机已验证的那几个键，仍保留店扫的基本差别）→ 印相档
            val safeJson =
                FilmStockMapper.buildParamsJson(
                    roll.filmStockId, mappedPrint, effective,
                    includeStyleMark = false,
                    scanFinishLevel = FilmStockMapper.SCAN_LEVEL_SAFE,
                )
            // 未经验证的键（店扫新味 + 片框）先问过引擎再下发：
            // 真机上 y_filter_shift 越界曾让两档整档降级，引擎还可能收下键却不认值。
            // 被拒的剔掉，丢一味好过整档掉回印相。
            val scanExtras = if (effective.outputStyle.isScan) {
                FilmStockMapper.fullExtraEntries(effective.outputStyle)
            } else emptyList()
            // 片框：版式匹配是**渲染期**才校验的（参数会被收下、冲到 solve 才炸，
            // 引擎原文 `overscan: a 1440 x 1080 frame is not the 135 gate's shape (36 x 24)`），
            // 只做 setParams 预检抓不到。所以先拿该版式片门比例的小图真跑一遍挑出能出图的版式，
            // 一个都不行就本次不带片框 —— 总好过整档掉回兜底白冲一张。
            val frameFormat = if (effective.frameFormat.isOn) {
                pickFrameFormat(roll.filmStockId, mappedPrint, effective)
            } else null
            if (effective.frameFormat.isOn && frameFormat == null) {
                // 所有版式都不可用：关掉片框照常出图，别让用户等半天得到一个降级档
                Log.w(TAG, "片框不可用，本次冲洗不带片框")
                effective = effective.copy(frameFormat = FilmStockMapper.FrameFormat.OFF)
            }
            // 用户点的那格引擎不认、退到了另一格：不说一声就是"我选了 135 怎么给我半格"
            if (frameFormat != null && frameFormat != effective.frameFormat.overscanFormat) {
                Log.w(TAG, "片框版式 $frameFormat 与所选 ${effective.frameFormat.key} 不一致（引擎退回）")
                mainHandler.post {
                    android.widget.Toast.makeText(
                        this@DevelopmentService,
                        getString(
                            if (frameFormat == FilmStockMapper.OVERSCAN_FORMAT_HALF) {
                                R.string.dev_toast_frame_half
                            } else R.string.dev_toast_frame_full
                        ),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            // 片门宽高比：135 全画幅是 3:2，取景是 4:3，画面要先裁到片门再进引擎
            val gateAspect = frameFormat?.let {
                FilmStockMapper.FrameFormat.fromOverscanFormat(it).gateAspect
            } ?: 0f
            val frameExtras = frameFormat?.let {
                FilmStockMapper.frameBorderEntries(roll.filmStockId, 1, it)
            } ?: emptyList()
            val extras = scanExtras + frameExtras
            val rejected: Set<String> =
                if (extras.isEmpty()) emptySet()
                else runCatching { SpektraProcessor.probeRejectedKeys(this, safeJson, extras) }
                    .onFailure { Log.w(TAG, "参数预检失败，按保守档出图", it) }
                    .getOrDefault(extras.map { it.first }.toSet())
            val scanKeys = scanExtras.map { it.first }.toSet()
            val frameKeys = frameExtras.map { it.first }.toSet()
            val acceptedScan = scanExtras.filter { it.first !in rejected }
            val acceptedFrame = frameExtras.filter { it.first !in rejected }
            // 片框开着但帧号没被接受时，逐帧覆盖会把被拒的键塞回去 → 整卷失败，故记录
            val perFrameNumber =
                if (effective.frameFormat.isOn && "overscan_frame_number" !in rejected) 1 else 0
            var activeParamsJson = FilmStockMapper.buildParamsJson(
                roll.filmStockId, mappedPrint, effective,
                includeStyleMark = false,
                scanFinishLevel = FilmStockMapper.SCAN_LEVEL_FULL,
                scanExtraOverride = FilmStockMapper.scanFinishEntries(
                    effective.outputStyle, FilmStockMapper.SCAN_LEVEL_SAFE
                ) + acceptedScan,
                extraEntries = acceptedFrame.ifEmpty { null },
            )
            var activeFallbackChain: List<String> = buildList {
                if (effective.outputStyle.isScan) {
                    add(safeJson)
                    add(
                        FilmStockMapper.buildParamsJson(
                            roll.filmStockId, mappedPrint,
                            effective.copy(outputStyle = FilmStockMapper.OutputStyle.PRINT)
                        )
                    )
                }
            }
            val filmDisplayName = FilmStockMapper.stockDisplayName(roll.filmStockId)
            // 临时文件名带 rollId：即使并发（例如被系统重新拉起）也不会互相覆写同一个文件
            val tmpJpeg = File(cacheDir, "develop_${roll.rollId}.jpg")

            // 4) 逐帧
            var firstError: String? = null
            var fallbackFrames = 0
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
                    val usedFallback = processOneFrame(
                        roll, index, activeParamsJson, exifParamsJson, filmDisplayName,
                        tmpJpeg, framePixelLimit, activeFallbackChain,
                        // 对照实验只在第一帧跑一次（基线用保守档，实验结果才好对比）
                        matrixBaseJson = if (index == 1) safeJson else null,
                        // 片框的帧号逐帧递增（底片暗边本来就印着"1""2"…"36"）
                        frameNumber = if (perFrameNumber != 0) index else 0,
                        // 片门形状：135 全画幅 3:2，取景 4:3 —— 差的那一条边在这里裁掉
                        gateAspect = gateAspect,
                    )
                    if (usedFallback) {
                        fallbackFrames++
                        // 这一档引擎不认，后面 30 几帧就别再陪它失败一遍了
                        activeFallbackChain.firstOrNull()?.let { accepted ->
                            activeParamsJson = accepted
                            activeFallbackChain = emptyList()
                        }
                    }
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
                // 走了兜底必须让用户知道：成片是按印相档出的，不是他选的那档口味
                if (fallbackFrames > 0) {
                    Log.w(TAG, "$fallbackFrames 帧未用所选风格出图（引擎不接受该档参数）")
                    mainHandler.post {
                        android.widget.Toast.makeText(
                            this@DevelopmentService,
                            getString(R.string.dev_toast_style_fallback, fallbackFrames),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                nm.notify(NOTIF_ID, buildDoneNotification(roll))
            }
            // 整卷跑完：把这一次的分段耗时归档，供「关于 → 工程师模式」查看/复制
            DevDiagnostics.save(applicationContext)
            Log.i(TAG, "冲洗诊断（本次）: frames=${DevDiagnostics.frames} " +
                "avg=${DevDiagnostics.avgMs}ms max=${DevDiagnostics.maxMs}ms " +
                "decode=${DevDiagnostics.decodeMs / DevDiagnostics.frames.coerceAtLeast(1)}ms " +
                "engine=${DevDiagnostics.engineMs / DevDiagnostics.frames.coerceAtLeast(1)}ms " +
                "write=${DevDiagnostics.ioMs / DevDiagnostics.frames.coerceAtLeast(1)}ms " +
                "in=${DevDiagnostics.inW}x${DevDiagnostics.inH} " +
                "out=${DevDiagnostics.outW}x${DevDiagnostics.outH} " +
                "caps=${DevDiagnostics.caps}")
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

    /**
     * 解码前算一下 inSampleSize。
     *
     * 摄像头给的最大 JPEG 可能远大于引擎能吃的上限（50MP 输入 vs 12MP 上限）：
     * 先全尺寸解码、再交给 processFrame 内部缩放，等于把解码、像素转换、旋转
     * 三段的时间都按 4 倍白白烧掉一遍 —— 慢机上这一趟就是几十秒。
     * 这里只在 bounds 阶段看一眼尺寸，按 2 的幂降采样到「略高于上限」，
     * 后面 processFrame 里的精确缩放照旧，最终输出分辨率完全一样。
     */
    private fun computeSampleSize(w: Int, h: Int, maxPx: Int): Int {
        if (maxPx <= 0 || w <= 0 || h <= 0) return 1
        var s = 1
        val target = (maxPx * 1.08).toLong()
        while (s < 16 && ((w / s).toLong() * (h / s)) > target) s *= 2
        return s
    }

    private fun processOneFrame(
        roll: RollInfo,
        index: Int,
        paramsJson: String,
        exifParamsJson: String,
        filmDisplayName: String,
        tmpJpeg: File,
        framePixelLimit: Int = 0,
        fallbackParamsJsons: List<String> = emptyList(),
        matrixBaseJson: String? = null,
        /** >0：片框帧号，逐帧覆盖进参数；0 = 不带帧号（片框关闭或引擎不认这个键）。 */
        frameNumber: Int = 0,
        /**
         * >0：片门宽高比（36/24 或 24/18）。片框档位的片门形状跟取景画幅不一致时
         * （135 全画幅 3:2 vs 取景 4:3），进引擎前先把画面裁到片门 —— 引擎是拿输入尺寸
         * 对着片门校验的，差一点也不给出图。0 = 不裁。
         */
        gateAspect: Float = 0f,
    ): Boolean {
        val tTotal0 = android.os.SystemClock.elapsedRealtime()

        // 只看 bounds，算出解码缩放倍数（maxPx<=0 表示引擎不限，sample 保持 1）
        var sample = 1
        if (framePixelLimit > 0) {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                UndevelopedFilmStore.openFrameStream(roll.rollId, index)?.use { s ->
                    BitmapFactory.decodeStream(s, null, boundsOpts)
                }
            }
            sample = computeSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, framePixelLimit)
        }

        // openFrameStream → decode
        val stream = UndevelopedFilmStore.openFrameStream(roll.rollId, index)
            ?: throw IllegalStateException("openFrameStream 返回 null: $roll/$index")
        // ARGB_8888：8bit/通道。早前用 RGB_565「省内存」其实更费：bitmapToFloatRgb 转引擎格式时
        // 会再 copy 一份 8888，峰值 264MB 高于直接 8888 解码的 240MB，还白丢 2-3bit 色深
        // （冲洗强拉对比后暗部/天空会出现色带）。inMutable 供日期戳就地绘制。
        val decodeOpts = BitmapFactory.Options().apply {
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            inMutable = true
            inSampleSize = sample
        }
        val tDecode0 = android.os.SystemClock.elapsedRealtime()
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

        // 裁到片门：真机也是这么回事 —— 镜头成像圈是圆的，机身只取中间那块 36×24
        // 的矩形，画面之外的部分从来就没落在底片上。引擎同样只认片门形状的输入。
        if (gateAspect > 0f) {
            val srcW = bmp.width
            val srcH = bmp.height
            val crop = FilmStockMapper.gateCrop(srcW, srcH, gateAspect)
            if (crop.cropped) {
                val gated = Bitmap.createBitmap(crop.w, crop.h, Bitmap.Config.ARGB_8888)
                Canvas(gated).drawBitmap(bmp, -crop.x.toFloat(), -crop.y.toFloat(), Paint())
                bmp.recycle()
                bmp = gated
                Log.d(TAG, "片门裁切 ${crop.w}x${crop.h}（源 ${srcW}x${srcH}）")
            }
        }

        var writtenOk = false
        var inW = 0
        var inH = 0
        var outW = 0
        var outH = 0
        var wasDownscaled = false
        var fellBack = false
        var tDecodeMs = 0L
        var tEngineMs = 0L
        var tIoMs = 0L
        try {
            val takenAt = try {
                UndevelopedFilmStore.frameTakenAt(roll.rollId, index)
            } catch (_: Throwable) {
                System.currentTimeMillis()
            }
            val iso = UndevelopedFilmStore.frameIso(roll.rollId, index)
            // 这一帧的拍摄参数（闪光/白平衡/EV/快门/失焦）随成片 EXIF 保存，供「拍摄同款」复现
            val shotJson = UndevelopedFilmStore.frameShot(roll.rollId, index)

            // 日期戳：直接画在进引擎的位图上，省掉一次全尺寸 decode + encode；
            // 水印会随 LUT 一起调色（等同底片曝光日期）。失败不影响整卷冲洗。
            if (isDateStampEnabled()) {
                try {
                    DateStampRenderer.drawOnBitmap(applicationContext, bmp, takenAt)
                } catch (e: Throwable) {
                    Log.e(TAG, "日期戳绘制失败（跳过）: ${e.message}", e)
                }
            }

            // 镜头光学：暗角压在日期戳之后（真机的日期也是透过同一支镜头曝在底片上的）
            try {
                applySonnarVignette(bmp)
            } catch (e: Throwable) {
                Log.e(TAG, "暗角绘制失败（跳过）: ${e.message}", e)
            }

            // 引擎冲洗到临时 JPEG（全分辨率）
            // recycleInput=true：像素转入 float 缓冲后立即回收位图，把 48MB 堆让给 solve 阶段
            tDecodeMs = android.os.SystemClock.elapsedRealtime() - tDecode0
            // 参数对照实验：只在外部私有目录放了 spektra_matrix.json 时才跑（跑完即删）
            runMatrixProbe(bmp, matrixBaseJson)
            inW = bmp.width
            inH = bmp.height
            val tEngine0 = android.os.SystemClock.elapsedRealtime()
            val frameParamsJson =
                if (frameNumber > 0) FilmStockMapper.withFrameNumber(paramsJson, frameNumber)
                else paramsJson
            val result = SpektraProcessor.processFrame(
                applicationContext, bmp, frameParamsJson, tmpJpeg.absolutePath,
                recycleInput = true, fallbackParamsJsons = fallbackParamsJsons
            )
            tEngineMs = android.os.SystemClock.elapsedRealtime() - tEngine0
            outW = result.width
            outH = result.height
            wasDownscaled = result.downscaled
            if (!result.ok) {
                throw IllegalStateException("processFrame 失败: ${result.message}")
            }
            fellBack = result.fellBack

            // EXIF（用引擎实际输出尺寸；镜头/胶卷元数据）
            // DateTimeOriginal 记**冲洗完成时刻**而不是拍摄时刻：成片是刚从暗房拿出来的，
            // 拿旧照片的拍摄时间去当文件时间，系统相册会把它排到几个月前，等于冲了个看不见。
            // 真正的拍摄日期刻在画面日期戳上（上面 drawOnBitmap 用的 takenAt），并留一份
            // orig_taken_at 进 EXIF 快照。
            ContaxExif.write(
                tmpJpeg, iso, result.width, result.height, takenAt,
                metadataAtMs = System.currentTimeMillis(),
                filmDisplayName = filmDisplayName,
                filmStockId = roll.filmStockId,
                printStockId = roll.printStockId,
                rollId = roll.rollId,
                paramsJson = exifParamsJson,
                shotJson = shotJson,
            )

            // 落盘前校验临时 JPEG 非空
            check(tmpJpeg.exists() && tmpJpeg.length() > 0) {
                "冲洗后临时 JPEG 为空: ${tmpJpeg.absolutePath}"
            }

            // 落地为成片
            val tIo0 = android.os.SystemClock.elapsedRealtime()
            val uri = UndevelopedFilmStore.writeDevelopedFrame(roll.rollId, index, tmpJpeg)
            tIoMs = android.os.SystemClock.elapsedRealtime() - tIo0
            Log.d(TAG, "帧 $index 落盘完成 uri=$uri")
            writtenOk = true
        } finally {
            bmp.recycle()
            // 只有成功落地后才删 tmpJpeg；失败时保留以便诊断或手动重试
            if (writtenOk) {
                tmpJpeg.delete()
            }
            val tTotal = android.os.SystemClock.elapsedRealtime() - tTotal0
            tDecodeMs = tDecodeMs.coerceAtMost(tTotal)
            // 每帧打一条分段耗时：慢机上到底慢在哪一段（解码 / 引擎 / 落盘），
            // 抓个 logcat 就能看出来，不用猜。
            Log.i(
                TAG,
                "帧 $index 用时 ${tTotal}ms：解码 ${tDecodeMs}ms（sample=$sample）· " +
                    "引擎 ${tEngineMs}ms · 落盘 ${tIoMs}ms · ${outW}x${outH}"
            )
            DevDiagnostics.frame(
                tTotal, tDecodeMs, tEngineMs, tIoMs, inW, inH, outW, outH, wasDownscaled
            )
        }
        return fellBack
    }

    /**
     * 参数对照实验：**只有**外部私有目录里存在 `spektra_matrix.json` 时才跑（跑完即删）。
     *
     * 为什么要它：肉眼反馈「偏暖 / 偏冷」太粗 —— 翻了滤色片符号还说一样，
     * 就无法判断到底是这个键没生效、还是被别的参数盖过去了。这里用同一张帧、
     * 同一套基线参数，逐个键各渲一张小图，拉回来做像素统计就能定量看出
     * 哪个键真有作用、方向朝哪、量级多大。
     *
     * 配置格式（值写 JSON 字面量）：
     * ```json
     * [{"name":"y_plus","extra":{"y_filter_shift":"1.0"}}, ...]
     * ```
     */
    private fun runMatrixProbe(bmp: Bitmap, baseJson: String?) {
        if (baseJson == null) return
        val dir = getExternalFilesDir(null) ?: return
        val specFile = java.io.File(dir, "spektra_matrix.json")
        if (!specFile.exists()) return
        val cases = runCatching {
            val arr = org.json.JSONArray(specFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val extra = o.optJSONObject("extra")
                val map = LinkedHashMap<String, String>()
                if (extra != null) {
                    val it = extra.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        map[k] = extra.getString(k)
                    }
                }
                o.getString("name") to map
            }
        }.onFailure { Log.w(TAG, "矩阵配置解析失败", it) }.getOrNull()
        if (cases.isNullOrEmpty()) {
            runCatching { specFile.delete() }
            return
        }
        val outDir = java.io.File(dir, "matrix_out")
        runCatching { outDir.deleteRecursively() }
        outDir.mkdirs()
        // 小图渲染只为看色彩方向，640px 足够，跑一组不至于让冲洗慢到不能忍
        val smallW = 640
        val smallH = (smallW.toFloat() * bmp.height / bmp.width).toInt().coerceAtLeast(1)
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, smallW, smallH, true)
        val body = baseJson.trimEnd().removeSuffix("}")
        val report = StringBuilder()
        try {
            for ((name, extra) in cases) {
                // 覆盖语义：基线里已有的同名键必须被替换。直接追加会写出重复键，
                // 引擎取第一个 → 那组实验等于没跑（上一轮 max_lift / print_exposure
                // 就是这么白测的，出图和 base 字节级相同）。
                val json = runCatching {
                    val o = org.json.JSONObject(baseJson)
                    extra.forEach { (k, v) -> o.put(k, org.json.JSONTokener(v).nextValue()) }
                    o.toString()
                }.getOrElse {
                    buildString {
                        append(body)
                        extra.forEach { (k, v) -> append(",\"").append(k).append("\":").append(v) }
                        append('}')
                    }
                }
                val out = java.io.File(outDir, "$name.jpg")
                val r = runCatching {
                    SpektraProcessor.processFrame(
                        applicationContext, small, json, out.absolutePath, recycleInput = false
                    )
                }.getOrNull()
                report.append(name).append(' ').append(r?.ok).append(' ').append(r?.message).append('\n')
                Log.i(TAG, "矩阵 $name -> ok=${r?.ok} ${r?.message}")
            }
        } finally {
            small.recycle()
            runCatching { java.io.File(dir, "spektra_matrix_report.txt").writeText(report.toString()) }
            runCatching { specFile.delete() }   // 跑一次就删，别每次冲洗都来一遍
        }
        Log.i(TAG, "参数对照实验完成: ${outDir.absolutePath} (${cases.size} 张)")
    }

    /**
     * 挑一个能出图的片框版式：拿**该版式片门比例**的小图真跑一遍 render
     * （setParams + solve + export），第一个不报错的就用；全都不行返回 null。
     *
     * 为什么必须真跑：片框的版式与底片尺寸匹配是渲染期校验，setParams 会爽快收下参数，
     * 冲到 solve 才抛 `overscan: a 1440 x 1080 frame is not the 135 gate's shape (36 x 24)`。
     * 只做参数预检的话，结果是整档掉回兜底档 —— 用户看到的就是「1 帧未能按所选风格冲洗」。
     *
     * 用户选的那格优先；它不行再退另一格（135 全画幅 ↔ 半格），总好过整卷没有片框。
     */
    private fun pickFrameFormat(
        filmStockId: String,
        printStockId: String,
        settings: FilmStockMapper.DevSettings,
    ): String? {
        val notes = ArrayList<String>()
        var chosen: String? = null
        for (format in FilmStockMapper.overscanCandidates(settings.frameFormat)) {
            // 自检小图必须按这个版式的片门比例来，否则测的就不是同一件事
            val aspect = FilmStockMapper.FrameFormat.fromOverscanFormat(format).gateAspect
            val pw = 96
            val ph = (pw / aspect.coerceAtLeast(0.1f)).toInt().coerceAtLeast(1)
            val json = FilmStockMapper.buildParamsJson(
                filmStockId, printStockId, settings,
                includeStyleMark = false,
                extraEntries = FilmStockMapper.frameBorderEntries(filmStockId, 1, format),
            )
            val err = SpektraProcessor.probeRenderOk(this, json, pw, ph)
            if (err == null) {
                Log.i(TAG, "片框版式可用: $format (${pw}x$ph)")
                notes.add("$format ${pw}x$ph -> OK")
                chosen = format
                break
            }
            Log.w(TAG, "片框版式 $format 不可用 (${pw}x$ph): $err")
            notes.add("$format ${pw}x$ph -> $err")
        }
        // 落盘：logcat 不一定抓得到（无线调试经常断），留个文件随时能 pull
        runCatching {
            val dir = getExternalFilesDir(null) ?: filesDir
            java.io.File(dir, "spektra_frame_probe.txt").writeText(
                notes.joinToString("\n") + "\nchosen=" + (chosen ?: "none")
            )
        }
        return chosen
    }

    /** 按 90° 整数倍旋转位图。整数倍旋转无插值，旋转后立即回收原图。 */
    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        val swap = degrees % 180 != 0
        val outW = if (swap) src.height else src.width
        val outH = if (swap) src.width else src.height
        // 必须新建 mutable 位图：后面要在上面叠暗角（只读位图无法就地绘制）
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.translate(outW / 2f, outH / 2f)
        canvas.rotate(degrees.toFloat())
        canvas.translate(-src.width / 2f, -src.height / 2f)
        canvas.drawBitmap(src, 0f, 0f, Paint())
        src.recycle()
        return out
    }

    /**
     * 蔡司 Sonnar 35/2.8 全开的轻微暗角。
     *
     * 手机 HAL 会把镜头阴影校正得很干净，四角衰减几乎为零 —— 这也是「数码味」的一部分：
     * 真机全开时四角约 -0.25 EV，收到 f/5.6 才基本消失。这里按全开还原一个很轻的衰减，
     * 只在四角动手（中心 58% 半径内完全不动），不会压暗主体。
     *
     * 就地叠加，不额外分配位图 —— 12MP 再来一张 48MB 会顶爆堆。
     */
    private fun applySonnarVignette(bmp: Bitmap) {
        if (bmp.isRecycled || !bmp.isMutable) return
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return
        val cx = w / 2f
        val cy = h / 2f
        val r = kotlin.math.hypot(cx.toDouble(), cy.toDouble()).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, r,
                intArrayOf(0x00000000, 0x00000000, 0x2A000000),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        Canvas(bmp).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
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
