package com.filmcam.app.develop

import android.util.Log
import androidx.exifinterface.media.ExifInterface
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
 *  - Software → "FilmCam + SpektraLab Engine"
 *  - BodySerialNumber → rollId（胶卷编号）
 *
 * 全部为模拟值，无真实机型信息。saveAttributes 后读回校验并打日志。
 */
object ContaxExif {

    private const val TAG = "ContaxExif"

    /**
     * 给 [file] 写 CONTAX T3 模拟 EXIF + 胶卷元数据。
     * [width]/[height] 为冲洗后 JPEG 的实际像素尺寸。
     * [takenAtMs] 为该帧拍摄时刻。
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
        filmDisplayName: String,
        filmStockId: String,
        printStockId: String,
        rollId: String,
        paramsJson: String,
    ) {
        try {
            val exif = ExifInterface(file.absolutePath)

            // 机型模拟
            exif.setAttribute(ExifInterface.TAG_MAKE, "CONTAX")
            exif.setAttribute(ExifInterface.TAG_MODEL, "T3")
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())

            // 时间
            val ts = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(takenAtMs))
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, ts)
            exif.setAttribute(ExifInterface.TAG_DATETIME, ts)

            // 镜头参数模拟
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "35.0")
            exif.setAttribute(ExifInterface.TAG_F_NUMBER, "2.8")

            // ISO
            if (iso != null && iso > 0) {
                exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, iso.toString())
            }

            // 胶卷信息
            // LensModel → 胶卷显示名（模拟"这卷胶片是什么"）
            exif.setAttribute(ExifInterface.TAG_LENS_MODEL, filmDisplayName)
            // ImageDescription → film_stock / print_stock id（便于复现）
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "film=$filmStockId print=$printStockId")
            // BodySerialNumber → rollId（胶卷编号）
            exif.setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, rollId)
            // Software → 应用 + 引擎标识
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "FilmCam + SpektraLab Engine")
            // UserComment → 冲洗参数 JSON 快照（用 ASCII 前缀确保兼容性）
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "ASCII\n$paramsJson")

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
}
