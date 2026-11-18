package app.spektralab.engine

/**
 * 引擎原生的日期印字参数（`date_imprint_*`）。
 *
 * 🔴这是引擎自带的能力，**不要自己在 Bitmap 上画日期**。
 * 引擎在成像链路里把日期当作「额外曝光」印上去，会一起吃镜头柔度、颗粒、
 * 印相曲线与纸基散射 —— 这才是「印在胶片上」而不是「贴纸」的根本原因。
 * App 侧手绘只能在成片上叠一坨像素，无论怎么调都融不进去。
 *
 * 参数语义（与参照实现 SpektraLab-Android v1.6.2m 逐字一致，同一个 .so）：
 *  - [style]：`lcd` 七段 / `dots` 点阵 / `data` 拍摄数据
 *  - [placement]：`frame` 画面内 / `rebate` 片基（齿孔带）
 *  - [corner]：`br` / `bl` / `tr` / `tl`
 *  - [size]：字高系数，字高 ≈ 1.3mm × size（`dots` 为 0.95mm）
 *  - [insetXMm]：画幅边 → 文字**边缘**的距离（mm）
 *  - [insetYMm]：画幅上/下 → 文字**基线**的距离（mm）
 *  - [ev]：印字曝光量（EV），越大越亮
 *
 * 单位是**毫米**、相对真实画幅（135 = 36×24mm），所以同一组数值在 120 6×6
 * （56mm 宽）上会自动变细变小，与真实换机背的行为一致。
 */
data class DateImprint(
    val text: String,
    val style: String = STYLE_LCD,
    val placement: String = PLACE_FRAME,
    val corner: String = CORNER_BR,
    val size: Float = DEFAULT_SIZE,
    val insetXMm: Float = DEFAULT_INSET_X_MM,
    val insetYMm: Float = DEFAULT_INSET_Y_MM,
    val ev: Float = DEFAULT_EV,
) {
    init {
        require(text.isNotEmpty()) { "日期文本不能为空" }
        require(style in STYLES) { "未知日期风格: $style（可选 $STYLES）" }
        require(placement in PLACEMENTS) { "未知落位: $placement（可选 $PLACEMENTS）" }
        require(corner in CORNERS) { "未知角: $corner（可选 $CORNERS）" }
        require(size > 0f) { "字号系数必须为正: $size" }
        require(insetXMm >= 0f && insetYMm >= 0f) { "内缩不能为负: $insetXMm/$insetYMm" }
    }

    /** JSON 片段（不含首尾花括号，带前导逗号），直接拼进参数串。 */
    fun jsonEntries(): List<Pair<String, String>> = listOf(
        "date_imprint_active" to "true",
        "date_imprint_text" to quoted(text),
        "date_imprint_style" to quoted(style),
        "date_imprint_placement" to quoted(placement),
        "date_imprint_corner" to quoted(corner),
        "date_imprint_size" to num(size),
        "date_imprint_inset_x" to num(insetXMm),
        "date_imprint_inset_y" to num(insetYMm),
        "date_imprint_ev" to num(ev),
    )

    private fun quoted(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun num(v: Float): String {
        // 两位小数足够（引擎按 f64 收），避免 "1.0" 这种长尾
        val r = kotlin.math.round(v * 100f) / 100f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }

    companion object {
        const val STYLE_LCD = "lcd"
        const val STYLE_DOTS = "dots"
        const val STYLE_DATA = "data"
        val STYLES = listOf(STYLE_LCD, STYLE_DOTS, STYLE_DATA)

        const val PLACE_FRAME = "frame"
        const val PLACE_REBATE = "rebate"
        val PLACEMENTS = listOf(PLACE_FRAME, PLACE_REBATE)

        const val CORNER_BR = "br"
        const val CORNER_BL = "bl"
        const val CORNER_TR = "tr"
        const val CORNER_TL = "tl"
        val CORNERS = listOf(CORNER_BR, CORNER_BL, CORNER_TR, CORNER_TL)

        /** 引擎默认：字高 = 1.3mm（135 画幅宽 36mm 的 3.6%）。 */
        const val DEFAULT_SIZE = 1f
        const val DEFAULT_INSET_X_MM = 3f
        const val DEFAULT_INSET_Y_MM = 2.4f
        const val DEFAULT_EV = 6f

        /**
         * 右上角「'YY M D」——真机石英机背格式：两位年带前导撇号、月日不补零。
         * 抽成纯函数便于单测锁定格式。
         */
        fun textFor(year: Int, month: Int, day: Int): String =
            "'" + (year % 100).toString().padStart(2, '0') + " " + month + " " + day

        /** 拍摄时间 → 机背格式（与 [textFor] 同口径）。 */
        fun textForMillis(ms: Long): String {
            val c = java.util.Calendar.getInstance(java.util.Locale.US)
            c.timeInMillis = ms
            return textFor(
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH),
            )
        }

        /** 按 T3 真机成片实测反推的一套参数（135 画幅）：字高约 1.05mm、离边 3.6mm。 */
        fun t3Style(text: String, portrait: Boolean = false): DateImprint = DateImprint(
            text = text,
            style = STYLE_LCD,
            placement = PLACE_FRAME,
            // 引擎在**底片画幅坐标**里印字：竖拍时相机转了 90°，整条日期跟着转 90°，
            // 落点也从「画幅右下」映射到竖幅的**左下**——这就是真机 T3 竖拍冲出来的样子。
            // 11-18 四角定标实测（竖拍）：br→画面内左下 / bl→左上 / tl→右上 /
            // tr→右下但落在片基上被齿孔盖住。所以两种朝向都用 br，位置才落在画面内。
            corner = CORNER_BR,
            size = 0.8f,
            insetXMm = 3.6f,
            insetYMm = 1.3f,
            ev = DEFAULT_EV,
        )

        /**
         * 把日期键追加进一份参数 JSON。`null` 原样返回（母片 / 日期关闭）。
         *
         * 🔴必须是「每次调用时合成」，不能提前烘进 `activeParamsJson`：
         * 那串参数是**整卷共用**的，而日期逐帧不同（每帧 takenAt 不一样），
         * 烘进去会让后面 35 帧全印成第 1 帧的日期。
         */
        fun inject(json: String?, imprint: DateImprint?): String? {
            if (json.isNullOrBlank() || imprint == null) return json
            val body = json.trimEnd().removeSuffix("}").trimEnd()
            val sb = StringBuilder(body.length + 320)
            sb.append(body)
            imprint.jsonEntries().forEach { (k, v) ->
                sb.append(",\"").append(k).append("\":").append(v)
            }
            sb.append('}')
            return sb.toString()
        }
    }
}
