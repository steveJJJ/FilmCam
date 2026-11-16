package com.filmcam.app.film

import java.util.Locale
import kotlin.math.round

/**
 * 实拍参数 ↔ 标准 EXIF 字段的纯 Kotlin 转换（零 Android 依赖，可 JVM 单测）。
 *
 * 写入方 [com.filmcam.app.develop.ContaxExif] 与读取方 PhotoViewerActivity
 * 共用同一套口径，避免「EXIF 里写的是 1/10、面板显示成别的」这类漂移。
 */
object CaptureExifFormat {

    /**
     * ExposureTime / FNumber 等 tag 在 ExifInterface 的 sTagSetForCompatibility 里：
     * setAttribute 会先 `Double.parseDouble(value)` 再转 Rational，传 "1/10" 会抛
     * NumberFormatException 并被静默丢弃（真机成片里就是因此完全没有快门）。
     * 所以这几个 tag **必须传小数字符串**，由库自己转 rational。
     */
    fun exposureTimeValue(sec: Float): String =
        if (sec <= 0f) "0" else String.format(Locale.US, "%.6f", sec)

    /** FNumber 同属兼容集合，用小数字符串。 */
    fun apertureValue(aperture: Float): String =
        if (aperture <= 0f) "0" else String.format(Locale.US, "%.2f", aperture)

    /**
     * FocalLength / ExposureBiasValue **不在**兼容集合：setAttribute 走 `split("/")`
     * 分支，必须传 "num/den"；传小数（旧版 35.0）会因取 parts[1] 越界被吞掉。
     */
    fun focalLengthRational(mm: Float): String {
        if (mm <= 0f) return "0/1"
        return "${round(mm * 10f).toLong()}/10"
    }

    /** EV → SRATIONAL 字符串：0.5 一档正好用 x/2 无损表示。 */
    fun evRational(ev: Float): String = "${round(ev * 2f).toLong()}/2"

    /**
     * 把 getAttribute 读回的 "num/den" / 小数统一成不带单位的十进制字符串。
     * FocalLength / FNumber 是 URATIONAL，库可能返回 "43/10" 或 "28000/10000"，
     * 直接拼进面板会显示成 "43/10mm"，必须先归约。
     */
    fun decimal(raw: String?): String {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return ""
        val d = if (v.contains("/")) {
            val n = v.substringBefore("/").toDoubleOrNull()
            val den = v.substringAfter("/").toDoubleOrNull()
            if (n != null && den != null && den != 0.0) n / den else null
        } else {
            v.toDoubleOrNull()
        }
        return d?.let { String.format(Locale.US, "%.2f", it).trimEnd('0').trimEnd('.') } ?: v
    }

    /** EXIF 读回可能是 "1/10" 或 "0.1"，统一刻成机身可读的 1/N / N.Ns。 */
    fun shutterLabel(raw: String): String {
        val v = raw.trim()
        val sec = if (v.contains("/")) {
            val n = v.substringBefore("/").toFloatOrNull()
            val d = v.substringAfter("/").toFloatOrNull()
            if (n != null && d != null && d != 0f) n / d else null
        } else {
            v.toFloatOrNull()
        }
        return when {
            sec == null || sec <= 0f -> "${v}s"
            sec >= 1f -> String.format(Locale.US, "%.1fs", sec)
            else -> "1/" + round(1f / sec).toInt()
        }
    }

    /** 档案里存的是完整 UUID，铭牌上只刻 4 位短码（0000ffe1-… → FFE1）。 */
    fun shortUuid(raw: String): String {
        val s = raw.trim().removePrefix("0x")
        val short = Regex("^0000([0-9a-fA-F]{4})-0000-1000-8000-00805f9b34fb$")
            .find(s)?.groupValues?.get(1)
        return (short ?: s.take(8)).uppercase()
    }
}
