package com.filmcam.app.develop

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.filmcam.app.film.CaptureExifFormat
import com.filmcam.app.film.ShotMeta
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CONTAX T3 模拟 EXIF（规格 §6.5）。
 *
 * 字段集合：
 *  - Make=CONTAX, Model=T3
 *  - Orientation=1
 *  - PixelXDimension / PixelYDimension
 *  - DateTimeOriginal（yyyy:MM:dd HH:mm:ss，按帧 takenAt）
 *  - FocalLength=35.0mm, FNumber=2.8
 *  - ISO 有则写
 *  - LensModel → 胶卷显示名（如 "Kodak Portra 400"）
 *  - ImageDescription → 引擎 film_stock / print_stock id（便于复现）
 *  - UserComment → 冲洗参数 JSON 快照（grain/halation/glare/exposure/paper）
 *  - Software → "Relic Cam + SpektraLab Engine"
 *  - BodySerialNumber → rollId（胶卷编号）
 *
 * 全部为模拟值，无真实机型信息。saveAttributes 后读回校验并打日志。
 */
object ContaxExif {

    private const val TAG = "ContaxExif"

    /**
     * 给 [file] 写 CONTAX T3 模拟 EXIF + 胶卷元数据。
     * [width]/[height] 为冲洗后 JPEG 的实际像素尺寸。
     * [takenAtMs] 为该帧拍摄时刻（原照片真正被拍下的那一刻）。
     * [metadataAtMs] 为成片的产生时刻（冲洗完成那一刻），写进 DateTimeOriginal。
     *   两者刻意分开：导入的旧照片拍摄时刻可能是几个月前，若拿它当文件时间，
     *   系统相册会按拍摄时间排序，成片直接跑到历史位置去，用户以为没冲出来
     *   （真机实测：datetaken 被填成 2025-12-28，相册里根本翻不到）。
     *   真正的拍摄日期刻在画面日期戳上（DateStampRenderer 用的是 [takenAtMs]），
     *   同时也写进 UserComment 快照的 `orig_taken_at` 留档。
     * [filmDisplayName] 胶卷显示名（如 "Kodak Portra 400"），写入 LensModel。
     * [filmStockId] 引擎 film stock id（如 "kodak_portra_400"），写入 ImageDescription。
     * [printStockId] 引擎 print stock id（如 "kodak_portra_endura"），写入 ImageDescription。
     * [rollId] 胶卷编号，写入 BodySerialNumber。
     * [paramsJson] 冲洗参数 JSON 快照，写入 UserComment。
     */
    fun write(
        file: File,
        iso: Int?,
        width: Int,
        height: Int,
        takenAtMs: Long,
        metadataAtMs: Long = takenAtMs,
        filmDisplayName: String,
        filmStockId: String,
        printStockId: String,
        rollId: String,
        paramsJson: String,
        shotJson: String = "",
    ) {
        try {
            val exif = ExifInterface(file.absolutePath)
            // 这一帧的实拍参数（老成片没有 shot 快照 → null，只保留原有的模拟字段）
            val shot = ShotMeta.fromJson(shotJson)

            // 机型模拟
            exif.setAttribute(ExifInterface.TAG_MAKE, "CONTAX")
            exif.setAttribute(ExifInterface.TAG_MODEL, "T3")
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())

            // 时间
            val ts = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(metadataAtMs))
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, ts)
            exif.setAttribute(ExifInterface.TAG_DATETIME, ts)

            // 镜头参数：用户拍板「拍摄参数直接上真实的」——写 HAL 上报的真实
            // 焦距 / 光圈，不再拿 T3 的 35mm f/2.8 冒充。老帧没实拍值才回落。
            // ⚠️两个 tag 格式不同：FocalLength 要 "num/den"，FNumber 要小数。
            val realFocal = shot?.focalMm ?: 0f
            val realAperture = shot?.aperture ?: 0f
            exif.setAttribute(
                ExifInterface.TAG_FOCAL_LENGTH,
                if (realFocal > 0f) CaptureExifFormat.focalLengthRational(realFocal) else "35/1"
            )
            exif.setAttribute(
                ExifInterface.TAG_F_NUMBER,
                if (realAperture > 0f) CaptureExifFormat.apertureValue(realAperture) else "2.8"
            )

            // ISO：系统相册读的就是 ISOSpeedRatings(0x8827)，直接写真实传感器 ISO。
            // 模拟胶卷感光度仍在 LensModel / UserComment 里，不占标准 ISO 字段；
            // 只有拿不到实拍值的导入旧帧才回落模拟胶卷 ISO。
            val realIso = shot?.sensorIso ?: 0
            if (realIso > 0) {
                exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, realIso.toString())
            } else if (iso != null && iso > 0) {
                exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, iso.toString())
            }

            // 实拍参数：写进标准 EXIF 字段，任何相册 / EXIF 查看器都能读到。
            // ISOSpeed(0x8833) 是 0x8827 之外的独立 tag，两份都写真实传感器 ISO，
            // 兼容只读新字段的查看器。
            shot?.let { s ->
                if (s.sensorIso > 0) {
                    exif.setAttribute(ExifInterface.TAG_ISO_SPEED, s.sensorIso.toString())
                }
                if (s.sensorShutterSec > 0f) {
                    // ⚠️必须传小数：兼容集合会 Double.parseDouble，"1/10" 会被静默丢弃
                    exif.setAttribute(
                        ExifInterface.TAG_EXPOSURE_TIME,
                        CaptureExifFormat.exposureTimeValue(s.sensorShutterSec)
                    )
                }
                // Flash 标准位只有 bit0=fired，并没有「外置」位；外闪身份
                // （灯名 / MAC / 通道 / 触发帧）放在 UserComment.shot 里，不硬塞自定义值。
                exif.setAttribute(
                    ExifInterface.TAG_FLASH,
                    if (s.flash.equals("OFF", ignoreCase = true)) "0" else "1"
                )
                // EXT 档的拨盘语义是闪光灯细档补偿；常规档用整数 AE 补偿。
                val bias = if (s.flash.equals("EXTERNAL", ignoreCase = true)) {
                    s.flashCompEv
                } else {
                    s.ev.toFloat()
                }
                exif.setAttribute(
                    ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
                    CaptureExifFormat.evRational(bias)
                )
            }

            // 胶卷信息
            // LensModel → 胶卷显示名（模拟"这卷胶片是什么"）
            exif.setAttribute(ExifInterface.TAG_LENS_MODEL, filmDisplayName)
            // ImageDescription → film_stock / print_stock id（便于复现）
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "film=$filmStockId print=$printStockId")
            // BodySerialNumber → rollId（胶卷编号）
            exif.setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, rollId)
            // Software → 应用 + 引擎标识
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Relic Cam + SpektraLab Engine")
            // UserComment → 冲洗参数 + 拍摄参数 JSON 快照（用 ASCII 前缀确保兼容性）。
            // shot 作为嵌套对象并入同一份 JSON：一个 UserComment 装完，旧解析器读不到的键会忽略。
            exif.setAttribute(
                ExifInterface.TAG_USER_COMMENT,
                "ASCII\n" + mergeShot(paramsJson, shotJson, takenAtMs, metadataAtMs)
            )

            exif.saveAttributes()

            // 读回校验
            val check = ExifInterface(file.absolutePath)
            val readLens = check.getAttribute(ExifInterface.TAG_LENS_MODEL)
            val readDesc = check.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
            val readRoll = check.getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER)
            val readSoftware = check.getAttribute(ExifInterface.TAG_SOFTWARE)
            val readComment = check.getAttribute(ExifInterface.TAG_USER_COMMENT)
            Log.d(
                TAG,
                "EXIF 写回校验: Make=${check.getAttribute(ExifInterface.TAG_MAKE)} " +
                    "Model=${check.getAttribute(ExifInterface.TAG_MODEL)} " +
                    "Lens=$readLens " +
                    "Desc=$readDesc " +
                    "W=${check.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION)} " +
                    "H=${check.getAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION)} " +
                    "ISO=${check.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)} " +
                    "SensorISO=${check.getAttribute(ExifInterface.TAG_ISO_SPEED)} " +
                    "ExposureTime=${check.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)} " +
                    "Flash=${check.getAttribute(ExifInterface.TAG_FLASH)} " +
                    "Bias=${check.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)} " +
                    "Focal=${check.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)} " +
                    "FNum=${check.getAttribute(ExifInterface.TAG_F_NUMBER)} " +
                    "Roll=$readRoll " +
                    "Software=$readSoftware " +
                    "Comment=${readComment?.take(80)}"
            )
            // 如果关键字段读回为空，说明 ExifInterface 未正确写入，打警告
            if (readLens.isNullOrBlank()) {
                Log.w(TAG, "LensModel 读回为空！ExifInterface 可能未创建 EXIF IFD")
            }
        } catch (e: Throwable) {
            // 失败不再静默吞掉：rethrow 进 processOneFrame 的 catch → 计入 errorCount → PHASE_ERROR
            Log.e(TAG, "EXIF 写入失败: ${e.message}", e)
            throw e
        }
    }

    /**
     * 把拍摄参数并进冲洗参数 JSON 的 `shot` 键。
     * 成片时间不等于原拍摄时刻时（导入的旧照片），额外记一份 `orig_taken_at` ——
     * 文件时间虽改成冲洗时刻，照片真正是哪天拍的不能丢。
     * 解析失败时原样返回冲洗参数 —— EXIF 是附加信息，不能因为它写坏了整卷冲洗。
     */
    private fun mergeShot(
        paramsJson: String,
        shotJson: String,
        takenAtMs: Long,
        metadataAtMs: Long,
    ): String {
        return runCatching {
            var json = org.json.JSONObject(paramsJson)
            if (shotJson.isNotBlank()) {
                json = json.put("shot", org.json.JSONObject(shotJson))
            }
            if (metadataAtMs != takenAtMs) {
                json = json.put("orig_taken_at", takenAtMs)
            }
            json.toString()
        }.getOrDefault(paramsJson)
    }
}
