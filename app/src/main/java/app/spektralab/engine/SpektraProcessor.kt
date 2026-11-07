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
        /** 主参数没被引擎接受、靠兜底档出图的（调用方要提示用户，别闷声换口味）。 */
        val fellBack: Boolean = false,
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
            // 放外部私有目录：adb 能直接 pull，联调时不用 root 就能看引擎在抱怨什么
            val logPath = java.io.File(appContext.getExternalFilesDir(null), "spektrafilm_vk.log").absolutePath
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
     *  - [fallbackParamsJsons] 是**逐级降级**的兜底参数链：主参数失败就换下一档重跑。
     *    为什么需要链：引擎对不认识 / 越界的键是硬失败（unknown parameter），
     *    而不同机型/引擎版本认的键并不完全一致。与其让整卷冲不出来，
     *    不如逐档退到更保守的参数，至少出图。每换一档都另开一个 session：
     *    规格要求 session 打开后不得再发含 film_stock/print_stock 的 delta。
     */
    fun processFrame(
        context: Context,
        input: Bitmap,
        paramsJson: String,
        outJpegPath: String,
        recycleInput: Boolean = false,
        fallbackParamsJsons: List<String> = emptyList(),
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
            // 主参数 + 兜底链（去重，避免同一份参数白跑一遍）
            val chain = ArrayList<String>(fallbackParamsJsons.size + 1)
            chain.add(paramsJson)
            for (p in fallbackParamsJsons) if (p != paramsJson && p !in chain) chain.add(p)

            var rendered: Pair<Int, Int>? = null
            var fellBack = false
            for (i in chain.indices) {
                // 每次尝试前复位（native 侧可能动过 position/limit），limit 必须回到这一帧的真实字节数
                buf.clear()
                buf.limit(w * h * 3 * 4)
                rendered = renderOne(eng, buf, w, h, chain[i], outJpegPath, context)
                if (rendered != null) {
                    fellBack = i > 0
                    if (fellBack) {
                        Log.w(TAG, "主参数未被引擎接受，已用第 ${i + 1} 档兜底参数出图: $outJpegPath")
                    }
                    break
                }
                Log.w(TAG, "第 ${i + 1}/${chain.size} 档参数冲洗失败，换下一档: ${chain[i]}")
            }
            val dims = rendered
                ?: throw IllegalStateException("冲洗失败（${chain.size} 档参数全部未被引擎接受）")
            ProcessResult(true, dims.first, dims.second, downscaled, "", fellBack)
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
     * P3 RAW 通路：DNG 底片直接进引擎（openRawSession → 内置 LibRaw 解码 → 16bit 线性
     * → 胶片链）。绕过手机 ISP 出 JPEG 前的三道加工（tone mapping / AWB / 多帧合成），
     * 这是「让成片贴近真机 T3」的信源天花板。
     *
     * 与 [processFrame] 的差别：RAW 会话在 open 时就应用参数（解码管线读它），
     * 所以兜底链换档必须**整个会话重开**，不能像位图会话那样 setParams 重试。
     */
    fun processRawFrame(
        context: Context,
        dngPath: String,
        paramsJson: String,
        outJpegPath: String,
        fallbackParamsJsons: List<String> = emptyList(),
        /** 这一帧拍摄时实际下发的 CONTROL_ZOOM_RATIO（ShotMeta.zoom）；0 = 老帧，回落到设备默认值 */
        zoomRatio: Float = 0f,
        /** v40 输入层曝光（EV），由 ISP 引导选出，作用在 DNG 线性 Bayer 上 */
        gainEv: Double = 0.0,
    ): ProcessResult {
        return try {
            val eng = ensureEngine(context)
            val maxPx = eng.maxFramePixels()
            // RAW 输入是引擎内 LibRaw 解码出的**线性**场景光（DNG 不带 sRGB cctf 编码），
            // 而共享参数里的 input_cctf_decoding=true 是按 JPEG 通路写的（HAL 出的 JPEG
            // 是 sRGB 编码）。对线性数据再反一次 gamma，中灰 0.18 掉到 0.027（≈ -2.7EV）、
            // 暗部 0.05 掉到 0.004 —— 真机观感就是「RAW 成片比 JPEG 暗一大截、黑位发死」。
            // 所以 RAW 会话按线性输入，关掉 cctf 解码（主参数与所有兜底档一视同仁）。
            val primaryJson = asLinearInput(paramsJson)
            // 主参数 + 兜底链（去重）。兜底档也必须按线性输入：RAW 输入是线性 DNG，
            // 这一物理事实与用哪一档参数无关；兜底档若留着 input_cctf_decoding=true
            // 就会二次反 gamma（中灰 0.18→0.027，≈ -2.7EV）——主参数一旦被拒掉兜底，
            // 成片立刻「暗、闷、黑位死」，正是这次用户反馈的现象。
            val chain = buildRawParamChain(primaryJson, fallbackParamsJsons)
            // 片门预裁：改写缓存副本的 ActiveArea（见 prepareGateDng），原底片不动。
            // zoomRatio 来自帧记录（ShotMeta.zoom），不用进程内的全局动态值——冲洗可能
            // 发生在另一个进程、冷启动后直接进暗房，那时相机还没初始化，全局值不准。
            val workPath = prepareGateDng(dngPath, context.cacheDir, paramsJson, zoomRatio, gainEv)

            var rendered: Pair<Int, Int>? = null
            var fellBack = false
            try {
                for (i in chain.indices) {
                    rendered = renderRawOne(eng, context, workPath, chain[i], outJpegPath, maxPx)
                    if (rendered != null) {
                        fellBack = i > 0
                        if (fellBack) {
                            Log.w(TAG, "RAW 主参数未被引擎接受，已用第 ${i + 1} 档兜底参数出图: $outJpegPath")
                        }
                        break
                    }
                    Log.w(TAG, "RAW 第 ${i + 1}/${chain.size} 档参数冲洗失败，换下一档: $dngPath")
                }
            } finally {
                if (workPath != dngPath) runCatching { java.io.File(workPath).delete() }
            }
            val dims = rendered
                ?: throw IllegalStateException("RAW 冲洗失败（${chain.size} 档参数全部未被引擎接受）")
            val src = runCatching { SpektraLabEngine.rawSourceSize() }.getOrDefault(null)
            val outKb = runCatching { java.io.File(outJpegPath).length() / 1024 }.getOrDefault(0)
            Log.i(
                TAG,
                "RAW 底片 ${java.io.File(dngPath).name} 源尺寸=${src?.joinToString("x")} " +
                    "出图 ${dims.first}x${dims.second} 文件=${outKb}KB 兜底=$fellBack " +
                    "帧变焦=$zoomRatio→${effectiveZoomRatio(zoomRatio)}"
            )
            ProcessResult(true, dims.first, dims.second, false, "", fellBack)
        } catch (e: Throwable) {
            Log.e(TAG, "processRawFrame 失败: ${e.message} | dng=$dngPath out=$outJpegPath", e)
            ProcessResult(false, 0, 0, false, e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------- P3 RAW 片门预裁（ActiveArea 改写方案） ----------

    /**
     * 把参数里的 `input_cctf_decoding` 改成 false —— RAW 会话专用。
     *
     * 引擎内 LibRaw 解出来的 DNG 像素是**线性**场景光，不带 sRGB 传输函数；
     * 共享参数里那个 `true` 是给 JPEG 通路的（HAL 出的 JPEG 是 sRGB 编码）。
     * 对线性数据再反 gamma = 中灰 -2.7EV、暗部直接压死（真机观感「RAW 偏暗」）。
     * 参数里没有该键、JSON 解析失败时原样返回（交给引擎默认行为）。
     */
    internal fun asLinearInput(paramsJson: String): String = runCatching {
        val o = org.json.JSONObject(paramsJson)
        if (!o.has("input_cctf_decoding")) return@runCatching paramsJson
        if (!o.optBoolean("input_cctf_decoding", true)) return@runCatching paramsJson
        o.put("input_cctf_decoding", false)
        val out = o.toString()
        Log.i(TAG, "RAW 线性输入：input_cctf_decoding true → false（避免对线性底片二次反 gamma）")
        out
    }.getOrDefault(paramsJson)

    /**
     * RAW 会话的参数档链：主参数 + 去重后的兜底档，**每一档都按线性输入**。
     *
     * 抽成纯函数是为了可单测：兜底档若留着 `input_cctf_decoding=true`，主参数
     * 一被引擎拒就掉兜底、RAW 被二次反 gamma（≈ -2.7EV）——真机反馈「RAW 成片
     * 偏暗」的直接来源。RAW 输入是线性 DNG，这跟用哪一档参数无关。
     */
    internal fun buildRawParamChain(primaryJson: String, fallbacks: List<String>): List<String> {
        val chain = ArrayList<String>(fallbacks.size + 1)
        chain.add(primaryJson)
        for (p in fallbacks) {
            val q = asLinearInput(p)
            if (q != primaryJson && q !in chain) chain.add(q)
        }
        return chain
    }

    /**
     * RAW 通路的片门预裁：引擎在渲染前拿输入尺寸对片门形状校验（差一点都不行），
     * 而 4:3 的 DNG 直接过不了 135（3:2）的检查 —— JPEG 通路是先裁位图再进引擎，
     * RAW 的位图在引擎内部解码、外面裁不到。
     *
     * 曾试过引擎参数 `io.geometry.crop_*`（.so 字符串表里有）—— key 被收下但值
     * 两种量纲都在 `spk_open` 被拒，且注进去还会把本来能开的会话弄挂，弃用。
     *
     * 真机标定（S25U 实测）：引擎的 LibRaw 解码按 **ActiveArea（0xc68d）** 出图 ——
     * 把 ActiveArea 从 (0,0,3060,4080) 改成 (170,0,2890,4080)，解码尺寸即变
     * 4080x2720 = 精确 3:2，片门检查直接通过。所以正确做法是：把 DNG 拷一份到
     * 缓存、改写副本的 ActiveArea，原底片保持原样（可重冲、可换版式）。
     *
     * [cacheDir] 传 context.cacheDir。画面已是片门形状、参数没开片框、解析不动
     * 或 DNG 没有 ActiveArea 标签时原样返回，交给引擎自带的兜底链处理。
     */
    internal fun prepareGateDng(
        dngPath: String,
        cacheDir: java.io.File,
        paramsJson: String,
        /** 帧记录的变焦比；0 / 越界 = 老帧或异常值，回落到设备默认 */
        zoomRatio: Float = 0f,
        /** v40 输入层曝光（EV）；0 = 不缩放。只改缓存副本，原底片不动 */
        gainEv: Double = 0.0,
    ): String {
        val geo = runCatching {
            java.io.File(dngPath).inputStream().use { ins ->
                val head = ByteArray(8)
                if (ins.read(head) == 8) tiffGeometry(ins, head) else null
            }
        }.getOrNull() ?: return dngPath
        val off = geo.activeAreaOffset ?: run {
            Log.w(TAG, "DNG 无 ActiveArea 标签，跳过预裁: $dngPath")
            return dngPath
        }
        val a = geo.activeArea!!   // activeAreaOffset 非 null 时必有
        // ① T3 35mm 视角对齐：采集端 JPEG/预览由 HAL 按 CONTROL_ZOOM_RATIO（=
        //    [FilmStockMapper.T3_ZOOM_RATIO]）中心裁切，RAW_SENSOR 是全幅 Bayer 不被裁
        //    （真机实锤：RAW 成片比 JPEG 广 1.52 倍）——冲洗端自己补同样的裁切。
        //    原点偶对齐：CFAPattern 从 ActiveArea 左上角起排，偶偏移不改 Bayer 相位。
        //    片框开关都裁（视角是机身属性，与片框无关）
        val z = zoomCrop(geo.width, geo.height, zoomRatio)
        var cx = z[0]
        var cy = z[1]
        var cw = z[2]
        var ch = z[3]
        // ② 片门预裁（片框开 135 时）叠在变焦裁切上；竖拍取倒数片门比。
        //    3:2 在偶原点下取精确比例的偶尺寸对：横 w=3k/h=2k、竖 w=2k/h=3k（k 取偶），
        //    引擎片门校验吃得稳（gateCrop 的近似取整在偶数对下可能差 1px）
        val aspectL = gateAspectFromParams(paramsJson)
        if (aspectL != null) {
            val aspect = if (geo.height > geo.width) 1f / aspectL else aspectL
            val c = FilmStockMapper.gateCrop(cw, ch, aspect)
            if (c.cropped) {
                var k = if (aspect >= 1f) c.h / 2 else c.w / 2
                if (k % 2 == 1) k -= 1
                if (k >= 2) {
                    val gw = if (aspect >= 1f) 3 * k else 2 * k
                    val gh = if (aspect >= 1f) 2 * k else 3 * k
                    if (gw <= z[2] && gh <= z[3]) {
                        cw = gw
                        ch = gh
                        cx = z[0] + (z[2] - gw) / 2
                        cy = z[1] + (z[3] - gh) / 2
                    }
                }
            }
        }
        if (cx % 2 == 1) cx -= 1
        if (cy % 2 == 1) cy -= 1
        // v40：即使不需要裁切，只要要做输入层曝光就必须走工作副本，原底片不动
        if (cx == 0 && cy == 0 && cw == geo.width && ch == geo.height &&
            kotlin.math.abs(gainEv) <= 1e-6
        ) return dngPath
        val work = java.io.File(cacheDir, "gate_" + java.io.File(dngPath).name)
        val ok = runCatching {
            java.io.File(dngPath).inputStream().use { ins -> work.outputStream().use { ins.copyTo(it) } }
            java.io.RandomAccessFile(work, "rw").use { raf ->
                val order = java.nio.ByteOrder.LITTLE_ENDIAN.takeIf { geo.littleEndian }
                    ?: java.nio.ByteOrder.BIG_ENDIAN
                val bb = java.nio.ByteBuffer.allocate(16).order(order)
                bb.putInt(a[0] + cy)              // top
                bb.putInt(a[1] + cx)              // left
                bb.putInt(a[0] + cy + ch)         // bottom
                bb.putInt(a[1] + cx + cw)         // right
                raf.seek(off.toLong())
                raf.write(bb.array())
                // 固定日光 WB 覆写已撤回：tag 号写错（0xC625 实为 CameraCalibration2，
                // AsShotWhiteXY 是 0xC629）、只改 WhiteXY 不改 AsShotNeutral 无效、
                // 未经真机 A/B 确认，且可能写坏 DNG 标定数据导致 RAW 会话失败掉兜底档。
                // 现回到「按 DNG 原始白平衡解码」的中性基线。
            }
            // v40：输入层曝光标定 —— 在引擎看到 DNG 之前对线性 Bayer 缩放。
            // 引擎的 print_exposure 恒定，所以片框颜色不受这档补偿影响。
            if (kotlin.math.abs(gainEv) > 1e-6) {
                val layout = readDngGainLayout(work)
                if (layout != null) applyDngGain(work, layout, gainEv)
                else Log.w(TAG, "DNG 增益布局解析失败，跳过输入曝光标定")
            }
            true
        }.getOrDefault(false)
        if (!ok) {
            runCatching { work.delete() }
            Log.w(TAG, "预裁改写失败，用原底片冲: $dngPath")
            return dngPath
        }
        Log.i(TAG, "RAW 预裁：源 ${geo.width}x${geo.height} → ${cw}x${ch}（变焦对齐 1/${effectiveZoomRatio(zoomRatio)} + 片门，已改写 ActiveArea）")
        return work.absolutePath
    }

    /**
     * 这一帧真正用于补裁的变焦比。
     *
     * 帧记录（[com.filmcam.app.film.ShotMeta.zoom]）是权威：拍摄时把实际下发给
     * CONTROL_ZOOM_RATIO 的值随帧落盘，冲洗时按帧取值。这样冲洗发生在另一个
     * 进程、冷启动后直接进暗房、或换了设备导入，都不会读到错的全局默认值。
     *
     * 0 / 越界 = 老帧（升级前拍的、没有该字段）或坏数据 → 回落到当前设备的
     * 动态默认值 [FilmStockMapper.T3_ZOOM_RATIO]。上限收到 2.0：35mm 定焦相对
     * 主摄等效 18~28mm 最多裁 1.94x，再大只可能是坏元数据或动态公式读错了焦距，
     * 会把 ActiveArea 裁到极小（成片分辨率塌成几百 KB）——宁可回落也不裁坏底片。
     */
    internal fun effectiveZoomRatio(zoomRatio: Float): Float =
        if (zoomRatio in 1.0f..2.0f) zoomRatio else FilmStockMapper.T3_ZOOM_RATIO

    /**
     * T3 35mm 视角的中心裁切（1/[effectiveZoomRatio]）。
     * 返回 [x, y, w, h]；原点偶对齐（CFAPattern 相位不变），尺寸不钳偶（起始相位才影响解码）。
     */
    internal fun zoomCrop(w: Int, h: Int, zoomRatio: Float = 0f): IntArray {
        val f = 1f / effectiveZoomRatio(zoomRatio)
        val cw = (w * f).toInt().coerceIn(2, w)
        val ch = (h * f).toInt().coerceIn(2, h)
        var x = (w - cw) / 2
        var y = (h - ch) / 2
        if (x % 2 == 1) x -= 1
        if (y % 2 == 1) y -= 1
        return intArrayOf(x, y, cw, ch)
    }

    /** 参数里片框开着且版式是 135 时返回片门宽高比（3:2）；其余返回 null（交给后处理裁）。 */
    internal fun gateAspectFromParams(paramsJson: String): Float? = runCatching {
        val o = org.json.JSONObject(paramsJson)
        if (!o.optBoolean("overscan_active", false)) return null
        when (o.optString("overscan_format")) {
            "135" -> 36f / 24f
            else -> null
        }
    }.getOrNull()

    /** DNG/TIFF 的几何信息：画面尺寸（引擎实际会解码出来的那块）+ ActiveArea 位置。 */
    internal class TiffGeometry(
        val width: Int,
        val height: Int,
        /** ActiveArea(top,left,bottom,right)；无该标签为 null（非 DNG 的普通 TIFF） */
        val activeArea: IntArray?,
        /** ActiveArea 值域在文件内的偏移（外存 LONG×4），改写用；内联或无标签为 null */
        val activeAreaOffset: Int?,
        val littleEndian: Boolean,
    )

    /**
     * 解析 DNG/TIFF 的几何：扫全部 IFD（含 SubIFD 链），取面积最大的那组
     * ImageWidth(0x0100) / ImageLength(0x0101) 作为画面尺寸；DNG 的
     * ActiveArea(0xc68d, top/left/bottom/right) 存在时以它为准 ——
     * 引擎的 LibRaw 解码就是按 ActiveArea 出图的（真机 S25U 标定）。
     * [head] 是已读的文件头 8 字节。解析不动返回 null。
     */
    internal fun tiffGeometry(ins: java.io.InputStream, head: ByteArray): TiffGeometry? {
        return runCatching {
            if (head[0] != head[1]) return null   // II / MM 头两个字节相同 = 不是 TIFF
            val le = head[0] == 'I'.code.toByte()
            val order = java.nio.ByteOrder.LITTLE_ENDIAN.takeIf { le } ?: java.nio.ByteOrder.BIG_ENDIAN
            fun u16(b: ByteArray, off: Int) =
                ((b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)).let {
                    if (le) it else java.lang.Short.reverseBytes(it.toShort()).toInt() and 0xFFFF
                }
            if (u16(head, 2) != 42) return null
            // TIFF 的 IFD 偏移是相对文件头的绝对偏移：头要拼回 buffer 再索引，
            // 否则所有偏移整体错位 8 字节（真机 DNG 上实测翻车）
            val rest = ins.readBytes()
            val buf = head + rest
            fun u32(b: ByteArray, off: Int): Int = java.nio.ByteBuffer.wrap(b, off, 4).order(order).int
            var best: Pair<Int, Int>? = null
            var bestArea = 0
            var aa: IntArray? = null
            var aaOff: Int? = null
            // 固定日光 WB 覆写已撤回，不再解析/改写 DNG 白平衡标签：外部 AI 那版把
            // AsShotWhiteXY 的 tag 写成 0xC625（实为 CameraCalibration2，正确是 0xC629），
            // 且只改 WhiteXY 不改 AsShotNeutral（LibRaw 优先读后者）→ 要么无效、要么写坏
            // 相机标定数据让 RAW 会话失败掉兜底档。回到「按 DNG 原始 WB 解码」的中性基线。
            val ifdQueue = ArrayDeque<Int>()
            ifdQueue.add(u32(head, 4))
            val seen = mutableSetOf<Int>()
            while (ifdQueue.isNotEmpty()) {
                val ifd = ifdQueue.removeFirst()
                if (ifd <= 0 || ifd + 2 > buf.size || !seen.add(ifd)) continue
                val count = u16(buf, ifd)
                var p = ifd + 2
                var curW = 0
                var curH = 0
                repeat(count) {
                    if (p + 12 > buf.size) return@repeat
                    val tag = u16(buf, p)
                    val type = u16(buf, p + 2)
                    val cnt = u32(buf, p + 4)
                    when (tag) {
                        0x0100, 0x0101 -> {   // ImageWidth / ImageLength（同一 IFD 内配对）
                            if (cnt == 1 && (type == 3 || type == 4)) {
                                val v = if (type == 3) u16(buf, p + 8) else u32(buf, p + 8)
                                if (tag == 0x0100) curW = v else curH = v
                            }
                        }
                        0x0141 -> {           // SubIFDs：往下钻
                            if (type == 4 && cnt in 1..8) {
                                val base = if (4L * cnt <= 4) p + 8 else u32(buf, p + 8)
                                repeat(cnt) { k ->
                                    if (base + 4 * k + 4 <= buf.size) ifdQueue.add(u32(buf, base + 4 * k))
                                }
                            }
                        }
                        0xc68d -> {           // ActiveArea：top/left/bottom/right，LONG×4 外存
                            if (type == 4 && cnt == 4) {
                                val base = u32(buf, p + 8)
                                if (base > 0 && base + 16 <= buf.size) {
                                    aa = intArrayOf(u32(buf, base), u32(buf, base + 4), u32(buf, base + 8), u32(buf, base + 12))
                                    aaOff = base
                                }
                            }
                        }
                        // 不再扫描 AsShotWhiteXY（固定日光 WB 已撤回，见上）
                    }
                    p += 12
                }
                val area = curW.toLong() * curH
                if (curW > 0 && curH > 0 && area > bestArea) {
                    bestArea = area.toInt()
                    best = curW to curH
                }
                if (ifd + 2 + 12 * count + 4 <= buf.size) {
                    val next = u32(buf, ifd + 2 + 12 * count)
                    if (next > 0) ifdQueue.add(next)
                }
            }
            val dims = best?.takeIf { it.first > 0 && it.second > 0 } ?: return null
            // ActiveArea 存在且落在画面里 → 引擎解码的其实是这一块
            val a = aa
            if (a != null && a[3] > a[1] && a[2] > a[0] &&
                a[3] <= dims.first && a[2] <= dims.second
            ) {
                TiffGeometry(a[3] - a[1], a[2] - a[0], a, aaOff, le)
            } else {
                TiffGeometry(dims.first, dims.second, null, null, le)
            }
        }.getOrNull()
    }

    /** 只需要尺寸的旧入口（测试与日志用）。 */
    internal fun tiffDimensions(ins: java.io.InputStream, head: ByteArray): Pair<Int, Int>? =
        tiffGeometry(ins, head)?.let { it.width to it.height }

    /** 开一个 RAW session 跑完整冲洗链（open 已带参数 → solve → exportJpeg）。失败返回 null。 */
    private fun renderRawOne(
        eng: SpektraLabEngine,
        context: Context,
        dngPath: String,
        paramsJson: String,
        outJpegPath: String,
        maxPx: Int,
        maxLongEdge: Int = 0,
    ): Pair<Int, Int>? {
        // open 也要在 try 里：引擎对坏参数是「解码完才炸」（spk_open failed after
        // RAW decode），open 一抛就把整条兜底链带崩，后面的档永远没机会试
        val session = try {
            eng.openRawSession(dngPath, paramsJson, if (maxPx > 0) maxPx else 0, maxLongEdge)
        } catch (e: Throwable) {
            Log.e(TAG, "RAW 会话打开失败: ${e.message} | dng=$dngPath", e)
            return null
        }
        return try {
            dumpParamsOnce(context, session)
            session.solve("both")
            val info = session.exportJpeg(outJpegPath, 95, 512, "full")
            val outFile = java.io.File(outJpegPath)
            val validDims = info.size >= 2 && info[0] > 0 && info[1] > 0
            if (!validDims || !outFile.exists() || outFile.length() <= 0L) {
                throw java.io.IOException(
                    "RAW exportJpeg 未产出有效文件: path=$outJpegPath exists=${outFile.exists()} " +
                        "size=${outFile.length()} info=${info.joinToString(",")}"
                )
            }
            info[0] to info[1]
        } catch (e: Throwable) {
            Log.e(TAG, "renderRawOne 失败: ${e.message} | dng=$dngPath out=$outJpegPath", e)
            null
        } finally {
            session.close()
        }
    }

    /**
     * 导入 RAW 时的兜底/预览出图：限制长边开 RAW 会话 → solve → exportJpeg。
     * 一石二鸟 —— 既是入卷的 JPEG 帧（RAW 通路万一失败的兜底），也是
     * 「这个 RAW 引擎解不解得开」的入卷前验证：解不开返回 null，调用方跳过这张。
     */
    fun renderRawPreview(
        context: Context,
        dngPath: String,
        paramsJson: String,
        outJpegPath: String,
        maxLongEdge: Int = 1920,
    ): Pair<Int, Int>? {
        return try {
            val eng = ensureEngine(context.applicationContext)
            // 预览同样是 RAW 解码出来的线性数据，同样不能二次反 gamma
            renderRawOne(eng, context, dngPath, asLinearInput(paramsJson), outJpegPath, 0, maxLongEdge)
        } catch (e: Throwable) {
            Log.e(TAG, "renderRawPreview 失败: ${e.message} | dng=$dngPath", e)
            null
        }
    }

    /**
     * 覆盖语义的参数合并：基线里已有的同名键必须被**替换**，直接追加会写出重复键，
     * 引擎取第一个 → 那组实验等于没跑（矩阵实验里 max_lift / print_exposure 踩过）。
     */
    internal fun mergeParams(baseJson: String, extra: Map<String, String>): String = runCatching {
        val o = org.json.JSONObject(baseJson)
        extra.forEach { (k, v) -> o.put(k, org.json.JSONTokener(v).nextValue()) }
        o.toString()
    }.getOrDefault(baseJson)

    /**
     * RAW 通路默认测光方式。11-07 白天大光比（中央亮）+ 中央中灰两张完全不同
     * 场景的真机定量 A/B 选出：center_weighted 两次都把画面压暗（meanL 64.2 / 75.6，
     * 暗部 48~64%），balanced 稳定回到 101.8 / 111.3、暗部细节恢复、无硬溢出。
     * median / center 各有胜负，balanced 的提亮幅度最可预测（+37.6 / +35.6）。
     *
     * ⚠️只作用于 RAW 帧的参数链，**绝不能写进 [FilmStockMapper.buildParamsJson]
     * 基础串** —— 那是 JPEG/RAW 共用的，改了会破坏「JPEG 回归旧水平」的定调。
     * 直闪帧（v31）已 auto_exposure=false，曝光量在拍摄时就由 EI + 闪光指数定死，
     * 再发测光方式没有意义，故原样返回。
     */
    const val RAW_DEFAULT_EXPOSURE_METHOD = "balanced"

    internal fun withRawExposureMethod(baseJson: String, flashShot: Boolean): String =
        if (flashShot) baseJson
        else mergeParams(baseJson, mapOf("auto_exposure_method" to "\"$RAW_DEFAULT_EXPOSURE_METHOD\""))

    /**
     * ISP 引导的 RAW 曝光定标（v33）—— 把「引擎逐张猜曝光」换成「设备报曝光」。
     *
     * 为什么改：同一个测光方式 / print_exposure 值在不同底片上落点不同，因为
     * 每张 RAW 的输入曝光量由 HAL AE 决定（室外强光顶到 1/8000s、iso 23~39，
     * 底片中位码值贴着 BlackLevel=64）。靠拍样片选一个默认值永远收敛不了。
     *
     * 设备其实早就把答案给了：同一次快门三星 ISP 输出的那张 JPEG（ZSL/HDR/
     * AE/AWB 全做完）已随帧落盘，用户 11-07 实拍确认「观感正常」。用它解码后的
     * 亮度分位当目标，程序自己渲几张小图选放大机曝光 —— 零调参、跨场景自适应。
     *
     * 边界：**只用亮度分位数**，不叠 JPEG 像素、不叠二次 tone mapper、
     * 不反向对齐 JPEG 的观感风格。这是输入曝光标定（IDT），不是风格迁移。
     */
    /**
     * v34 候选档（相对基础 print_exposure 的偏移，EV）。
     *
     * ⚠️不能出现 < −0.5：基础 print_exposure=0.83，减 1.0 会得到 −0.17，
     * 引擎直接拒参数（v33 五帧报告里 ev=−1.0/−1.5 全 FAIL）。
     * 太小又会在「0.0 档 p50≈13」与「−0.5 档 p50≈62~216」之间留下空档，
     * 所以提亮侧加密到 0.25 步长，再靠 [interpolateIspGuideEv] 插值补精度。
     */
    val ISP_GUIDE_EV_OFFSETS = doubleArrayOf(-0.5, -0.25, 0.0, 0.25, 0.5)

    /**
     * v40：输入层曝光候选（EV）。逐帧曝光标定改在 DNG 线性 Bayer 上做，
     * 引擎的 `print_exposure` 恒定 → 印相阶段叠上去的片框颜色不随补偿变。
     *
     * 真机 DNG 实测量纲：Black=64 / White=1023，净信号 p50=25、p90=181、
     * p99=241；gain=4 时仅 1.1% 像素饱和、gain=8 就 26%。上限取 +2EV
     * （gain 4），足够覆盖 v34 用 `print_exposure` 0.83→0.33 那档提亮量。
     * `exposure_compensation_ev` 已实测无效（±1EV 影像 p50 35→36），不再使用。
     */
    val ISP_GUIDE_GAIN_EVS = doubleArrayOf(0.0, 0.7, 1.3, 2.0)

    /** 固定放大机曝光 + 关引擎自动曝光；输入曝光由 DNG 像素承载。 */
    internal fun withFixedPrintExposure(baseJson: String): String = mergeParams(
        baseJson,
        mapOf(
            "auto_exposure" to "false",
            "print_exposure" to basePrintExposure(baseJson).toString(),
            "exposure_compensation_ev" to "0",   // 实测无效，显式归零防脏值
        )
    )

    /** 单样本线性曝光缩放，抽成纯函数便于 JVM 单测。 */
    internal fun gainSample(v: Int, black: Int, white: Int, gain: Double): Int =
        (black + (v - black) * gain).toInt().coerceIn(0, white)

    /** DNG 像素布局：只解析 IFD0 头部 64KB（strip 表 / 黑位 / 白位都在文件前段）。 */
    internal class DngGainLayout(
        val stripOffsets: LongArray,
        val stripCounts: LongArray,
        val blackLevel: Int,
        val whiteLevel: Int,
    )

    internal fun readDngGainLayout(file: java.io.File): DngGainLayout? = runCatching {
        val head = ByteArray(65536)
        java.io.FileInputStream(file).use { it.read(head) }
        val le = head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte()
        val order = java.nio.ByteOrder.LITTLE_ENDIAN.takeIf { le }
            ?: java.nio.ByteOrder.BIG_ENDIAN
        fun u16(o: Int) = java.nio.ByteBuffer.wrap(head, o, 2).order(order).short.toInt() and 0xFFFF
        fun u32(o: Int) = java.nio.ByteBuffer.wrap(head, o, 4).order(order).int
        val ifd = u32(4)
        require(ifd > 0 && ifd + 2 < head.size) { "IFD 偏移越界" }
        val n = u16(ifd)
        var strips: LongArray? = null
        var counts: LongArray? = null
        var black = 0
        var white = 1023
        fun longs(cnt: Int, inline: Int): LongArray {
            val off = if (cnt == 1) inline else u32(inline)
            require(off > 0 && off + 4L * cnt <= head.size) { "LONG 数组越界" }
            return LongArray(cnt) { u32(off + 4 * it).toLong() }
        }
        for (i in 0 until n) {
            val p = ifd + 2 + i * 12
            if (p + 12 > head.size) break
            val tag = u16(p)
            val type = u16(p + 2)
            val cnt = u32(p + 4)
            if (cnt <= 0 || cnt > 20000) continue
            when (tag) {
                0x0111 -> if (type == 4) strips = longs(cnt, p + 8)
                0x0117 -> if (type == 4) counts = longs(cnt, p + 8)
                0xc61a -> {   // BlackLevel：RATIONAL 第一个 num/den
                    val b = u32(p + 8)
                    val den = u32(b + 4)
                    if (den > 0) black = u32(b) / den
                }
                0xc61d -> if (type == 4 && cnt == 1) white = u32(p + 8)
            }
        }
        val s = strips ?: return null
        val c = counts ?: return null
        require(s.size == c.size && s.isNotEmpty()) { "strip 表不完整" }
        DngGainLayout(s, c, black, white)
    }.onFailure { Log.w(TAG, "DNG 增益布局解析失败: ${it.message}") }.getOrNull()

    /** 对未压缩 16bit Bayer 逐 strip 做 black+(v-black)*gain，原地改写 [file]。 */
    internal fun applyDngGain(file: java.io.File, layout: DngGainLayout, ev: Double) {
        val gain = java.lang.Math.pow(2.0, ev)
        java.io.RandomAccessFile(file, "rw").use { raf ->
            val row = ByteArray(1_000_000)
            for (i in layout.stripOffsets.indices) {
                val off = layout.stripOffsets[i]
                val len = layout.stripCounts[i].toInt()
                if (off <= 0 || len <= 1 || len > row.size) continue
                raf.seek(off)
                raf.readFully(row, 0, len)
                var k = 0
                while (k + 1 < len) {
                    val v = (row[k].toInt() and 0xFF) or ((row[k + 1].toInt() and 0xFF) shl 8)
                    val nv = gainSample(v, layout.blackLevel, layout.whiteLevel, gain)
                    row[k] = (nv and 0xFF).toByte()
                    row[k + 1] = ((nv shr 8) and 0xFF).toByte()
                    k += 2
                }
                raf.seek(off)
                raf.write(row, 0, len)
            }
        }
    }

    /** 一档候选渲染的亮度分位（p50 / p90），ev = 相对基础 print_exposure 的偏移。 */
    data class IspGuidePoint(val ev: Double, val p50: Double, val p90: Double)

    /** 256 桶灰度直方图 → (p50, p90)。纯函数，JVM 单测可直接构直方图验证。 */
    internal fun luminancePercentiles(hist: IntArray): Pair<Double, Double> {
        if (hist.size != 256) return 0.0 to 0.0
        val total = hist.sum().toDouble()
        if (total <= 0.0) return 0.0 to 0.0
        fun at(q: Double): Double {
            val want = q * total
            var acc = 0.0
            for (i in 0 until 256) {
                val prev = acc
                acc += hist[i]
                if (acc >= want) {
                    val denom = hist[i].coerceAtLeast(1)
                    return i + ((want - prev) / denom).coerceIn(0.0, 1.0)
                }
            }
            return 255.0
        }
        return at(0.50) to at(0.90)
    }

    /**
     * 目标分位钳制。同帧 ISP JPEG 自己也会过曝（v33 #2 target p50=202.8 /
     * p90=255.6，照抄就把成片推成全白），不能无脑相信设备参考。
     */
    internal fun clampIspTarget(p50: Double, p90: Double): Pair<Double, Double> =
        p50.coerceIn(40.0, 150.0) to p90.coerceIn(100.0, 230.0)

    /**
     * 相邻两档之间按 p50 线性插值求精确 ΔEV —— v33 只选离散档是死黑/过曝片的
     * 直接原因（0.0 档 p50≈13、−0.5 档 p50≈62~216，目标 103 落在中间却只能挑极端）。
     * 目标超出候选覆盖范围时退化为「取 p50 最接近的一档」。
     */
    internal fun interpolateIspGuideEv(
        targetP50: Double,
        points: List<IspGuidePoint>,
    ): Double? {
        if (points.isEmpty()) return null
        val sorted = points.sortedBy { it.ev }
        if (sorted.size == 1) return sorted[0].ev
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            val lo = kotlin.math.min(a.p50, b.p50)
            val hi = kotlin.math.max(a.p50, b.p50)
            val span = b.p50 - a.p50
            if (targetP50 >= lo && targetP50 <= hi && kotlin.math.abs(span) > 1e-6) {
                val t = ((targetP50 - a.p50) / span).coerceIn(0.0, 1.0)
                return a.ev + t * (b.ev - a.ev)
            }
        }
        return sorted.minByOrNull { kotlin.math.abs(it.p50 - targetP50) }?.ev
    }

    /**
     * 插值失败时的兜底：选与目标分位最接近的一档。p50 权重高（中灰落点），
     * p90 权重压到 0.2 —— RAW 小图的 p90 几乎恒定 ~210（片框/高光），
     * 区分度低，权重给大了会干扰选优。
     */
    internal fun pickIspGuideEv(
        target: Pair<Double, Double>,
        points: List<IspGuidePoint>,
    ): Double? = points.minByOrNull {
        kotlin.math.abs(it.p50 - target.first) + 0.2 * kotlin.math.abs(it.p90 - target.second)
    }?.ev

    /** 基础串里的放大机曝光基线（当前设备 0.83）；缺键按 0.83 兜底。 */
    internal fun basePrintExposure(baseJson: String): Double = runCatching {
        org.json.JSONObject(baseJson).optDouble("print_exposure", 0.83)
    }.getOrDefault(0.83)

    /**
     * v39 根治：把逐帧 ΔEV 从**印相层**挪到**拍摄层**。
     *
     * 旧实现（v34）把 ΔEV 加到 `print_exposure`（放大机曝光时间）。但引擎的 135
     * 片框（黑片基 / 橙色暗边印刷 / 齿孔）是在印相阶段一起叠上去的，
     * `print_exposure` 一变片框就跟着变淡 —— 这才是「边框颜色偶尔不对」的根因。
     * v35/v37/v38 都想在出图后把颜色校回去（CDF / 色度 LUT），但参考图只有
     * 320px、成片 2710px，带内像素构成差太多，分布匹配不可靠：v38 真机把黑片基
     * 染成橙棕 (101,59,33)，标准浓度应是 (10,12,15)。事后统计这条路到头了。
     *
     * 新实现让 `print_exposure` **恒等于基础值**（v32 时代的标准浓度），把亮度
     * 补偿放到 `exposure_compensation_ev`（拍摄层 EV）：它作用于底片影像曝光、
     * 不经过印相链，片框颜色天然恒定，整个 FrameBorderLock 后处理不再需要。
     * 物理上也更对 —— 曝光标定本属于拍摄层，不是放大机曝光时间。
     *
     * 闭环与符号无关：候选小图和主参数都由本函数生成，选档只看 p50/p90 距目标
     * 的远近，[interpolateIspGuideEv] 用 min/max 处理区间，所以即便 ec 的正负
     * 含义与推测相反，也能收敛到让画面亮度匹配 ISP JPEG 的那个值。
     *
     * [useEc] = false 是热回退（外部私有目录放 `ec_ev_off`）：回到 v34 的
     * `print_exposure` 路径并重新启用片框校色，不用重装 APK。
     */
    internal fun withIspGuidedExposure(
        baseJson: String,
        deltaEv: Double,
        useEc: Boolean = true,
    ): String {
        val base = basePrintExposure(baseJson)
        val extra = if (useEc) {
            mapOf(
                "auto_exposure" to "false",
                "print_exposure" to base.toString(),
                // EV 是对数量，可正可负；钳 ±3EV 防异常值把整帧打飞
                "exposure_compensation_ev" to (-deltaEv).coerceIn(-3.0, 3.0).toString(),
            )
        } else {
            // v34 旧路径：下限 0.05，print_exposure 为负会被引擎整帧拒掉
            mapOf(
                "auto_exposure" to "false",
                "print_exposure" to (base + deltaEv).coerceIn(0.05, 6.0).toString(),
                "exposure_compensation_ev" to "0",
            )
        }
        return mergeParams(baseJson, extra)
    }

    /** 直闪帧曝光量在拍摄时已定死（v31 已关 AE），不需要也不该再引导。 */
    internal fun shouldUseIspGuide(isFlash: Boolean, guideEnabled: Boolean): Boolean =
        !isFlash && guideEnabled

    /**
     * RAW 影调 A/B 探针的单组渲染：同一张 DNG、同一套基线参数，覆盖 extra 后渲一张
     * 小图。只由 DevelopmentService.runRawToneProbe 调用（外部配置文件触发），
     * 默认冲洗路径完全不碰它。
     *
     * 为什么要这个：RAW 是线性 scene-referred 输入，而所有胶片参数都是对着
     * 「三星 ISP 渲染过的 JPEG」标定的；取消冻结校色后 RAW 连补偿都没有，成片
     * 冷、硬、平。曝光/影调/调色的正确值只能靠真机定量 A/B 选，不能凭观感猜。
     */
    fun renderRawProbeFrame(
        context: Context,
        dngPath: String,
        paramsJson: String,
        outJpegPath: String,
        longEdge: Int = 640,
        zoomRatio: Float = 0f,
    ): Pair<Int, Int>? {
        return try {
            val eng = ensureEngine(context.applicationContext)
            val workPath = prepareGateDng(dngPath, context.cacheDir, paramsJson, zoomRatio)
            try {
                renderRawOne(eng, context, workPath, asLinearInput(paramsJson), outJpegPath, 0, longEdge)
            } finally {
                if (workPath != dngPath) runCatching { java.io.File(workPath).delete() }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "RAW 影调探针渲染失败: ${e.message} | dng=$dngPath", e)
            null
        }
    }

    /**
     * v34：一次 prepareGateDng + 多组小图渲染。
     *
     * v33 每组都调 [renderRawProbeFrame]，内部每次复制 25MB DNG 改 ActiveArea，
     * 7 组 = 175MB IO，实测冲洗从 3.2s 涨到 12s。片门几何只与 zoom/片门参数
     * 有关、与曝光参数无关，所以整批只预裁一次，之后复用同一个 workDng。
     */
    fun renderIspGuideBatch(
        context: Context,
        dngPath: String,
        paramJsons: List<String>,
        outPaths: List<String>,
        longEdge: Int = 320,
        zoomRatio: Float = 0f,
        /** v40：每档对应一个 DNG 输入曝光 EV；为空则全部按 0 处理（v34 旧行为） */
        gainEvs: List<Double> = emptyList(),
    ): List<Pair<Int, Int>?> {
        if (paramJsons.isEmpty() || paramJsons.size != outPaths.size) return emptyList()
        return try {
            val eng = ensureEngine(context.applicationContext)
            // v40：每档的 DNG 像素不同，必须各用一份工作副本（v34 共用一份是因为
            // 只有参数在变）。4 档 × 25MB，实测仍在可接受的冲洗耗时内。
            paramJsons.mapIndexed { i, json ->
                val work = prepareGateDng(
                    dngPath, context.cacheDir, json, zoomRatio,
                    gainEvs.getOrElse(i) { 0.0 }
                )
                try {
                    renderRawOne(eng, context, work, asLinearInput(json), outPaths[i], 0, longEdge)
                } catch (e: Throwable) {
                    Log.w(TAG, "ISP 定标小图 $i 渲染失败: ${e.message}")
                    null
                } finally {
                    if (work != dngPath) runCatching { java.io.File(work).delete() }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "ISP 定标批量渲染失败: ${e.message} | dng=$dngPath", e)
            paramJsons.map { null }
        }
    }

    /**
     * 参数预检：逐个候选键单独开一个小 session 试 setParams，返回引擎**不接受**的键。
     *
     * 为什么要有这一步：引擎对未知键 / 越界值都是硬失败，而且**只有真下发才会暴露** ——
     * 真机原文：`'y_filter_shift'=12.000000 is outside [-1.000000, 1.000000]`。
     * 与其等整卷冲出来才发现整档掉回印相，不如冲洗前花一次引擎预热的代价先问清楚，
     * 被拒的键剔掉、剩下的照常下发，下次再加新参数也不会重蹈覆辙。
     *
     * 96x96 小图只为走通校验：不 solve、不导 JPEG。
     */
    fun probeRejectedKeys(
        context: Context,
        baseJson: String,
        entries: List<Pair<String, String>>,
    ): Set<String> {
        if (entries.isEmpty()) return emptySet()
        val appContext = context.applicationContext
        val eng = ensureEngine(appContext)
        val n = 96
        val buf = ByteBuffer.allocateDirect(n * n * 3 * 4).order(ByteOrder.nativeOrder())
        val rejected = LinkedHashSet<String>()
        val body = baseJson.trimEnd().removeSuffix("}")
        for ((key, value) in entries) {
            val probe = "$body,\"$key\":$value}"
            val session = eng.openSession(buf, n, n, 3)
            try {
                session.setParams(probe)
                Log.i(TAG, "参数预检通过: $key=$value")
            } catch (t: Throwable) {
                rejected.add(key)
                Log.w(TAG, "参数预检被拒: $key=$value -> ${t.message}")
            } finally {
                runCatching { session.close() }
            }
        }
        writeProbeReport(appContext, entries, rejected)
        return rejected
    }

    /**
     * 小图**真跑一遍**（setParams → solve → export），返回 null 表示这套参数能出图，
     * 否则返回引擎报错原文。
     *
     * 光做 setParams 预检不够：有些校验发生在渲染期，参数会被爽快收下、冲到 solve 才炸。
     * 真机原文：`overscan: the negative does not match this pipeline's overscan layout`
     * —— 片框的版式匹配就是这样，结果整档掉回兜底、白冲一张。
     *
     * [w]/[h] 要按真实画幅的比例给（比如 4:3 用 96×72），否则测的不是同一件事。
     */
    fun probeRenderOk(context: Context, paramsJson: String, w: Int, h: Int): String? {
        val appContext = context.applicationContext
        return runCatching {
            val eng = ensureEngine(appContext)
            val bmp = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888
            )
            bmp.eraseColor(0xFF808080.toInt())
            val buf = bitmapToFloatRgb(bmp)
            bmp.recycle()
            val out = java.io.File(appContext.cacheDir, "probe_render.jpg")
            val session = eng.openSession(buf, w, h, 3)
            try {
                session.setParams(paramsJson)
                session.solve("both")
                val info = session.exportJpeg(out.absolutePath, 80, 512, "full")
                if (info.size < 2 || info[0] <= 0 || info[1] <= 0) {
                    "exportJpeg 未产出有效尺寸: ${info.joinToString(",")}"
                } else if (!out.exists() || out.length() <= 0L) {
                    "exportJpeg 未写出文件: ${out.absolutePath}"
                } else null
            } finally {
                runCatching { session.close() }
            }
        }.getOrElse { t -> t.message ?: t.toString() }
    }

    /** 预检结果落盘（外部私有目录，release 包也能 adb pull 出来）；工程师模式关着就不写。 */
    private fun writeProbeReport(
        context: Context,
        entries: List<Pair<String, String>>,
        rejected: Set<String>,
    ) {
        // 只给翻出开发旋钮的人落盘：普通用户看不懂，还白占机身存储（logcat 里照样有）
        if (!com.filmcam.app.DevDump.enabled(context)) return
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val f = java.io.File(dir, "spektra_probe.txt")
            f.writeText(
                "entries=" + entries.joinToString { "${it.first}=${it.second}" } + "\n" +
                    "rejected=" + rejected.joinToString() + "\n"
            )
            Log.i(TAG, "参数预检结果: ${f.absolutePath}")
        }.onFailure { Log.w(TAG, "写预检结果失败", it) }
    }

    /**
     * 把引擎解析后的**全量**参数落一份到 filesDir/spektra_params.json。
     *
     * 我们 setParams 只传 11 个扁平字段，引擎内部还有一整棵参数树
     * （扫描仪模糊、黑白校正、片基边框、场景宽容度、对比遮罩……）。
     * 摸清这棵树才能做「印相 / Frontier / Noritsu」三种输出，否则只能靠猜。
     * 每个进程只写一次，不影响冲洗耗时；工程师模式关着就一次都不写。
     */
    private fun dumpParamsOnce(context: Context, session: SpektraLabEngine.Session) {
        if (!com.filmcam.app.DevDump.enabled(context)) return
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
