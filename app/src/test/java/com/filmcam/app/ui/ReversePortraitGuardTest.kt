package com.filmcam.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v55：只拦反向竖屏，必须放正竖与两侧横屏过去（横屏是相机主拍摄方向）。
 */
class ReversePortraitGuardTest {

    @Test
    fun `传感器未知角度不锁方向`() {
        assertFalse(ReversePortraitGuard.isReversePortrait(-1))
    }

    @Test
    fun `正竖与两侧横屏都不锁`() {
        for (d in listOf(0, 30, 45, 89, 90, 91, 120, 135, 225, 269, 270, 271, 315, 359)) {
            assertFalse("角度 $d 不该锁成 portrait", ReversePortraitGuard.isReversePortrait(d))
        }
    }

    @Test
    fun `倒竖区间锁成正竖`() {
        for (d in listOf(150, 160, 180, 200, 210)) {
            assertTrue("角度 $d 应锁成 portrait", ReversePortraitGuard.isReversePortrait(d))
        }
    }

    @Test
    fun `区间边界外立即解锁`() {
        assertFalse(ReversePortraitGuard.isReversePortrait(149))
        assertFalse(ReversePortraitGuard.isReversePortrait(211))
    }
}
