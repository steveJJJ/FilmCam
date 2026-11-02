package app.spektralab.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 冲洗（成片）流程唯一引擎入口。
 * 规格 §5.3 冻结 API 逐字实现。
 *
 * 关键纪律：
 *  - 进程内共用一个引擎实例（create 一次 / close 一次）。
 *  - 每帧独立 openSession → setParams（一次）→ solve("both") → exportJpeg → close。
 *  - session 打开后绝不再发含 film_stock/print_stock 的 delta。
 */
object SpektraProcessor {

    private const val TAG = "SpektraProcessor"

    data class ProcessResult(
        val ok: Boolean,
        val width: Int,
        val height: Int,
        val downscaled: Boolean,
        val message: String,
    )

    @Volatile
    private var engine: SpektraLabEngine? = null
    private val engineLock = Any()

    /** 复用的 floatRGB direct buffer，按需扩容；36 帧不重复 alloc 144MB。 */
    @Volatile
    private var reusableFloatBuf: ByteBuffer? = null
    private val bufLock = Any()

    /** 先 EngineResources.ensureLoaded，再懒创建引擎；异常向上抛。 */
    fun ensureEngine(context: Context): SpektraLabEngine {
        engine?.let {
            cancelScheduledRelease()  // 复用现有引擎，取消待执行的延迟释放
            return it
        }
        val appContext0 = context.applicationContext
        // 必须在任何 SpektraLabEngine 引用（触发其 static 块 loadLibrary）之前完成
        // 版本化依赖（libjpeg.so.8 / libz.so.1）的 SONAME 注册。
        EngineResources.ensureNativeLibs(appContext0)
        synchronized(engineLock) {
            engine?.let { return it }
            val appContext = appContext0
            val resourcesDir = EngineResources.ensureLoaded(appContext)
            FilmStockMapper.initialize(appContext)

            // Vulkan 诊断日志路径：应用读不到 stderr，必须靠这个文件定位原生层失败。
            val logPath = java.io.File(appContext.filesDir, "spektrafilm_vk.log").absolutePath
            // 删旧日志，防止下一轮读到上一轮的残留。
            runCatching { java.io.File(logPath).delete() }

            val created = try {
                SpektraLabEngine.create(resourcesDir, logPath)
            } catch (e: Throwable) {
                val tail = runCatching {
                    val f = java.io.File(logPath)
                    if (f.exists() && f.length() > 0) f.readText(Charsets.UTF_8).takeLast(1200) else ""
                }.getOrDefault("")
                val buildInfo = runCatching { SpektraLabEngine.buildInfo() }.getOrDefault("?")
                throw RuntimeException(
                    "引擎创建失败: ${e.message}\nbuild=$buildInfo\n---SPK_VK_LOG---\n$tail",
                    e
                )
            }
            Log.i(TAG, "引擎就绪: " + SpektraLabEngine.buildInfo())
            Log.i(TAG, "capabilities: " + created.capabilities())
            engine = created
            return created
        }
    }

    /**
     * 把 Bitmap 转成 3 float/像素、行优先、直接 ByteBuffer（0..1）。
     * 非 ARGB_8888 先转码。复用进程内 direct buffer，避免每帧 alloc 144MB。
     *
     * 内存纪律（12MP 帧，堆上限通常 256MB，此处曾 OOM）：
     *  - 不再一次性 getPixels 全图（旧写法多占一个 48MB IntArray），
     *    改为 ~1MB 行分块读取，边读边填 float 缓冲；
     *  - direct buffer 复用（native 内存，不计入 Java 堆）。
     */
    fun bitmapToFloatRgb(bmp: Bitmap): ByteBuffer {
        val w = bmp.width
        val h = bmp.height
        val src: Bitmap = if (bmp.config == Bitmap.Config.ARGB_8888) {
            bmp
        } else {
            bmp.copy(Bitmap.Config.ARGB_8888, false) ?: bmp
        }

        val needed = w * h * 3 * 4
        // 复用或扩容 direct buffer
        val buf: ByteBuffer = synchronized(bufLock) {
            val existing = reusableFloatBuf
            if (existing != null && existing.capacity() >= needed) {
                existing.clear()
                existing.limit(needed)
                existing
            } else {
                ByteBuffer
                    .allocateDirect(needed)
                    .order(ByteOrder.nativeOrder())
                    .also { reusableFloatBuf = it }
            }
        }

        // 行分块读像素：块大小约 1MB，峰值只多这一小块
        val rowsPerChunk = ((256 * 1024) / (w * 4)).coerceAtLeast(1)
        val chunk = IntArray(w * rowsPerChunk)
        val fb = buf.asFloatBuffer()
        var fIdx = 0
        var y = 0
        while (y < h) {
            val rows = minOf(rowsPerChunk, h - y)
            src.getPixels(chunk, 0, w, 0, y, w, rows)
            val n = w * rows
            var i = 0
            while (i < n) {
                val px = chunk[i++]
                fb.put(fIdx, ((px ushr 16) and 0xFF) / 255.0f)
                fb.put(fIdx + 1, ((px ushr 8) and 0xFF) / 255.0f)
                fb.put(fIdx + 2, (px and 0xFF) / 255.0f)
                fIdx += 3
            }
            y += rows
        }
        if (src !== bmp) src.recycle()

        buf.position(0)
        buf.limit(needed)
        return buf
    }

