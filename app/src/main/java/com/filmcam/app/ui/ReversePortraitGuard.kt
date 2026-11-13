package com.filmcam.app.ui

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener

/**
 * v55：`fullSensor` 下只禁掉「反向竖屏」（手机竖着倒过来），横屏照常可用。
 * vc76 D：加滞回 + 去抖，消除微抖跨边界时连续改 `requestedOrientation`。
 *
 * ## 为什么不用 Manifest 的 `portrait`
 * 各 Activity 原本是 `fullSensor`（四个方向都能用）。直接改成 `portrait` 确实能让
 * 倒过来不翻转，但会**连两侧横屏一起禁掉** —— 横屏是相机的主要拍摄方向，
 * Play 商店首图也是横屏取景器。所以这里在传感器层面拦掉 180° 那一个方向：
 * 检测到倒竖就把当前 Activity 锁成 `SCREEN_ORIENTATION_PORTRAIT`（界面保持正竖，
 * 铭牌不会跑进物理顶部的状态栏 / 挖孔预留区），离开该角度再还回 `FULL_SENSOR`。
 *
 * ## 为什么放在 Application
 * 通过 [Application.ActivityLifecycleCallbacks] 跟踪当前 resume 的页面，一处覆盖
 * MainActivity / 暗房 / 相册 / 关于 / 设置，不用每个 Activity 各写一遍；
 * 进程被后台杀死后系统直接 resume 栈顶页面时也能立刻生效。
 *
 * ⚠️ vc76 起 MainActivity 声明了 `configChanges`，`requestedOrientation` 变化
 * 不再触发 Activity 重建（也不关相机）；但其他 4 页仍会重建，所以滞回/去抖
 * 依旧要留在这一层，且**继续对所有页面生效** —— 只对 MainActivity 生效会让
 * 相册/关于倒过来时铭牌与状态栏挤在一起，正是本类当初要修的问题。
 */
object ReversePortraitGuard {

    /** 进入反向竖屏的窄区间：必须比较明确地倒拿才锁。 */
    private const val ENTER_MIN = 165
    private const val ENTER_MAX = 195

    /** 维持已锁状态的宽区间：要越出 145~215 才解锁（滞回带，防边界抖动）。 */
    private const val EXIT_MIN = 145
    private const val EXIT_MAX = 215

    /** 角度跨过阈值后要稳定这么久才真正写 requestedOrientation。 */
    private const val DEBOUNCE_MS = 300L

    /**
     * 纯函数：带滞回的反向竖屏判定。
     * - 未锁：只有 165~195° 才进锁；
     * - 已锁：145~215° 内一直保持，出了这个范围才解锁；
     * - `OrientationEventListener.ORIENTATION_UNKNOWN`(-1) 一律保持现状（手机平放）。
     *
     * 把纯判定抽出来是为了 JVM 单测能直接覆盖边界，不依赖传感器。
     */
    fun nextReverse(current: Boolean, degrees: Int): Boolean {
        if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN) return current
        val d = ((degrees % 360) + 360) % 360
        return if (current) d in EXIT_MIN..EXIT_MAX else d in ENTER_MIN..ENTER_MAX
    }

    /** v55 原始无滞回口径，保留给诊断与旧单测对照，不再参与实际决策。 */
    fun isReversePortrait(degrees: Int): Boolean = degrees in 150..210

    private var reverse = false
    private var current: Activity? = null
    private var latestDegrees = OrientationEventListener.ORIENTATION_UNKNOWN
    private var pending: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    fun install(app: Application) {
        val listener = object : OrientationEventListener(app, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(degrees: Int) {
                latestDegrees = degrees
                val want = nextReverse(reverse, degrees)
                if (want == reverse) {
                    // 回到稳定区间：撤销还没落地的切换，抖动就不会累积成多次改方向
                    cancelPending()
                    return
                }
                if (pending != null) return
                val task = Runnable {
                    pending = null
                    // 用「最新角度」而不是触发时的角度再判一次，短促的抖动会被自然吃掉
                    val settled = nextReverse(reverse, latestDegrees)
                    if (settled == reverse) return@Runnable
                    reverse = settled
                    current?.let { apply(it) }
                }
                pending = task
                handler.postDelayed(task, DEBOUNCE_MS)
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

    private fun cancelPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
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
