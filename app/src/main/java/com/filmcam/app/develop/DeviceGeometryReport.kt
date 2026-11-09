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
    private var dirty = false
    private var updatedAtMs = System.currentTimeMillis()

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
        dirty = true
        updatedAtMs = System.currentTimeMillis()
    }
}
