package com.filmcam.app.ui

import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.filmcam.app.develop.DeviceGeometryReport

/**
 * 把 [FoldPosture.Result] 落到取景器界面（P0 骨架，Contax 645 MF-2 取景罩语言）。
 *
 * 设计取舍：不重建布局、不 reparent —— 只改 6 个 View 的 LayoutParams / 可见性，
 * 并把 root 下其余 chrome 整体隐藏。这样 `MainActivity.swapChromeForOrientation()`
 * 重新 inflate 机身控件后，只要再调一次 [apply] 就幂等恢复，不需要跟着换布局文件。
 *
 * B 半屏从上到下三段：
 *  ① 取景罩顶沿 [topBar]：一行细刻字铭牌 + 两颗螺丝（MF-2 罩壁顶框）
 *  ② 磨砂取景屏：预览 + 取景框让出顶沿高度，叠加 [overlay] 画四角小三角与放大镜位
 *  ③ 折叠前盖 [bar]：CLOSE / 实时读数 / 快门
 * A 半屏铺纯黑遮罩：折叠屏都是 OLED，纯黑等于像素关闭，既不给被摄者看到
 * 界面反光也几乎不耗电。App 层无法真正关闭半屏背光，这是通用做法。
 *
 * 入场动效模拟 MF-2「单触弹开」：顶沿从上落下、前盖从下抬起、屏框淡入，240ms。
 */
object WaistLevelChrome {

    private val originalLp = HashMap<View, FrameLayout.LayoutParams>()
    private val originalVisibility = HashMap<View, Int>()
    private var lastWaist = false
    /** 弹开动画进行中：期间不做稳态自愈，免把动画中途的 alpha 提到 1。 */
    private var opening = false

    private const val OPEN_MS = 240L

    /**
     * @return 是否真的改动了布局（值不变时不写，避免 layout 回调自激循环）。
     */
    fun apply(
        root: FrameLayout,
        gl: View,
        frame: View,
        mask: View,
        topBar: View,
        overlay: View,
        bar: View,
        developingOverlay: View,
        posture: FoldPosture.Result,
    ): Boolean {
        val waist = posture.mode == FoldPosture.Mode.WAIST
        val whitelist = setOf(gl, frame, mask, topBar, overlay, bar, developingOverlay)
        var changed = false

        // 首次见到时留一份原始参数，退出腰平时逐像素还原。
        listOf(gl, frame).forEach { v ->
            (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                originalLp.putIfAbsent(v, FrameLayout.LayoutParams(lp))
            }
        }
        for (idx in 0 until root.childCount) {
            val v = root.getChildAt(idx)
            if (v in whitelist) continue
            originalVisibility.putIfAbsent(v, v.visibility)
            val want = if (waist) View.GONE else (originalVisibility[v] ?: View.VISIBLE)
            if (v.visibility != want) {
                v.visibility = want
                changed = true
            }
        }

        // 顶沿（34dp）与前盖（72dp）都是实心块，取景屏夹在两者之间：
        // 预览高度必须把两段都扣掉，否则取景格下沿会被前盖压住看不见。
        val topH = (topBar.layoutParams?.height ?: 0).coerceAtLeast(0)
        val barH = (bar.layoutParams?.height ?: 0).coerceAtLeast(0)

        // 画幅矩形：在「取景窗 − 顶沿 − 前盖」的剩余区域里按 posture.gateAspect
        // 内接居中。GL 表面 / T3 取景框 / MF-2 覆盖层三者同几何铺在这一块上 ——
        // 预览裁切填充铺满（waistFill），表面即画幅，所见即成片、零拉伸。
        val f = posture.finder
        val areaW = if (waist) f.width else 0
        val areaH = if (waist) (f.height - topH - barH).coerceAtLeast(0) else 0
        val ga = posture.gateAspect.takeIf { it > 0f } ?: 3f / 2f
        var gateW = areaW.toFloat()
        var gateH = if (ga > 0f) gateW / ga else 0f
        if (gateH > areaH) {
            gateH = areaH.toFloat()
            gateW = gateH * ga
        }
        val gateL = f.left + (areaW - gateW) / 2f
        val gateT = (f.top + topH) + (areaH - gateH) / 2f

        // 预览与取景框：腰平态只占画幅矩形；退出后逐像素还原。
        listOf(gl, frame).forEach { v ->
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return@forEach
            val before = FrameLayout.LayoutParams(lp)
            if (waist) {
                lp.width = gateW.toInt()
                lp.height = gateH.toInt()
                lp.gravity = Gravity.TOP or Gravity.START
                lp.leftMargin = gateL.toInt()
                lp.topMargin = gateT.toInt()
                lp.rightMargin = 0
                lp.bottomMargin = 0
            } else {
                originalLp[v]?.let { snap ->
                    lp.width = snap.width
                    lp.height = snap.height
                    lp.gravity = snap.gravity
                    lp.leftMargin = snap.leftMargin
                    lp.topMargin = snap.topMargin
                    lp.rightMargin = snap.rightMargin
                    lp.bottomMargin = snap.bottomMargin
                }
            }
            if (different(before, lp)) {
                v.layoutParams = lp
                changed = true
            }
        }

        // 纯黑遮罩：只盖摄像头那一半，盖在所有 chrome 之上（它是 root 的后置 child）。
        val maskWant = if (waist) View.VISIBLE else View.GONE
        if (mask.visibility != maskWant) {
            mask.visibility = maskWant
            changed = true
        }
        if (waist) {
            val m = posture.mask
            changed = setLp(mask) {
                width = m.width
                height = m.height
                gravity = Gravity.TOP or Gravity.START
                leftMargin = m.left
                topMargin = m.top
                rightMargin = 0
                bottomMargin = 0
            } || changed
        }

        // 取景罩顶沿：贴在取景窗最上沿，宽度与取景窗一致。
        if (topBar.visibility != maskWant) {
            topBar.visibility = maskWant
            changed = true
        }
        if (waist) {
            val f = posture.finder
            changed = setLp(topBar) {
                width = f.width
                height = topH
                gravity = Gravity.TOP or Gravity.START
                leftMargin = f.left
                topMargin = f.top
                rightMargin = 0
                bottomMargin = 0
            } || changed
        }

        // 屏框覆盖层：与画幅矩形同几何，MF-2 取景格直接画在它自己的 bounds 上。
        if (overlay.visibility != maskWant) {
            overlay.visibility = maskWant
            changed = true
        }
        if (waist) {
            changed = setLp(overlay) {
                width = gateW.toInt()
                height = gateH.toInt()
                gravity = Gravity.TOP or Gravity.START
                leftMargin = gateL.toInt()
                topMargin = gateT.toInt()
                rightMargin = 0
                bottomMargin = 0
            } || changed
        }

        // 折叠前盖：贴在取景窗近端（靠摄影者的下沿）。
        val barWant = if (waist) View.VISIBLE else View.GONE
        if (bar.visibility != barWant) {
            bar.visibility = barWant
            changed = true
        }
        if (waist) {
            val f = posture.finder
            changed = setLp(bar) {
                width = f.width
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = f.left
                topMargin = 0
                rightMargin = 0
                bottomMargin = (root.height - f.bottom).coerceAtLeast(0)
            } || changed
        }

        // 只在「常规 → 腰平」这一次跳变播弹开动效；反复 apply 不重播。
        if (waist && !lastWaist) {
            playOpen(topBar, overlay, bar)
        } else if (!waist && lastWaist) {
            resetTransforms(topBar, overlay, bar)
        } else if (waist && !opening) {
            // 稳态自愈：硬叠加层的 alpha 动画末帧可能被合成器丢掉（真机实测：
            // 属性已到 1.0 但屏幕仍停在上一帧，需一次 invalidate 才显形）。
            if (overlay.alpha != 1f) {
                overlay.alpha = 1f
                overlay.invalidate()
            }
        }
        lastWaist = waist

        runCatching {
            DeviceGeometryReport.putAll(
                "waist.mode" to posture.mode.name,
                "waist.reason" to posture.reason,
                "waist.finder" to posture.finder.toString(),
                "waist.mask" to posture.mask.toString(),
                "waist.root" to "${root.width}x${root.height}",
                "waist.topH" to topH.toString(),
                "waist.barH" to barH.toString(),
                "waist.gateAspect" to "%.4f".format(ga),
                "waist.gate" to "${gateW.toInt()}x${gateH.toInt()}",
            )
        }
        return changed
    }

