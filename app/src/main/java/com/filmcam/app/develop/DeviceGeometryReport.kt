package com.filmcam.app.develop

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v63 内测诊断：跨机型「屏幕 / 取景器几何」取证聚合器。
 *
 * 纯 Kotlin、零 Android 依赖，可在 JVM 单测里验证。设计约束（别违背）：
 *  - **只记录，不参与任何布局 / 曝光 / 渲染计算**；所有调用方都包在 runCatching 里，
 *    这一层抛异常也绝不能影响出图。
 *  - 值相同不置脏，避免 layout / GL 回调反复写文件。
 *  - 字段按 `group.name` 命名，[render] 按组输出，内测用户整段复制发回即可。
 *
 * 用途：vivo X300 Pro 等机型上核对 WindowMetrics / insets / contentRect /
 * previewSize / SurfaceTexture transform matrix / uMode / surface-view 是否一致，
 * 先取证再定 P0，不靠猜参数。
 */
object DeviceGeometryReport {

    private val fields = LinkedHashMap<String, String>()
    private val matrixCache = HashMap<String, FloatArray>()
    private var dirty = false
    private var updatedAtMs = System.currentTimeMillis()

    /** 与报告的 3 位小数精度对齐：小于半个最小显示单位的变化不会改文本 */
    private const val MATRIX_EPS = 0.0005f

    private val groupOrder = listOf(
        "device", "app", "window", "insets", "content", "chrome", "camera", "gl"
    )

    /** @return 值是否发生了变化（变化才会置脏） */
    @Synchronized
    fun put(key: String, value: Any?): Boolean {
        val v = (value?.toString() ?: "?")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
        if (fields[key] == v) return false
        fields[key] = v
        dirty = true
        updatedAtMs = System.currentTimeMillis()
        return true
    }

    @Synchronized
    fun putAll(vararg pairs: Pair<String, Any?>) {
        pairs.forEach { put(it.first, it.second) }
    }

    /**
     * GL 变换矩阵：只在数值**变化**时才格式化并置脏。
     *
     * v64：冷启动前两帧 SurfaceTexture 往往还是未初始化的单位阵，HAL 就绪后
     * 才会换成真实旋转矩阵；只采前两帧会把单位阵永久写进报告，误导 uMode 判断。
     * 调用方可以每帧安全调用 —— 相同矩阵直接返回 false，不做字符串格式化。
     */
    @Synchronized
    fun putMatrix(key: String, values: FloatArray): Boolean {
        val prev = matrixCache[key]
        if (prev != null && sameMatrix(prev, values)) return false
        matrixCache[key] = values.copyOf()
        return put(key, values.joinToString(",") { "%.3f".format(Locale.US, it) })
    }

    private fun sameMatrix(a: FloatArray, b: FloatArray): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (kotlin.math.abs(a[i] - b[i]) > MATRIX_EPS) return false
        return true
    }

    /** 有变化才返回全文并清脏标记；落盘方用它避免重复 IO。 */
    @Synchronized
    fun snapshotIfDirty(): String? {
        if (!dirty) return null
        val text = render()
        dirty = false
        return text
    }

    @Synchronized
    fun render(): String = buildString {
        appendLine("=== Relic Cam 跨机型几何诊断 ===")
        appendLine(
            "生成时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(updatedAtMs))
        )
        appendLine("说明: 只读取证，不含照片 / 位置 / 账号信息；整段复制发回开发者即可。")
        appendLine()
        val grouped = fields.keys.groupBy { it.substringBefore('.', "other") }
        val ordered = groupOrder.filter { grouped.containsKey(it) } +
            grouped.keys.filter { it !in groupOrder }.sorted()
        for (g in ordered) {
            appendLine("[$g]")
            for (k in grouped[g].orEmpty().sorted()) appendLine("$k = ${fields[k]}")
            appendLine()
        }
        append("字段数=${fields.size}")
    }

    @Synchronized
    fun fieldCount(): Int = fields.size

    @Synchronized
    fun clear() {
        fields.clear()
        matrixCache.clear()
        dirty = true
        updatedAtMs = System.currentTimeMillis()
    }
}
