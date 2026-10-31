package com.filmcam.app.develop

import android.content.Context
import android.content.Intent

/**
 * 冲洗侧入口控制器（规格 §6.1 冻结契约）。
 *
 *  - [startDevelopment]：打开暗房活动（带仪式感 UI）；若该卷已在冲洗则仅打开活动，
 *    活动自身负责挂接 [DevelopmentState] 的进度。
 *  - [developRollInBackground]：直接起前台服务后台冲洗（调试即时出片路径；
 *    暗房活动"后台冲洗"按钮也复用——活动 finish 后服务自然续跑）。
 */
object DevelopController {

    const val EXTRA_ROLL_ID = "roll_id"

    /** 启动 DevelopmentActivity（带 extra roll_id）。 */
    fun startDevelopment(context: Context, rollId: String) {
        val intent = Intent(context, DevelopmentActivity::class.java).apply {
            putExtra(EXTRA_ROLL_ID, rollId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 直接 startForegroundService 启动 DevelopmentService 后台冲洗。 */
    fun developRollInBackground(context: Context, rollId: String) {
        val intent = Intent(context, DevelopmentService::class.java).apply {
            putExtra(EXTRA_ROLL_ID, rollId)
        }
        DevelopmentService.start(context, intent)
    }
}
