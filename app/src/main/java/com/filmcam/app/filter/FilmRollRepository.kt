package com.filmcam.app.filter

import com.filmcam.app.R

/**
 * 胶卷库
 */
object FilmRollRepository {

    val rolls = listOf(
        FilmRoll(
            name = "PORTRA 400",
            iconResId = R.drawable.ic_film_portra400,
            description = "暖调人像，肤色细腻",
            tag = "彩色"
        ),
        FilmRoll(
            name = "EKTAR 100",
            iconResId = R.drawable.ic_film_ektar100,
            description = "高饱和，鲜艳明快",
            tag = "彩色"
        ),
        FilmRoll(
            name = "GOLD 200",
            iconResId = R.drawable.ic_film_gold200,
            description = "暖黄复古，日常扫街",
            tag = "彩色"
        ),
        FilmRoll(
            name = "SUPERIA 400",
            iconResId = R.drawable.ic_film_superia400,
            description = "富士清新，通透自然",
            tag = "彩色"
        ),
        FilmRoll(
            name = "KODACHROME 64",
            iconResId = R.drawable.ic_film_kodachrome64,
            description = "柯达经典反转片，浓郁暖红调",
            tag = "反转"
        ),
        FilmRoll(
            name = "VISION3 250D",
            iconResId = R.drawable.ic_film_vision3_250d,
            description = "柯达电影卷，日光平衡柔和",
            tag = "电影"
        ),
        FilmRoll(
            name = "TRI-X 400",
            iconResId = R.drawable.ic_film_trix400,
            description = "黑白胶片，颗粒浓郁",
            tag = "黑白"
        ),
        FilmRoll(
            name = "HP5 PLUS",
            iconResId = R.drawable.ic_film_hp5,
            description = "黑白高对比，硬朗",
            tag = "黑白"
        ),
        FilmRoll(
            name = "CINESTILL 800T",
            iconResId = R.drawable.ic_film_cinestill800t,
            description = "钨丝灯平衡，夜色霓虹",
            tag = "彩色"
        ),
        // ---- 引擎 catalog 全量胶卷（与 FilmStockMapper.ROLL_TABLE 一一对应）----
        FilmRoll(
            name = "PORTRA 160",
            iconResId = R.drawable.ic_film_portra160,
            description = "柯达人像，柔润低反差",
            tag = "彩色"
        ),
        FilmRoll(
            name = "PORTRA 800",
            iconResId = R.drawable.ic_film_portra800,
            description = "柯达高感人像，弱光可用",
            tag = "彩色"
        ),
        FilmRoll(
            name = "PORTRA 800 PUSH 1",
            iconResId = R.drawable.ic_film_portra800p1,
            description = "波特拉 800 增感 1 档",
            tag = "彩色"
        ),
        FilmRoll(
            name = "PORTRA 800 PUSH 2",
            iconResId = R.drawable.ic_film_portra800p2,
            description = "波特拉 800 增感 2 档",
            tag = "彩色"
        ),
        FilmRoll(
            name = "ULTRAMAX 400",
            iconResId = R.drawable.ic_film_ultramax400,
            description = "柯达日常卷，活泼色彩",
            tag = "彩色"
        ),
        FilmRoll(
            name = "C200",
            iconResId = R.drawable.ic_film_c200,
            description = "富士日光卷，清新自然",
            tag = "彩色"
        ),
        FilmRoll(
            name = "PRO 400H",
            iconResId = R.drawable.ic_film_pro400h,
            description = "富士人像，柔和粉调",
            tag = "彩色"
        ),
        FilmRoll(
            name = "T-MAX 100",
            iconResId = R.drawable.ic_film_tmax100,
            description = "柯达黑白，细腻锐利",
            tag = "黑白"
        ),
        FilmRoll(
            name = "NEOPAN ACROS 100 II",
            iconResId = R.drawable.ic_film_acros100,
            description = "富士黑白，超细颗粒",
            tag = "黑白"
        ),
        FilmRoll(
            name = "PROVIA 100F",
            iconResId = R.drawable.ic_film_provia100f,
            description = "富士反转片，纯正色彩",
            tag = "反转"
        ),
        FilmRoll(
            name = "VELVIA 100",
            iconResId = R.drawable.ic_film_velvia100,
            description = "富士反转片，浓郁风光",
            tag = "反转"
        ),
        FilmRoll(
            name = "EKTACHROME 100",
            iconResId = R.drawable.ic_film_ektachrome100,
            description = "柯达反转片，明亮通透",
            tag = "反转"
        ),
        FilmRoll(
            name = "VERITA 200D",
            iconResId = R.drawable.ic_film_verita200d,
            description = "柯达电影分装，复古色调",
            tag = "电影"
        ),
        FilmRoll(
            name = "VISION3 50D",
            iconResId = R.drawable.ic_film_vision3_50d,
            description = "柯达电影，日光下最细腻",
            tag = "电影"
        ),
        FilmRoll(
            name = "VISION3 200T",
            iconResId = R.drawable.ic_film_vision3_200t,
            description = "柯达电影，灯光色温平衡",
            tag = "电影"
        ),
        FilmRoll(
            name = "VISION3 500T",
            iconResId = R.drawable.ic_film_vision3_500t,
            description = "柯达电影，夜景利器",
            tag = "电影"
        )
    )
}
