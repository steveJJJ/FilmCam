package com.filmcam.app.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import app.spektralab.engine.FilmStockMapper
import app.spektralab.engine.SpektraProcessor
import com.filmcam.app.film.MotherFilmRegistry
import com.filmcam.app.film.MotherNegativeStore
import com.filmcam.app.film.RollInfo
import com.filmcam.app.film.ShotMeta
import com.filmcam.app.film.UndevelopedFilmStore
import java.io.File

/**
 * 片边档带框成片的**预渲染器**（v2 编辑导出加速）。
 *
 * 背景：带框成片的齿孔/片基是 `overscan_*` 参数在引擎渲染期合成的，无法本地叠加，
 * 每次导出都要从原始输入重渲（真机 5~8 秒/帧、3 张约 20 秒）。既然冲洗时已经在
 * 同一份原始输入上渲过纯色彩母片，就趁热再渲一份**带框版本**存私有目录，编辑导出
 * 片边档时直接取图叠日期入册（亚秒级）——把引擎成本从「导出前台等」挪到「冲洗后台跑」。
 *
 * 🔴一致性铁律：这里的参数构建 + 真跑验证 + gate 裁切 + 兜底链，必须与
 * [EditExportService.exportOne] 的片边档路径**逐字同源**——否则预渲染图和即时重渲
 * 图会有色差/版式差，用户两次导出同一张得到不同成片。两处的函数序列由
 * EditPageContractTest 锁定。
 *
 * 纯 IO + 引擎、无 Service 状态；失败一律上层吞掉，绝不影响冲洗主链路。
 */
object FramedPreRenderer {
    private const val TAG = "FramedPreRenderer"

    /** 片框版式真跑探测缓存（key=胶片|印相|format|朝向 → 错误原文；空串=通过）。与导出侧同口径。 */
    private val probeCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun clearCache() { probeCache.clear() }

    /**
     * 冲洗后预渲染一帧的带框成片到 [out]（全分辨率、带引擎齿孔、无日期/无白边/无 EXIF）。
     * @return 是否成功产出非空带框图；任何失败返回 false（上层忽略，导出时回落即时重渲）。
     */
    fun preRender(context: Context, rollId: String, index: Int): Boolean {
        val mother = MotherNegativeStore.mother(rollId, index) ?: return false
        val full = MotherNegativeStore.fullFile(rollId, index)
        if (!full.exists()) return false   // 母片还没落，正常冲洗流程里不会
        val out = MotherNegativeStore.framedFile(rollId, index)
        return try {
            val params = buildFramedParams(context, rollId, index, mother, full) ?: run {
                Log.i(TAG, "帧 $index 无可用片框版式，跳过预渲染")
                return false
            }
            renderFromSource(context, mother, params, out)
        } catch (e: Throwable) {
            Log.w(TAG, "帧 $index 带框预渲染异常（导出时回落即时重渲）: ${e.message}")
            out.delete()
            false
        }
    }

    /**
     * 构建带框参数：与 EditExportService.exportOne 的片边档段同源。
     * 返回 null = 该版式真跑被拒，本帧不带框（与导出侧同策略，保住画面）。
     */
    private fun buildFramedParams(
        context: Context, rollId: String, index: Int,
        mother: MotherFilmRegistry.MotherRec, full: File,
    ): String? {
        val rollInfo: RollInfo = UndevelopedFilmStore.rollInfo(rollId)
        val dev = FilmStockMapper.readDevSettings(context, rollInfo.filmStockId)
        val mappedPrint = rollInfo.printStockId.ifBlank { FilmStockMapper.DEFAULT_PRINT_FALLBACK }
        val resolved = FilmStockMapper.resolvePrintStock(rollInfo.filmStockId, mappedPrint, dev.paperOverride)
        val effective = dev.copy(paperOverride = resolved.paperId)

        val gate = runCatching { ShotMeta.fromJson(mother.shotJson)?.gate }.getOrDefault(null)
        val medium = gate == "6x6"
        val format = if (medium) FilmStockMapper.FilmGate.MF6x6.overscanFormat
            else FilmStockMapper.OVERSCAN_FORMAT_FULL
        // 朝向：读已 gate 裁好的全分辨率母片（与导出侧一致）
        val portrait = runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(full.absolutePath, o)
            o.outWidth < o.outHeight
        }.getOrDefault(false)

