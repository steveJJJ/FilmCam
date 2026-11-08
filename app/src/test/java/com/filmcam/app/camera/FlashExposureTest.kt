package com.filmcam.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v62 P0 的两条契约：
 *  ① 不具备 MANUAL_SENSOR 的机型绝不走「关 AE + 手动曝光」；
 *  ② 快门被设备上限截断后，必须按 ISO × t 守恒反算 ISO，不能凭空欠 1~2 EV。
 *
 * 基准机型参数来自 11-09 真机日志：
 *   闪光固定曝光 iso=228 t=100ms gain=4.0（= 57 × 4.0）
 */
class FlashExposureTest {

    private val ms = 1_000_000L

    /** S25U：ISO 50~3200、设备最大曝光 176ms（被 150ms 上限夹住）。 */
    private fun s25u() = FlashExposure.Caps(
        hasManualSensor = true, minIso = 50, maxIso = 3200,
        minShutterNs = 8 * ms, maxShutterNs = 150 * ms
    )

    private fun solve(caps: FlashExposure.Caps, baseIso: Float = 228f, baseT: Long = 100 * ms) =
        FlashExposure.solve(baseIso, baseT, caps.minIso, caps.maxIso, caps.minShutterNs, caps.maxShutterNs)

    @Test
    fun `无 MANUAL_SENSOR 必须降级不许锁曝光`() {
        assertFalse(s25u().copy(hasManualSensor = false).canLockExposure())
        assertTrue(s25u().canLockExposure())
    }

    @Test
    fun `能力区间非法的设备也不许锁曝光`() {
        assertFalse(s25u().copy(maxIso = 50).canLockExposure())
        assertFalse(s25u().copy(minIso = 0).canLockExposure())
        assertFalse(s25u().copy(maxShutterNs = 7 * ms).canLockExposure())
        assertFalse(s25u().copy(minShutterNs = 0L).canLockExposure())
    }

    @Test
    fun `基准机型 S25U 逐像素等价旧行为`() {
        val r = solve(s25u())
        assertEquals(100 * ms, r.shutterNs)
        assertEquals(228, r.iso)
        assertFalse(r.clamped)
        assertEquals("", r.note)
    }

    @Test
    fun `快门上限 33ms 的机型按 ISO 补回曝光量`() {
        val caps = s25u().copy(maxShutterNs = 33 * ms)
        val r = solve(caps)
        assertEquals(33 * ms, r.shutterNs)
        assertEquals(691, r.iso)
        assertFalse(r.clamped)
        // 关键：旧实现会变成 33ms × 228（欠 1.6EV），现在 ISO×t 仍守恒
        val target = 228.0 * 100 * ms
        assertTrue("曝光量应守恒", kotlin.math.abs(r.iso * r.shutterNs - target) / target < 0.01)
    }

    @Test
    fun `ISO 上限低时拉长快门而不是砍曝光量`() {
        val caps = s25u().copy(maxIso = 200)
        val r = solve(caps)
        assertEquals(114 * ms, r.shutterNs)
        assertEquals(200, r.iso)
        assertFalse(r.clamped)
    }

    @Test
    fun `设备能力不足时标记 clamped 且返回值仍合法`() {
        // 快门最多 33ms、ISO 最多 400：无论如何凑不出 228×100ms 的曝光量
        val caps = s25u().copy(maxIso = 400, maxShutterNs = 33 * ms)
        val r = solve(caps)
        assertEquals(33 * ms, r.shutterNs)
        assertEquals(400, r.iso)
        assertTrue(r.clamped)
        assertTrue(r.note.contains("设备能力不足"))
        assertTrue(r.iso >= caps.minIso && r.iso <= caps.maxIso)
        assertTrue(r.shutterNs >= caps.minShutterNs && r.shutterNs <= caps.maxShutterNs)
    }

    @Test
    fun `EV 拨盘加两档能量翻四倍`() {
        val base = solve(s25u())
        val up = solve(s25u(), baseIso = 228f * 4f)
        assertEquals(4.0, (up.iso.toDouble() * up.shutterNs) / (base.iso.toDouble() * base.shutterNs), 0.01)
    }

    @Test
    fun `各种设备组合下返回值都落在合法区间内`() {
        val capsList = listOf(
            s25u(),
            s25u().copy(maxShutterNs = 16 * ms, maxIso = 800),
            s25u().copy(minIso = 100, maxIso = 6400, maxShutterNs = 66 * ms),
            s25u().copy(maxShutterNs = 8 * ms, maxIso = 1600),
        )
        for (c in capsList) {
            for (g in listOf(0.25f, 1f, 4f, 10f)) {
                val r = solve(c, baseIso = 228f * g)
                assertTrue("ISO 越界 $c g=$g", r.iso >= c.minIso && r.iso <= c.maxIso)
                assertTrue("快门越界 $c g=$g", r.shutterNs >= c.minShutterNs && r.shutterNs <= c.maxShutterNs)
            }
        }
    }
}
