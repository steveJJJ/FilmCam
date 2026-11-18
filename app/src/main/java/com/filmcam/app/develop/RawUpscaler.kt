package com.filmcam.app.develop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import app.spektralab.engine.SpektraProcessor
import com.filmcam.app.film.ShotMeta

/**
 * RAW 成片「裁后放大」补偿（v2 尺寸问题）。
 *
 * 背景（真机 11-18 定量）：为了对齐 Contax T3 的 35mm 等效视角，RAW 传感器全幅
 * 4080×3060 在 prepareGateDng 里被 zoomCrop 中心裁到 2596×1947（1/1.57），
 * 只剩 5MP。JPEG 帧是 HAL 按同样比例裁好再输出，所以看起来"RAW 小、JPEG 大"。
 * 引擎没有放大参数（openRawSession 只有 maxPixels/maxLongEdge 缩小），只能在
 * App 层把裁后的图按同一倍率放大回去，让成片分辨率回到接近传感器原始尺寸。
 *
 * 代价（已与用户确认）：放大不产生真实细节，放大看会略软；这是"尺寸数字回到位"
 * 的取舍，构图/视角完全不变，成像链路/色彩不动。倍率精确 = effectiveZoomRatio，
 * 于是 2596×1.5715≈4080、1947×1.5715≈3060，正好补回原始尺寸。
 *
 * 纯计算的 [targetSize] 可在 JVM 单测；缩放用 Canvas+Matrix+FILTER（等效双三次，
 * Android 平台高质量路径）。只放 RAW 帧，JPEG 帧 zoom=0 时 [targetSize] 返回原尺寸。
 */
object RawUpscaler {
    private const val TAG = "RawUpscaler"
    /** 放大后长边硬上限：防止异常 zoom 算出超大尺寸 OOM（RAW 源长边约 4080）。 */
    private const val MAX_LONG_EDGE = 4608

    /**
     * 计算放大目标尺寸。倍率 = effectiveZoomRatio(shot.zoom)；JPEG 帧/无裁切返回原尺寸。
     * 纯函数、无 android 依赖，可 JVM 单测。
     */
    fun targetSize(w: Int, h: Int, zoomRatio: Float): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return w to h
        val ratio = SpektraProcessor.effectiveZoomRatio(zoomRatio)
        // ratio 越接近 1 = 没裁切（JPEG 帧 zoom=0 时 effectiveZoomRatio 回落默认 1.57，
        // 所以调用方必须只对 RAW 帧调用本函数；这里额外用 zoom 判定放在调用方）。
        var tw = (w * ratio).toInt()
        var th = (h * ratio).toInt()
        val longEdge = maxOf(tw, th)
        if (longEdge > MAX_LONG_EDGE) {
            val k = MAX_LONG_EDGE.toFloat() / longEdge
            tw = (tw * k).toInt()
            th = (th * k).toInt()
        }
        return tw.coerceAtLeast(1) to th.coerceAtLeast(1)
    }

    /** 是否需要对这一帧放大：仅 RAW 帧（shotJson 有 zoom 且 >1）且确有裁切。 */
    fun shouldUpscale(shotJson: String?): Boolean {
        val zoom = runCatching { ShotMeta.fromJson(shotJson)?.zoom }.getOrNull() ?: 0f
        return zoom > 1f
    }

    /**
     * 放大一个 Bitmap（调用方负责 recycle 旧图）。返回新图；无需放大/失败时返回原图。
     */
    fun upscale(src: Bitmap, shotJson: String?): Bitmap {
        if (!shouldUpscale(shotJson)) return src
        val zoom = ShotMeta.fromJson(shotJson)?.zoom ?: 0f
        val (tw, th) = targetSize(src.width, src.height, zoom)
        if (tw == src.width && th == src.height) return src
        return try {
            val out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            val m = Matrix().apply {
                setScale(tw.toFloat() / src.width, th.toFloat() / src.height)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            Canvas(out).drawBitmap(src, m, paint)
            Log.i(TAG, "RAW 裁后放大 ${src.width}x${src.height} → ${tw}x${th}（zoom=$zoom，补回传感器尺寸）")
            src.recycle()
            out
        } catch (e: Throwable) {
            Log.w(TAG, "RAW 放大失败，保持原尺寸: ${e.message}")
            src
        }
    }

    /** 放大一个 JPEG 文件（预渲染/回落路径用：文件在磁盘，旋转后放大再回写）。 */
    fun upscaleFile(file: java.io.File, shotJson: String?): Boolean {
        if (!shouldUpscale(shotJson)) return false
        val opts = BitmapFactoryOptions()
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts) ?: return false
        var out = bmp
        try {
            out = upscale(bmp, shotJson)
            if (out === bmp) return false
            java.io.FileOutputStream(file).use { os ->
                out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, os)
            }
            return true
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
            if (out !== bmp && !out.isRecycled) out.recycle()
        }
    }

    private fun BitmapFactoryOptions() = android.graphics.BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inMutable = true
    }
}
