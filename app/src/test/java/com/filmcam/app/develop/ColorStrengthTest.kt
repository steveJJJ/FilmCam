package com.filmcam.app.develop

import app.spektralab.engine.JpegNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v73 机身「色彩浓度」档位的纯逻辑契约（不依赖 Context / Android 运行时）。
 *
 * 守住三件事：就地循环顺序、与工程师滑杆共用值域、自定义值不被吸附回档位。
 */
class ColorStrengthTest {

    private val cs = ColorStrength

    @Test
    fun `标准到淡到原始再回标准`() {
        val standard = cs.STANDARD
        assertEquals(ColorStrength.Level.STANDARD, cs.levelOf(standard))
        val soft = cs.next(standard)
        assertEquals(1f, soft, 1e-6f)
        val original = cs.next(soft)
        assertEquals(0f, original, 1e-6f)
        assertEquals(standard, cs.next(original), 1e-6f)
    }

    @Test
    fun `工程师滑杆拖到中间值时机身显示自定义且循环回默认档`() {
        assertEquals(ColorStrength.Level.CUSTOM, cs.levelOf(0.37f))
        assertEquals(ColorStrength.Level.CUSTOM, cs.levelOf(0.85f))
        // 不擅自把用户的细调值吸附到最近的档位：下一次点击从默认档（11-13 起＝淡）重新开始
        assertEquals(cs.DEFAULT_VALUE, cs.next(0.37f), 1e-6f)
        assertEquals(cs.DEFAULT_VALUE, cs.next(0.85f), 1e-6f)
    }

    @Test
    fun `11-13 新装默认档是淡且标准档仍可手动切回`() {
        assertEquals(1f, cs.DEFAULT_VALUE, 1e-6f)
        assertEquals(cs.SOFT, cs.DEFAULT_VALUE, 1e-6f)
        assertEquals(ColorStrength.Level.SOFT, cs.levelOf(cs.DEFAULT_VALUE))
        // 默认淡 → 点一下原始 → 再点标准 → 再点回淡，三档没因为改默认而塌掉
        assertEquals(cs.ORIGINAL, cs.next(cs.DEFAULT_VALUE), 1e-6f)
        assertEquals(cs.STANDARD, cs.next(cs.ORIGINAL), 1e-6f)
        assertEquals(cs.SOFT, cs.next(cs.STANDARD), 1e-6f)
        assertTrue(cs.STANDARD < cs.SOFT)
    }

    @Test
    fun `标准档就是引擎默认强度且原始档严格为零`() {
        assertEquals(JpegNormalizer.DEFAULT_STRENGTH, cs.STANDARD, 1e-6f)
        assertEquals(ColorStrength.Level.ORIGINAL, cs.levelOf(cs.ORIGINAL))
        // 0 档逐像素等价 v49：effectiveStrength 必须返回 0
        assertEquals(0f, JpegNormalizer.effectiveStrength(1f, cs.ORIGINAL), 1e-6f)
    }

    @Test
    fun `所有档位都落在滑杆合法值域内`() {
        for (v in cs.presets()) {
            assertTrue("档位越界: $v", v >= 0f && v <= JpegNormalizer.MAX_STRENGTH)
        }
        // 淡档吃满天花板，标准档不吃满（11-08 真机满档 L50 +6.7%、偏淡偏亮）
        assertTrue(cs.SOFT > cs.STANDARD)
        assertTrue(cs.STANDARD > cs.ORIGINAL)
    }
}
