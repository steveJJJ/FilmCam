package com.filmcam.app

/**
 * v67：反馈邮件正文合成（纯 Kotlin、零 Android 依赖，便于 JVM 单测）。
 *
 * 关于页原本有「复制设备诊断报告」「分享设备诊断报告」两个重复入口，
 * 现合并进「发邮件反馈」：点发邮件时自动把设备诊断报告的全文
 * 附在邮件正文末尾，用户无需再手动复制或分享。
 *
 * 报告以纯文本内嵌，不走附件 —— `ACTION_SENDTO` 不支持 `EXTRA_STREAM`，
 * 加附件要引入 FileProvider 与 URI 授权，而报告只有约 2 KB，正文足够。
 * 这样也保持零新增权限、零新增组件。
 */
object FeedbackMail {

    /**
     * 把诊断报告接到反馈模板后面。
     *
     * - 模板尾部换行归一化，避免出现连续多个空行；
     * - [report] 为空 / 读取失败时**不追加段落**，邮件仍可正常发出；
     * - [header] 为空时只留一个空行分隔；
     * - 报告内部换行、分组标题原样保留，只裁掉首尾空白。
     */
    fun composeBody(template: String, header: String, report: String): String {
        val base = template.trimEnd()
        val diag = report.trim()
        if (diag.isEmpty()) return base
        val label = header.trim()
        return if (label.isEmpty()) "$base\n\n$diag" else "$base\n\n$label\n$diag"
    }
}