    /**
     * 全分辨率冲洗一帧。
     *  - 若 w*h > engine.maxFramePixels() 且 maxFramePixels()>0，等比缩小（downscaled=true）。
     *  - openSession → setParams(paramsJson) 一次 → solve("both") → exportJpeg(path,95,512,"full")。
     *  - recycleInput=true 时，输入位图在像素转入 float 缓冲后立即回收——solve 阶段
     *    引擎自身分配内存，输入位图（12MP 8888 约 48MB）晚一秒回收都可能顶爆堆。
     *    调用方此后不得再使用 input。
     *  - 任何异常捕获后返回 ProcessResult(false,0,0,downscaled,e.message)。
     *  - fallbackParamsJson 非空时，主参数失败会**另开一个 session**用兜底参数重跑一次。
     *    扫描档会下发一批引擎新增参数（扫描仪镜头/局部反差/宽容度），老引擎可能不认；
     *    与其让整卷冲不出来，不如退成印相档出图。不能在同一 session 上补发：
     *    规格要求 session 打开后不得再发含 film_stock/print_stock 的 delta。
     */
    fun processFrame(
        context: Context,
        input: Bitmap,
        paramsJson: String,
        outJpegPath: String,
        recycleInput: Boolean = false,
        fallbackParamsJson: String? = null,
    ): ProcessResult {
        var downscaled = false
        var working = input
        var scaledByProcessor = false
        // 提到 try 外，便于 catch 日志带上现场尺寸/上限/参数。
        var w = 0
        var h = 0
        var maxPx = 0
        return try {
            val eng = ensureEngine(context)
            w = working.width
            h = working.height

            maxPx = eng.maxFramePixels()
            if (maxPx > 0 && w.toLong() * h > maxPx) {
                val scale = Math.sqrt(maxPx.toDouble() / (w.toDouble() * h.toDouble()))
                val newW = Math.max(1, (w * scale).toInt())
                val newH = Math.max(1, (h * scale).toInt())
                Log.w(
                    TAG,
                    "帧 ${w}x${h} 超过设备上限 $maxPx，等比缩小到 ${newW}x${newH}"
                )
                val scaled = Bitmap.createScaledBitmap(working, newW, newH, true)
                if (scaled !== working) {
                    if (scaledByProcessor) working.recycle()
                    working = scaled
                    scaledByProcessor = true
                }
                w = newW
                h = newH
                downscaled = true
            }

            val buf = bitmapToFloatRgb(working)
            if (recycleInput && !working.isRecycled) {
                // 输入像素已全部进入缓冲，此刻回收可省 48MB 堆给 solve 阶段
                working.recycle()
            }
            var rendered = renderOne(eng, buf, w, h, paramsJson, outJpegPath, context)
            if (rendered == null && fallbackParamsJson != null) {
                // 主参数失败：另开 session 用兜底参数重跑。buf 仍是完整像素，可以再来一次。
                Log.w(TAG, "主参数冲洗失败，回退到兜底参数重跑: $outJpegPath")
                rendered = renderOne(eng, buf, w, h, fallbackParamsJson, outJpegPath, context)
            }
            val dims = rendered ?: throw IllegalStateException("冲洗失败")
            ProcessResult(true, dims.first, dims.second, downscaled, "")
        } catch (e: Throwable) {
            Log.e(
                TAG,
                "processFrame 失败: ${e.message} | w=$w h=$h maxFramePixels=$maxPx downscaled=$downscaled out=$outJpegPath params=$paramsJson",
                e
            )
            ProcessResult(false, 0, 0, downscaled, e.message ?: e.javaClass.simpleName)
        } finally {
            if (scaledByProcessor) working.recycle()
        }
    }

