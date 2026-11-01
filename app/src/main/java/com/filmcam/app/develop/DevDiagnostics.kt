package com.filmcam.app.develop

import android.content.Context

/**
 * 冲洗耗时诊断。
 *
 * 同一份代码在不同机器上的冲洗速度能差好几倍：GPU 有没有 Vulkan、CPU 几核、
 * 摄像头最大 JPEG 是不是 50MP —— 光看“慢”没法定论。这里把每帧的时间拆成
 * 三段（解码 / 引擎 / 落盘），连同输入输出分辨率、引擎版本与能力一起记下来，
 * 冲完可以在「关于 → 工程师模式」里看到并复制走。
 */
object DevDiagnostics {

    @Volatile var frames: Int = 0
    @Volatile var totalMs: Long = 0
    @Volatile var maxMs: Long = 0
    @Volatile var decodeMs: Long = 0
    @Volatile var engineMs: Long = 0
    @Volatile var ioMs: Long = 0
    @Volatile var inW: Int = 0
    @Volatile var inH: Int = 0
    @Volatile var outW: Int = 0
    @Volatile var outH: Int = 0
    @Volatile var downscaled: Boolean = false
    @Volatile var build: String = "?"
    @Volatile var caps: String = "?"

    fun begin(buildInfo: String, capabilities: String) {
        frames = 0; totalMs = 0; maxMs = 0; decodeMs = 0; engineMs = 0; ioMs = 0
        inW = 0; inH = 0; outW = 0; outH = 0; downscaled = false
        build = buildInfo.lineSequence().firstOrNull()?.take(160) ?: "?"
        caps = capabilities.replace('\n', ' ').take(300)
    }

    fun frame(
        frameMs: Long,
        decode: Long,
        engine: Long,
        io: Long,
        inputW: Int,
        inputH: Int,
        outputW: Int,
        outputH: Int,
        wasDownscaled: Boolean,
    ) {
        frames++
        totalMs += frameMs
        if (frameMs > maxMs) maxMs = frameMs
        decodeMs += decode
        engineMs += engine
        ioMs += io
        inW = inputW; inH = inputH
        outW = outputW; outH = outputH
        downscaled = downscaled || wasDownscaled
    }

    val avgMs: Long get() = if (frames > 0) totalMs / frames else 0

    /** 归档到 preferences，下次打开 App（进程重启后）也能看到上一次的读数 */
    fun save(context: Context) {
        context.getSharedPreferences("filmcam", Context.MODE_PRIVATE).edit().apply {
            putInt("diag_frames", frames)
            putLong("diag_avg_ms", avgMs)
            putLong("diag_max_ms", maxMs)
            putLong("diag_decode_ms", if (frames > 0) decodeMs / frames else 0)
            putLong("diag_engine_ms", if (frames > 0) engineMs / frames else 0)
            putLong("diag_io_ms", if (frames > 0) ioMs / frames else 0)
            putInt("diag_in_w", inW)
            putInt("diag_in_h", inH)
            putInt("diag_out_w", outW)
            putInt("diag_out_h", outH)
            putBoolean("diag_downscaled", downscaled)
            putString("diag_build", build)
            putString("diag_caps", caps)
            putString("diag_device", deviceLabel())
        }.apply()
    }

    private fun deviceLabel(): String = runCatching {
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE})"
    }.getOrDefault("?")

    /** 汇总文本（logcat 与工程师模式共用一份） */
    fun summary(context: Context): String {
        val p = context.getSharedPreferences("filmcam", Context.MODE_PRIVATE)
        val n = p.getInt("diag_frames", frames)
        if (n <= 0) return ""
        val avg = p.getLong("diag_avg_ms", avgMs)
        val max = p.getLong("diag_max_ms", maxMs)
        val dec = p.getLong("diag_decode_ms", 0)
        val eng = p.getLong("diag_engine_ms", 0)
        val io = p.getLong("diag_io_ms", 0)
        return buildString {
            appendLine("frames=$n avg=${avg}ms max=${max}ms")
            appendLine("split: decode=${dec}ms engine=${eng}ms write=${io}ms")
            appendLine("input=${p.getInt("diag_in_w", inW)}x${p.getInt("diag_in_h", inH)} " +
                "-> output=${p.getInt("diag_out_w", outW)}x${p.getInt("diag_out_h", outH)} " +
                "downscaled=${p.getBoolean("diag_downscaled", downscaled)}")
            appendLine("engine=${p.getString("diag_build", build)}")
            appendLine("caps=${p.getString("diag_caps", caps)}")
            append("device=${p.getString("diag_device", deviceLabel())}")
        }
    }

    /** 是否有过一次完整冲洗的读数 */
    fun hasData(context: Context): Boolean =
        context.getSharedPreferences("filmcam", Context.MODE_PRIVATE).getInt("diag_frames", frames) > 0
}
