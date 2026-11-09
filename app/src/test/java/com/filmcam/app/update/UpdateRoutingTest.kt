package com.filmcam.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v65 更新路由契约：Play 包走应用内更新，其余一律降级跳转，绝不自己联网。 */
class UpdateRoutingTest {

    @Test
    fun `只有 Play 安装来源才走应用内更新`() {
        assertTrue(UpdateRouting.isPlayInstall("com.android.vending"))
        assertTrue(UpdateRouting.isPlayInstall("com.google.android.feedback"))
        assertFalse(UpdateRouting.isPlayInstall(null))
        assertFalse(UpdateRouting.isPlayInstall(""))
        assertFalse(UpdateRouting.isPlayInstall("com.android.shell"))
        assertFalse(UpdateRouting.isPlayInstall("com.android.vending.fake"))
    }

    @Test
    fun `侧载包跳 GitHub Releases Play 包跳商店详情页`() {
        assertEquals(
            "https://github.com/steveJJJ/FilmCam/releases",
            UpdateRouting.fallbackUrl(playInstall = false)
        )
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.filmcam.app",
            UpdateRouting.fallbackUrl(playInstall = true)
        )
    }

    @Test
    fun `侧载包无论更新状态都降级跳转`() {
        assertEquals(
            UpdateRouting.Action.FALLBACK,
            UpdateRouting.decide(playInstall = false, updateAvailable = true, flexibleAllowed = true)
        )
        assertEquals(
            UpdateRouting.Action.FALLBACK,
            UpdateRouting.decide(playInstall = false, updateAvailable = false, flexibleAllowed = false)
        )
    }

    @Test
    fun `Play 包无更新时只提示 已是最新`() {
        assertEquals(
            UpdateRouting.Action.NO_UPDATE,
            UpdateRouting.decide(playInstall = true, updateAvailable = false, flexibleAllowed = true)
        )
        assertEquals(
            UpdateRouting.Action.NO_UPDATE,
            UpdateRouting.decide(playInstall = true, updateAvailable = false, flexibleAllowed = false)
        )
    }

    @Test
    fun `Play 包有更新且允许 flexible 才启动应用内更新`() {
        assertEquals(
            UpdateRouting.Action.FLEXIBLE,
            UpdateRouting.decide(playInstall = true, updateAvailable = true, flexibleAllowed = true)
        )
        assertEquals(
            UpdateRouting.Action.FALLBACK,
            UpdateRouting.decide(playInstall = true, updateAvailable = true, flexibleAllowed = false)
        )
    }
}
