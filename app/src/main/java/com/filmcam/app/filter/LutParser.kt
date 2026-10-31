package com.filmcam.app.filter

import java.io.BufferedReader

/**
 * .cube LUT 文件解析器
 *
 * .cube 格式（Adobe/Resolve 标准）：
 *   LUT_3D_SIZE N
 *   DOMAIN_MIN 0 0 0
 *   DOMAIN_MAX 1 1 1
 *   R G B   （共 N^3 行）
 *
 * 用法：用 spektrafilm 离线烘焙出 Kodak Portra 400 等 LUT，放进 res/raw/
 */
class LutParser {

    data class LutResult(
        val size: Int,
        val data: FloatArray   // size^3 * 3 个 float
    )

    fun parse(reader: BufferedReader): LutResult {
        var size = 0
        val values = mutableListOf<Float>()
        reader.forEachLine { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("LUT_3D_SIZE") -> {
                    size = trimmed.split(" ")[1].toInt()
                }
                trimmed.startsWith("#") || trimmed.startsWith("TITLE")
                        || trimmed.startsWith("DOMAIN_") -> {
                    // 跳过注释和元信息
                }
                trimmed.isEmpty() -> {}
                else -> {
                    val rgb = trimmed.split(Regex("\\s+"))
                    if (rgb.size == 3) {
                        values.add(rgb[0].toFloat())
                        values.add(rgb[1].toFloat())
                        values.add(rgb[2].toFloat())
                    }
                }
            }
        }
        require(size > 0) { "LUT_3D_SIZE not found" }
        return LutResult(size, values.toFloatArray())
    }
}
