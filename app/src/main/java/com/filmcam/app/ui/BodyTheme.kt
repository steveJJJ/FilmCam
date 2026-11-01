package com.filmcam.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable

/**
 * 机身主题：康泰时 T3 量产就两个配色 ——
 *   0 = 黑钛（BLACK，钛胚上黑漆，现在的样子）
 *   1 = 钛银（TITAN，裸钛本色，冷银灰带细拉丝）
 *
 * 真机上「换颜色」换的是机身外壳，取景器画面、液晶窗口、按键本身都不变：
 * 银色版的快门钮同样是黑色蓝宝石、机身键同为黑色，LCD 仍是深色窗口。
 * 所以这里只定义「机身底色 + 底上的文字/图标色 + 面板/弹窗配色」，
 * 按键 drawable（bg_shutter / bg_key / bg_darkroom）一律不动。
 */
object BodyTheme {

    const val BLACK = 0
    const val TITAN = 1

    /** 受光分档的高度位置（0=顶 1=底），与 bodyShadeRgb / bodyShadeColors 一一对应 */
    val BODY_SHADE_STOPS = floatArrayOf(0f, 0.08f, 0.42f, 0.78f, 1f)

    private const val PREF_KEY = "bodyTheme"

    var current: Int = BLACK
        private set

    val isTitan: Boolean get() = current == TITAN

    fun load(context: Context) {
        current = context.getSharedPreferences("filmcam", Context.MODE_PRIVATE)
            .getInt(PREF_KEY, BLACK).coerceIn(BLACK, TITAN)
    }

    fun save(context: Context) {
        context.getSharedPreferences("filmcam", Context.MODE_PRIVATE)
            .edit().putInt(PREF_KEY, current).apply()
    }

    /** 切换（0→1→0），返回切换后的档位名 */
    fun cycle(context: Context): String {
        current = if (current == TITAN) BLACK else TITAN
        save(context)
        return name(context)
    }

    fun name(context: Context): String =
        context.getString(if (isTitan) com.filmcam.app.R.string.body_titan else com.filmcam.app.R.string.body_black)

    // ===== 配色 =====

    /** 机身外壳底：黑钛是纯黑；钛银用拉丝钛 Drawable（带细横纹与受光明暗） */
    fun bodyBackground(): Drawable =
        if (isTitan) TitanBodyDrawable() else ColorDrawable(Color.BLACK)

    /** 机身底色（纯色版，用于需要 int 的地方，如状态栏） */
    val bodyColor: Int get() = if (isTitan) 0xFFC6BEB0.toInt() else Color.BLACK

    /**
     * 钛面受光：5 档明暗（顶/肩/中/下/底，0-1 的 RGB），对应高度比例见 BODY_SHADE_STOPS。
     * 真机 T3 是 **smooth titanium**（各评测原话：smooth / 流线平滑，保时捷设计），
     * 不是拉丝面——所以这里只有大面积平缓的明暗起伏，没有任何规则纹路。
     * 裸钛也不是不锈钢冷银，自然光下偏暖的香槟灰。
     * 主页留白是 GL 画的（见 LutGLSurfaceView），与 TitanBodyDrawable 共用这一组值。
     */
    val bodyShadeRgb: FloatArray
        get() = if (isTitan) floatArrayOf(
            0.878f, 0.847f, 0.792f,   // 0.00 顶缘：肩线受光最亮
            0.847f, 0.816f, 0.761f,   // 0.08 肩部
            0.757f, 0.722f, 0.663f,   // 0.42 中段：最暗
            0.808f, 0.776f, 0.718f,   // 0.78 下部回升
            0.847f, 0.816f, 0.757f    // 1.00 底缘：一线反射
        ) else FloatArray(15)

    /** 同上，Canvas 侧的 int 颜色版（供 LinearGradient 用） */
    val bodyShadeColors: IntArray
        get() = if (isTitan) intArrayOf(
            0xFFE0D9CA.toInt(),
            0xFFD8D0C2.toInt(),
            0xFFC1B8A9.toInt(),
            0xFFCEC6B7.toInt(),
            0xFFD8D0C1.toInt()
        ) else intArrayOf(Color.BLACK, Color.BLACK, Color.BLACK, Color.BLACK, Color.BLACK)

    /** 缎面砂感的强度（0-1 的亮度扰动幅度）：光滑钛面只有极细的哑光颗粒 */
    const val BODY_GRAIN = 0.028f

    /** 机身底上的主文字/图标色：黑底浅字，钛底深字（像蚀刻在钛面上，偏暖的深灰） */
    val onBodyText: Int get() = if (isTitan) 0xFF2E2B27.toInt() else 0xFFE8E4DA.toInt()

    /** 机身底上的次要文字 */
    val onBodyTextSecondary: Int get() = if (isTitan) 0xFF5F5A52.toInt() else 0xFF8A8A8E.toInt()

    /** 面板/弹层底色（弹窗、底部弹层不是机身，比机身略亮一档，保持"面板"层次） */
    val panelBg: Int get() = if (isTitan) 0xFFD3CBBD.toInt() else 0xFF1A1A1A.toInt()

    /** 面板底上的主文字 */
    val panelText: Int get() = if (isTitan) 0xFF2E2B27.toInt() else 0xFFE8E4DA.toInt()

    /** 面板底上的次要文字 */
    val panelTextSecondary: Int get() = if (isTitan) 0xFF5F5A52.toInt() else 0xFF8A8A8E.toInt()

    /** 弹窗主题（AlertDialog 的 style） */
    val dialogTheme: Int get() =
        if (isTitan) com.filmcam.app.R.style.TitanDialog else com.filmcam.app.R.style.DarkDialog

