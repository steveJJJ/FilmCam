package com.filmcam.app.camera

/**
 * 方案 A：内置直闪的**成片反馈闭环**（11-14 23:2x）。
 *
 * 背景（11-14 全链路复盘，tmp/samsung_exif_1114 / slow_sync_1114 /
 * fimo_scene_1114）：S25U 的公开 Camera2 三条联动入口全部证伪——
 * precapture 永不收敛、解除钳制会带爆主体（LED 随曝光窗口持续）、
 * 场景模式改不了功率基准（HAL 在 AE_ON 下忽略手写 SENSOR_*）。
 * 我们拿不到 HAL 的 BV，但**成片本身就是最好的测光传感器**：
 * vc87/88/89 已证裁边 7% 的 mean / p50 / plateau 能可靠判别
 * 「主体过曝」与「背景死黑」。
 *
 * 机制：每张直闪成片冲洗完成后统计一次 → 与目标区间比对 → 修正量
 * [corrEv] 步进 + EMA → 下一张拍摄把总曝光量上限乘上 2^corrEv。
 * 修正只动 `FLASH_MAX_EXPOSURE_PRODUCT` 这一个安全帽（3600 基准），
 * ISO≤250 与 -0.7EV 不动——主体受光仍由既有钳制保住；方向上暗场景
 * 向上探（FIMO 的背景亮得多），过亮向下收。
 *
 * 上限 +0.6EV ⇒ product≈5456，留在 vc87 实测**主体过曝下界 5609**
 * 之下（slow_sync #5 的 5615 是「发白但隐约有纹理」的卡线值）；
 * 下限 -1.0EV ⇒ product≈1800，给亮场景留退暗空间。
 *
 * ⚠️ 这是跨张累积的开环探索：第一张永远从 corr=0 出发（可能仍偏暗），
 * 场景突变会来回振荡——步长小（≤0.25EV）+ 区间滞回（带内不动）兜住。
 * 机型无关：不碰 HAL、不碰 vendor tag，只读写自己的常量与成片统计。
 *
 * 本对象刻意**零 Android import**：规则部分可在 JVM 单测里直接驱动；
 * 解码成片的 [onDevelopedFrame] 由冲洗端（DevelopmentService）在
 * 后台线程调用，内部 runCatching——统计失败绝不影响冲洗落盘。
 */
object FlashFeedbackLoop {

    /** 修正量上限（EV）。3600×2^0.6 ≈ 5456 < vc87 实测主体过曝下界 5609。 */
    const val MAX_CORR_EV = 0.6f

    /** 修正量下限（EV）。3600×2^-1.0 = 1800。 */
    const val MIN_CORR_EV = -1.0f

    /** 背景死黑判定门限：裁边成片 p50 低于此值向上探。
     *  vc83 定稿 p50≈26、FIMO≈86；25 以下=背景贴黑电平。 */
    const val DARK_P50 = 25

    /** 整体偏暗判定门限：mean 低于此值小幅上探。
     *  vc83 定稿 mean≈52、HAL 实验包 37、FIMO 93。 */
    const val DIM_MEAN = 45

    /** 整体偏亮回落门限：mean 高于此值小幅回收（白墙等亮背景会推高 mean，
     *  但 11-14 的教训是宁可保守回收，主体过曝不可逆）。 */
    const val BRIGHT_MEAN = 100

    /** 过曝保守回落门限：plateau（≥150 占比）与 mean 同时超限 → 优先向下。
     *  slow_sync #1 过爆手掌实测 plateau 41 / mean 104.7 —— 门限必须压到
     *  100 才接得住；白墙场景（plateau 47 / mean 116）也会触发，保守方向
     *  是对的（暗一点只是背景黑，爆了主体就没了）。 */
    const val OVER_PLATEAU = 35
    const val OVER_MEAN = 100

    /** 单帧单证（只喂内置直闪帧）。 */
    data class FrameStats(val mean: Float, val p50: Int, val plateauPct: Float)

    @Volatile private var corrEv = 0f
    @Volatile private var updates = 0
    @Volatile private var lastVerdict = "none"

    /** 当前总曝光量乘子（2^corr），关闭实验时调用侧不引用本值。 */
    fun productScale(): Float = Math.pow(2.0, corrEv.toDouble()).toFloat()

    /** 当前修正量（EV），用于日志与诊断。 */
    fun correctionEv(): Float = corrEv

