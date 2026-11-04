package com.filmcam.app.develop

import java.io.InputStream

/**
 * 导入本地照片时的「探测尺寸 → 降采样解码」。
 *
 * 之所以单独抽出来，是因为这段控制流踩过一次坑：Android 的 `BitmapFactory` 在
 * `inJustDecodeBounds=true` 时**必定返回 null**（尺寸只写进 Options），早先写成
 *
 * ```
 * openStream()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
 * ```
 *
 * 于是每张照片都在探测阶段被判成「读不到」，导入永远 0 张，且日志里什么都看不出来。
 * 现在探测只看尺寸、不看解码返回值，并做成泛型 [T] —— 单测传个字符串当假位图
 * 就能覆盖，不必碰 Android 类。
 */
object PhotoImportDecoder {

    data class Size(val width: Int, val height: Int)

    /** 常见相机 RAW 扩展名（LibRaw 支持面里挑的高频档；按小写比较） */
    val RAW_EXTENSIONS = setOf(
        "dng", "arw", "srf", "sr2",          // Sony / Adobe
        "nef", "nrw",                        // Nikon
        "cr2", "cr3", "crw",                 // Canon
        "raf",                               // Fuji
        "orf",                               // Olympus
        "rw2", "rwl",                        // Panasonic / Leica
        "pef", "ptx",                        // Pentax
        "srw",                               // Samsung
        "raw", "dcr", "kdc", "erf", "mrw", "mef", "mos", "iiq", "3fr", "fff", "x3f",
    )

    /** 文件名是不是 RAW（按扩展名；SAF 对 RAW 的 mime 常常是 octet-stream，扩展名更可靠） */
    fun isRawFileName(displayName: String?): Boolean {
        if (displayName.isNullOrBlank()) return false
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return ext in RAW_EXTENSIONS
    }

    /**
     * mime 是不是 RAW。BitmapFactory 能解的压缩格式（jpeg/png/webp/heic/bmp/gif）
     * 都不是 RAW；只认明确带 dng/raw 的和各家厂商 RAW mime。
     * （注意别在注释里写 image 斜杠星这种字面量——Kotlin 块注释可嵌套，会被当成新注释开头）
     */
    fun isRawMime(mime: String?): Boolean {
        if (mime.isNullOrBlank()) return false
        val m = mime.lowercase()
        if (m == "image/jpeg" || m == "image/png" || m == "image/webp" ||
            m == "image/heic" || m == "image/heif" || m == "image/gif" || m == "image/bmp"
        ) return false
        return m.contains("dng") || m.contains("raw") || m.contains("arw") ||
            m.contains("nef") || m.contains("cr2") || m.contains("cr3") || m.contains("raf") ||
            m.contains("orf") || m.contains("rw2") || m.contains("pef") || m.contains("srw")
    }

    /**
     * 按像素上限算 `inSampleSize`（必须是 2 的幂）。
     * 尺寸非法时给 1，交给下游判断，不在这里抛。
     */
    fun sampleSizeFor(width: Int, height: Int, maxPixels: Long): Int {
        if (width <= 0 || height <= 0 || maxPixels <= 0) return 1
        var sample = 1
        while ((width / sample).toLong() * (height / sample).toLong() > maxPixels) sample *= 2
        return sample
    }

    /**
     * 先探测原图尺寸，再按降采样解码。任何一步失败返回 null。
     *
     * @param openStream 每次调用都要开一条**新的**流：探测和解码各读一次。
     * @param probe 只负责把尺寸读出来。成败由返回的 [Size] 是否为 null 决定，
     *   不看底层解码器的返回值 —— 那正是之前踩坑的地方。
     * @param decode 按给定 `inSampleSize` 真正解码。
     */
    fun <T> decodeSampled(
        openStream: () -> InputStream?,
        probe: (InputStream) -> Size?,
        decode: (InputStream, Int) -> T?,
        maxPixels: Long,
    ): T? {
        val size = openStream()?.use(probe) ?: return null
        val sample = sampleSizeFor(size.width, size.height, maxPixels)
        return openStream()?.use { decode(it, sample) }
    }
}
