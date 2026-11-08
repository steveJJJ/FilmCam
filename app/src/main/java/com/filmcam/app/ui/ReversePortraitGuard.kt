package com.filmcam.app.ui

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.hardware.SensorManager
import android.os.Bundle
import android.view.OrientationEventListener

/**
 * v55：`fullSensor` 下只禁掉「反向竖屏」（手机竖着倒过来），横屏照常可用。
 *
 * ## 为什么不用 Manifest 的 `portrait`
 * 各 Activity 原本是 `fullSensor`（四个方向都能用）。直接改成 `portrait` 确实能让
 * 倒过来不翻转，但会**连两侧横屏一起禁掉** —— 横屏是相机的主要拍摄方向，
 * Play 商店首图也是横屏取景器。所以这里在传感器层面拦掉 180° 那一个方向：
 * 检测到倒竖就把当前 Activity 锁成 `SCREEN_ORIENTATION_PORTRAIT`（界面保持正竖，
 * 铭牌不会跑进物理顶部的状态栏 / 挖孔预留区），离开该角度立刻还回 `FULL_SENSOR`。
 *
 * ## 为什么放在 Application
 * 通过 [Application.ActivityLifecycleCallbacks] 跟踪当前 resume 的页面，一处覆盖
 * MainActivity / 暗房 / 相册 / 关于 / 设置，不用每个 Activity 各写一遍；
 * 进程被后台杀死后系统直接 resume 栈顶页面时也能立刻生效。
 *
 * 传感器回调早于系统切换方向，所以在窗口真正转成 reversePortrait 之前就已锁定，
 * 正常情况下不会触发一次多余的配置重建。
 */
object ReversePortraitGuard {

    /**
     * 纯函数：设备物理角度是否落在反向竖屏区间（150°~210°，留了容差）。
     * `OrientationEventListener.ORIENTATION_UNKNOWN`(-1) 自动返回 false。
     */
    fun isReversePortrait(degrees: Int): Boolean = degrees in 150..210

    private var reverse = false
    private var current: Activity? = null

    fun install(app: Application) {
        val listener = object : OrientationEventListener(app, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(degrees: Int) {
                val now = isReversePortrait(degrees)
                if (now == reverse) return
                reverse = now
                current?.let { apply(it) }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                current = activity
                apply(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (current === activity) current = null
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (current === activity) current = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    private fun apply(activity: Activity) {
        if (activity.isFinishing) return
        val wanted = if (reverse) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        if (activity.requestedOrientation != wanted) {
            activity.requestedOrientation = wanted
        }
    }
}
