package com.filmcam.app.filter

import android.content.Context
import com.filmcam.app.R
import com.filmcam.app.ui.AppLocale

/**
 * 胶卷库。
 *
 * 支持用户自定义排序：胶卷列表里长按拖拽调整顺序后，把顺序持久化到
 * SharedPreferences（只存 name 序列），下次打开就按用户的顺序展示——
 * 于是常用的胶卷可以拖到最上面方便切换。
 *
 * 双语：每支胶卷带中英文的 description / tag，对外入口 [rolls] 按
 * AppLocale.isEnglish 投影成当前语言，展示层（Adapter）无需感知。
 */
object FilmRollRepository {

    private const val PREFS = "filmcam"
    private const val KEY_ORDER = "film_order"
    /** 分隔符：胶卷名不含控制字符，用它拼接最安全 */
    private const val SEP = "\u0001"

    /** 引擎 catalog 全量胶卷（与 FilmStockMapper.ROLL_TABLE 一一对应），出厂默认顺序 */
    private val defaultRolls = listOf(
        FilmRoll(
            name = "PORTRA 400",
            iconResId = R.drawable.ic_film_portra400,
            description = "暖调人像，肤色细腻",
            tag = "彩色",
            descriptionEn = "Warm portrait tones, smooth skin",
            tagEn = "Color"
        ),
        FilmRoll(
            name = "EKTAR 100",
            iconResId = R.drawable.ic_film_ektar100,
            description = "高饱和，鲜艳明快",
            tag = "彩色",
            descriptionEn = "High saturation, vivid and punchy",
            tagEn = "Color"
        ),
        FilmRoll(
            name = "GOLD 200",
            iconResId = R.drawable.ic_film_gold200,
            description = "暖黄复古，日常扫街",
            tag = "彩色",
            descriptionEn = "Warm amber retro, everyday street",
            tagEn = "Color"
        ),
        FilmRoll(
            name = "SUPERIA 400",
            iconResId = R.drawable.ic_film_superia400,
            description = "富士清新，通透自然",
            tag = "彩色",
            descriptionEn = "Fuji fresh, clean and natural",
            tagEn = "Color"
        ),
        FilmRoll(
            name = "KODACHROME 64",
            iconResId = R.drawable.ic_film_kodachrome64,
            description = "柯达经典反转片，浓郁暖红调",
            tag = "反转",
            descriptionEn = "Kodak classic slide, deep warm reds",
            tagEn = "Slide"
        ),
        FilmRoll(
            name = "VISION3 250D",
            iconResId = R.drawable.ic_film_vision3_250d,
            description = "柯达电影卷，日光平衡柔和",
            tag = "电影",
            descriptionEn = "Kodak cine stock, soft daylight balance",
            tagEn = "Cine"
        ),
        FilmRoll(
            name = "TRI-X 400",
            iconResId = R.drawable.ic_film_trix400,
            description = "黑白胶片，颗粒浓郁",
            tag = "黑白",
            descriptionEn = "Classic B&W, heavy grain",
            tagEn = "B&W"
        ),
        FilmRoll(
            name = "HP5 PLUS",
            iconResId = R.drawable.ic_film_hp5,
            description = "黑白高对比，硬朗",
            tag = "黑白",
            descriptionEn = "High-contrast B&W, hard edged",
            tagEn = "B&W"
        ),
        FilmRoll(
            name = "CINESTILL 800T",
            iconResId = R.drawable.ic_film_cinestill800t,
            description = "钨丝灯平衡，夜色霓虹",
            tag = "彩色",
            descriptionEn = "Tungsten balanced, night neon",
            tagEn = "Color"
        ),
        // ---- 引擎 catalog 全量胶卷（与 FilmStockMapper.ROLL_TABLE 一一对应）----
        FilmRoll(
            name = "PORTRA 160",
            iconResId = R.drawable.ic_film_portra160,
            description = "柯达人像，柔润低反差",
            tag = "彩色",
            descriptionEn = "Kodak portrait, soft low contrast",
            tagEn = "Color"
        ),
        FilmRoll(
            name = "PORTRA 800",
            iconResId = R.drawable.ic_film_portra800,
            description = "柯达高感人像，弱光可用",
            tag = "彩色",
            descriptionEn = "Kodak fast portrait, holds up in low light",
            tagEn = "Color"
        ),
        FilmRoll(
            name = "PORTRA 800 PUSH 1",
            iconResId = R.drawable.ic_film_portra800p1,
            description = "波特拉 800 增感 1 档",
            tag = "彩色",
            descriptionEn = "Portra 800 pushed 1 stop",
            tagEn = "Color"
        ),
        FilmRoll(
            name = "PORTRA 800 PUSH 2",
            iconResId = R.drawable.ic_film_portra800p2,
            description = "波特拉 800 增感 2 档",
            tag = "彩色",
            descriptionEn = "Portra 800 pushed 2 stops",
            tagEn = "Color"
        ),
        FilmRoll(
            name = "ULTRAMAX 400",
            iconResId = R.drawable.ic_film_ultramax400,
            description = "柯达日常卷，活泼色彩",
            tag = "彩色",
            descriptionEn = "Kodak everyday stock, lively color",
            tagEn = "Color"
        ),
        FilmRoll(
            name = "C200",
            iconResId = R.drawable.ic_film_c200,
            description = "富士日光卷，清新自然",
            tag = "彩色",
            descriptionEn = "Fuji daylight stock, fresh and natural",
            tagEn = "Color"
        ),
        FilmRoll(
            name = "PRO 400H",
            iconResId = R.drawable.ic_film_pro400h,
            description = "富士人像，柔和粉调",
            tag = "彩色",
            descriptionEn = "Fuji portrait, soft pastel tones",
            tagEn = "Color"
        ),
        FilmRoll(
            name = "T-MAX 100",
            iconResId = R.drawable.ic_film_tmax100,
            description = "柯达黑白，细腻锐利",
            tag = "黑白",
            descriptionEn = "Kodak B&W, fine grained and sharp",
            tagEn = "B&W"
        ),
        FilmRoll(
            name = "NEOPAN ACROS 100 II",
            iconResId = R.drawable.ic_film_acros100,
            description = "富士黑白，超细颗粒",
            tag = "黑白",
            descriptionEn = "Fuji B&W, ultra-fine grain",
            tagEn = "B&W"
        ),
        FilmRoll(
            name = "PROVIA 100F",
            iconResId = R.drawable.ic_film_provia100f,
            description = "富士反转片，纯正色彩",
            tag = "反转",
            descriptionEn = "Fuji slide, faithful color",
            tagEn = "Slide"
        ),
        FilmRoll(
            name = "VELVIA 100",
            iconResId = R.drawable.ic_film_velvia100,
            description = "富士反转片，浓郁风光",
            tag = "反转",
            descriptionEn = "Fuji slide, saturated landscapes",
            tagEn = "Slide"
        ),
        FilmRoll(
            name = "EKTACHROME 100",
            iconResId = R.drawable.ic_film_ektachrome100,
            description = "柯达反转片，明亮通透",
            tag = "反转",
            descriptionEn = "Kodak slide, bright and clean",
            tagEn = "Slide"
        ),
        FilmRoll(
            name = "VERITA 200D",
            iconResId = R.drawable.ic_film_verita200d,
            description = "柯达电影分装，复古色调",
            tag = "电影",
            descriptionEn = "Kodak cine respool, vintage tones",
            tagEn = "Cine"
        ),
        FilmRoll(
            name = "VISION3 50D",
            iconResId = R.drawable.ic_film_vision3_50d,
            description = "柯达电影，日光下最细腻",
            tag = "电影",
            descriptionEn = "Kodak cine, finest grain in daylight",
            tagEn = "Cine"
        ),
        FilmRoll(
            name = "VISION3 200T",
            iconResId = R.drawable.ic_film_vision3_200t,
            description = "柯达电影，灯光色温平衡",
            tag = "电影",
            descriptionEn = "Kodak cine, balanced for tungsten",
            tagEn = "Cine"
        ),
        FilmRoll(
            name = "VISION3 500T",
            iconResId = R.drawable.ic_film_vision3_500t,
            description = "柯达电影，夜景利器",
            tag = "电影",
            descriptionEn = "Kodak cine, made for night",
            tagEn = "Cine"
        )
    )

