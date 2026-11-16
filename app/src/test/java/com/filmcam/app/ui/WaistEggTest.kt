package com.filmcam.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 腰平彩蛋的触发语义：**只有折叠屏折到半开才允许进入**。
 *
 * Android 的 FoldingFeature 只区分展平 / 半开，没有角度读数，所以「折到 90°」
 * 在这里落成 `HALF_OPENED + isSeparating`；直板机永远拿不到这两个条件，
 * 唯一例外是工程模式里手动选「模拟」档（调试用）。
 */
class WaistEggTest {

    @Test
    fun `关档永不触发，哪怕折叠屏正半开`() {
        assertFalse(FoldPosture.allowsWaist(FoldPosture.EGG_OFF, true, true, false))
        assertFalse(FoldPosture.allowsWaist(FoldPosture.EGG_OFF, false, false, false))
        assertFalse(FoldPosture.allowsWaist(null, true, true, false))
    }

    @Test
    fun `自动档必须半开且折痕把窗口分开`() {
        assertTrue(FoldPosture.allowsWaist(FoldPosture.EGG_AUTO, true, true, false))
        // 折了但没分离（柔性屏小弯）不算半开
        assertFalse(FoldPosture.allowsWaist(FoldPosture.EGG_AUTO, true, false, false))
        // 展平
        assertFalse(FoldPosture.allowsWaist(FoldPosture.EGG_AUTO, false, true, false))
        // 直板机：永远 false
        assertFalse(FoldPosture.allowsWaist(FoldPosture.EGG_AUTO, false, false, false))
    }

    @Test
    fun `模拟档不看折痕，给直板机调试`() {
        assertTrue(FoldPosture.allowsWaist(FoldPosture.EGG_SIMULATE, false, false, false))
    }

    @Test
    fun `本次半开被用户收起后，重新展平前不许再进`() {
        assertFalse(FoldPosture.allowsWaist(FoldPosture.EGG_AUTO, true, true, true))
        assertFalse(FoldPosture.allowsWaist(FoldPosture.EGG_SIMULATE, false, false, true))
    }

    @Test
    fun `工程模式三态循环 关到自动到模拟再回关`() {
        assertEquals(FoldPosture.EGG_AUTO, FoldPosture.nextEgg(FoldPosture.EGG_OFF))
        assertEquals(FoldPosture.EGG_SIMULATE, FoldPosture.nextEgg(FoldPosture.EGG_AUTO))
        assertEquals(FoldPosture.EGG_OFF, FoldPosture.nextEgg(FoldPosture.EGG_SIMULATE))
        // 老 prefs / 坏值都回到安全的第一档
        assertEquals(FoldPosture.EGG_OFF, FoldPosture.nextEgg(""))
        assertEquals(FoldPosture.EGG_OFF, FoldPosture.nextEgg(null))
    }

    @Test
    fun `半开但系统已缩窗时纯函数判 WINDOW_HALF 不切分`() {
        // 书本式横折：窗口只剩整屏 60% 高 → 系统 Flex 已经缩过窗，腰平必须放弃
        val r = FoldPosture.solve(
            FoldPosture.Input(
                windowW = 1080, windowH = 1400, displayW = 1080, displayH = 2340,
                hinge = FoldPosture.Hinge.HORIZONTAL, halfOpened = true,
                boundsLeft = 0, boundsTop = 700, boundsRight = 1080, boundsBottom = 710,
                manual = false,
            )
        )
        assertEquals(FoldPosture.Mode.WINDOW_HALF, r.mode)
    }
}
