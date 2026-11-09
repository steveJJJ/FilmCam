package com.filmcam.app.update

/**
 * 「检查更新」路由决策（纯 Kotlin、零 Android 依赖，可 JVM 单测）。
 *
 * v65 采用 A+B 组合，**零新增权限** —— App 自己不联网：
 *  - Play 安装的包 → 官方 Play In-App Updates（检查 / 下载 / 安装全由 Play 商店完成）；
 *  - 其余（侧载内测包、Play 服务不可用、不允许 flexible）→ ACTION_VIEW 跳下载页。
 *
 * ⚠️ 绝不做「自己请求版本接口 + 下载 APK 安装」：那需要 INTERNET 与
 *    REQUEST_INSTALL_PACKAGES，违反 Play「不得用 Play 以外机制自更新」政策，
 *    也会推翻隐私政策 / 商店文案里「完全离线」的核心承诺。
 */
object UpdateRouting {

    const val PLAY_INSTALLER = "com.android.vending"
    /** 部分设备上报 Play 反馈组件作为安装来源 */
    const val PLAY_FEEDBACK_INSTALLER = "com.google.android.feedback"

    const val PLAY_DETAILS_URL = "https://play.google.com/store/apps/details?id=com.filmcam.app"
    const val RELEASES_URL = "https://github.com/steveJJJ/FilmCam/releases"

    fun isPlayInstall(installer: String?): Boolean =
        installer == PLAY_INSTALLER || installer == PLAY_FEEDBACK_INSTALLER

    /** Play 安装但应用内更新不可用 → 商店详情页；侧载包 → GitHub Releases */
    fun fallbackUrl(playInstall: Boolean): String =
        if (playInstall) PLAY_DETAILS_URL else RELEASES_URL

    enum class Action { FLEXIBLE, NO_UPDATE, FALLBACK }

    fun decide(
        playInstall: Boolean,
        updateAvailable: Boolean,
        flexibleAllowed: Boolean,
    ): Action {
        if (!playInstall) return Action.FALLBACK
        if (!updateAvailable) return Action.NO_UPDATE
        return if (flexibleAllowed) Action.FLEXIBLE else Action.FALLBACK
    }
}
