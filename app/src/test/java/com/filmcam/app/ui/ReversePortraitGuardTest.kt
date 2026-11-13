package com.filmcam.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v55：只拦反向竖屏，必须放正竖与两侧横屏过去（横屏是相机主拍摄方向）。
 * vc76 D：新增带滞回的 nextReverse 边界覆盖。
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

    // ===== vc76 D：滞回 ====

    @Test
    fun `未锁时只有窄区间才进锁`() {
        for (d in listOf(0, 90, 144, 145, 164, 196, 215, 216, 270, 359)) {
            assertFalse("未锁状态下 $d 不该进锁", ReversePortraitGuard.nextReverse(false, d))
        }
        for (d in listOf(165, 170, 180, 195)) {
            assertTrue("未锁状态下 $d 应进锁", ReversePortraitGuard.nextReverse(false, d))
        }
    }

    @Test
    fun `已锁时在宽区间内保持锁`() {
        for (d in listOf(145, 150, 160, 164, 180, 196, 210, 215)) {
            assertTrue("已锁状态下 $d 必须维持（滞回带）", ReversePortraitGuard.nextReverse(true, d))
        }
        for (d in listOf(0, 90, 144, 216, 270, 359)) {
            assertFalse("已锁状态下 $d 才解锁", ReversePortraitGuard.nextReverse(true, d))
        }
    }

    @Test
    fun `边界抖动不翻转状态`() {
        // 160→164 抖：未锁时始终不进锁（旧口径 150~210 会在 150 就锁）
        assertFalse(ReversePortraitGuard.nextReverse(false, 160))
        assertFalse(ReversePortraitGuard.nextReverse(false, 164))
        // 已锁时 210→216→210 抖：只在真正越出 215 的那一刻解锁
        assertTrue(ReversePortraitGuard.nextReverse(true, 210))
        assertFalse(ReversePortraitGuard.nextReverse(true, 216))
        assertFalse(ReversePortraitGuard.nextReverse(false, 210))
    }

    @Test
    fun `传感器未知角度一律保持现状`() {
        assertTrue("已锁时平放不能解锁", ReversePortraitGuard.nextReverse(true, -1))
        assertFalse("未锁时平放不能进锁", ReversePortraitGuard.nextReverse(false, -1))
    }

    @Test
    fun `角度归一化后再判定`() {
        assertTrue(ReversePortraitGuard.nextReverse(false, 360 + 180))
        assertTrue(ReversePortraitGuard.nextReverse(true, -180 + 360))
        assertFalse(ReversePortraitGuard.nextReverse(false, -90 + 360))
    }
}
