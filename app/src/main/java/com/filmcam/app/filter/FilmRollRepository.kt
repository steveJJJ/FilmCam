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
            name = "CLASSIC CHROME",
            iconResId = R.drawable.ic_film_classic_chrome,
            description = "富士素雅，低饱和胶片感",
            tag = "彩色"
        ),
        FilmRoll(
            name = "ETERNA",
            iconResId = R.drawable.ic_film_eterna,
            description = "电影感，低对比柔和",
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
            name = "CN800",
            iconResId = R.drawable.ic_film_cn800,
            description = "高感胶片，夜色氛围",
            tag = "彩色"
        )
    )
}
