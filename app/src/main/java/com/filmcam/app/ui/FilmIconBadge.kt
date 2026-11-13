package com.filmcam.app.ui

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider

/**
 * 当前胶卷图标（`tvFilmName`）的圆角方牌裁剪。
 *
 * 它的 background `bg_darkroom` 本来就是 14dp 圆角方牌（与旁边 DARKROOM 同款），
 * 但 `centerCrop` 把方形胶卷 PNG 铺满整个 View，四个直角把圆角背景完全盖住了 ——
 * 于是机身右栏里只有它显得方。这里在 View 级把内容裁成同半径圆角，
 * 让 `bg_darkroom` 的外框渐变重新露出来。
 *
 * 好处：25 张胶卷 PNG 一张不动、不改 scaleType、不新增 drawable、不动点击区域。
 *
 * ⚠️ 必须在 [com.filmcam.app.MainActivity] 的 `wireChrome()` 里调用：vc76 起
 * 旋转会重新 inflate `bottomBar`（`tvFilmName` 是它的子项），只在 onCreate 设一次
 * 会在横竖切换后丢失。
 */
object FilmIconBadge {

    /** 与 `res/drawable/bg_darkroom.xml` 外层 `<corners android:radius="14dp" />` 一致。 */
    const val CORNER_RADIUS_DP = 14f

    fun apply(view: View) {
        val radiusPx = CORNER_RADIUS_DP * view.resources.displayMetrics.density
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        view.clipToOutline = true
    }
}
