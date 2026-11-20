package com.filmcam.app.ui

import android.animation.TimeInterpolator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator

/**
 * Relic Cam 统一动效 token（11-21 起）。
 *
 * 设计口径：机械感 = 段落 + 阻尼，不是弹跳。
 * 常规转场一律不用 overshoot spring；只有「拍下拨盘」那一刻允许极轻过冲（≤1.01）。
 *
 * 时长来源对齐 Material Design 3：短 100ms / 中 200ms / 长 300ms 的档位，
 * 这里按 Relic Cam 的机械质感整体压缩一档（机身按键比触屏 UI 更「利落」）。
 */
object Motion {

    const val PRESS_DOWN = 60L
    const val PRESS_UP = 140L
    const val TICK = 120L
    const val ROLL = 160L
    const val PANEL_IN = 250L
    const val PANEL_OUT = 180L
    const val CHROME_IN = 120L
    const val REVEAL = 400L

    /** M3 Standard decelerate：快起慢收，用于入场/回弹/滚字 */
    val decelerate: TimeInterpolator = PathInterpolator(0f, 0f, 0f, 1f)

    /** M3 Standard accelerate：慢起快收，用于按下/退场 */
    val accelerate: TimeInterpolator = PathInterpolator(0.3f, 0f, 1f, 1f)

    /** 系统动画开关关闭时压到 30%，调用方只保留 alpha、去掉位移/缩放。 */
    fun duration(context: Context, baseMs: Long): Long {
        val scale = try {
            @Suppress("DEPRECATION")
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        } catch (_: Exception) {
            1f
        }
        return if (scale <= 0f) (baseMs * 0.3f).toLong() else (baseMs * scale).toLong()
    }

    fun reduced(context: Context): Boolean = duration(context, 100L) < 100L

    /** 七段管滚字进度 0→1；变化位的 y 偏移 = h×(1−eased)。 */
    fun rollProgress(nowMs: Long, startMs: Long, baseMs: Long): Float {
        if (startMs <= 0L) return 1f
        val raw = ((nowMs - startMs).toFloat() / baseMs).coerceIn(0f, 1f)
        return raw * raw * (3f - 2f * raw) // smoothstep，等价于 decelerate 的视觉曲线
    }
}

/**
 * 机身按键的机械按压：按下快（accelerate 下沉）、抬手慢（decelerate 回弹）。
 *
 * 挂在 DOWN / UP / CANCEL 三处——CANCEL（父容器抢走手势 / 手指移出后系统取消）
 * 也必须回弹，否则按钮会卡在缩小态。每次先 cancel 旧动画，快速连点不累积缩放。
 * 返回 false 不消费事件，原有 OnClick（hapticClick）照常触发。
 *
 * reduced-motion 下不做缩放，只做轻微的 alpha 变化。
 */
fun View.attachPressFeedback(
    scaleDown: Float = 0.94f,
    downMs: Long = Motion.PRESS_DOWN,
    upMs: Long = Motion.PRESS_UP
) {
    val ctx = context
    val reduced = Motion.reduced(ctx)
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().cancel()
                if (reduced) {
                    v.animate().alpha(0.82f)
                        .setDuration(Motion.duration(ctx, downMs))
                        .setInterpolator(Motion.accelerate)
                } else {
                    v.animate().scaleX(scaleDown).scaleY(scaleDown)
                        .setDuration(Motion.duration(ctx, downMs))
                        .setInterpolator(Motion.accelerate)
                }.start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().cancel()
                if (reduced) {
                    v.animate().alpha(1f)
                        .setDuration(Motion.duration(ctx, upMs))
                        .setInterpolator(Motion.decelerate)
                } else {
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(Motion.duration(ctx, upMs))
                        .setInterpolator(Motion.decelerate)
                }.start()
            }
        }
        false
    }
}

/** 面板入场：干净淡入 + 12dp 上移（不交叉淡化，M3 clean fade）。 */
fun View.animatePanelIn() {
    val ctx = context
    val reduced = Motion.reduced(ctx)
    alpha = 0f
    if (!reduced) translationY = 12f * resources.displayMetrics.density
    animate().cancel()
    animate().alpha(1f)
        .also { if (!reduced) it.translationY(0f) }
        .setDuration(Motion.duration(ctx, Motion.PANEL_IN))
        .setInterpolator(Motion.decelerate)
        .start()
}

/** 面板退场：比入场短，干净淡出。 */
fun View.animatePanelOut(onEnd: () -> Unit) {
    val ctx = context
    val reduced = Motion.reduced(ctx)
    animate().cancel()
    animate().alpha(0f)
        .also { if (!reduced) it.translationY(8f * resources.displayMetrics.density) }
        .setDuration(Motion.duration(ctx, Motion.PANEL_OUT))
        .setInterpolator(Motion.accelerate)
        .withEndAction {
            visibility = View.GONE
            onEnd()
        }
        .start()
}

/**
 * 旋转换 chrome：新机身只做干净淡入，不动位移。
 *
 * 铭牌/机顶键的位置是布局契约（真机调过很多轮），不能像面板那样带上 translationY；
 * 旧 chrome 也不能等淡出结束再摘 —— 那样 binding / 触摸分发会短暂指向旧布局，
 * 旋转契约（方向重放、Pro 徽标恢复）会被打破。所以这里只淡入新 chrome。
 */
fun View.fadeInChrome() {
    val ctx = context
    if (Motion.reduced(ctx)) {
        alpha = 1f
        return
    }
    alpha = 0f
    animate().cancel()
    animate().alpha(1f)
        .setDuration(Motion.duration(ctx, Motion.CHROME_IN))
        .setInterpolator(Motion.decelerate)
        .start()
}

/**
 * 场景入场（暗房显影页）：400ms 干净淡入 + 16dp 上移，reduced-motion 只留 alpha。
 * 不叠在显影页自己的「模糊→清晰」上太久：它只在 onCreate 跑一次。
 */
fun View.animateSceneReveal() {
    val ctx = context
    val reduced = Motion.reduced(ctx)
    alpha = 0f
    if (!reduced) translationY = 16f * resources.displayMetrics.density
    animate().cancel()
    animate().alpha(1f)
        .also { if (!reduced) it.translationY(0f) }
        .setDuration(Motion.duration(ctx, Motion.REVEAL))
        .setInterpolator(Motion.decelerate)
        .start()
}
