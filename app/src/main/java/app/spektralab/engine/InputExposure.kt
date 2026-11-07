package app.spektralab.engine

import android.graphics.Bitmap

/**
 * v46：输入层曝光补偿 —— 在引擎看到像素之前做一次**线性域**增益。
 *
 * ## 为什么不在印相层调
 * 引擎的 `print_exposure`（放大机曝光）一变，135 片框（黑片基 / 橙色暗边印刷 /
 * 白齿孔）会跟着画面一起变浓变淡 —— 用户 11-07 明确「这个不应该作用于胶片框」。
 * 片基密度是冲洗定死的，换曝光只该改变画面。所以印相曝光锁死
 * （[FilmStockMapper.PRINT_EXPOSURE_LOCKED]），曝光调整改在**进引擎之前**做：
 * 等价于换了档感光度/曝光时间，而不是动放大机。
 *
 * ## 为什么必须在 sRGB 线性域
 * JPEG 通路的输入是 8bit display-referred sRGB（引擎按 `input_cctf_decoding=true`
 * 反 gamma）。直接在码值上加 `v*2^ev` 等于在 gamma 域乘方，中灰会偏：
 * 0.5EV 名义该亮 41%，码值直接乘 1.41 只剩约 0.29EV。走
 * sRGB 解码 → ×2^ev → 编码 才是真正的曝光（v39 在 RAW 通道验证过这条量纲）。
 *
 * 逐像素只做一次 256 项查表，12MP 一帧约百毫秒级，不构成冲洗瓶颈。
 */
object InputExposure {

    private const val TAG = "InputExposure"

    /** 单通道 sRGB 码值（0..255）→ 线性光 [0,1]。 */
    internal fun srgbToLinear(code: Int): Double {
        val v = code.coerceIn(0, 255) / 255.0
        return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    /** 线性光 [0,1] → sRGB 码值（0..255，四舍五入）。 */
    internal fun linearToSrgb(linear: Double): Int {
        val l = linear.coerceIn(0.0, 1.0)
        val v = if (l <= 0.0031308) 12.92 * l else 1.055 * Math.pow(l, 1.0 / 2.4) - 0.055
        // +0.5 再取整：纯截断会让 1.0 落到 254.9999… → 254，白色被啃掉一格
        return (v * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }

    /**
     * 256 项映射表：`out[v] = sRGB(2^ev × linear(v))`。
     * 纯函数，JVM 单测可直接验证恒等/单调/端点。
     */
    internal fun lut(ev: Double): IntArray {
        // 0EV 必须是**严格恒等**：sRGB 编解码是浮点 pow，往返取整会在低码值差 1
        // （实测 12 → 11），那样「没调曝光」也会悄悄改像素。
        if (kotlin.math.abs(ev) < 1e-4) return IntArray(256) { it }
        val gain = Math.pow(2.0, ev)
        return IntArray(256) { linearToSrgb(srgbToLinear(it) * gain) }
    }

    /**
     * 就地把 [bmp] 的曝光整体乘 2^[ev]。返回是否真的动过。
     *
     * 不可变位图 / 补偿小于 0.001EV / 异常 → 返回 false 不动像素
     * （冲洗侧后续步骤仍按原样跑，绝不因为这一档补偿失败丢帧）。
     */
    fun apply(bmp: Bitmap, ev: Double): Boolean = runCatching {
        if (kotlin.math.abs(ev) < 1e-3) return false
        if (!bmp.isMutable || bmp.isRecycled) {
            android.util.Log.w(TAG, "位图不可变，跳过输入曝光补偿（应传 inMutable=true）")
            return false
        }
        val table = lut(ev)
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var touched = false
            for (x in 0 until w) {
                val p = row[x]
                val r = table[(p shr 16) and 0xFF]
                val g = table[(p shr 8) and 0xFF]
                val b = table[p and 0xFF]
                val np = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                if (np != p) {
                    row[x] = np
                    touched = true
                }
            }
            if (touched) bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        true
    }.onFailure { android.util.Log.e(TAG, "输入曝光补偿异常（跳过）", it) }.getOrDefault(false)
}