        val entries = FilmStockMapper.frameBorderEntries(rollInfo.filmStockId, index, format)
        val framed = FilmStockMapper.buildParamsJson(
            rollInfo.filmStockId, mappedPrint, effective,
            includeStyleMark = false,
            extraEntries = entries,
        )
        val aspect = if (medium) 1f else if (portrait) 2f / 3f else 3f / 2f
        val w = if (portrait) (96 * aspect).toInt().coerceAtLeast(1) else 96
        val h = if (portrait) 96 else (96 / aspect).toInt().coerceAtLeast(1)
        val probeKey = "${rollInfo.filmStockId}|$mappedPrint|$format|$portrait"
        val probeErr = probeCache.getOrPut(probeKey) {
            val err = runCatching { SpektraProcessor.probeRenderOk(context, framed, w, h) }
                .onFailure { Log.w(TAG, "帧 $index 片框真跑预检异常: ${it.message}") }
                .getOrNull() ?: ""   // "" = 通过
            Log.i(TAG, "片框版式真跑探测 key=$probeKey → ${if (err.isEmpty()) "通过" else err}")
            err
        }
        return if (probeErr.isEmpty()) framed else {
            Log.w(TAG, "帧 $index 片框版式 $format 真跑被拒: $probeErr → 本帧不预渲染带框")
            null
        }
    }

    /** 从原始输入渲染：与 EditExportService.rerender 逐字同源（RAW 直渲 / JPEG 旋转+gate 裁切+引擎）。 */
    private fun renderFromSource(
        context: Context,
        mother: MotherFilmRegistry.MotherRec,
        params: String,
        out: File,
    ): Boolean {
        val fallbacks = listOfNotNull(
            mother.recipeJson.takeIf { it.isNotBlank() && it != params },
        )
        val ok = if (mother.rawPath.isNotEmpty() && File(mother.rawPath).exists()) {
            val shot = ShotMeta.fromJson(mother.shotJson)
            // 🔴11-18 根因：主冲洗 RAW 走 ISP 引导 gainEv + 锁 print_exposure，
            // 片边重渲原来两者都缺 → 片边比母片暗（用户「RAW 白边正常、片边不曝」）。
            // 复刻母片登记的实际曝光口径：参数串叠 raw exposure method + fixed print，
            // 并把同一 gainEv 传进引擎，保证片边与白边母片逐像素同亮度。
            val isFlash = shot?.flash?.let { it != "OFF" } == true
            val rawParams = if (mother.rawParamsJson.isNotBlank()) {
                // 母片参数已是最终口径（含 fixed print / exposure method），只叠加片框键：
                // 用 framed 串的片框 entries 相对差异补进去 —— 直接以 framed 为底再施加同变换。
                var p = SpektraProcessor.withRawExposureMethod(params, isFlash)
                p = SpektraProcessor.withFixedPrintExposure(p)
                p
            } else {
                // 老卷无登记：沿用旧行为，不额外锁（保持向后兼容）
                params
            }
            val gain = mother.rawGainEv.toDouble()
            val rawOk = SpektraProcessor.processRawFrame(
                context, mother.rawPath, rawParams, out.absolutePath,
                fallbackParamsJsons = fallbacks.map {
                    var p = SpektraProcessor.withRawExposureMethod(it, isFlash)
                    if (mother.rawParamsJson.isNotBlank()) p = SpektraProcessor.withFixedPrintExposure(p)
                    p
                },
                zoomRatio = shot?.zoom ?: 0f,
                gainEv = gain,
            ).ok
            // 🔴RAW 竖拍方向（真机 11-18）：LibRaw 不应用 DNG 方向标签，引擎输出
            // 可能是横的（2596x1947）。主冲洗通路 developFrameFromRaw 有这段后处理，
            // 之前预渲染器漏了 → 竖拍带框图导出成横的。这里补齐同源旋转+回写。
            if (rawOk) orientRawOutput(context, mother.rollId, mother.index, out)
            rawOk
        } else {
            renderJpeg(context, mother, params, fallbacks, out)
        }
        val result = ok && out.exists() && out.length() > 0
        if (!result) out.delete()
        else Log.i(TAG, "帧 ${mother.index} 带框预渲染完成 ${out.length() / 1024}KB（导出片边档秒取）")
        return result
    }

    /**
     * RAW 引擎输出的方向后处理（与主冲洗通路 developFrameFromRaw 同源）。
     * 引擎的 LibRaw 多半不应用 DNG 方向标签：竖拍（rotation 90/270）输出却是横的 → 按帧记录旋转。
     * 带框成片比例远离 4:3，不做 gate 裁切（与主通路带框分支一致，避免裁烂片框）。
     */
    internal fun orientRawOutput(context: Context, rollId: String, index: Int, file: File) {
        val rotation = UndevelopedFilmStore.frameOrientation(rollId, index)
        val needRotate = (rotation == 90 || rotation == 270) || rotation == 180
        if (needRotate) {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = true
            }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return
            var out = bmp
            try {
                if ((rotation == 90 || rotation == 270) && bmp.width > bmp.height) {
                    out = rotate(bmp, rotation)
                } else if (rotation == 180) {
                    out = rotate(bmp, 180)
                }
                if (out !== bmp) {
                    java.io.FileOutputStream(file).use { os ->
                        out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, os)
                    }
                    Log.i(TAG, "帧 $index RAW 带框图按记录旋转 ${rotation}° → ${out.width}x${out.height}")
                }
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
                if (out !== bmp && !out.isRecycled) out.recycle()
            }
        }
        // 裁后放大补回传感器尺寸（横拍/引擎已转好的帧也要放大，因此放在旋转判定之外）
        val shotJson = runCatching { UndevelopedFilmStore.frameShot(rollId, index) }.getOrNull()
        RawUpscaler.upscaleFile(file, shotJson)
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val swap = degrees % 180 != 0
        val outW = if (swap) src.height else src.width
        val outH = if (swap) src.width else src.height
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.translate(outW / 2f, outH / 2f)
        canvas.rotate(degrees.toFloat())
        canvas.translate(-src.width / 2f, -src.height / 2f)
        canvas.drawBitmap(src, 0f, 0f, android.graphics.Paint())
        src.recycle()
        return out
    }

    /**
     * 片边重渲 JPEG 帧时复刻冲洗期的输入侧反 ISP 归一化（根因修复 11-18）。
     * 冲洗母片在进引擎前用 JpegNormalizer 退掉了 HAL 的提饱和；片边档从原始 HAL JPEG
     * 重渲时若跳过这步，就会比母片浓（用户「选片边后颜色很浓」）。用母片登记的
     * 同一组 inputEv/normK 复刻，保证逐像素一致。RAW 帧 normK=0，走曝光分支/不动。
     */
    internal fun restoreJpegInput(
        bmp: Bitmap,
        inputEv: Float,
        normK: Float,
    ) {
        if (normK > 1e-4f) {
            app.spektralab.engine.JpegNormalizer.apply(bmp, inputEv.toDouble(), normK)
        } else if (kotlin.math.abs(inputEv) > 1e-3f) {
            app.spektralab.engine.InputExposure.apply(bmp, inputEv.toDouble())
        }
    }

    private fun renderJpeg(
        context: Context,
        mother: MotherFilmRegistry.MotherRec,
        params: String,
        fallbacks: List<String>,
        out: File,
    ): Boolean {
        val bytes = UndevelopedFilmStore.openFrameStream(mother.rollId, mother.index)?.use { it.readBytes() }
            ?: return false
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = true
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return false
        val rotation = UndevelopedFilmStore.frameOrientation(mother.rollId, mother.index)
        var oriented = if (rotation == 90 || rotation == 180 || rotation == 270) {
            val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            bmp.recycle(); r
        } else bmp
        try {
            val gate = FilmStockMapper.FilmGate.fromKey(ShotMeta.fromJson(mother.shotJson)?.gate)
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
            // 🔴复刻冲洗期输入侧反 ISP 归一化（与母片同色，根因修复）
            restoreJpegInput(oriented, mother.inputEv, mother.normK)
            return SpektraProcessor.processFrame(
                context, oriented, params, out.absolutePath,
                recycleInput = true, fallbackParamsJsons = fallbacks,
            ).ok
        } finally {
            if (!oriented.isRecycled) oriented.recycle()
        }
    }
}