    /** 出厂定义按 name 索引：投影时用它取回双语原值，切语言来回切都不会丢 */
    private val byName = defaultRolls.associateBy { it.name }

    /** 权威顺序（中文原值）。用户拖拽的顺序体现在 [displayCache] 上，落盘只读 name 序列。 */
    private var ordered: List<FilmRoll> = defaultRolls

    /** 当前语言的展示列表：实例在同一语言下保持稳定，Adapter 就地在它上面调顺序。 */
    private var displayCache: MutableList<FilmRoll>? = null
    private var displayEnglish: Boolean = AppLocale.isEnglish

    /**
     * 当前展示顺序（用户可拖拽调整），已按当前语言渲染 description/tag。
     * 所有读取方请直接用这个属性。
     *
     * 语言切换（AppLocale.isEnglish 变化）时按缓存里现有的 name 顺序重建，
     * 拖拽出来的顺序不会丢。
     */
    var rolls: MutableList<FilmRoll>
        get() {
            val english = AppLocale.isEnglish
            val cache = displayCache
            if (cache != null && displayEnglish == english) return cache
            val src = cache ?: ordered
            val out = ArrayList<FilmRoll>(src.size)
            src.mapTo(out) { (byName[it.name] ?: it).localized(english) }
            displayCache = out
            displayEnglish = english
            return out
        }
        private set(value) {
            ordered = value
            displayCache = null
        }

    /**
     * 从 SharedPreferences 恢复用户的排序（应在 Activity 初始化、restoreSettings 之前调用）。
     * 容错：存档里没有的新胶卷（比如升级后新增的）追加到末尾，避免它们在列表里消失。
     */
    fun restore(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ORDER, null)
        if (saved.isNullOrBlank()) {
            rolls = defaultRolls.toMutableList()
            return
        }
        val names = saved.split(SEP)
        val nameSet = names.toHashSet()
        val defByName = defaultRolls.associateBy { it.name }
        val orderedList = ArrayList<FilmRoll>(defaultRolls.size)
        names.mapNotNullTo(orderedList) { defByName[it] }
        defaultRolls.filterNot { it.name in nameSet }.forEach { orderedList.add(it) }
        rolls = orderedList
    }

    /** 落盘当前顺序（拖拽松手时调用一次）。 */
    fun persist(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ORDER, rolls.joinToString(SEP) { it.name })
            .apply()
    }

    /** 恢复出厂默认顺序。 */
    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ORDER).apply()
        rolls = defaultRolls.toMutableList()
    }
}
