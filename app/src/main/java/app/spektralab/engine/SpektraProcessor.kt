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
    ): ProcessResult {
        return try {
            val eng = ensureEngine(context)
            val maxPx = eng.maxFramePixels()
            // 主参数 + 兜底链（去重，与 processFrame 同规则）
            val chain = ArrayList<String>(fallbackParamsJsons.size + 1)
            chain.add(paramsJson)
            for (p in fallbackParamsJsons) if (p != paramsJson && p !in chain) chain.add(p)
            // 片门预裁：改写缓存副本的 ActiveArea（见 prepareGateDng），原底片不动
            val workPath = prepareGateDng(dngPath, context.cacheDir, paramsJson)

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
            Log.i(
                TAG,
                "RAW 底片 ${java.io.File(dngPath).name} 源尺寸=${src?.joinToString("x")} 出图 ${dims.first}x${dims.second}"
            )
            ProcessResult(true, dims.first, dims.second, false, "", fellBack)
        } catch (e: Throwable) {
            Log.e(TAG, "processRawFrame 失败: ${e.message} | dng=$dngPath out=$outJpegPath", e)
            ProcessResult(false, 0, 0, false, e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------- P3 RAW 片门预裁（ActiveArea 改写方案） ----------

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
    internal fun prepareGateDng(dngPath: String, cacheDir: java.io.File, paramsJson: String): String {
        val aspectL = gateAspectFromParams(paramsJson) ?: return dngPath
        val geo = runCatching {
            java.io.File(dngPath).inputStream().use { ins ->
                val head = ByteArray(8)
                if (ins.read(head) == 8) tiffGeometry(ins, head) else null
            }
        }.getOrNull() ?: return dngPath
        // 竖拍走竖置片门（画面在底片上本来就是竖的）：片门比取倒数
        val aspect = if (geo.height > geo.width) 1f / aspectL else aspectL
        val crop = FilmStockMapper.gateCrop(geo.width, geo.height, aspect)
        if (!crop.cropped) return dngPath
        val off = geo.activeAreaOffset ?: run {
            Log.w(TAG, "DNG 无 ActiveArea 标签，跳过片门预裁: $dngPath")
            return dngPath
        }
        val a = geo.activeArea!!   // activeAreaOffset 非 null 时必有
        val work = java.io.File(cacheDir, "gate_" + java.io.File(dngPath).name)
        val ok = runCatching {
            java.io.File(dngPath).inputStream().use { ins -> work.outputStream().use { ins.copyTo(it) } }
            java.io.RandomAccessFile(work, "rw").use { raf ->
                raf.seek(off.toLong())
                val order = java.nio.ByteOrder.LITTLE_ENDIAN.takeIf { geo.littleEndian }
                    ?: java.nio.ByteOrder.BIG_ENDIAN
                val bb = java.nio.ByteBuffer.allocate(16).order(order)
                bb.putInt(a[0] + crop.y)             // top
                bb.putInt(a[1] + crop.x)             // left
                bb.putInt(a[0] + crop.y + crop.h)    // bottom
                bb.putInt(a[1] + crop.x + crop.w)    // right
                raf.write(bb.array())
            }
            true
        }.getOrDefault(false)
        if (!ok) {
            runCatching { work.delete() }
            Log.w(TAG, "片门预裁改写失败，用原底片冲: $dngPath")
            return dngPath
        }
        Log.i(TAG, "RAW 片门预裁：源 ${geo.width}x${geo.height} → $aspect（已改写 ActiveArea）")
        return work.absolutePath
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
            renderRawOne(eng, context, dngPath, paramsJson, outJpegPath, 0, maxLongEdge)
        } catch (e: Throwable) {
            Log.e(TAG, "renderRawPreview 失败: ${e.message} | dng=$dngPath", e)
            null
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
