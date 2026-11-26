package com.filmcam.app.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import app.spektralab.engine.DateImprint
import app.spektralab.engine.FilmStockMapper
import app.spektralab.engine.SpektraProcessor
import com.filmcam.app.film.MotherFilmRegistry
import com.filmcam.app.film.MotherNegativeStore
import com.filmcam.app.film.ShotMeta
import com.filmcam.app.film.UndevelopedFilmStore
import java.io.File

/**
 * 编辑期的「从原始输入重渲」核心：导出与编辑页预览共用同一份实现。
 *
 * 🔴为什么必须共用：日期戳现在由**引擎**印（`date_imprint_*`），预览与成片如果各走
 * 一套渲染，字高/落位/颜色/颗粒就会对不上 —— 而用户恰恰是因为「预览和成片不一样、
 * 而且都不好看」才连着两轮返工。参数构建、gate 裁切、JpegNormalizer 复刻、RAW 曝光
 * 口径这四件事任何一处漂移都会露馅，所以只留一份。
 */
object EditRenderCore {
    private const val TAG = "EditRenderCore"

    /**
     * 这一帧是不是竖拍。引擎按底片画幅印日期，竖拍要把 corner 换成视觉右下角
     * （见 [DateImprint.t3Style]），所以调用方构造参数前必须先问这个。
     * 优先读已按片门裁好的全分辨率母片；没有时退回原始帧的方向标签。
     */
    fun isPortrait(rollId: String, index: Int): Boolean {
        val f = MotherNegativeStore.fullFile(rollId, index)
        if (f.exists()) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            if (o.outWidth > 0 && o.outHeight > 0) return o.outHeight > o.outWidth
        }
        return UndevelopedFilmStore.frameOrientation(rollId, index).let { it == 90 || it == 270 }
    }

    /**
     * 从原始输入重渲到 [out]。[params] 是（可能带片框 + 日期）的主参数；
     * [fallbackJson] 是母片纯色彩配方——片框/日期参数被引擎拒时至少退回纯色彩出图。
     */
    fun rerender(
        context: Context,
        mother: MotherFilmRegistry.MotherRec,
        params: String,
        out: File,
        fallbackJson: String = "",
    ): SpektraProcessor.ProcessResult {
        val fallbacks = listOfNotNull(
            fallbackJson.takeIf { it.isNotBlank() && it != params },
            mother.recipeJson.takeIf { it.isNotBlank() && it != params && it != fallbackJson },
        )
        return if (mother.rawPath.isNotEmpty() && File(mother.rawPath).exists()) {
            val shot = ShotMeta.fromJson(mother.shotJson)
            // 与 FramedPreRenderer 同源：复刻母片 RAW 曝光口径（ISP gain + fixed print），
            // 避免片边重渲比白边母片暗。
            val isFlash = shot?.flash?.let { it != "OFF" } == true
            val locked = mother.rawParamsJson.isNotBlank()
            fun rawParam(p: String): String {
                var x = SpektraProcessor.withRawExposureMethod(p, isFlash)
                if (locked) x = SpektraProcessor.withFixedPrintExposure(x)
                return x
            }
            val rawResult = SpektraProcessor.processRawFrame(
                context, mother.rawPath, rawParam(params), out.absolutePath,
                fallbackParamsJsons = fallbacks.map { rawParam(it) },
                zoomRatio = shot?.zoom ?: 0f,
                gainEv = mother.rawGainEv.toDouble(),
            )
            if (rawResult.ok) {
                FramedPreRenderer.orientRawOutput(context, mother.rollId, mother.index, out)
            }
            rawResult
        } else {
            val bytes = UndevelopedFilmStore.openFrameStream(mother.rollId, mother.index)
                ?.use { it.readBytes() }
                ?: throw IllegalStateException("原始帧流为空")
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = true
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: throw IllegalStateException("原始帧解码失败")
            val rotation = UndevelopedFilmStore.frameOrientation(mother.rollId, mother.index)
            var oriented = if (rotation == 90 || rotation == 180 || rotation == 270) {
                val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                bmp.recycle(); r
            } else bmp
            // 🔴片门裁切：引擎对片框输入按 135 gate 校验，原始帧是 3:4，直接送报
            // "not the 135 gate's shape; crop the picture to the gate first"。
            val shot = ShotMeta.fromJson(mother.shotJson)
            val gate = FilmStockMapper.FilmGate.fromKey(shot?.gate)
            val aspect = FilmStockMapper.gateAspectOf(gate, oriented.width < oriented.height)
            val crop = FilmStockMapper.gateCrop(oriented.width, oriented.height, aspect)
            if (crop.cropped) {
                val gated = Bitmap.createBitmap(crop.w, crop.h, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(gated).drawBitmap(
                    oriented, -crop.x.toFloat(), -crop.y.toFloat(), android.graphics.Paint()
                )
                oriented.recycle()
                oriented = gated
            }
            // 复刻冲洗期反 ISP 归一化（与母片同色）
            FramedPreRenderer.restoreJpegInput(oriented, mother.inputEv, mother.normK)
            try {
                SpektraProcessor.processFrame(
                    context, oriented, params, out.absolutePath,
                    recycleInput = true, fallbackParamsJsons = fallbacks,
                )
            } finally {
                if (!oriented.isRecycled) oriented.recycle()
            }
        }
    }

    /**
     * 编辑页预览：按当前档位 + 日期开关渲一张（带片框/纯画面，不含白边），
     * 白边由调用侧用 [WhiteBorderRenderer] 本地补 —— 与导出完全同序。
     *
     * @return 渲染出的临时文件；失败返回 null（调用侧回落旧预览）。
     */
    fun renderPreview(
        context: Context,
        rollId: String,
        index: Int,
        frame: Int,
        dateImprint: DateImprint?,
    ): Bitmap? {
        val mother = MotherNegativeStore.mother(rollId, index) ?: return null
        val rollInfo = UndevelopedFilmStore.rollInfo(rollId)
        val dev = FilmStockMapper.readDevSettings(context, rollInfo.filmStockId)
        val mappedPrint = rollInfo.printStockId.ifBlank { FilmStockMapper.DEFAULT_PRINT_FALLBACK }
        val resolved = FilmStockMapper.resolvePrintStock(rollInfo.filmStockId, mappedPrint, dev.paperOverride)
        val effective = dev.copy(paperOverride = resolved.paperId)

        val base = mother.recipeJson.ifBlank {
            // 🔴extraEntries 空表封死默认分支（vc114）：无框/白边预览的兜底绝不能带 overscan
            FilmStockMapper.buildParamsJson(
                rollInfo.filmStockId, mappedPrint, effective,
                includeStyleMark = false,
                extraEntries = emptyList(),
            )
        }
        val params = if (frame == EditsCodec.FRAME_FILM) {
            val gate = runCatching { ShotMeta.fromJson(mother.shotJson)?.gate }.getOrDefault(null)
            val medium = gate == "6x6"
            val format = if (medium) FilmStockMapper.FilmGate.MF6x6.overscanFormat
            else FilmStockMapper.OVERSCAN_FORMAT_FULL
            // 朝向交给引擎（它按输入比例选横/竖片门）；万一这一格不认，
            // 下面的兜底链会退回纯色彩出图，预览略差但不会整帧失败。
            FilmStockMapper.buildParamsJson(
                rollInfo.filmStockId, mappedPrint, effective,
                includeStyleMark = false,
                extraEntries = FilmStockMapper.frameBorderEntries(
                    rollInfo.filmStockId, index, format
                ),
                filmFormatMm = if (medium) 56f else 36f,
            )
        } else base

        val out = File(context.cacheDir, "edit_preview_${rollId}_${index}_${System.nanoTime()}.jpg")
        return try {
            val r = rerender(
                context, mother,
                DateImprint.inject(params, dateImprint) ?: params,
                out,
                fallbackJson = DateImprint.inject(base, dateImprint) ?: base,
            )
            if (!r.ok) {
                Log.w(TAG, "预览渲染失败: ${r.message}")
                null
            } else {
                decodeScaled(out, 1600)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "预览渲染异常: ${e.message}")
            null
        } finally {
            out.delete()
        }
    }

    private fun decodeScaled(f: File, target: Int): Bitmap? {
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeFile(
            f.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}