    /** 累计有效更新次数（0 = 下一张仍是无修正基线）。 */
    fun updates(): Int = updates

    /** 最近一次判定（dark/dim/hold/bright/over/skip），用于日志。 */
    fun lastVerdict(): String = lastVerdict

    /** 测试与换卷诊断用：清零回路状态。 */
    fun reset() {
        corrEv = 0f
        updates = 0
        lastVerdict = "none"
    }

    /**
     * 用一张成片统计驱动回路。规则顺序：过曝保守判定优先（安全第一），
     * 然后背景死黑、整体偏暗、整体偏亮，带内滞回不动。
     * @return 判定字符串（同 [lastVerdict]）
     */
    fun onStats(stats: FrameStats): String {
        var step = 0f
        val verdict = when {
            stats.plateauPct >= OVER_PLATEAU && stats.mean >= OVER_MEAN -> {
                step = -0.25f; "over"
            }
            stats.p50 <= DARK_P50 -> {
                step = +0.25f; "dark"
            }
            stats.mean < DIM_MEAN -> {
                step = +0.15f; "dim"
            }
            stats.mean > BRIGHT_MEAN -> {
                step = -0.15f; "bright"
            }
            else -> "hold"
        }
        if (step != 0f) {
            corrEv = (corrEv + step).coerceIn(MIN_CORR_EV, MAX_CORR_EV)
            updates++
        }
        lastVerdict = verdict
        return verdict
    }

    /**
     * 冲洗端入口：读成片 JPEG → 裁边 7% 统计 → 只在内置直闪帧时喂回路。
     * 统计口径与 tmp 取证脚本完全一致（短边含白边，裁 7% 去边框；
     * 白边 6% 全部落在裁切区内）。
     *
     * @param jpeg 已完成白边/EXIF 的成片临时文件（删除前调用）
     * @param shotJson 该帧的 ShotMeta JSON（flash != BUILT_IN 直接跳过）
     * @return 判定字符串；非直闪帧 / 解码失败返回 "skip"
     */
    fun onDevelopedFrame(jpeg: java.io.File, shotJson: String?): String {
        val meta = com.filmcam.app.film.ShotMeta.fromJson(shotJson)
        if (meta?.flash != "BUILT_IN") {
            lastVerdict = "skip"
            return "skip"
        }
        val stats = analyze(jpeg) ?: run {
            lastVerdict = "skip"
            return "skip"
        }
        val verdict = onStats(stats)
        android.util.Log.i(
            "FlashFeedback",
            "VERDICT=$verdict mean=%.1f p50=%d plateau=%.1f%% corrEv=%.2f scale=%.3f updates=%d".format(
                stats.mean, stats.p50, stats.plateauPct, corrEv, productScale(), updates
            )
        )
        return verdict
    }

    /**
     * 成片统计：解码到长边 ≤512（inSampleSize，防大图 OOM），裁四边 7%，
     * 灰度直方图求 mean / p50 / ≥150 占比。任何失败返回 null（调用侧跳过）。
     */
    fun analyze(jpeg: java.io.File): FrameStats? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(jpeg.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = android.graphics.BitmapFactory.decodeFile(jpeg.absolutePath, opts)
            ?: return@runCatching null
        bmp.use { b ->
            val cx = (b.width * 0.07f).toInt().coerceAtLeast(1)
            val cy = (b.height * 0.07f).toInt().coerceAtLeast(1)
            val hist = IntArray(256)
            for (y in cy until b.height - cy) {
                for (x in cx until b.width - cx) {
                    val p = b.getPixel(x, y)
                    val l = (((p shr 16 and 0xFF) * 299 +
                        (p shr 8 and 0xFF) * 587 +
                        (p and 0xFF) * 114) / 1000)
                    hist[l]++
                }
            }
            val total = hist.sum()
            if (total <= 0) return@runCatching null
            var sum = 0L
            var acc = 0
            var p50 = 255
            for (i in 0..255) {
                sum += i.toLong() * hist[i]
                acc += hist[i]
                if (p50 == 255 && acc >= total / 2) p50 = i
            }
            val plateau = (hist.slice(150..255).sum().toFloat() / total) * 100f
            FrameStats(sum.toFloat() / total, p50, plateau)
        }
    }.getOrNull()

    private inline fun <T> android.graphics.Bitmap.use(block: (android.graphics.Bitmap) -> T): T =
        try {
            block(this)
        } finally {
            recycle()
        }
}
