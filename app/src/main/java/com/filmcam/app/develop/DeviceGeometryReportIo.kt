package com.filmcam.app.develop

import android.content.Context
import java.io.File

/**
 * [DeviceGeometryReport] 的落盘与读取。
 *
 * 文件写在 **外部私有目录**（`getExternalFilesDir`），内测用户不需要 root / adb，
 * 用系统文件管理器就能找到；App 内「关于 → 问题反馈」还能一键复制或分享全文。
 * 路径：/sdcard/Android/data/com.filmcam.app/files/filmcam_device_report.txt
 */
object DeviceGeometryReportIo {

    const val FILE_NAME = "filmcam_device_report.txt"

    /** 只在字段有变化时写一次；任何失败都吞掉，绝不影响主链路。 */
    fun flush(context: Context) {
        val text = runCatching { DeviceGeometryReport.snapshotIfDirty() }.getOrNull() ?: return
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, FILE_NAME).writeText(text)
        }
    }

    /** 先补写一次，再返回最新全文；文件读不到时退回内存报告。 */
    fun read(context: Context): String {
        flush(context)
        return runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val f = File(dir, FILE_NAME)
            if (f.exists()) f.readText() else DeviceGeometryReport.render()
        }.getOrDefault(DeviceGeometryReport.render())
    }
}
