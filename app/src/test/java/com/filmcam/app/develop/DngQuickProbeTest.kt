package com.filmcam.app.develop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DNG 快速探针契约。判定阈值来自 11-17 S25U 真机取证：
 * 空白底片（mean=64.2 / p99=66 / 邻差 0.9）vs 正常帧（mean=88.7 / p99=441 / 邻差 9.3）
 * vs 未初始化噪声（邻差 1714）。
 */
class DngQuickProbeTest {

    /** 生成一段统计特征可控的 16bit 采样行。 */
    private fun row(n: Int = 4080, make: (Int) -> Int): IntArray = IntArray(n) { make(it) }

    @Test
    fun `空白底片 整行恒定黑电平 判废`() {
        // 真机实测：值恒定在 64±1，邻差 0.9
        val blank = row { 64 + (it % 3) - 1 }
        assertTrue(DngQuickProbe.looksLikeGarbage(blank))
    }

    @Test
    fun `正常帧 有场景结构 不判废`() {
        // 真机实测正常帧：邻差 ~9，暗区到亮区跨几百 DN
        val real = row { i ->
            val base = 60 + (i * 40) / 4080 * 30          // 缓慢横向渐变
            base + ((i * 37) % 11) - 5                     // ±5 读出噪声
        }
        assertFalse(DngQuickProbe.looksLikeGarbage(real))
    }

    @Test
    fun `未初始化缓冲 高频不相关噪声 判废`() {
        // 真机实测第 2 帧：邻差 ~1714，值随机
        var seed = 12345L
        val noise = row { _ ->
            seed = seed * 6364136223846793005L + 1442695040888963407L
            ((seed ushr 33) % 65535).toInt()
        }
        assertTrue(DngQuickProbe.looksLikeGarbage(noise))
    }

    @Test
    fun `采样过短 不判废`() {
        assertFalse(DngQuickProbe.looksLikeGarbage(IntArray(100) { 0 }))
    }

    @Test
    fun `暗色墙面场景 不误判`() {
        // 最容易误判的场景：均匀暗墙 + 读出噪声。真实传感器读噪（±8）远大于空白帧（±1）
        var seed = 99L
        val darkWall = row { _ ->
            seed = seed * 6364136223846793005L + 1442695040888963407L
            300 + ((seed ushr 40) % 17).toInt() - 8
        }
        assertFalse(DngQuickProbe.looksLikeGarbage(darkWall))
    }

    /** 构造最小合法 TIFF（II/42），只含 StripOffsets(273) 与 StripByteCounts(279)。 */
    private fun miniTiff(samples: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        val put: (ByteArray) -> Unit = { out.write(it) }
        put(byteArrayOf(0x49, 0x49, 0x2A, 0x00))          // II, magic 42
        val ifdOff = 8
        put(byteArrayOf(8, 0, 0, 0))
        // IFD：2 条 entry + next=0
        val ifd = ByteBuffer.allocate(2 + 2 * 12 + 4).order(ByteOrder.LITTLE_ENDIAN)
        ifd.putShort(2)
        val dataOff = ifdOff + 2 + 2 * 12 + 4
        // tag 273 StripOffsets, type 4 LONG, count 1, value=dataOff
        ifd.putShort(273); ifd.putShort(4); ifd.putInt(1); ifd.putInt(dataOff)
        // tag 279 StripByteCounts, type 4 LONG, count 1, value=samples*2
        ifd.putShort(279); ifd.putShort(4); ifd.putInt(1); ifd.putInt(samples.size * 2)
        ifd.putInt(0) // next IFD
        put(ifd.array())
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bb.putShort(it.toShort()) }
        put(bb.array())
        return out.toByteArray()
    }

    @Test
    fun `解析合成TIFF 取回第一条strip采样`() {
        val samples = row(n = 1000) { 64 + (it % 3) - 1 }
        val parsed = DngQuickProbe.parseFirstStrip(miniTiff(samples))
        assertNotNull(parsed)
        assertTrue(parsed!!.size == 1000)
        assertTrue(parsed.contentEquals(samples))
    }

    @Test
    fun `非TIFF头 返回null`() {
        assertTrue(DngQuickProbe.parseFirstStrip(ByteArray(4096) { it.toByte() }) == null)
    }
}
