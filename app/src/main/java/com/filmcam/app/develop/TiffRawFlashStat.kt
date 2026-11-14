package com.filmcam.app.develop

import java.io.File
import java.io.RandomAccessFile

/**
 * v81：内置直闪帧「RAW 底片是否吃光」的机型无关判定（11-14 全链路复盘）。
 *
 * 背景（S25U 真机定量，tmp/flash4_raw_1114）：这台 HAL 在闪光拍照中
 * RAW_SENSOR 流不吃光 —— 直闪帧 DNG 净信号仅 ≈30/1023（主体区同样贴黑），
 * 同快门 HAL JPEG 却正常吃光。把这种黑底片交 RAW 会话冲洗，引擎链路会
 * 从黑底上硬拉亮度：整体沉、加 EV 主体先爆背景仍在地板。但**别的机型
 * RAW 流多半正常吃光**，无条件跳 RAW 会把那些机器的直闪画质降级 —— 所以
 * 跳过与否由本工具读 DNG 原始像素判定，判定结果按设备缓存（HAL 行为是
 * 设备级属性，帧间稳定）。
 *
 * 为什么读原始 Bayer 而不是引擎渲图：引擎的 auto_exposure/印相会把黑底
 * 归一化拉亮，渲图亮度会骗人（S25U 黑底片 RAW 通路成片 mean 67~71）。
 * 只有 DNG 线性 Bayer 相对黑电平的净信号才反映「光进没进帧」。
 *
 * 解析口径与 tmp/flash4_raw_1114/dng_stat.py 一致（该脚本已用真机
 * 底片验证）：DngCreator 写的 DNG = 无压缩 16bit、每行一 strip、
 * BlackLevel(50714, RATIONAL)、WhiteLevel(50717)。只认这一种结构，
 * 任何不合预期（压缩/多采样/多字节采样/Strip 结构不齐）返回 null ——
 * 调用侧按「不跳 RAW」处理，宁可不修也不误降级。
 */
object TiffRawFlashStat {

    /** 判定为「闪光黑底片」的净信号门限（占白电平比例）。S25U 黑帧 2.9%~3.4%，
     *  正常吃光的闪光帧主体区至少是它的几倍；中间留缓冲。 */
    const val BLIND_NET_RATIO = 0.05f

    // TIFF tags（DngCreator 结构）
    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_BLACK_LEVEL = 50714
    private const val TAG_WHITE_LEVEL = 50717