    /** 过卷/冲洗遮罩：黑钛是全黑，钛银是"机身面板盖上来"的钛面 */
    val overlayColor: Int get() = if (isTitan) 0xFFC6BEB0.toInt() else Color.BLACK

    // ===== 整棵 View 树的配色重映射 =====

    /**
     * 亮底/暗底文字配对表：左边是「深底上的浅字」，右边是「钛底上的蚀刻深字」。
     * 只列中性的黑白灰阶 —— 琥珀、暗房红这类强调色一律不参与，免得换主题把
     * 品牌色和暗房氛围一起改掉。
     */
    private val TEXT_PAIRS = mapOf(
        0xFFFFFFFF.toInt() to 0xFF2E2B27.toInt(),
        0xFFE8E4DA.toInt() to 0xFF2E2B27.toInt(),
        0xFFE8D5C8.toInt() to 0xFF3A3630.toInt(),
        // 关于页开源致谢是代码里动态建的文本，这几个色必须也在表内，
        // 否则切到钛银后它们还是浅字，银底上直接糊掉（踩过一次）。
        0xFFD8D4CC.toInt() to 0xFF2E2B27.toInt(),
        0xFFCBC7BE.toInt() to 0xFF3A3630.toInt(),
        0xFFC9C5BC.toInt() to 0xFF3A3630.toInt(),
        0xFFB8B8BC.toInt() to 0xFF5F5A52.toInt(),
        0xFF9A9A9E.toInt() to 0xFF5F5A52.toInt(),
        0xFF8A8A8E.toInt() to 0xFF5F5A52.toInt(),
        0xFF7C7C80.toInt() to 0xFF5F5A52.toInt(),
        0xFF77777C.toInt() to 0xFF5F5A52.toInt(),
        0xFF6E6E6E.toInt() to 0xFF5F5A52.toInt(),
        0xFF6A6A6E.toInt() to 0xFF5F5A52.toInt(),
        0xFF999999.toInt() to 0xFF5F5A52.toInt(),
        0xFF666666.toInt() to 0xFF5F5A52.toInt()
    )

    /** 面板底色配对表：深底 ↔ 钛面（比机身略亮一档，保持"面板"层次） */
    private val BG_PAIRS = mapOf(
        Color.BLACK to 0xFFC6BEB0.toInt(),
        0xFF111111.toInt() to 0xFFC6BEB0.toInt(),
        0xFF1A1A1A.toInt() to 0xFFD3CBBD.toInt(),
        0xFF141416.toInt() to 0xFFD3CBBD.toInt(),
        0xFF222222.toInt() to 0xFFC0B8AA.toInt(),
        0xFF26262A.toInt() to 0xFFC0B8AA.toInt()
    )

    private val TEXT_TO_TITAN = TEXT_PAIRS
    private val TEXT_TO_BLACK = TEXT_PAIRS.entries.associate { (k, v) -> v to k }
    private val BG_TO_TITAN = BG_PAIRS
    private val BG_TO_BLACK = BG_PAIRS.entries.associate { (k, v) -> v to k }

    fun remapText(color: Int): Int =
        (if (isTitan) TEXT_TO_TITAN[color] else TEXT_TO_BLACK[color]) ?: color

    fun remapBg(color: Int): Int =
        (if (isTitan) BG_TO_TITAN[color] else BG_TO_BLACK[color]) ?: color

    /**
     * 把一棵 View 树刷成当前主题。
     * 两条保守规则，避免误伤：
     *  1) 背景只改「纯色」的（ColorDrawable）。bg_key / bg_shutter / bg_darkroom
     *     这类按键材质是 shape/gradient，一律不动 —— 真机两种机身色的按键本来就是黑的。
     *  2) 文字只改「没有自己背景」的 TextView。有底色的（按键牌面、chip、按钮）
     *     字色跟着它自己的底走，不能跟着页面底走。
     */
    fun applyToViewTree(root: android.view.View) {
        val bg = root.background
        if (bg is ColorDrawable) {
            val c = bg.color
            val mapped = remapBg(c)
            if (mapped != c) root.setBackgroundColor(mapped)
        }
        if (root is android.widget.TextView && root.background == null &&
            root.tag != KEEP_TEXT_COLOR
        ) {
            val c = root.currentTextColor
            val mapped = remapText(c)
            if (mapped != c) root.setTextColor(mapped)
        }
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) applyToViewTree(root.getChildAt(i))
        }
    }

    /** 打这个 tag 的控件，文字色不参与主题重映射（按键牌面、彩色 chip 等） */
    const val KEEP_TEXT_COLOR = "keepTextColor"

    /**
     * 系统栏：底色换了，图标也得跟着换深浅，否则银色状态栏上还是浅色图标=看不见。
     * 钛银档开 LIGHT_STATUS_BARS / LIGHT_NAVIGATION_BARS（图标转深色）。
     */
    fun applySystemBars(window: android.view.Window) {
        window.statusBarColor = bodyColor
        window.navigationBarColor = bodyColor
        val decor = window.decorView
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val ctrl = decor.windowInsetsController ?: return
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            ctrl.setSystemBarsAppearance(if (isTitan) light else 0, light)
        } else {
            @Suppress("DEPRECATION")
            var flags = decor.systemUiVisibility
            flags = if (isTitan) {
                flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    (if (android.os.Build.VERSION.SDK_INT >= 26)
                        android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            } else {
                flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                    (if (android.os.Build.VERSION.SDK_INT >= 26)
                        android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else -1)
            }
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = flags
        }
    }
}
