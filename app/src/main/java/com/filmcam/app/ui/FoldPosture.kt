package com.filmcam.app.ui

/**
 * 折叠屏「腰平取景」姿态求解（vc86 之上 P0 骨架）。
 *
 * 物理形态：书本式内折 / 竖折 Flip 折成 L 形，带后置摄像头的那半屏竖起朝被摄者，
 * 另一半平放、屏幕朝上，摄影者低头俯视 —— 就是禄来双反的毛玻璃。Android 通常
 * 仍把两块屏当**一个 Display**，所以这里只求「在这个窗口里哪一块是取景窗、
 * 哪一块铺纯黑遮罩」，不碰任何相机请求 / 冲洗 / 成片方向。
 *
 * 纯 Kotlin、零 Android 依赖：折痕 bounds / 窗口尺寸全部拆成 Int，便于 JVM 单测。
 * P1 接 androidx.window 的 [FoldingFeature] 后，把 state/bounds/orientation
 * 填进 [Input] 即可，本求解器不用改。
 */
object FoldPosture {

    /** 折痕朝向：HORIZONTAL = 折痕是水平线（上下两半），VERTICAL = 左右两半。 */
    enum class Hinge { NONE, HORIZONTAL, VERTICAL }

    /** NORMAL = 常规全屏；WINDOW_HALF = 系统已把窗口缩到一半，不再切分；WAIST = 半屏腰平。 */
    enum class Mode { NORMAL, WINDOW_HALF, WAIST }

    /**
     * 画幅档：AUTO 按取景窗宽高比自动判定（方窗→6×6、宽窗→3:2、竖窗→2:3）；
     * SQUARE / STANDARD 是手动覆盖（误判兜底）。画幅跟着**取景窗几何**走而不是
     * 设备型号走 —— 竖折/阔折/横折各家半屏比例都不一样，按比例分类天然设备无关。
     */
    enum class GateStyle { AUTO, SQUARE, STANDARD }

    /**
     * 画幅分类带宽（AUTO 档）：
     *  - [SQUARE_LO, SQUARE_HI] → 1:1（6×6）：S25U 半屏 1080/1170≈0.92、Flip 半屏
     *    ≈0.82、部分阔折半屏 ≈1.2，都落在这条带里；
     *  - 更宽 → 3:2（135 横置）：书本式横折的下半屏通常 ≥1.4；
     *  - 更窄 → 2:3（135 竖置）：竖长取景窗兜底。
     */
    const val SQUARE_LO = 0.8f
    const val SQUARE_HI = 1.25f

    /** 腰平彩蛋总闸三态（工程模式，prefs `waistEgg`）。 */
    const val EGG_OFF = "off"
    const val EGG_AUTO = "auto"
    const val EGG_SIMULATE = "simulate"

    /** 工程模式点击循环：关 → 自动 → 模拟 → 关。 */
    fun nextEgg(current: String?): String = when (current) {
        EGG_OFF -> EGG_AUTO
        EGG_AUTO -> EGG_SIMULATE
        else -> EGG_OFF
    }

    /**
     * 是否允许进入腰平（彩蛋语义的唯一判定）：
     *  - [EGG_OFF]：永不触发；
     *  - [EGG_AUTO]：只有真实折痕 **半开且把窗口分成两半**（isSeparating）才触发 ——
     *    Android 的 FoldingFeature 没有角度读数，「折到 90°」在这里就是 HALF_OPENED；
     *  - [EGG_SIMULATE]：直板机调试，不看折痕，按窗口平分；
     *  - [dismissed]：本次半开已被用户 CLOSE / 返回键收起，重新展平前不许再进。
     */
    fun allowsWaist(
        egg: String?, halfOpened: Boolean, separating: Boolean, dismissed: Boolean,
    ): Boolean {
        if (dismissed) return false
        return when (egg) {
            EGG_SIMULATE -> true
            EGG_AUTO -> halfOpened && separating
            else -> false
        }
    }