    /** 取景罩弹开：顶沿落下、前盖抬起、屏框淡入。 */
    private fun playOpen(topBar: View, overlay: View, bar: View) {
        val dec = DecelerateInterpolator()
        val topH = (topBar.height.takeIf { it > 0 } ?: topBar.layoutParams?.height ?: 0).toFloat()
        val barH = (bar.height.takeIf { it > 0 } ?: bar.layoutParams?.height ?: 0).toFloat()
        topBar.translationY = -topH
        bar.translationY = barH
        overlay.alpha = 0f
        opening = true
        topBar.animate().translationY(0f).setDuration(OPEN_MS).setInterpolator(dec).start()
        bar.animate().translationY(0f).setDuration(OPEN_MS).setInterpolator(dec).start()
        overlay.animate().alpha(1f).setDuration(OPEN_MS).setInterpolator(dec)
            // 末帧必须落到 alpha=1 并主动 invalidate：否则叠层可能停在动画中间那一帧，
            // 屏幕上看就是「风罩里什么都没画」（真机复现过，点一下任意控件才显现）。
            .withEndAction {
                overlay.alpha = 1f
                overlay.invalidate()
                opening = false
            }
            .start()
    }

    /** 收起时立刻归位，避免残留位移影响下次。 */
    private fun resetTransforms(topBar: View, overlay: View, bar: View) {
        opening = false
        topBar.animate().cancel()
        bar.animate().cancel()
        overlay.animate().cancel()
        topBar.translationY = 0f
        bar.translationY = 0f
        overlay.alpha = 1f
    }

    private fun setLp(v: View, block: FrameLayout.LayoutParams.() -> Unit): Boolean {
        val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return false
        val before = FrameLayout.LayoutParams(lp)
        block(lp)
        if (!different(before, lp)) return false
        v.layoutParams = lp
        return true
    }

    private fun different(a: FrameLayout.LayoutParams, b: FrameLayout.LayoutParams): Boolean =
        a.width != b.width || a.height != b.height || a.gravity != b.gravity ||
            a.leftMargin != b.leftMargin || a.topMargin != b.topMargin ||
            a.rightMargin != b.rightMargin || a.bottomMargin != b.bottomMargin
}
