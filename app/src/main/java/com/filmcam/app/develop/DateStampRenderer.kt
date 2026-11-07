package com.filmcam.app.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日期戳渲染（规格 §6.4，v2：仿真石英日期机背成像）。
 *
 * 参考真实胶片机背（Contax T3 Quartz Date 一类）的光学特征：
 *  - 七段 LCD 数码管字形（dseg7.ttf），字距紧、段间几乎贴合
 *  - 颜色为红色 LED 透橙色滤片曝光后的橙红（#FF7E15 一带），不是数码金黄
 *  - LCD 隔着机背玻璃与片基曝光 → 边缘天然失焦发软（小半径模糊）
 *  - LCD 表面反光在玻璃上 → 约 0.5px 的鬼影（低透明度错位重绘一次）
 *  - 只有微弱光渗（大半径、极低透明度），没有霓虹式辉光
 *  - 曝光在底片上而非后期叠加 → 整体略降不透明度，且随引擎一起吃颗粒/调色
 *
 * 直接画在冲洗流程的输入位图上（进引擎之前），不产生任何 JPEG 编解码。
 *
 * v36：引擎输出自带片框时，日期戳必须落在**画面区（片门矩形）**里，而不是
 * 整张带框成片的右下角 —— 真机日期机背把日期曝在底片画面内，不会印到片基 /
 * 暗边印刷带上。RAW 通路进引擎的是 DNG 没法提前画，只能在引擎输出后补画，
 * 故需要调用侧按成片比例传内容区；JPEG 通路画在进引擎前的裸画面上，传 null。
 */
object DateStampRenderer {

    // 石英日期机背实际成色：橙红主色 + 更深的红做光渗
    private const val COLOR_INK = 0xFFFF7E15.toInt()      // 主体（略降 alpha 模拟曝光密度）
    private const val COLOR_BLOOM = 0xFFFF5A00.toInt()    // 光渗

    private const val INK_ALPHA = 232                     // 曝光在片基上达不到纯 255
    private const val BLOOM_ALPHA = 42                    // 微弱光渗

    /** 日期字高 / 内缩边距相对「内容区宽度」的比例（真机背约 3.6% / 3.2%）。 */
    private const val TEXT_SIZE_FRACTION = 0.036f
    private const val MARGIN_FRACTION = 0.032f

