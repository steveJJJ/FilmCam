package com.filmcam.app.develop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 导出验真（v2 方案，移植自同引擎参照 App SpektraLab-Android v1.6.2m）：
 *  - 灰度归一化互相关（NCC）：导出图 vs 预期画布，正常 ≈0.95-0.97，花屏 ≈0；ncc<0.55 判花屏。
 *  - X/Y 对比度比：判拉伸/花屏（相邻行/列灰度差之比，正常 0.35~3.0）。
 *
 * 纯算法核心（数组入参）在 object [ExportVerifier]，无 android 依赖部分可 JVM 单测；
 * Android Bitmap 适配层在 [BitmapVerifierAdapter]。
 */
object ExportVerifier {

    const val NCC_MIN = 0.55
    const val RATIO_MIN = 0.35
    const val RATIO_MAX = 3.0

    /**
     * 灰度均值与去均值序列的 NCC。
     * @param a 导出图的灰度网格（0..255，长度 n）
     * @param b 预期画布的灰度网格（同长度）
     * @return -1..1；n<=0 / 方差为 0 返回 -1
     */
    fun ncc(a: DoubleArray, b: DoubleArray): Double {
        val n = minOf(a.size, b.size)
        if (n <= 0) return -1.0
        var ma = 0.0
        var mb = 0.0
        for (i in 0 until n) { ma += a[i]; mb += b[i] }
        ma /= n; mb /= n
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until n) {
            val va = a[i] - ma
            val vb = b[i] - mb
            num += va * vb; da += va * va; db += vb * vb
        }
        if (da <= 1e-9 || db <= 1e-9) return -1.0
        return num / sqrt(da * db)
    }

    /** 是否花屏（NCC 判据）。 */
    fun isCorruptByNcc(score: Double): Boolean = score < NCC_MIN

    /**
     * X/Y 对比度比判拉伸。返回 null=正常，非 null=异常描述。
     * @param gray 宽 w 高 h 的灰度图（行优先，长度 >= w*h）
     */
    fun contrastRatioVerdict(gray: IntArray, w: Int, h: Int): String? {
        if (w < 64 || h < 64 || gray.size < w * h) return null
        var sx = 0L; var nx = 0L
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w - 1) {
                sx += abs(gray[row + x] - gray[row + x + 1]); nx++
            }
        }
        var sy = 0L; var ny = 0L
        for (y in 0 until h - 1) {
            val row = y * w
            for (x in 0 until w) {
                sy += abs(gray[row + x] - gray[row + w + x]); ny++
            }
        }
        if (nx == 0L || ny == 0L) return null
        val dx = sx.toDouble() / nx
        val dy = sy.toDouble() / ny
        if (dy <= 0.0) return null
        val ratio = dx / dy
        return if (ratio < RATIO_MIN || ratio > RATIO_MAX) {
            String.format(java.util.Locale.US, "X/Y contrast %.2f (X %.1f, Y %.1f) at %dx%d", ratio, dx, dy, w, h)
        } else null
    }

    /** ARGB -> 灰度（与参照 App 的 (R+G+B)/3 一致）。 */
    fun argbToGray(argb: Int): Int =
        (((argb shr 16) and 255) + ((argb shr 8) and 255) + (argb and 255)) / 3
}

/** Android Bitmap 适配：采样文件为灰度网格跑 NCC，解码文件跑 X/Y。 */
object BitmapVerifierAdapter {

    private const val GW = 48

    fun nccOf(path: String, expected: Bitmap): Double = runCatching {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return -1.0
        var sample = 1
        while (max(probe.outWidth, probe.outHeight) / (sample * 2) >= 200) sample *= 2
        val opt = BitmapFactory.Options().apply { inSampleSize = sample }
        val bm = BitmapFactory.decodeFile(path, opt) ?: return -1.0
        val gh = max(8, (GW.toFloat() * bm.height / bm.width).roundToInt())
        val a = Bitmap.createScaledBitmap(bm, GW, gh, true)
        val b = Bitmap.createScaledBitmap(expected, GW, gh, true)
        val ga = DoubleArray(GW * gh)
        val gb = DoubleArray(GW * gh)
        val pa = IntArray(GW * gh)
        val pb = IntArray(GW * gh)
        a.getPixels(pa, 0, GW, 0, 0, GW, gh)
        b.getPixels(pb, 0, GW, 0, 0, GW, gh)
        for (i in pa.indices) {
            ga[i] = ExportVerifier.argbToGray(pa[i]).toDouble()
            gb[i] = ExportVerifier.argbToGray(pb[i]).toDouble()
        }
        ExportVerifier.ncc(ga, gb)
    }.getOrElse { -1.0 }

    fun contrastVerdict(path: String): String? = runCatching {
        val opt = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bm = BitmapFactory.decodeFile(path, opt) ?: return "decode failed"
        val w = bm.width; val h = bm.height
        if (w < 64 || h < 64) return null
        val px = IntArray(w * h)
        bm.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h) { ExportVerifier.argbToGray(px[it]) }
        ExportVerifier.contrastRatioVerdict(gray, w, h)
    }.getOrElse { null }
}
