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
import app.spektralab.engine.FrameBorderLock
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
            // 第一帧出图前 EMA 还没有样本，先用上一次冲洗的实测速度当种子，
            // 「约剩多少」从第 0 秒就有；第一帧一完成，EMA 直接用实测值把它顶掉。
            DevelopmentState.frameMsSeed = lastRunFrameMs()
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
            // 滤色强度增益（色彩微调的 filter_shift_scale）：**值域未标定**，不能直接下发。
            // 只在印相档下发（店扫档不叠色彩微调，见 buildParamsJson），先拿「不带它」的
            // 保守档当基线单独问过引擎——被拒就从本场所有参数里撤掉；
            // 它要是混进参数里，连兜底链的每一级都会带着它一起失败。
            if (effective.ccScale != null && !effective.outputStyle.isScan) {
                val cleanSafe = FilmStockMapper.buildParamsJson(
                    roll.filmStockId, mappedPrint, effective.copy(ccScale = null),
                    includeStyleMark = false,
                    scanFinishLevel = FilmStockMapper.SCAN_LEVEL_SAFE,
                )
                val scaleEntry = "filter_shift_scale" to
                    FilmStockMapper.ccScaleJsonLiteral(effective.ccScale!!)
                val rejectedScale = runCatching {
                    SpektraProcessor.probeRejectedKeys(this, cleanSafe, listOf(scaleEntry))
                }.getOrDefault(setOf(scaleEntry.first))
                if (scaleEntry.first in rejectedScale) {
                    Log.w(TAG, "filter_shift_scale=${effective.ccScale} 被引擎拒，本场不带")
                    effective = effective.copy(ccScale = null)
                }
            }
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
            val frameCheck = if (effective.frameFormat.isOn) {
                pickFrameFormat(roll.filmStockId, mappedPrint, effective)
            } else FrameCheck(null, false, false)
            val frameFormat = frameCheck.format
            if (effective.frameFormat.isOn && frameFormat == null) {
                // 这一格引擎不认：关掉片框照常出图，别让用户等半天得到一个降级档
                Log.w(TAG, "片框不可用，本次冲洗不带片框")
                effective = effective.copy(frameFormat = FilmStockMapper.FrameFormat.OFF)
                // 只剩一格可试，没有"退回另一格"这回事了，得说一声，否则用户只看到片框莫名消失
                mainHandler.post {
                    android.widget.Toast.makeText(
                        this@DevelopmentService,
                        getString(R.string.dev_toast_frame_dropped),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            // 成片片门：取景 4:3，真正落到"底片"上的只有中间那块 36×24（3:2）。
            // 片框关着也一样按这一格出片 —— 这才是"拍出来是 3:2"那件事本身。
            val gate = effective.outputGate
            // v46 输入层曝光：曝光旋钮不再动引擎的 print_exposure（那会连片框一起冲浓），
            // 改成进引擎之前对像素加线性增益。两条通路的绝对曝光来源不同：
            //   RAW → 同帧 ISP JPEG 引导（逐帧自适应）；JPEG/导入 → 没有参考，用固定基准。
            // 所以 RAW 只叠用户补偿，JPEG 还要多一个基础提亮。
            val userInputEv = FilmStockMapper.inputEvFor(effective)
            // 上限收到 +2EV：8bit JPEG 再往上拉只会把高光整片削平（RAW 通路的 DNG 增益同理）
            val jpegInputEv = (userInputEv + FilmStockMapper.JPEG_INPUT_BASE_EV).coerceIn(-1.8f, 2.0f)
            val frameExtras = frameFormat?.let {
                FilmStockMapper.frameBorderEntries(roll.filmStockId, 1, it)
            } ?: emptyList()
            // 直闪味（预检混在同一个批次里跑，省一次引擎起session）
            val flashExtras = FilmStockMapper.flashFinishEntries()
            val extras = scanExtras + frameExtras + flashExtras
            val rejected: Set<String> =
                if (extras.isEmpty()) emptySet()
                else runCatching { SpektraProcessor.probeRejectedKeys(this, safeJson, extras) }
                    .onFailure { Log.w(TAG, "参数预检失败，按保守档出图", it) }
                    .getOrDefault(extras.map { it.first }.toSet())
            val scanKeys = scanExtras.map { it.first }.toSet()
            val frameKeys = frameExtras.map { it.first }.toSet()
            val acceptedScan = scanExtras.filter { it.first !in rejected }
            val acceptedFrame = frameExtras.filter { it.first !in rejected }
            val acceptedFlash = flashExtras.filter { it.first !in rejected }
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
                extraEntries = null,
            )
            // 带片框的那一份（逐帧按朝向决定用哪份：引擎可能只认横置片门）
            val frameParamsJson = if (frameFormat != null && acceptedFrame.isNotEmpty()) {
                FilmStockMapper.buildParamsJson(
                    roll.filmStockId, mappedPrint, effective,
                    includeStyleMark = false,
                    scanFinishLevel = FilmStockMapper.SCAN_LEVEL_FULL,
                    scanExtraOverride = FilmStockMapper.scanFinishEntries(
                        effective.outputStyle, FilmStockMapper.SCAN_LEVEL_SAFE
                    ) + acceptedScan,
                    extraEntries = acceptedFrame,
                )
            } else null
            // 直闪帧的两份变体（无片框 / 带片框）：辉光增强 + 预检过的直闪键。
            // 真机上直闪底片在暗房就是另一套印法，这里把闪光帧和自然光帧真正分开。
            val flashParamsJson = FilmStockMapper.buildParamsJson(
                roll.filmStockId, mappedPrint, effective,
                includeStyleMark = false,
                scanFinishLevel = FilmStockMapper.SCAN_LEVEL_FULL,
                scanExtraOverride = FilmStockMapper.scanFinishEntries(
                    effective.outputStyle, FilmStockMapper.SCAN_LEVEL_SAFE
                ) + acceptedScan,
                flashShot = true,
                flashEntries = acceptedFlash,
            )
            val flashFrameParamsJson = if (frameParamsJson != null) {
                FilmStockMapper.buildParamsJson(
                    roll.filmStockId, mappedPrint, effective,
                    includeStyleMark = false,
                    scanFinishLevel = FilmStockMapper.SCAN_LEVEL_FULL,
                    scanExtraOverride = FilmStockMapper.scanFinishEntries(
                        effective.outputStyle, FilmStockMapper.SCAN_LEVEL_SAFE
                    ) + acceptedScan,
                    extraEntries = acceptedFrame,
                    flashShot = true,
                    flashEntries = acceptedFlash,
                )
            } else null
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
            // v53：输入校正的「整卷基准」。一卷底片是整卷一起冲的，工艺参数全卷一致；
            // 逐帧各算各的会让同卷浓淡跳变（实测同场景三帧 hi 0.055~0.072 →
            // 自适应 0.632~1.0）。首帧统计定基准，后续帧沿用，且不再重复采样。
            // -1 = 尚未定基准（RAW 通路不经过这段，始终不写）。
            val rollNormBaseline = FloatArray(1) { -1f }
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
                        // 成片片门（横 36×24 / 竖 24×36）：画面按这一格裁
                        gate = gate,
                        frameParamsJson = frameParamsJson,
                        frameOkPortrait = frameCheck.okPortrait,
                        // 直闪帧变体（闪光的帧用它，非闪光帧不受影响）
                        flashParamsJson = flashParamsJson,
                        flashFrameParamsJson = flashFrameParamsJson,
                        inputEv = jpegInputEv,
                        rawInputEv = userInputEv,
                        jpegNormStrength = effective.jpegNormalizeStrength,
                        rollNormBaseline = rollNormBaseline,
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
     * 上一次冲洗的单帧平均耗时（[DevDiagnostics] 归档进 prefs 的 diag_avg_ms）。
     *
     * 只当"种子"用：第一帧（含引擎预热）通常比稳态慢，所以这里**不参与 EMA**，
     * 第一帧一完成就被实测值替换。离谱的旧记录（0 或者几十分钟）一律当没有。
     */
    private fun lastRunFrameMs(): Float {
        val ms = getSharedPreferences("filmcam", MODE_PRIVATE).getLong("diag_avg_ms", 0L)
        return if (ms in 200..600_000) ms.toFloat() else 0f
    }

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
         * 成片片门：横帧走 36×24（3:2），竖帧走 24×36（2:3）。取景是 4:3，落到底片上的
         * 只有中间那一格 —— 竖拍绝不能套横置片门，那等于把竖照片压成横的、裁掉一半高度。
         */
        gate: FilmStockMapper.FrameFormat = FilmStockMapper.FrameFormat.FULL,
        /** 非 null = 带片框的那份参数；逐帧按朝向决定用不用它。 */
        frameParamsJson: String? = null,
        /** 引擎是否认这一格的**竖置**片门（冲洗前的自检问过；不认的竖帧就不带片框）。 */
        frameOkPortrait: Boolean = true,
        /** 直闪帧参数（无片框版）；闪光帧优先用它，渲染失败兜底回普通参数。 */
        flashParamsJson: String? = null,
        /** 直闪帧参数（带片框版）；null = 这卷没有可用的片框版式。 */
        flashFrameParamsJson: String? = null,
        /**
         * JPEG 通路要施加的**输入层**曝光（EV）：用户补偿 + [FilmStockMapper.JPEG_INPUT_BASE_EV]。
         * 曝光旋钮已经不在印相层（那里会连片框一起冲浓），见 [FilmStockMapper.PRINT_EXPOSURE_LOCKED]。
         */
        inputEv: Float = 0f,
        /**
         * RAW 通路的用户曝光补偿（EV，**不含** JPEG 基础提亮）。
         * RAW 的绝对曝光由同帧 ISP JPEG 引导决定，这里只叠用户口味偏移。
         */
        rawInputEv: Float = 0f,
        /** v50：JPEG 通路输入侧反 ISP 归一化倍率 [0,1]；0 = 关（旧行为）。 */
        jpegNormStrength: Float = 0f,
        /**
         * v53：长度 1 的整卷基准槽。[0] < 0 表示尚未定基准，由本帧（首帧）统计写入；
         * >= 0 时全卷沿用，不再重复采样。保证一卷里 36 张浓淡一致。
         */
        rollNormBaseline: FloatArray = FloatArray(1) { -1f },
    ): Boolean {
        val tTotal0 = android.os.SystemClock.elapsedRealtime()

        // ---- P3 RAW 通路：有 DNG 底片的帧走引擎 RAW 会话（绕过 ISP 的 JPEG 加工）----
        // 任何失败（引擎不认 / 渲染炸 / 后处理异常）→ 落回下方 JPEG 通路，帧不丢
        val rawDngPath = runCatching {
            UndevelopedFilmStore.frameRawPath(roll.rollId, index)
        }.getOrNull()
        if (rawDngPath != null) {
            val tRaw0 = android.os.SystemClock.elapsedRealtime()
            val rawFellBack = runCatching {
                developFrameFromRaw(
                    roll, index, rawDngPath, paramsJson, exifParamsJson, filmDisplayName,
                    tmpJpeg, fallbackParamsJsons, frameNumber, gate,
                    frameParamsJson, frameOkPortrait, flashParamsJson, flashFrameParamsJson,
                    userInputEv = rawInputEv,
                )
            }.onFailure {
                Log.w(TAG, "帧 $index RAW 通路失败，落回 JPEG 通路: ${it.message}", it)
            }.getOrNull()
            if (rawFellBack != null) {
                val tRaw = android.os.SystemClock.elapsedRealtime() - tRaw0
                Log.i(TAG, "帧 $index [RAW] 用时 ${tRaw}ms · ${tmpJpeg.length() / 1024}KB")
                DevDiagnostics.frame(tRaw, 0, tRaw, 0, 0, 0, 0, 0, false)
                return rawFellBack
            }
        }

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
        // 竖拍走竖置片门（画面在底片上本来就是竖的，齿孔照样在上下）。
        val crop = FilmStockMapper.gateCrop(gate, bmp.width, bmp.height)
        if (crop.cropped) {
            val srcW = bmp.width
            val srcH = bmp.height
            val gated = Bitmap.createBitmap(crop.w, crop.h, Bitmap.Config.ARGB_8888)
            Canvas(gated).drawBitmap(bmp, -crop.x.toFloat(), -crop.y.toFloat(), Paint())
            bmp.recycle()
            bmp = gated
            Log.d(TAG, "片门裁切 ${crop.w}x${crop.h}（源 ${srcW}x${srcH}）")
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
            // v42：竖拍且片框开着时，引擎会把这张裸竖画面映射进横置 135 版式
            // （成片画面区 x 0.17~0.85），整图右下角会落到右侧片基上 →
            // 传等效内容区，把日期戳在裸画面上就往左收并放大，映射后右端
            // 约 0.805（v43：v42 的 0.78 离片框太远），与 RAW 通路的带框
            // 成片落点一致。片框关闭 / 横拍传 null。
            if (isDateStampEnabled()) {
                try {
                    DateStampRenderer.drawOnBitmap(
                        applicationContext, bmp, takenAt,
                        DateStampRenderer.portraitFramedSource(
                            bmp.width, bmp.height, frameParamsJson != null
                        ),
                    )
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

            // 输入层曝光（v46）：曝光补偿在这里施加，引擎的 print_exposure 恒定 →
            // 片基/齿孔/暗边印刷的浓度不随曝光变（用户 11-07 的定调）。
            // 放在日期戳与暗角之后：日期戳是真机曝在底片上的字，该跟着底片一起变亮变暗。
            // v50：JPEG 通路专属的输入侧反 ISP 归一化。RAW 通路在上面已提前 return，
            // 所以这里只会是 HAL 直出 JPEG 或导入照片；片基/齿孔由引擎后叠，不受影响。
            // 强度 0 时严格走旧路径（包括 ev=0 时一个像素都不动），可随时回退。
            if (jpegNormStrength > 1e-4f) {
                val norm = app.spektralab.engine.JpegNormalizer
                val baseline = rollNormBaseline[0]
                val isFirst = baseline < 0f
                // 整卷只采样一次：首帧定基准，后续帧沿用，避免同卷浓淡跳变
                val stats = if (isFirst) norm.measure(bmp) else null
                val adaptive = if (isFirst) {
                    val k = norm.suggestStrength(stats!!)
                    rollNormBaseline[0] = k
                    k
                } else baseline
                val effectiveK = norm.effectiveStrength(adaptive, jpegNormStrength)
                val ok = norm.apply(bmp, inputEv.toDouble(), effectiveK)
                val tail = "倍率=${"%.2f".format(jpegNormStrength)} " +
                    "实际=${"%.3f".format(effectiveK)} " +
                    "曝光=${if (inputEv >= 0) "+" else ""}$inputEv EV（${if (ok) "已施加" else "跳过"}）"
                Log.i(
                    TAG,
                    if (isFirst) "帧 $index 输入校正【整卷基准】hi=${"%.3f".format(stats!!.hi)} " +
                        "暗部=${"%.2f".format(stats.darkRatio)} 自适应=${"%.3f".format(adaptive)} $tail"
                    else "帧 $index 输入校正 沿用整卷基准=${"%.3f".format(adaptive)} $tail"
                )
            } else if (kotlin.math.abs(inputEv) > 1e-3) {
                val ok = app.spektralab.engine.InputExposure.apply(bmp, inputEv.toDouble())
                Log.i(TAG, "帧 $index 输入曝光 ${if (inputEv >= 0) "+" else ""}$inputEv EV（${if (ok) "已施加" else "跳过"}）")
            }

            // 引擎冲洗到临时 JPEG（全分辨率）
            // recycleInput=true：像素转入 float 缓冲后立即回收位图，把 48MB 堆让给 solve 阶段
            tDecodeMs = android.os.SystemClock.elapsedRealtime() - tDecode0
            // 参数对照实验：只在外部私有目录放了 spektra_matrix.json 时才跑（跑完即删）
            runMatrixProbe(bmp, matrixBaseJson)
            inW = bmp.width
            inH = bmp.height
            val tEngine0 = android.os.SystemClock.elapsedRealtime()
            // 竖帧能不能带片框：引擎认不认竖置片门是冲洗前自检问来的，
            // 不认就这一帧只出画面（画幅照样是竖片门那一格），别为一片片基把画面毁了。
            // 闪光帧（BUILT_IN / EXTERNAL）换用直闪变体：底片上它们就是另一种东西。
            val isFlash = com.filmcam.app.film.ShotMeta.fromJson(shotJson)
                ?.flash?.let { it != "OFF" } == true
            val portrait = bmp.width < bmp.height
            val useFrame = frameParamsJson != null && (!portrait || frameOkPortrait)
            val baseJson = when {
                useFrame && isFlash -> flashFrameParamsJson ?: frameParamsJson!!
                useFrame -> frameParamsJson!!
                isFlash -> flashParamsJson ?: paramsJson
                else -> paramsJson
            }
            // 直闪参数万一被引擎拒，先兜回同一帧的普通参数 —— 味道丢了，帧不能丢
            val effectiveFallbacks =
                if (isFlash && baseJson != paramsJson) listOf(paramsJson) + fallbackParamsJsons
                else fallbackParamsJsons
            val frameParamsJsonOrNull =
                if (useFrame && frameNumber > 0) {
                    FilmStockMapper.withFrameNumber(baseJson, frameNumber)
                } else baseJson
            val result = SpektraProcessor.processFrame(
                applicationContext, bmp, frameParamsJsonOrNull, tmpJpeg.absolutePath,
                recycleInput = true, fallbackParamsJsons = effectiveFallbacks
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
     * P3 RAW 通路的一帧冲洗：DNG 底片 → 引擎 RAW 会话出图 → 后处理（旋转 / 片门 /
     * 日期戳 / 暗角）→ EXIF → 落盘成片。返回值语义与 [processOneFrame] 一致（fellBack）。
     *
     * 后处理说明：JPEG 通路是「画在进引擎的位图上」（日期戳透过镜头曝在底片上、暗角
     * 长在底片上），RAW 通路进引擎的是 DNG 文件没法画，所以改画在**引擎输出**上——
     * 等于把日期戳和暗角印在相纸上，顺序近似，等真机观感定夺要不要再挪。
     */
    private fun developFrameFromRaw(
        roll: RollInfo,
        index: Int,
        dngPath: String,
        paramsJson: String,
        exifParamsJson: String,
        filmDisplayName: String,
        tmpJpeg: File,
        fallbackParamsJsons: List<String>,
        frameNumber: Int,
        gate: FilmStockMapper.FrameFormat,
        frameParamsJson: String?,
        frameOkPortrait: Boolean,
        flashParamsJson: String?,
        flashFrameParamsJson: String?,
        /** 用户输入层曝光补偿（EV）。RAW 的绝对曝光仍由 ISP 引导决定，这里只叠偏移。 */
        userInputEv: Float = 0f,
    ): Boolean {
        val takenAt = try {
            UndevelopedFilmStore.frameTakenAt(roll.rollId, index)
        } catch (_: Throwable) { System.currentTimeMillis() }
        val iso = UndevelopedFilmStore.frameIso(roll.rollId, index)
        val shotJson = UndevelopedFilmStore.frameShot(roll.rollId, index)
        val rotation = UndevelopedFilmStore.frameOrientation(roll.rollId, index)

        // 参数选择与 JPEG 通路同一套规则（片框/直闪/竖片门），portrait 由帧记录的方向推。
        // 顺带取出这一帧的变焦比：拍摄时随帧落盘，冲洗按帧补裁 RAW（老帧 = 0，回落设备默认）。
        val shotMeta = com.filmcam.app.film.ShotMeta.fromJson(shotJson)
        val isFlash = shotMeta?.flash?.let { it != "OFF" } == true
        val frameZoom = shotMeta?.zoom ?: 0f
        val portrait = rotation == 90 || rotation == 270
        val useFrame = frameParamsJson != null && (!portrait || frameOkPortrait)
        val baseJson = when {
            useFrame && isFlash -> flashFrameParamsJson ?: frameParamsJson!!
            useFrame -> frameParamsJson!!
            isFlash -> flashParamsJson ?: paramsJson
            else -> paramsJson
        }
        val effectiveFallbacks =
            if (isFlash && baseJson != paramsJson) listOf(paramsJson) + fallbackParamsJsons
            else fallbackParamsJsons
        val frameParamsJsonOrNull =
            if (useFrame && frameNumber > 0) {
                FilmStockMapper.withFrameNumber(baseJson, frameNumber)
            } else baseJson

        // RAW 专属默认测光方式（v32 / 11-07 两场景定量标定）：只改本通路的参数链，
        // 主参数与所有兜底档统一注入，避免主参数失败掉兜底时又退回 center_weighted。
        // 绝不碰 buildParamsJson 基础串 —— JPEG/RAW 共用，改了会破坏「JPEG 回归旧水平」。
        val rawParamsJson = SpektraProcessor.withRawExposureMethod(frameParamsJsonOrNull, isFlash)
        val rawFallbacks = effectiveFallbacks.map {
            SpektraProcessor.withRawExposureMethod(it, isFlash)
        }

        // v33：设备定标。同一次快门三星 ISP 已经渲出一张「曝光正确」的 JPEG，
        // 用它的亮度分位当目标，程序自己选放大机曝光，取代引擎逐张猜曝光。
        // 任何一步失败都回落 v32 参数链，绝不阻断冲洗；直闪帧跳过（v31 已固定曝光）。
        // v40：设备定标。同一次快门三星 ISP 已渲出「曝光正确」的 JPEG，用它的
        // 亮度分位当目标，逐档渲不同**输入层曝光**的小图选最接近的一档。
        // 曝光改在 DNG 线性 Bayer 上做，引擎的 print_exposure 恒定 → 片框颜色恒定。
        // 任何一步失败都回落 v32 参数链，绝不阻断冲洗；直闪帧跳过（v31 已固定曝光）。
        // v46：导入的 RAW 没有可信的 ISP 参考 —— 入卷时随帧存的那张 JPEG 是引擎
        // 自己渲的兜底图（已过整条胶片链 + 印相），拿它当曝光目标等于印相两次，
        // 成片发闷发暗（用户：「导入照片冲洗的照片也比较暗」）。这类帧跳过引导。
        val importedRaw = shotJson.isNullOrBlank()
        val ispGainEv =
            if (!importedRaw && SpektraProcessor.shouldUseIspGuide(isFlash, ispGuideEnabled())) {
                runCatching {
                    guideRawExposureByIsp(roll.rollId, index, dngPath, rawParamsJson, frameZoom)
                }.onFailure {
                    Log.w(TAG, "ISP 输入曝光定标失败，回落 v32 参数链（不影响冲洗）", it)
                }.getOrNull()
            } else null
        // 绝对曝光：相机 RAW 由 ISP 引导定标，导入 RAW 用固定基准（没有参考可追）。
        // 用户补偿在两支上都叠加 —— 但必须在输入层（印相层会连片框一起冲浓）。
        val baseGainEv = if (importedRaw) {
            FilmStockMapper.RAW_IMPORT_BASE_EV.toDouble()
        } else ispGainEv ?: 0.0
        val totalGainEv = (baseGainEv + userInputEv).coerceIn(-1.5, 2.5)
        // 只要输入层承载了曝光（引导成功 / 导入 / 用户拖过旋钮），就必须关掉引擎的
        // 自动打试条：否则 auto_exposure 会把输入层的补偿重新归一回去，旋钮等于无效。
        val lockPrint = importedRaw || ispGainEv != null || kotlin.math.abs(userInputEv) > 1e-3
        val finalParamsJson = if (lockPrint) {
            SpektraProcessor.withFixedPrintExposure(rawParamsJson)
        } else rawParamsJson
        val finalFallbacks = if (lockPrint) {
            rawFallbacks.map { SpektraProcessor.withFixedPrintExposure(it) }
        } else rawFallbacks
        if (lockPrint) {
            Log.i(
                TAG,
                "帧 $index [RAW] 输入曝光 ${if (totalGainEv >= 0) "+" else ""}${"%.2f".format(totalGainEv)} EV" +
                    "（ISP ${ispGainEv ?: "无"} · 导入=$importedRaw · 用户 +$userInputEv）" +
                    " · print_exposure 恒定 ${SpektraProcessor.basePrintExposure(rawParamsJson)}"
            )
        }

        // RAW 影调 A/B 探针：只在外部私有目录放了 raw_tone_ab.json 且是第一帧时跑，
        // 全程吞异常、不影响本帧正常冲洗。仍用 v32 参数链，保持历轮 A/B 的实验口径。
        runCatching { runRawToneProbe(dngPath, rawParamsJson, index, frameZoom) }
            .onFailure { Log.w(TAG, "RAW 影调探针异常（不影响冲洗）", it) }

        // RAW 竖拍方向：试过 io.geometry.quarter_turns（让引擎解码后先转画面再按
        // 竖置片门渲染，齿孔就能落上下）——真机实测被拒：与 crop_* 同组整组不收，
        // 竖拍帧全部掉兜底档（冲洗完成页报「有帧未按要求出图」）。已撤回，见
        // FilmStockMapper 白名单注释。
        // 引擎 RAW 会话出图（主参数 + 兜底链；主参数失败整个会话重开）
        Log.d(TAG, "帧 $index [RAW] 帧记录变焦比=$frameZoom（0=老帧回落设备默认）")
        val result = SpektraProcessor.processRawFrame(
            applicationContext, dngPath, finalParamsJson, tmpJpeg.absolutePath,
            fallbackParamsJsons = finalFallbacks,
            zoomRatio = frameZoom,
            gainEv = totalGainEv
        )
        check(result.ok) { "processRawFrame 失败: ${result.message}" }

        // ---- 后处理：旋转 / 片门 / 日期戳 / 暗角 ----
        // v37：必须像 JPEG 通路（:578）那样显式 inMutable —— 否则 rotation=0 的横拍
        // 拿到的是不可变 Bitmap，FrameBorderLock.setPixels 与日期戳 Canvas 都会抛
        // IllegalStateException，被 runCatching 静默吞掉，表现为「片框校色 0 像素」
        // 和「横拍根本没有日期戳」。竖拍因为走 rotateBitmap（createBitmap 可变）
        // 侥幸正常，所以这个洞藏了很久。
        val rawDecodeOpts = BitmapFactory.Options().apply {
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            inMutable = true
        }
        var bmp = BitmapFactory.decodeFile(tmpJpeg.absolutePath, rawDecodeOpts)
            ?: throw IllegalStateException("RAW 输出解码失败: $tmpJpeg")
        try {
            // v35：先把片框颜色校回固定浓度，再旋转/裁片门。参考图是引擎原始
            // 输出同几何的 ev=0 小图，旋转后两者坐标系就不一致了，故放在最前。
            // 只调边框区域的暗片基/橙色印刷，画面像素与帧号文字一个不动。
            // v40：确定性固定色表恢复印刷字颜色（不依赖 CDF / 参考图 / 统计）
            runCatching { applyFrameBorderLock(bmp, finalParamsJson, index) }
                .onFailure { Log.w(TAG, "片框颜色恢复异常（不影响冲洗）", it) }
            // 方向启发式：引擎的 LibRaw 解码多半不应用 DNG 的方向标签——
            // 竖拍（rotation 90/270）而输出是横的 → 按帧记录旋转；输出已是竖的 = 引擎转过了
            if ((rotation == 90 || rotation == 270) && bmp.width > bmp.height) {
                val rotated = rotateBitmap(bmp, rotation)
                bmp.recycle()
                bmp = rotated
                Log.d(TAG, "帧 $index [RAW] 输出按记录旋转 $rotation°")
            } else if (rotation == 180) {
                val rotated = rotateBitmap(bmp, 180)
                bmp.recycle()
                bmp = rotated
            }
            // 片门：只裁真正的 4:3 裸画面（片框关闭时引擎输出原画幅）。带片框的
            // 出图总比例远离 4:3（画面外有片基+齿孔），按片门比例硬裁会把片框裁烂
            // ——真机实锤：竖拍旋转后右侧残留一条齿孔带、左侧被裁没、剩半块边缘文字。
            // 画面比例已在 prepareGateDng 用 ActiveArea 预裁过，理论上不会走到这。
            val r = bmp.width.toFloat() / bmp.height.toFloat()
            val isBare43 = kotlin.math.abs(r - 3f / 4f) < 0.02f ||
                kotlin.math.abs(r - 4f / 3f) < 0.03f
            if (isBare43) {
                val crop = FilmStockMapper.gateCrop(gate, bmp.width, bmp.height)
                if (crop.cropped) {
                    val gated = Bitmap.createBitmap(crop.w, crop.h, Bitmap.Config.ARGB_8888)
                    Canvas(gated).drawBitmap(bmp, -crop.x.toFloat(), -crop.y.toFloat(), Paint())
                    bmp.recycle()
                    bmp = gated
                    Log.d(TAG, "帧 $index [RAW] 片门裁切 ${crop.w}x${crop.h}")
                }
            }
            if (isDateStampEnabled()) {
                runCatching {
                    // v36：RAW 在这里画的是「引擎输出（已含片框）」，必须把日期戳
                    // 定位到画面区而不是整张带框成片的右下角，否则会印到片基 /
                    // 暗边印刷带上（用户 11-07 14:49 报的「日期水印被搞到相框上」）。
                    // 片框关闭 / gateCrop 出裸画面时 contentForAspect 返回 null，
                    // 行为与 JPEG 通路一致（按整图定位）。
                    DateStampRenderer.drawOnBitmap(
                        applicationContext, bmp, takenAt,
                        DateStampRenderer.contentForAspect(bmp.width, bmp.height),
                    )
                }.onFailure { Log.w(TAG, "帧 $index [RAW] 日期戳绘制失败（跳过）", it) }
            }
            runCatching { applySonnarVignette(bmp) }
            // 回写 tmpJpeg（引擎输出被后处理替换）
            java.io.FileOutputStream(tmpJpeg).use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
            }
        } finally {
            bmp.recycle()
        }

        // EXIF + 落盘成片（与 JPEG 通路同一收尾）
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
        check(tmpJpeg.exists() && tmpJpeg.length() > 0) { "RAW 冲洗后临时 JPEG 为空: $tmpJpeg" }
        val uri = UndevelopedFilmStore.writeDevelopedFrame(roll.rollId, index, tmpJpeg)
        Log.d(TAG, "帧 $index [RAW] 落盘完成 uri=$uri")
        tmpJpeg.delete()
        return result.fellBack
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
    /**
     * RAW 影调 A/B 探针：外部私有目录存在 `raw_tone_ab.json` 时，用同一张 DNG
     * 逐组参数各渲一张小图到 `raw_tone_out/`，拉回来做像素统计即可定量选出
     * RAW 通路该用的曝光 / 影调 / 调色值。跑一次即删配置，默认路径零影响。
     *
     * 配置格式与 spektra_matrix.json 相同（extra 的值是 JSON 字面量）：
     * `[{"name":"base","extra":{},"long_edge":640}, ...]`
     *
     * 为什么非做不可：RAW 是线性 scene-referred 输入，而所有胶片参数都是对着
     * 「三星 ISP 渲染过的 JPEG」标定的；v22 取消冻结校色后 RAW 连补偿都没有，
     * 成片冷、硬、平。正确值只能靠真机 A/B 定量，凭观感猜必翻车。
     */
    private fun runRawToneProbe(dngPath: String, baseJson: String?, index: Int, zoomRatio: Float) {
        if (baseJson == null || index != 1) return
        val dir = getExternalFilesDir(null) ?: return
        val spec = java.io.File(dir, "raw_tone_ab.json")
        if (!spec.exists()) return
        val cases = runCatching {
            val arr = org.json.JSONArray(spec.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ex = o.optJSONObject("extra")
                val map = LinkedHashMap<String, String>()
                if (ex != null) {
                    val keys = ex.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        map[k] = ex.get(k).toString()   // 数字/布尔/字符串都吃
                    }
                }
                Triple(o.getString("name"), map, o.optInt("long_edge", 640).coerceIn(320, 2048))
            }
        }.onFailure { Log.w(TAG, "RAW 影调探针配置解析失败", it) }.getOrNull()
        if (cases.isNullOrEmpty()) {
            runCatching { spec.delete() }
            return
        }
        val outDir = java.io.File(dir, "raw_tone_out")
        runCatching { outDir.deleteRecursively() }
        outDir.mkdirs()
        val report = StringBuilder()
        try {
            for ((name, extra, longEdge) in cases) {
                val json = SpektraProcessor.mergeParams(baseJson, extra)
                val out = java.io.File(outDir, "$name.jpg")
                val dims = runCatching {
                    SpektraProcessor.renderRawProbeFrame(
                        applicationContext, dngPath, json, out.absolutePath, longEdge, zoomRatio
                    )
                }.onFailure { Log.w(TAG, "RAW 影调探针 $name 失败", it) }.getOrNull()
                val label = dims?.let { "${it.first}x${it.second}" } ?: "FAIL"
                val kb = if (out.exists()) out.length() / 1024 else 0
                report.append(name).append(' ').append(label).append(' ').append(kb).append("KB\n")
                Log.i(TAG, "RAW 影调探针 $name -> $label ${kb}KB")
            }
        } finally {
            runCatching { java.io.File(dir, "raw_tone_ab_report.txt").writeText(report.toString()) }
            runCatching { spec.delete() }   // 跑一次就删，别每卷都来一遍
        }
        Log.i(TAG, "RAW 影调探针完成: ${outDir.absolutePath} (${cases.size} 张)")
    }

    /**
     * v40 设备定标：用同快门的 ISP JPEG 给这张 RAW 选**输入层曝光**。
     *
     * 同一次快门 HAL 同时给了 JPEG（三星 ZSL/HDR/AE/AWB 全处理完）和 RAW_SENSOR，
     * `UndevelopedFilmStore.saveFrame` 两样都随帧落盘。JPEG 就是「设备认为曝光
     * 正确」的参考：拿它的中心区亮度分位当目标，逐档对 DNG 线性 Bayer 施加
     * 不同的 2^EV 增益并渲 320px 小图比一次，选最接近的一档。
     * 引擎参数（尤其 print_exposure）在所有档里完全相同，片框颜色因此恒定。
     *
     * 只比亮度分位数 —— 不叠 JPEG 像素、不叠二次 tone mapper、不对齐 JPEG 风格。
     * 任何异常返回 null，调用侧回落 v32 参数链，绝不阻断冲洗。
     */
    private fun guideRawExposureByIsp(
        rollId: String,
        index: Int,
        dngPath: String,
        baseJson: String,
        zoomRatio: Float,
    ): Double? {
        // 参考图是**本帧专用**：先清掉上一帧残留，否则直闪帧 / 引导回落帧会拿
        // 旧帧的标准浓度去校正，比不校更糟。
        runCatching { File(cacheDir, "isp_guide/frame_border_ref.jpg").delete() }
        val targetHist = runCatching { ispJpegHistogram(rollId, index) }.getOrNull() ?: return null
        val rawTarget = SpektraProcessor.luminancePercentiles(targetHist)
        if (rawTarget.first < 8.0) return null   // 全黑 / 解码异常，别拿它定标
        // v34：ISP 参考自己过曝时不能照抄（v33 #2 target 203/256 → 成片全白）
        val target = SpektraProcessor.clampIspTarget(rawTarget.first, rawTarget.second)

        val outDir = File(cacheDir, "isp_guide").apply { runCatching { mkdirs() } }
        val gains = SpektraProcessor.ISP_GUIDE_GAIN_EVS
        // v40：所有档参数完全相同（固定 print_exposure），差别只在 DNG 输入曝光
        val paramJsons = gains.map { SpektraProcessor.withFixedPrintExposure(baseJson) }
        val outFiles = gains.map { File(outDir, "g_$it.jpg") }
        val dims = SpektraProcessor.renderIspGuideBatch(
            applicationContext, dngPath, paramJsons,
            outFiles.map { it.absolutePath }, 320, zoomRatio, gains.toList()
        )
        val points = ArrayList<SpektraProcessor.IspGuidePoint>()
        val report = StringBuilder(
            "帧$index rawTarget p50=${rawTarget.first} p90=${rawTarget.second} " +
                "clamped=${target.first}/${target.second}\n"
        )
        gains.forEachIndexed { i, ev ->
            val out = outFiles[i]
            val hist = if (dims.getOrNull(i) != null && out.exists()) {
                runCatching { jpegHistogram(out) }.getOrNull()
            } else null
            if (hist == null) {
                report.append("  gainEV=$ev FAIL\n")
            } else {
                val st = SpektraProcessor.luminancePercentiles(hist)
                points.add(SpektraProcessor.IspGuidePoint(ev, st.first, st.second))
                report.append("  gainEV=$ev p50=${st.first} p90=${st.second}\n")
            }
            runCatching { out.delete() }
        }
        // 相邻档按 p50 线性插值出精确 EV，超范围才退回离散选优
        val chosen = SpektraProcessor.interpolateIspGuideEv(target.first, points)
            ?: SpektraProcessor.pickIspGuideEv(target, points)
        report.append("  chosenGainEV=${chosen ?: "NONE 回落 v32"}\n")
        runCatching {
            getExternalFilesDir(null)?.let {
                File(it, "isp_guide_report.txt").appendText(report.toString())
            }
        }
        Log.i(
            TAG,
            "ISP 曝光定标 帧$index target p50/p90=${target.first}/${target.second} " +
                "候选=${points.size} 选中=$chosen"
        )
        return chosen
    }

    /** 同快门 ISP JPEG → 中心区灰度直方图（冲洗后才删除，此处仍在卷里）。 */
    private fun ispJpegHistogram(rollId: String, index: Int): IntArray? {
        val stream = UndevelopedFilmStore.openFrameStream(rollId, index) ?: return null
        val bmp = stream.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 })
        } ?: return null
        return runCatching { centerHistogram(bmp) }.also { bmp.recycle() }.getOrNull()
    }

    /** 定标小图 → 中心区灰度直方图。 */
    private fun jpegHistogram(file: File): IntArray? {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return runCatching { centerHistogram(bmp) }.also { bmp.recycle() }.getOrNull()
    }

    /** 中心 80% 区域的 256 桶亮度直方图：四边各裁 10% 去掉片框白边差异。 */
    private fun centerHistogram(bmp: Bitmap): IntArray {
        val x0 = (bmp.width * 0.10f).toInt()
        val y0 = (bmp.height * 0.10f).toInt()
        val w = (bmp.width * 0.80f).toInt().coerceAtLeast(1)
        val h = (bmp.height * 0.80f).toInt().coerceAtLeast(1)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x0, y0, w, h)
        val hist = IntArray(256)
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            hist[(r * 77 + g * 150 + b * 29) shr 8]++
        }
        return hist
    }

    /** 热关开关：外部私有目录放 isp_guide_off 即停用，出问题不用重装。 */
    private fun ispGuideEnabled(): Boolean =
        getExternalFilesDir(null)?.let { !File(it, "isp_guide_off").exists() } ?: true

    /** v35 热关开关：放 frame_lock_off 即停用片框颜色锁定（行为回到 v34）。 */
    private fun frameLockOff(): Boolean =
        getExternalFilesDir(null)?.let { File(it, "frame_lock_off").exists() } ?: false

    /** v40：确定性固定色表恢复片框印刷字颜色，不依赖参考图或任何统计。 */
    private fun applyFrameBorderLock(bmp: Bitmap, paramsJson: String, index: Int) {
        if (!FrameBorderLock.shouldApply(paramsJson, frameLockOff())) return
        val changed = FrameBorderLock.apply(bmp)
        Log.i(TAG, "帧 $index [RAW] 片框颜色恢复：$changed 像素")
    }

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
                // 可选渲染长边：默认 640（看色彩方向够用）。
                // lens_blur_um 这类**胶片尺度**的量纲参数必须渲大图才能显形：
                // 36mm→640px 时 1px≈56μm，10μm 级的镜头柔度会被缩没；2048px 时 1px≈17.6μm。
                val longEdge = o.optInt("long_edge", 640).coerceIn(320, 4096)
                Triple(o.getString("name"), map, longEdge)
            }
        }.onFailure { Log.w(TAG, "矩阵配置解析失败", it) }.getOrNull()
        if (cases.isNullOrEmpty()) {
            runCatching { specFile.delete() }
            return
        }
        val outDir = java.io.File(dir, "matrix_out")
        runCatching { outDir.deleteRecursively() }
        outDir.mkdirs()
        // 小图渲染只为看色彩方向，640px 足够，跑一组不至于让冲洗慢到不能忍；
        // 个别**胶片尺度**量纲的参数（lens_blur_um）按 spec 里的 long_edge 放大渲
        var small: android.graphics.Bitmap? = null
        val body = baseJson.trimEnd().removeSuffix("}")
        val report = StringBuilder()
        try {
            for ((name, extra, longEdge) in cases) {
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
                if (small == null || small!!.width != longEdge) {
                    small?.recycle()
                    val h = (longEdge.toFloat() * bmp.height / bmp.width).toInt().coerceAtLeast(1)
                    small = android.graphics.Bitmap.createScaledBitmap(bmp, longEdge, h, true)
                }
                val out = java.io.File(outDir, "$name.jpg")
                val r = runCatching {
                    SpektraProcessor.processFrame(
                        applicationContext, small!!, json, out.absolutePath, recycleInput = false
                    )
                }.getOrNull()
                report.append(name).append(' ').append(r?.ok).append(' ').append(r?.message).append('\n')
                Log.i(TAG, "矩阵 $name -> ok=${r?.ok} ${r?.message}")
            }
        } finally {
            small?.recycle()
            runCatching { java.io.File(dir, "spektra_matrix_report.txt").writeText(report.toString()) }
            runCatching { specFile.delete() }   // 跑一次就删，别每次冲洗都来一遍
        }
        Log.i(TAG, "参数对照实验完成: ${outDir.absolutePath} (${cases.size} 张)")
    }

    /**
     * 挑一个能出图的片框版式，并问清楚**横竖两个朝向**各自认不认。
     *
     * 为什么必须真跑：片框的版式与底片尺寸匹配是渲染期校验，setParams 会爽快收下参数，
     * 冲到 solve 才抛 `overscan: a 1440 x 1080 frame is not the 135 gate's shape (36 x 24)`。
     * 只做参数预检的话，结果是整档掉回兜底档 —— 用户看到的就是「1 帧未能按所选风格冲洗」。
     *
     * 为什么横竖都要测：竖拍走的是竖置片门（画面在底片上本来就是竖的），引擎未必认。
     * 认横不认竖时，横帧照常带片框、竖帧只出画面，别为了一片片基把竖照片毁掉。
     *
     * 只剩 135 一格，没有可退的版式：这一格不认就本次不带片框，总好过整卷没有片框。
     */
    private fun pickFrameFormat(
        filmStockId: String,
        printStockId: String,
        settings: FilmStockMapper.DevSettings,
    ): FrameCheck {
        val notes = ArrayList<String>()
        var chosen: String? = null
        var okLandscape = false
        var okPortrait = false
        for (format in FilmStockMapper.overscanCandidates(settings.frameFormat)) {
            val frame = FilmStockMapper.FrameFormat.fromOverscanFormat(format)
            val lErr = probeGate(filmStockId, printStockId, settings, format, frame, false)
            val pErr = probeGate(filmStockId, printStockId, settings, format, frame, true)
            notes.add("$format 横 -> ${lErr ?: "OK"}")
            notes.add("$format 竖 -> ${pErr ?: "OK"}")
            if (lErr == null || pErr == null) {
                Log.i(TAG, "片框版式可用: $format（横 ${lErr ?: "OK"} / 竖 ${pErr ?: "OK"}）")
                chosen = format
                okLandscape = lErr == null
                okPortrait = pErr == null
                break
            }
            Log.w(TAG, "片框版式 $format 不可用: 横=$lErr | 竖=$pErr")
        }
        // 落盘：logcat 不一定抓得到（无线调试经常断），留个文件随时能 pull；
        // 工程师模式关着就不写（那不是给用户看的东西），日志照常
        if (com.filmcam.app.DevDump.enabled(this)) runCatching {
            val dir = getExternalFilesDir(null) ?: filesDir
            java.io.File(dir, "spektra_frame_probe.txt").writeText(
                notes.joinToString("\n") + "\nchosen=" + (chosen ?: "none")
            )
        }
        return FrameCheck(chosen, okLandscape, okPortrait)
    }

    /** 自检结果：用的版式 + 横/竖两个朝向各自能不能出图。 */
    private data class FrameCheck(
        val format: String?,
        val okLandscape: Boolean,
        val okPortrait: Boolean,
    )

    /** 拿该版式这个朝向的片门比例跑一遍小图（96px 长边），返回引擎报错原文或 null。 */
    private fun probeGate(
        filmStockId: String,
        printStockId: String,
        settings: FilmStockMapper.DevSettings,
        format: String,
        frame: FilmStockMapper.FrameFormat,
        portrait: Boolean,
    ): String? {
        val aspect = frame.gateAspectFor(portrait).coerceAtLeast(0.1f)
        val long = 96
        val w = if (portrait) (long * aspect).toInt() else long
        val h = if (portrait) long else (long / aspect).toInt()
        val json = FilmStockMapper.buildParamsJson(
            filmStockId, printStockId, settings,
            includeStyleMark = false,
            extraEntries = FilmStockMapper.frameBorderEntries(filmStockId, 1, format),
        )
        return SpektraProcessor.probeRenderOk(this, json, w.coerceAtLeast(1), h.coerceAtLeast(1))
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
