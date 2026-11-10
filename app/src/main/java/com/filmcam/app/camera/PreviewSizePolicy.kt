package com.filmcam.app.camera

/**
 * v68 P0：Camera2 预览尺寸选择策略（**纯 Kotlin，零 Android 依赖**）。
 *
 * ## 根因（11-10 两台 vivo 实证，勿回退）
 * 旧实现 `maxByOrNull { width * height }` 只看面积。vivo V2227A / X300 Ultra
 * 的 HAL 会报出 1920×1920（1:1，面积 3.686M），比 4:3 的 1920×1440（2.765M）大，
 * 于是被选中；而取景器 shader 的 `uContentAspect` 是编译期常量（竖 3:4 / 横 4:3），
 * `SurfaceTexture` 变换矩阵只有旋转、没有比例缩放补偿，方形纹理被直接铺进 4:3
 * 内容区 → 一个轴被拉伸 33.3%（横屏横向变宽、竖屏纵向变长）。
 * S25U 之所以正常，只是因为它报出的最大面积候选恰好是 1920×1440。
 *
 * ## 策略
 * 1. **4:3 优先，再比面积**：比例一律按 `max(w,h)/min(w,h)` 归一化，兼容厂商
 *    把尺寸竖置上报（如 1440×1920 也算 4:3）。
 * 2. 没有任何 4:3 候选时**严格回退旧行为（面积最大）**——不擅自改变这类机型的
 *    取景，靠诊断字段报警，留给 P1 的 shader cover 裁剪彻底解决。
 * 3. 只负责「选哪个」，调用方负责 ≤1920 上限与兜底尺寸；比例容差 0.01 覆盖
 *    HAL 常见的 1920×1448 这类近似 4:3。
 *
 * 抽成纯 Kotlin 是为了能在 JVM 单测里直接灌入真机候选表逐机型断言。
 */
object PreviewSizePolicy {

    const val TARGET_ASPECT = 4f / 3f
    const val ASPECT_TOLERANCE = 0.01f

    /** 归一化长宽比：横置 / 竖置都返回同一个 ≥1 的值；非法尺寸返回 0。 */
    fun normalizedAspect(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        val long = maxOf(width, height)
        val short = minOf(width, height)
        return long.toFloat() / short.toFloat()
    }

    fun isTargetAspect(width: Int, height: Int): Boolean {
        val a = normalizedAspect(width, height)
        return a > 0f && kotlin.math.abs(a - TARGET_ASPECT) <= ASPECT_TOLERANCE
    }

    /** 相对目标 4:3 的比例偏差（如 1:1 → 0.25，即 25% 失配）。 */
    fun aspectErrorFraction(width: Int, height: Int): Float {
        val a = normalizedAspect(width, height)
        if (a <= 0f) return Float.POSITIVE_INFINITY
        return kotlin.math.abs(a - TARGET_ASPECT) / TARGET_ASPECT
    }

    /**
     * 面积升序 → 长边升序 → 宽升序；配 `maxWithOrNull` 取**最大面积**。
     * ⚠️ 别写成 descending 比较器再配 maxWithOrNull：那会选出面积最小的候选
     * （v68 首轮单测实测：1920×1440 被换成 1280×960）。并列时结果仍与输入顺序无关。
     */
    private val BY_AREA = compareBy<Pair<Int, Int>> {
        it.first.toLong() * it.second
    }.thenBy { maxOf(it.first, it.second) }
        .thenBy { it.first }

    /**
     * @param sizes 原始候选表（(width, height)），无需预先排序
     * @param maxLongEdge 长边上限（取景器 1920，避免 GL/EGL 绑定 12MP 私有流失败）
     * @return 选中的尺寸；候选为空 / 全部超限 / 全非法时返回 null，由调用方兜底
     */
    fun choose(sizes: Iterable<Pair<Int, Int>>, maxLongEdge: Int = Int.MAX_VALUE): Pair<Int, Int>? {
        val valid = sizes.filter { (w, h) ->
            w > 0 && h > 0 && maxOf(w, h) <= maxLongEdge
        }
        if (valid.isEmpty()) return null
        return valid.filter { (w, h) -> isTargetAspect(w, h) }.maxWithOrNull(BY_AREA)
            ?: valid.maxWithOrNull(BY_AREA)
    }
}
