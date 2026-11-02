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