    /** 带框成片里画面区的相对矩形；纯 Kotlin 数据类，便于 JVM 单测。 */
    data class ContentRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        /**
         * 字号补偿：字高按内容区宽度算，而竖拍版式的画面列比横拍窄得多，
         * 不补偿时竖拍日期戳只有横拍的 6 成大小（真机 54px vs 89px）。
         * 只给竖拍用，横拍 / 裸画面恒为 1。
         */
        val textScale: Float = 1f,
        /**
         * 水平内缩（占内容区宽的比例），与垂直方向分开：竖拍日期戳右侧
         * 紧邻片基，需要留更多余量，但不能把底部位置也一起抬高。
         * 默认与 MARGIN_FRACTION 一致 → 横拍 / 裸画面行为不变。
         */
        val marginXFraction: Float = MARGIN_FRACTION,
    ) {
        init {
            require(right > left && bottom > top) { "非法内容区: $this" }
            require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f) {
                "内容区必须落在 [0,1]: $this"
            }
            require(textScale > 0f) { "字号补偿必须为正: $this" }
            require(marginXFraction >= 0f && marginXFraction < 0.5f) { "水平内缩越界: $this" }
        }
    }

    /**
     * 135 全幅片门在**带框成片**中的实测画面区（11-07 用 v32 横拍基础曝光片量得）：
     * 上下齿孔带 y 0.059~0.141 / 0.866~0.946，画面区 y∈[0.141,0.866]，
     * 高 0.725H≈1815px，2710/1815≈1.493≈3:2。x 几乎满宽，留 1% 防压边。
     */
    val CONTENT_LANDSCAPE = ContentRect(0.01f, 0.141f, 0.99f, 0.866f)

    /**
     * 竖拍：引擎只有横置 135 版式，整条胶片被旋转 90° 套在竖画布上 →
     * 齿孔列落在 x 0.059~0.141 / 0.866~0.946，画面列对应横拍的
     * y 0.141~0.866，即 **x 0.141~0.866**，y 方向几乎满高。
     *
     * 11-07 17:35 真机 v40 竖拍（2503×2710）逐列精扫，取 y 0.10~0.90：
     *  - x 0.74~0.84 一直是影像（近黑 41~44% / 近白 16~25% / 中灰 33~39%）；
     *  - x 0.85 突然 100% 近黑 = 右片基，x 0.87~0.92 近白 38% = 右齿孔列；
     *  - 行方向 y 0.88~0.97 全是中灰影像，y 0.98 才进底部片基。
     * 竖排 KODAK / 帧号在齿孔列外侧（x≈0.95），不在 0.80~0.85 这一段。
     *
     * ⚠️旧值 right=0.77 来自 16:54 那张**暗场景**竖拍：右半是黑色物体，
     * 逐列「近黑」统计把画面内容误判成片基。结果日期戳右端只到 0.748，
     * 离真实画面右缘约 250px，用户 17:36 报「需要靠右边一点」。
     * v42/v43：用户先报「右边压到胶片框」（v41 右端 0.822，离 0.85 片基
     * 仅 0.028w），v42 用 0.10×cw 收到 0.782 后 18:16 又报「有点远」。
     * v43 折中到 **0.065×cw** → 右端 **0.805**，离片基 0.045w
     * （真机实测 v41 0.028w 太贴、v42 0.073w 太远）。垂直内缩仍是
     * 0.032×cw，bottom 保持 0.95，上下位置不动。
     *
     * textScale=1.60：cw 0.68w≈1702px → 字高 0.036×cw×1.60≈98px，
     * 与横拍 0.036×2656≈96px 基本齐平（v41 的 80px 仍偏小）。
     */
    val CONTENT_PORTRAIT = ContentRect(
        0.17f, 0.02f, 0.85f, 0.95f,
        textScale = 1.60f,
        marginXFraction = 0.065f,
    )

    /**
     * v42：竖拍**裸画面**（JPEG 通路，日期戳画在进引擎之前）的等效内容区。
     *
     * 引擎会把旋转后的裸竖画面映射进横置 135 版式，成片画面区实测
     * x 0.17~0.85 / y 0.01~0.98，即映射关系：
     *   `成片x = 0.17 + (裸x/w)×0.68`、`成片y ≈ 0.01 + (裸y/h)×0.97`。
     * 旧实现按整图定位（裸右端 0.966w）→ 成片右端 0.822，离 0.85 的
     * 右片基只剩 0.028w，用户 17:58 报「右边压到胶片框了」。
     *
     * v43 把裸画面水平内缩定在 0.065w → 裸右端 0.932 → 成片右端
     * **0.805**，与带框 RAW 通路用 0.065×cw 得到的 0.805 对齐，
     * 离 0.85 片基留 0.045w。垂直内缩仍是 0.032×cw，底部位置不动。
     * 字号 ×1.40（成片约 140px，相对旧 100px +40%）。
     *
     * 片框关闭时不返回（null），避免把纯画面日期戳也往左拉。
     */
    val SOURCE_PORTRAIT_FRAMED = ContentRect(
        0f, 0f, 1f, 1f,
        textScale = 1.40f,
        marginXFraction = 0.065f,
    )

    /** 仅「竖拍 + 片框开启」的裸画面需要这套等效内容区。 */
    internal fun portraitFramedSource(w: Int, h: Int, frameEnabled: Boolean): ContentRect? =
        if (w < h && frameEnabled) SOURCE_PORTRAIT_FRAMED else null

    /**
     * 按位图比例判断是不是「带片框的成片」。纯画面（片门 3:2 / 2:3、或 gateCrop
     * 的 4:3）传 null；带框成片横拍约 1.083、竖拍约 0.924，按朝向给内容区。
     * 用比例而不是参数判断 —— 竖拍版式自检失败时引擎会静默关框，参数仍写着 true。
     */
    internal fun contentForAspect(w: Int, h: Int): ContentRect? {
        if (w <= 0 || h <= 0) return null
        val r = w.toFloat() / h.toFloat()
        val bare = kotlin.math.abs(r - 1.5f) < 0.06f ||
            kotlin.math.abs(r - 2f / 3f) < 0.04f ||
            kotlin.math.abs(r - 4f / 3f) < 0.04f ||
            kotlin.math.abs(r - 3f / 4f) < 0.04f
        if (bare) return null
        return if (w < h) CONTENT_PORTRAIT else CONTENT_LANDSCAPE
    }

    @Volatile private var cachedTypeface: Typeface? = null

    private fun typeface(ctx: Context): Typeface {
        cachedTypeface?.let { return it }
        synchronized(this) {
            cachedTypeface?.let { return it }
            val tf = Typeface.createFromAsset(ctx.applicationContext.assets, "dseg7.ttf")
            cachedTypeface = tf
            return tf
        }
    }

    /** [left, top, width, height, 字号补偿, 水平内缩比例]，单位 px。null = 整张位图。 */
    internal fun contentMetrics(w: Int, h: Int, rect: ContentRect?): FloatArray =
        if (rect == null) floatArrayOf(0f, 0f, w.toFloat(), h.toFloat(), 1f, MARGIN_FRACTION)
        else floatArrayOf(
            rect.left * w,
            rect.top * h,
            (rect.right - rect.left) * w,
            (rect.bottom - rect.top) * h,
            rect.textScale,
            rect.marginXFraction,
        )

    /** 纯函数：日期文字的落点。抽出来是为了能单测「是否落在内容区内」。 */
    internal data class Placement(val x: Float, val baseline: Float, val textSize: Float)

    internal fun place(metrics: FloatArray, textWidth: Float, descent: Float): Placement {
        val left = metrics[0]
        val top = metrics[1]
        val cw = metrics[2]
        val ch = metrics[3]
        val textSize = cw * TEXT_SIZE_FRACTION * metrics.getOrElse(4) { 1f }
        // v42：水平内缩可单独加大（竖拍右侧要离片基远一点），垂直方向保持
        // 0.032×cw 不变 —— 否则字一放大就把底边一起抬高（v36 踩过的坑）。
        val marginX = cw * metrics.getOrElse(5) { MARGIN_FRACTION }
        val marginY = cw * MARGIN_FRACTION
        return Placement(
            x = left + cw - marginX - textWidth,
            baseline = top + ch - marginY - descent,
            textSize = textSize,
        )
    }

    /**
     * 在 [bmp] 的内容区右下角就地绘制日期戳（要求 mutable）。
     * 调用前位图必须已按拍摄方向旋转到位，否则日期戳会落在错误的一角。
     *
     * @param content 带框成片时传画面区相对矩形；裸画面（JPEG 通路、片框关闭）传 null。
     */
    fun drawOnBitmap(
        ctx: Context,
        bmp: Bitmap,
        takenAtMs: Long,
        content: ContentRect? = null,
    ) {
        require(bmp.isMutable) { "位图不可变，无法绘制日期戳" }
        val w = bmp.width
        val h = bmp.height

        val text = SimpleDateFormat("''yy MM dd", Locale.US).format(Date(takenAtMs))
        val metrics = contentMetrics(w, h, content)

        // measureText / fontMetrics 依赖 textSize，先按内容区算出字高再量一次
        val probePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(ctx)
            textSize = place(metrics, 0f, 0f).textSize
            letterSpacing = 0.06f
        }
        val placement = place(metrics, probePaint.measureText(text), probePaint.fontMetrics.descent)

        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typeface(ctx)
            textSize = placement.textSize
            letterSpacing = 0.06f                       // 段间贴合，不再是霓虹宽字距
            color = COLOR_INK
            alpha = INK_ALPHA
            // 失焦感：极小半径的边缘模糊（软件画布，BlurMaskFilter 生效）
            maskFilter = BlurMaskFilter(placement.textSize * 0.055f, BlurMaskFilter.Blur.NORMAL)
        }

        // 鬼影：LCD 反光，整体错位约 0.4px、低透明度
        val ghostPaint = Paint(basePaint).apply {
            alpha = (INK_ALPHA * 0.28f).toInt().coerceAtMost(255)
            maskFilter = BlurMaskFilter(placement.textSize * 0.075f, BlurMaskFilter.Blur.NORMAL)
        }

        // 光渗：大半径、极低透明度，模拟弱曝光在片基上的光晕
        val bloomPaint = Paint(basePaint).apply {
            color = COLOR_BLOOM
            alpha = BLOOM_ALPHA
            maskFilter = BlurMaskFilter(placement.textSize * 0.22f, BlurMaskFilter.Blur.NORMAL)
        }

        val canvas = Canvas(bmp)
        // 鬼影偏移量：约 0.4px，缩放到不同分辨率下等比
        val ghostDx = placement.textSize * 0.011f
        val ghostDy = -placement.textSize * 0.006f
        canvas.drawText(text, placement.x + ghostDx, placement.baseline + ghostDy, ghostPaint)
        canvas.drawText(text, placement.x, placement.baseline, bloomPaint)
        canvas.drawText(text, placement.x, placement.baseline, basePaint)
    }
}
