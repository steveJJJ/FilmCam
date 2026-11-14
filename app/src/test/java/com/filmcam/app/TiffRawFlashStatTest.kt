package com.filmcam.app

import com.filmcam.app.develop.TiffRawFlashStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v81：[TiffRawFlashStat] 的字节级回归。
 *
 * 真机踩坑记录（11-14 S25U，tmp/flash4_raw_1114/roll_1794630216879）：
 * DngCreator 的 BlackLevel 是 RATIONAL[4] = 6400/100 —— 只取分子会把 64
 * 当成 6400，大于 WhiteLevel 1023，整张底片被误判「结构未识别」，黑底片
 * 不跳 RAW、白走一趟 RAW 会话。这些测试用手工构造的最小 DNG 字节流锁住
 * 解析器行为，不依赖真机文件。
 */
class TiffRawFlashStatTest {

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Long) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

    /**
     * 构造 16x16、每行一 strip、无压缩 16bit 的最小 DNG。
     * [pixel] 每个采样值相同；BlackLevel=RATIONAL[4] 6400/100、WhiteLevel=1023，
     * 完全对齐 S25U 真机底片的标签口径。
     */
    private fun buildDng(pixel: Int, path: File) {
        val out = ByteArrayOutputStream()
        out.write("II".toByteArray(Charsets.US_ASCII))          // 0-1
        out.write(le16(42))                                     // 2-3
        out.write(le32(8))                                      // 4-7 IFD0 offset
        // ---- IFD0 @ 8：9 个条目 ----
        val stripsOff = 8 + 2 + 9 * 12 + 4                     // = 122
        val blackOff = stripsOff + 16 * 4                      // = 186
        val dataOff = blackOff + 4 * 8                          // = 218
        out.write(le16(9))
        // tag(2) type(2) count(4) value(4)
        fun entry(tag: Int, type: Int, count: Int, inline: ByteArray, extOff: Long = 0) {
            out.write(le16(tag)); out.write(le16(type)); out.write(le32(count.toLong()))
            // 外部引用传 inline=ByteArray(0)，内联值传正好 4 字节
            out.write(if (inline.size == 4) inline else le32(extOff))
        }
        entry(256, 4, 1, le32(16))                              // ImageWidth
        entry(257, 4, 1, le32(16))                              // ImageLength
        entry(258, 3, 1, byteArrayOf(16.toByte(), 0, 0, 0))     // BitsPerSample=16 (SHORT 内联)
        entry(273, 4, 16, ByteArray(0), stripsOff.toLong())     // StripOffsets LONG[16] 外部
        entry(277, 3, 1, byteArrayOf(1.toByte(), 0, 0, 0))      // SamplesPerPixel=1
        entry(278, 4, 1, le32(1))                               // RowsPerStrip=1
        entry(279, 4, 1, le32(32))                              // StripByteCounts（解析器不读，占位）
        entry(50714, 5, 4, ByteArray(0), blackOff.toLong())    // BlackLevel RATIONAL[4] 外部
        entry(50717, 4, 1, le32(1023))                          // WhiteLevel
        out.write(le32(0))                                      // next IFD = 0
        // ---- 外部数组 ----
        repeat(16) { r -> out.write(le32((dataOff + r * 32).toLong())) }   // StripOffsets[16]
        repeat(4) { out.write(le32(6400)); out.write(le32(100)) }          // BlackLevel 64.0 ×4
        // ---- 像素数据：16 行 × 16 像素 × 2 字节 ----
        repeat(16) {
            repeat(16) { out.write(le16(pixel)) }
        }
        path.writeBytes(out.toByteArray())
    }

    private fun tempDng(pixel: Int): File {
        val f = File.createTempFile("flash_raw_stat", ".dng")
        f.deleteOnExit()
        buildDng(pixel, f)
        return f
    }

    @Test
    fun `黑底片净信号低于门限判盲 S25U 口径`() {
        // 真机黑帧：BlackLevel 64 + 净信号 ~30 → 值 94，净比例 30/959 ≈ 3.1%
        val ratio = TiffRawFlashStat.netSignalRatio(tempDng(94))!!
        assertEquals(0.0313, ratio.toDouble(), 0.001)
        assertTrue(ratio < TiffRawFlashStat.BLIND_NET_RATIO)
    }

    @Test
    fun `正常吃光底片高于门限`() {
        // 闪光主体吃光：值 464 → 净 400/959 ≈ 41.7%，远高于 5% 门限
        val ratio = TiffRawFlashStat.netSignalRatio(tempDng(464))!!
        assertEquals(0.4171, ratio.toDouble(), 0.001)
        assertTrue(ratio >= TiffRawFlashStat.BLIND_NET_RATIO)
    }

    @Test
    fun `BlackLevel RATIONAL 必须除以分母 6400-100=64 不是 6400`() {
        // 若解析器只取分子（回归 bug），64 会变 6400 > WhiteLevel → 结构误判 null。
        // 能算出正确净比例本身就是分子/分母都对了的证明。
        val ratio = TiffRawFlashStat.netSignalRatio(tempDng(64))!!   // 恰好贴黑电平
        assertEquals(0.0, ratio.toDouble(), 1e-9)
    }

    @Test
    fun `非 TIFF 文件返回 null 调用侧不跳 RAW`() {
        val f = File.createTempFile("not_dng", ".bin")
        f.deleteOnExit()
        f.writeBytes(ByteArray(256) { 0x55 })
        assertNull(TiffRawFlashStat.netSignalRatio(f))
    }

    @Test
    fun `文件不存在返回 null`() {
        assertNull(TiffRawFlashStat.netSignalRatio(File("Z:/no/such/file.dng")))
    }
}