    data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        fun isEmpty(): Boolean = width <= 0 || height <= 0
    }

    data class Input(
        val windowW: Int,
        val windowH: Int,
        val displayW: Int,
        val displayH: Int,
        val hinge: Hinge = Hinge.NONE,
        /** P1：FoldingFeature.State.HALF_OPENED。P0 手动开关不依赖它。 */
        val halfOpened: Boolean = false,
        val boundsLeft: Int = 0,
        val boundsTop: Int = 0,
        val boundsRight: Int = 0,
        val boundsBottom: Int = 0,
        /** 设置面板里的可见开关，直板机也能用来模拟调试。 */
        val manual: Boolean = false,
        /** 「翻转取景窗」：API 不告诉哪半带摄像头，允许用户把 A / B 互换。 */
        val flip: Boolean = false,
        /** 画幅档：AUTO 按取景窗几何自动判定，SQUARE/STANDARD 手动覆盖。 */
        val gateStyle: GateStyle = GateStyle.AUTO,
    )

    data class Result(
        val mode: Mode,
        val finder: Region = Region(0, 0, 0, 0),
        val mask: Region = Region(0, 0, 0, 0),
        val reason: String = "",
        /**
         * 腰平取景窗的成片画幅（w/h）：方窗 1（6×6）、宽窗 3:2、竖窗 2:3。
         * 预览表面、MF-2 取景格、对焦映射、成片裁切全部从这一个值出发 ——
         * 取景窗是什么形状，成片就是什么形状，预览零拉伸。
         */
        val gateAspect: Float = 3f / 2f,
    )

    /** 窗口明显小于 Display 就认为系统已经做过 Flex / 分屏，别再切一次。 */
    private const val HALF_WINDOW_RATIO = 0.75f

    fun solve(i: Input): Result {
        if (i.windowW < 3 || i.windowH < 3) return Result(Mode.NORMAL, reason = "no-window")
        // P0 只有手动开关；P1 接 WindowInfoTracker 后 halfOpened 才会为 true。
        if (!i.manual && !i.halfOpened) return Result(Mode.NORMAL, reason = "flat")

        if (i.hinge == Hinge.HORIZONTAL && i.displayH > 0 &&
            i.windowH.toFloat() <= i.displayH * HALF_WINDOW_RATIO
        ) {
            return Result(Mode.WINDOW_HALF, reason = "system-split-vertical")
        }
        if (i.hinge == Hinge.VERTICAL && i.displayW > 0 &&
            i.windowW.toFloat() <= i.displayW * HALF_WINDOW_RATIO
        ) {
            return Result(Mode.WINDOW_HALF, reason = "system-split-horizontal")
        }

        // 竖直折痕切左右，其余（水平折痕 / 无折痕）切上下。
        val horizontal = i.hinge != Hinge.VERTICAL
        val limit = if (horizontal) i.windowH else i.windowW
        val split = if (i.hinge == Hinge.NONE) {
            // 直板机 / 设备不上报折痕：按 1/2 平分，用来在 S25U 上验证分区。
            limit / 2
        } else {
            val c = if (horizontal) (i.boundsTop + i.boundsBottom) / 2
            else (i.boundsLeft + i.boundsRight) / 2
            c.coerceIn(1, limit - 1)
        }

        val first: Region
        val second: Region
        if (horizontal) {
            first = Region(0, 0, i.windowW, split)
            second = Region(0, split, i.windowW, i.windowH)
        } else {
            first = Region(0, 0, split, i.windowH)
            second = Region(split, 0, i.windowW, i.windowH)
        }
        // 默认取「第二区」为取景窗：水平折痕 = 下半屏（用户描述的姿态）；
        // 竖直折痕 = 右半屏（哪半带镜头未知，flip 可互换）。
        val finder = if (i.flip) first else second
        val mask = if (i.flip) second else first
        if (finder.isEmpty() || mask.isEmpty()) return Result(Mode.NORMAL, reason = "degenerate")
        return Result(
            Mode.WAIST, finder, mask,
            if (horizontal) "hinge-horizontal" else "hinge-vertical",
            classifyGateAspect(finder.width, finder.height, i.gateStyle),
        )
    }

    /**
     * 按取景窗几何定画幅（w/h）。AUTO 分类带见 [SQUARE_LO]/[SQUARE_HI]；
     * SQUARE 恒 1:1（6×6 中画幅）；STANDARD 恒 135 全幅（按窗口横竖取向）。
     */
    fun classifyGateAspect(w: Int, h: Int, style: GateStyle): Float {
        if (w <= 0 || h <= 0) return 3f / 2f
        val a = w.toFloat() / h.toFloat()
        return when (style) {
            GateStyle.SQUARE -> 1f
            GateStyle.STANDARD -> if (a >= 1f) 3f / 2f else 2f / 3f
            GateStyle.AUTO -> when {
                a in SQUARE_LO..SQUARE_HI -> 1f
                a > SQUARE_HI -> 3f / 2f
                else -> 2f / 3f
            }
        }
    }
}