    /**
     * 开一个 session 跑完整条冲洗链（setParams → solve → exportJpeg）。
     * 返回输出尺寸；失败返回 null（异常已记录，给调用方决定要不要换参数重来）。
     */
    private fun renderOne(
        eng: SpektraLabEngine,
        buf: ByteBuffer,
        w: Int,
        h: Int,
        paramsJson: String,
        outJpegPath: String,
        context: Context,
    ): Pair<Int, Int>? {
        val session = eng.openSession(buf, w, h, 3)
        return try {
            session.setParams(paramsJson)
            dumpParamsOnce(context, session)
            session.solve("both")
            val info = session.exportJpeg(outJpegPath, 95, 512, "full")
            // 硬校验：原生侧可能返回 info 但未真正写文件（静默失败会被误判为成功）。
            val outFile = java.io.File(outJpegPath)
            val validDims = info.size >= 2 && info[0] > 0 && info[1] > 0
            if (!validDims || !outFile.exists() || outFile.length() <= 0L) {
                throw java.io.IOException(
                    "exportJpeg 未产出有效文件: path=$outJpegPath exists=${outFile.exists()} size=${outFile.length()} info=${info.joinToString(",")}"
                )
            }
            info[0] to info[1]
        } catch (e: Throwable) {
            Log.e(TAG, "renderOne 失败: ${e.message} | ${w}x$h out=$outJpegPath", e)
            null
        } finally {
            session.close()
        }
    }

    /**
     * 把引擎解析后的**全量**参数落一份到 filesDir/spektra_params.json。
     *
     * 我们 setParams 只传 11 个扁平字段，引擎内部还有一整棵参数树
     * （扫描仪模糊、黑白校正、片基边框、场景宽容度、对比遮罩……）。
     * 摸清这棵树才能做「印相 / Frontier / Noritsu」三种输出，否则只能靠猜。
     * 每个进程只写一次，不影响冲洗耗时。
     */
    private fun dumpParamsOnce(context: Context, session: SpektraLabEngine.Session) {
        if (paramsDumped) return
        synchronized(this) {
            if (paramsDumped) return
            paramsDumped = true
            runCatching {
                // 优先外部私有目录：release 包不能 run-as，放这里才能直接 adb pull 出来
                val dir = context.getExternalFilesDir(null) ?: context.applicationContext.filesDir
                val f = java.io.File(dir, "spektra_params.json")
                f.writeText(session.getParams())
                Log.i(TAG, "引擎参数快照: ${f.absolutePath} (${f.length()}B)")
            }.onFailure { Log.w(TAG, "写引擎参数快照失败", it) }
        }
    }

    @Volatile
    private var paramsDumped = false

    /**
     * 引擎单帧像素上限（<=0 表示不限）。会复用已就绪的引擎；引擎未就绪时这里负责拉起。
     *
     * 冲洗前先问到这个值很有用：如果摄像头最大 JPEG 是 50MP 而上限只有 12MP，
     * 就别再全尺寸解码了 —— 解码时就降采样，省掉的是解码 + 转换 + 缩放三段白白烧掉的时间。
     */
    fun framePixelLimit(context: Context): Int = try {
        ensureEngine(context.applicationContext).maxFramePixels()
    } catch (e: Throwable) {
        Log.w(TAG, "读取引擎像素上限失败（按不限处理）", e)
        0
    }

    /** 引擎能力串（Vulkan/CPU 后端、线程数等），用于诊断慢机到底跑在哪条路上 */
    fun capabilities(context: Context): String = try {
        ensureEngine(context.applicationContext).capabilities()
    } catch (e: Throwable) {
        "unavailable: ${e.message}"
    }

    /** 延迟释放引擎的 Handler（主线程）。 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val releaseRunnable = Runnable { doReleaseEngine() }
    private const val DELAYED_RELEASE_MS = 30_000L

    /**
     * 延迟释放引擎（默认 30 秒）；如果期间有新的 ensureEngine 调用会复用现有实例。
     * 冲洗完成后调此方法而非立即 releaseEngine，避免用户马上又冲洗下一卷时等重新初始化。
     */
    fun scheduleReleaseEngine() {
        mainHandler.removeCallbacks(releaseRunnable)
        mainHandler.postDelayed(releaseRunnable, DELAYED_RELEASE_MS)
    }

    /** 取消延迟释放（ensureEngine 成功时调）。 */
    fun cancelScheduledRelease() {
        mainHandler.removeCallbacks(releaseRunnable)
    }

    /** 立即释放引擎 + 复用 buffer；下次 ensureEngine 会重建。 */
    fun releaseEngine() {
        mainHandler.removeCallbacks(releaseRunnable)
        doReleaseEngine()
    }

    private fun doReleaseEngine() {
        synchronized(engineLock) {
            engine?.let {
                try { it.close() } catch (_: Throwable) {}
            }
            engine = null
        }
        synchronized(bufLock) {
            reusableFloatBuf = null
        }
    }
}
