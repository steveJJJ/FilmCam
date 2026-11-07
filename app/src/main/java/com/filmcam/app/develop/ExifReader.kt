package com.filmcam.app.develop

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface

/**
 * 成片 EXIF 读取器：把成片上的胶卷元数据解析成可展示的结构。
 *
 * 数据来源（写入方见 [ContaxExif]）：
 *  - LensModel → 胶卷显示名（如 "Kodak Portra 400"）
 *  - ImageDescription → "film=<filmStockId> print=<printStockId>"
 *  - BodySerialNumber → rollId（胶卷编号）
 *  - UserComment → 冲洗参数 JSON 快照
 *  - Software → "FilmCam + SpektraLab Engine"
 */
object ExifReader {

    private const val TAG = "ExifReader"

    /** 从 EXIF 解析出的成片元数据。 */
    data class FilmInfo(
        val displayName: String,       // 胶卷显示名
        val filmStockId: String,       // 引擎 film stock id
        val printStockId: String,      // 引擎 print stock id
        val rollId: String,            // 胶卷编号
        val params: DevParams,         // 冲洗参数
        val make: String,              // 相机厂商（CONTAX）
        val model: String,             // 机型（T3）
        val lens: String,              // LensModel
        val focalLength: String,       // 焦距
        val fNumber: String,           // 光圈
        val iso: String,               // 感光度
        val dateTimeOriginal: String,  // 拍摄时间
        val dimensions: String,        // 像素尺寸
        val software: String,          // 软件标识
        val shot: com.filmcam.app.film.ShotMeta? = null,  // 拍摄参数（闪光/白平衡/EV/快门/失焦）
    )

    /** 冲洗参数（从 UserComment 的 JSON 解析）。 */
    data class DevParams(
        /**
         * 输入层曝光补偿（EV，正 = 更亮）。v46 起记 `exposure_ev`；
         * 旧成片没有这个字段（那时曝光写在 `print_exposure` 上），为 null 就不显示。
         */
        val exposureEv: Float?,
        val grainAmount: Float?,
        val halationAmount: Float?,
        val glareAmount: Float?,
        val filmFormatMm: Float?,
        /** 成片出口：印相 / Frontier / Noritsu（老照片没有该字段时为 null，按印相处理） */
        val outputStyle: app.spektralab.engine.FilmStockMapper.OutputStyle? = null,
    )

    /**
     * 读取 uri 指向的成片 EXIF，解析成 [FilmInfo]。
     * 读取失败返回 null（调用方决定降级策略）。
     */
    fun read(context: Context, uri: Uri): FilmInfo? {
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: run {
                    Log.w(TAG, "openInputStream 返回 null: $uri")
                    return null
                }
            val exif = stream.use { ExifInterface(it) }

            // 诊断：打印所有关键字段
            val lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL).orEmpty()
            val desc = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).orEmpty()
            val roll = exif.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER).orEmpty()
            val make = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty()
            val model = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty()
            Log.d(TAG, "读取 EXIF: uri=$uri Make=$make Model=$model Lens=$lens Desc=$desc Roll=$roll")

            val filmId = desc.substringAfter("film=", "").substringBefore(" print=").trim()
            val printId = desc.substringAfter("print=", "").trim()

            FilmInfo(
                displayName = lens,
                filmStockId = filmId,
                printStockId = printId,
                rollId = roll,
                params = parseParams(exif.getAttribute(ExifInterface.TAG_USER_COMMENT)),
                make = make,
                model = model,
                lens = lens,
                focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH).orEmpty(),
                fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER).orEmpty(),
                iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS).orEmpty(),
                dateTimeOriginal = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).orEmpty(),
                dimensions = buildString {
                    val w = exif.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION).orEmpty()
                    val h = exif.getAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION).orEmpty()
                    if (w.isNotEmpty() && h.isNotEmpty()) "$w × $h" else ""
                },
                software = exif.getAttribute(ExifInterface.TAG_SOFTWARE).orEmpty(),
                shot = parseShot(exif.getAttribute(ExifInterface.TAG_USER_COMMENT)),
            )
        } catch (e: Throwable) {
            Log.w(TAG, "读取 EXIF 失败: ${e.message}")
            null
        }
    }

    /**
     * 解析 UserComment 里的冲洗参数 JSON。
     *
     * AndroidX ExifInterface 的 getAttribute(TAG_USER_COMMENT) 会对带字符集前缀
     * （"ASCII\0" / "UNICODE\0"）的值做解码，返回纯 JSON；但部分设备/写入器
     * 可能保留前缀，故这里做兜底剥离。
     */
    private fun parseParams(userComment: String?): DevParams {
        val obj = parseJson(userComment) ?: return DevParams(null, null, null, null, null)
        return try {
            DevParams(
                exposureEv = obj.optDouble(
                    app.spektralab.engine.FilmStockMapper.MARK_EXPOSURE_EV, Double.NaN
                ).takeIf { !it.isNaN() }?.toFloat(),
                grainAmount = obj.optDouble("grain_amount", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                halationAmount = obj.optDouble("halation_amount", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                glareAmount = obj.optDouble("glare_amount", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                filmFormatMm = obj.optDouble("film_format_mm", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                outputStyle = obj.optString("output_style", "")
                    .takeIf { it.isNotBlank() }
                    ?.let { app.spektralab.engine.FilmStockMapper.OutputStyle.fromWire(it) },
            )
        } catch (e: Throwable) {
            DevParams(null, null, null, null, null)
        }
    }

    /** 拍摄参数：UserComment 里的 `shot` 嵌套对象；老照片没有就是 null。 */
    private fun parseShot(userComment: String?): com.filmcam.app.film.ShotMeta? {
        val obj = parseJson(userComment) ?: return null
        val shot = obj.optJSONObject("shot") ?: return null
        return com.filmcam.app.film.ShotMeta.fromJson(shot.toString())
    }

    /** 把 UserComment 还原成 JSONObject（剥离字符集前缀与 NUL 字节，兼容各写入器）。 */
    private fun parseJson(userComment: String?): org.json.JSONObject? {
        if (userComment.isNullOrBlank()) return null
        var json = userComment.trim()
        json = json.substringAfter('\n', json)
        json = json.removePrefix("ASCII").removePrefix("UNICODE")
        json = json.trim().trimStart('\u0000').trim()
        if (!json.startsWith("{")) return null
        return try {
            org.json.JSONObject(json)
        } catch (_: Throwable) {
            null
        }
    }
}
