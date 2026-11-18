package com.filmcam.app.develop

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DNG 快速探针：只读第一条 strip 的采样，判断底片是不是「废片」。
 *
 * 为什么要它：11-17 真机取证发现，App 冷启动后立刻抢拍会产出**空白底片**——
 * 整帧像素恒定在黑电平（S25U 实测 mean=64 / p99=66 / 邻差 0.9，对比正常帧
 * mean=88 / p99=441 / 邻差 9.3），引擎忠实渲染就是「黑底 + 胶片颗粒」= 品红噪点。
 * 老链路同样会中招（冲洗直接把噪点写入相册），只是以前没人盯着看。
 * 判定必须发生在**渲染之前**并留下取证日志，否则用户只会看到一张莫名其妙的废片。
 *
 * 另一种废片形态是「未初始化缓冲」：整行随机噪声（实测邻差 1714，正常 ≤15），
 * 同样判废。两种形态都只告警不阻断——底片数据本身保留，由导出侧验真兜底不入册。
 */
object DngQuickProbe {

    data class Probe(
        val samples: IntArray,
        val mean: Double,
        val maxMin: Int,
        val neighborDiff: Double,
        /** true = 疑似废片（空白/未初始化），只作告警依据，不作阻断。 */
        val garbage: Boolean,
        val reason: String,
    ) {
        val summary: String
            get() = "mean=%.1f range=%d 邻差=%.1f".format(mean, maxMin, neighborDiff)
    }

    /** 判废阈值（11-17 S25U 两类废片 vs 正常帧实测标定）： */
    private const val RANGE_BLANK_MAX = 24        // 空白帧整行极差 ~4-6；真实暗帧也有读出噪声，远超此值
    private const val NDIFF_BLANK_MAX = 2.0       // 空白帧邻差 0.9；真实帧 ≥5（ISO100 暗墙）
    private const val NDIFF_NOISE_MIN = 200.0     // 未初始化噪声邻差 >1000；极限 ISO 读噪也 <100

    /**
     * 纯判定：给一行（或任意连续段）16bit 采样，判是否废片。
     * 双条件 OR：恒定黑电平（空白）或高频不相关噪声（未初始化缓冲）。
     */
    fun looksLikeGarbage(samples: IntArray): Boolean {
        if (samples.size < 256) return false
        var min = Int.MAX_VALUE; var max = Int.MIN_VALUE
        var sum = 0.0
        for (v in samples) {
            if (v < min) min = v
            if (v > max) max = v
            sum += v
        }
        val mean = sum / samples.size
        var dsum = 0.0
        var dn = 0
        var i = 2
        while (i < samples.size) {
            dsum += Math.abs(samples[i] - samples[i - 2])
            dn++
            i += 2
        }
        val ndiff = dsum / dn
        return if (max - min < RANGE_BLANK_MAX && ndiff < NDIFF_BLANK_MAX) {
            true // 空白：整行恒定在黑电平附近
        } else if (ndiff > NDIFF_NOISE_MIN) {
            true // 未初始化：完全不相关的高频噪声
        } else false
    }

    /** 从 DNG 字节解析第一条 strip 的 16bit LE 采样（≤100k 个）。格式异常返回 null。 */
    fun parseFirstStrip(data: ByteArray): IntArray? {
        return try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.short != 0x4949.toShort()) return null            // 'II' little-endian
            if (buf.short.toInt() != 42) return null
            val ifdOff = buf.int
            buf.position(ifdOff)
            val count = buf.short.toInt()
            var stripOff = -1L
            var stripBytes = -1L
            repeat(count) {
                val tag = buf.short.toInt()
                val type = buf.short.toInt()
                val cnt = buf.int
                // LONG=4 / SHORT=2，值 ≤4 字节就内联；我们只关心前两个值的第一个
                val inline = when (type) {
                    3 -> 2 * cnt <= 4; 4 -> 4 * cnt <= 4
                    else -> cnt * 8 <= 4 // 其余类型不关心，按最大宽度兜底
                }
                val v: Long = if (inline) {
                    val v = when (type) {
                        // SHORT 内联也要补齐 4 字节值域，否则下一条 entry 错位
                        3 -> { val s = buf.short.toLong() and 0xFFFF; buf.short; s }
                        4 -> buf.int.toLong() and 0xFFFFFFFFL
                        else -> { buf.int; -1L }
                    }
                    v
                } else {
                    val off = buf.int.toLong() and 0xFFFFFFFFL
                    // 跳到外部值取第一个（LONG/SHORT）
                    val saved = buf.position()
                    when (type) {
                        4 -> { buf.position(off.toInt()); buf.int.toLong() and 0xFFFFFFFFL }
                        3 -> { buf.position(off.toInt()); buf.short.toLong() and 0xFFFF }
                        else -> -1L
                    }.also { buf.position(saved) }
                }
                when (tag) {
                    273 -> stripOff = v
                    279 -> stripBytes = v
                }
            }
            if (stripOff < 0 || stripBytes <= 0) return null
            val n = minOf(stripBytes / 2, 100_000L).toInt()
            buf.position(stripOff.toInt())
            val out = IntArray(n)
            for (k in 0 until n) out[k] = buf.short.toInt() and 0xFFFF
            out
        } catch (_: Throwable) {
            null
        }
    }

    /** IO 包装：读文件头部 ~2MB 足够覆盖 IFD + 第一条 strip 的开头。 */
    fun probe(file: File): Probe? {
        if (!file.exists() || file.length() < 1024) return null
        return try {
            val head = file.inputStream().use { ins ->
                val buf = ByteArray(1024 * 1024)
                val n = ins.read(buf)
                if (n <= 0) null else buf.copyOf(n)
            } ?: return null
            val samples = parseFirstStrip(head) ?: return null
            var min = Int.MAX_VALUE; var max = Int.MIN_VALUE; var sum = 0.0
            for (v in samples) {
                if (v < min) min = v; if (v > max) max = v; sum += v
            }
            var dsum = 0.0; var dn = 0
            var i = 2
            while (i < samples.size) { dsum += Math.abs(samples[i] - samples[i - 2]); dn++; i += 2 }
            val mean = sum / samples.size
            val maxMin = max - min
            val ndiff = dsum / dn
            val garbage = looksLikeGarbage(samples)
            val reason = when {
                garbage && ndiff < NDIFF_BLANK_MAX -> "整行恒定黑电平（快门过早/传感器未就绪）"
                garbage -> "高频不相关噪声（缓冲未初始化）"
                else -> ""
            }
            Probe(samples, mean, maxMin, ndiff, garbage, reason)
        } catch (_: Throwable) {
            null
        }
    }
}
