package com.filmcam.app

import android.content.Context

/**
 * 调试落盘的总开关（`spektra_probe.txt` / `spektra_params.json` / `spektra_frame_probe.txt`）。
 *
 * 这些文件当初是为了**开发期能 `adb pull` 出来看**才写到外部私有目录的
 * （release 包不能 `run-as`，放内部存储拿不到）。但它们是每次冲洗都写的，
 * 普通用户既看不懂也不会去看 —— 白占机身存储，还被文件管理器的「安装包/文档」扫到。
 *
 * 所以统一跟着工程师模式：只有把开发旋钮翻出来的人，才需要这些落盘。
 * 关掉之后诊断信息仍然进 logcat，不丢排查能力。
 */
object DevDump {

    fun enabled(context: Context): Boolean = EngineerMode.isOn(context)
}
