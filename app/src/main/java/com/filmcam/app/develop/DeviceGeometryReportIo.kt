package com.filmcam.app.develop

import android.content.Context
import java.io.File

/**
 * [DeviceGeometryReport] 的落盘与读取。
 *
 * 文件写在 **外部私有目录**（`getExternalFilesDir`），内测用户不需要 root / adb，
 * 用系统文件管理器就能找到；App 内「关于 → 问题反馈 → 发邮件反馈」会自动把全文附进邮件正文。
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

    /**
     * 导出用：直接以**内存里的当前全文**覆写文件并返回。
     * v64：不依赖 dirty 标记 —— GL 线程与 UI 线程可能并发 flush 并清掉脏标记，
     * 而 transformMatrix 又在持续更新；用户点导出时必须拿到最新快照。
     */
    fun read(context: Context): String {
        val text = DeviceGeometryReport.render()
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, FILE_NAME).writeText(text)
        }
        return text
    }
}
