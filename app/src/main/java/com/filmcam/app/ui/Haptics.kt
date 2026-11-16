package com.filmcam.app.ui

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.widget.ScrollView
import kotlin.math.roundToInt

/**
 * 全局触感反馈（v78 计划起）：取景器（MainActivity）之外的所有页面共用。
 *
 * 口径与 MainActivity.vibrate() 完全一致 —— 同一个 "filmcam" prefs 里的
 * hapticLevel 档位（0=低 0.55 / 1=中 1.0 / 2=强 1.45），同一个默认振幅 170，
 * 同一个 [24,255] 夹紧。MainActivity 保留自己的私有实现（启动时已在内存里、
 * 避免高频调用反复读 prefs），两边改一处必须同步另一处。
 */
object Haptics {

    /** 默认振幅：三星线性马达上低于这个值会有「若有若无」的问题（见 MainActivity.vibrate 注释） */
    const val AMP_DEFAULT = 170

    private val LEVEL_SCALE = floatArrayOf(0.55f, 1.0f, 1.45f)

    fun vibrate(context: Context, ms: Long, amp: Int = AMP_DEFAULT) {
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val base = if (amp > 0) amp else AMP_DEFAULT
                val level = context.getSharedPreferences("filmcam", Context.MODE_PRIVATE)
                    .getInt("hapticLevel", 1).coerceIn(0, 2)
                val scaled = (base * LEVEL_SCALE[level])
                    .roundToInt()
                    .coerceIn(24, 255)
                vibrator?.vibrate(VibrationEffect.createOneShot(ms, scaled))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(ms)
            }
        } catch (_: Exception) {}
    }
}

/**
 * 机身按键式点击触感：按下先给一记短震再执行动作。
 * 与 MainActivity 里的同名私有扩展一致；这里给其余页面（相册 / 暗房 / 关于 / 看片）用。
 */
fun View.hapticClick(ms: Long = 24, amp: Int = 175, action: () -> Unit) {
    setOnClickListener { Haptics.vibrate(context, ms, amp); action() }
}

/**
 * 滑杆拖动触感：拖动时按时间节流给一记很轻的 tick（机械滑杆的棘轮手感）。
 *
 * 必须只在 onProgressChanged(fromUser=true) 时调用 —— 程序回填（syncUi/恢复默认）
 * 不震。节流间隔与 FilmRollPicker 的滑动触感同量级（12ms/95amp 那一套），
 * 太密会连成一片糊震，太疏又会觉得「丢了帧」。
 */
class SliderTick(
    private val context: Context,
    private val gapMs: Long = 55,
    private val ms: Long = 8,
    private val amp: Int = 95,
) {
    private var lastAtMs = 0L

    fun onUserProgress() {
        val now = SystemClock.uptimeMillis()
        if (now - lastAtMs >= gapMs) {
            lastAtMs = now
            Haptics.vibrate(context, ms, amp)
        }
    }
}

/**
 * 滚动棘轮：按「滚过一行」分档给一记极轻 tick。
 *
 * 与 [SliderTick] 同口径（8ms / 95amp），但按**距离分桶**而不是按拖动事件数：
 * 惯性滑动（fling）也会随经过的行给触感，快滑时又被 55ms 时间节流挡住，
 * 不会连成一片糊震。stepPx 取约一行的高度（44dp），由调用方按屏密度给。
 */
class ScrollDetents(
    private val context: Context,
    private val stepPx: Int,
    private val gapMs: Long = 55,
    private val ms: Long = 8,
    private val amp: Int = 95,
) {
    private var lastBucket = 0
    private var lastAtMs = 0L

    /** 起始 scrollY 归零，避免把「打开面板时的位置」误算成跨过了一档 */
    fun reset(scrollY: Int = 0) {
        lastBucket = if (stepPx > 0) scrollY / stepPx else 0
    }

    fun onScroll(scrollY: Int) {
        if (stepPx <= 0) return
        val bucket = scrollY / stepPx
        if (bucket == lastBucket) return
        lastBucket = bucket
        val now = SystemClock.uptimeMillis()
        if (now - lastAtMs >= gapMs) {
            lastAtMs = now
            Haptics.vibrate(context, ms, amp)
        }
    }
}

/**
 * 滚动到头/到底的「机械限位」触感：滚到顶或底再给一记轻震，
 * 像机身上的转盘拨到了头 —— 没有这记，翻到尽头那一下是纯视觉的空落感。
 * 只在「到达边缘」时震一次，离开边缘再回来才会再震（连续贴边拖不连震）。
 *
 * [detents] 非空时同时挂上滚动棘轮；[onScroll] 给调用方同步「还能不能往下滚」
 * （设置面板用它点亮底沿的渐隐与刻线）。关于页不传这两个参数，行为与 v78 一致。
 */
fun ScrollView.attachEdgeHaptics(
    ms: Long = 16,
    amp: Int = 130,
    detents: ScrollDetents? = null,
    onScroll: ((canScrollDown: Boolean) -> Unit)? = null
) {
    var atTop = !canScrollVertically(-1)
    var atBottom = !canScrollVertically(1)
    detents?.reset(scrollY)
    setOnScrollChangeListener { _, _, _, _, _ ->
        detents?.onScroll(scrollY)
        val topNow = !canScrollVertically(-1)
        val bottomNow = !canScrollVertically(1)
        if (topNow && !atTop) Haptics.vibrate(context, ms, amp)
        if (bottomNow && !atBottom) Haptics.vibrate(context, ms, amp)
        atTop = topNow
        atBottom = bottomNow
        onScroll?.invoke(!bottomNow)
    }
}