    /**
     * 中心区平均净信号占白电平比例。中心 128 行 × 每行中心 128 像素，
     * RandomAccessFile 顺序小块读，~10ms 级。
     * @return null = 结构不认识 / 读取失败（调用侧不得跳 RAW）
     */
    fun netSignalRatio(dng: File): Float? = runCatching {
        RandomAccessFile(dng, "r").use { raf ->
            val header = ByteArray(8)
            if (raf.read(header) != 8) return@runCatching null
            val little = when (String(header, 0, 2, Charsets.US_ASCII)) {
                "II" -> true
                "MM" -> false
                else -> return@runCatching null
            }
            fun u16(o: Int) = if (little) {
                (header[o].toInt() and 0xFF) or ((header[o + 1].toInt() and 0xFF) shl 8)
            } else {
                ((header[o].toInt() and 0xFF) shl 8) or (header[o + 1].toInt() and 0xFF)
            }
            fun u32(b: ByteArray, o: Int): Long = if (little) {
                (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
                    ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
            } else {
                ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
                    ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
            }
            // magic 42 在字节 2-3；IFD0 偏移在 4-7（放 header 里重用 u32）
            if (u16(2) != 42) return@runCatching null
            ifdStats(raf, u32(header, 4), little)?.second
        }
    }.getOrNull()

    /** 返回 (blackLevel, 净信号比例)；结构不认识返回 null。 */
    private fun ifdStats(
        raf: RandomAccessFile,
        ifdOffset: Long,
        little: Boolean,
    ): Pair<Int, Float>? {
        raf.seek(ifdOffset)
        val entryCountBuf = ByteArray(2)
        raf.readFully(entryCountBuf)
        val n = if (little) {
            (entryCountBuf[0].toInt() and 0xFF) or ((entryCountBuf[1].toInt() and 0xFF) shl 8)
        } else {
            ((entryCountBuf[0].toInt() and 0xFF) shl 8) or (entryCountBuf[1].toInt() and 0xFF)
        }
        if (n <= 0 || n > 200) return null
        val entries = ByteArray(n * 12)
        raf.readFully(entries)

        var width = -1; var height = -1; var bps = -1; var spp = 1
        var rowsPerStrip = -1L
        var blackLevel = -1L; var whiteLevel = -1L
        var stripOffsets: LongArray? = null

        fun entryValue(entryOff: Int): Long {
            // 类型 3=SHORT、4=LONG：单值内联在 value 字段（entryOff+8）
            val type = u16At(entries, entryOff + 2, little)
            return when (type) {
                3 -> u16At(entries, entryOff + 8, little).toLong()
                4 -> u32At(entries, entryOff + 8, little)
                else -> -1L
            }
        }

        var i = 0
        while (i < n) {
            val e = i * 12
            val tag = u16At(entries, e, little)
            val type = u16At(entries, e + 2, little)
            val count = u32At(entries, e + 4, little)
            when (tag) {
                TAG_IMAGE_WIDTH -> width = entryValue(e).toInt()
                TAG_IMAGE_LENGTH -> height = entryValue(e).toInt()
                TAG_BITS_PER_SAMPLE -> bps = entryValue(e).toInt()
                TAG_SAMPLES_PER_PIXEL -> spp = entryValue(e).toInt()
                TAG_ROWS_PER_STRIP -> rowsPerStrip = entryValue(e)
                TAG_STRIP_OFFSETS -> stripOffsets = longArray(entries, e, type, count, raf, little)
                TAG_BLACK_LEVEL -> blackLevel = rationalOrScalar(entries, e, type, count, raf, little)
                TAG_WHITE_LEVEL -> whiteLevel = entryValue(e)
            }
            i++
        }
        // 只认 DngCreator 的结构：无压缩由 strip 字节数推；16bit 单采样、每行一 strip
        if (width <= 0 || height <= 0 || bps != 16 || spp != 1) return null
        if (rowsPerStrip != 1L || stripOffsets == null || stripOffsets!!.size < height) return null
        if (blackLevel < 0 || whiteLevel <= blackLevel) return null

        // 中心 128 行 × 每行中心 128 像素的 16bit 采样
        val rows = minOf(128, height)
        val cols = minOf(128, width)
        val rowStart = (height - rows) / 2
        val colStart = (width - cols) / 2
        var sum = 0.0
        val rowBuf = ByteArray(cols * 2)
        var sampled = 0
        for (r in rowStart until rowStart + rows) {
            raf.seek(stripOffsets!![r] + colStart * 2L)
            if (raf.read(rowBuf) != rowBuf.size) return null
            for (c in 0 until cols) {
                val v = if (little) {
                    (rowBuf[c * 2].toInt() and 0xFF) or ((rowBuf[c * 2 + 1].toInt() and 0xFF) shl 8)
                } else {
                    ((rowBuf[c * 2].toInt() and 0xFF) shl 8) or (rowBuf[c * 2 + 1].toInt() and 0xFF)
                }
                sum += v
                sampled++
            }
        }
        if (sampled == 0) return null
        val mean = sum / sampled
        val net = mean - blackLevel
        return Pair(blackLevel.toInt(), (net / (whiteLevel - blackLevel).toDouble()).toFloat())
    }

    private fun u16At(b: ByteArray, o: Int, little: Boolean): Int =
        if (little) {
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        } else {
            ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        }

    private fun u32At(b: ByteArray, o: Int, little: Boolean): Long =
        if (little) {
            (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
                ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
        } else {
            ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
                ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
        }

    /** LONG/SHORT 数组：count>1 时值区在外部，先读指针再批量读。 */
    private fun longArray(
        entries: ByteArray, e: Int, type: Int, count: Long,
        raf: RandomAccessFile, little: Boolean,
    ): LongArray? = runCatching {
        if (count <= 0 || count > 200_000) return null
        val elemSize = when (type) {
            3 -> 2L
            4 -> 4L
            else -> return null
        }
        val out = LongArray(count.toInt())
        if (count * elemSize <= 4) {
            for (j in 0 until count.toInt()) {
                out[j] = if (type == 3) u16At(entries, e + 8 + (j * 2).toInt(), little).toLong()
                else u32At(entries, e + 8 + (j * 4).toInt(), little)
            }
        } else {
            val extOff = u32At(entries, e + 8, little)
            val buf = ByteArray((count * elemSize).toInt())
            raf.seek(extOff)
            if (raf.read(buf) != buf.size) return null
            for (j in 0 until count.toInt()) {
                out[j] = if (type == 3) u16At(buf, (j * 2).toInt(), little).toLong()
                else u32At(buf, (j * 4).toInt(), little)
            }
        }
        out
    }.getOrNull()

    /** BlackLevel：RATIONAL[4]（DngCreator CFA 写法，真机实测 6400/100=64.0）
     *  必须除以分母——只取分子会把 64 当成 6400、大于白电平被判成异常结构。 */
    private fun rationalOrScalar(
        entries: ByteArray, e: Int, type: Int, count: Long,
        raf: RandomAccessFile, little: Boolean,
    ): Long = runCatching {
        when (type) {
            5 -> {
                if (count <= 0 || count > 8) return -1
                val off = if (count > 1) u32At(entries, e + 8, little) else e + 8L
                val buf = ByteArray(8)
                raf.seek(off)
                if (raf.read(buf) != 8) return -1
                val numerator = u32At(buf, 0, little)
                val denominator = u32At(buf, 4, little)
                if (denominator <= 0) -1L else numerator / denominator
            }
            3 -> u16At(entries, e + 8, little).toLong()
            4 -> u32At(entries, e + 8, little)
            else -> -1
        }
    }.getOrDefault(-1L)
}
