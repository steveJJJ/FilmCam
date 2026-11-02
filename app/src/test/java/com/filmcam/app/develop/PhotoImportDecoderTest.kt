package com.filmcam.app.develop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 导入本地照片的解码控制流。
 *
 * 重点守住一条：Android 在 `inJustDecodeBounds=true` 时**必定返回 null**，
 * 所以探测阶段的成败只能看尺寸，不能看解码器的返回值。2026-11-03 就是因为
 * 写成 `openStream()?.use { decodeStream(...) } ?: return null`，
 * 每张照片都在探测时被判失败，导入永远 0 张（界面只提示「没读到这些照片」）。
 */
class PhotoImportDecoderTest {

    private fun stream(): java.io.InputStream = java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3))

    @Test
    fun probeThatReturnsNullButFillsSizeStillDecodes() {
        // 模拟真实 BitmapFactory：探测时返回 null（用 Unit 之后丢弃），尺寸却正常填出来了
        val samples = mutableListOf<Int>()
        val out = PhotoImportDecoder.decodeSampled(
            openStream = ::stream,
            probe = { PhotoImportDecoder.Size(8000, 6000) },
            decode = { _, sample -> samples.add(sample); "bitmap" },
            maxPixels = 24_000_000,
        )
        assertEquals("探测返回 null 不等于读失败，必须继续解码", "bitmap", out)
        assertEquals("48MP 超过 24MP 上限 → 降采样 2", listOf(2), samples)
    }

    @Test
    fun illegalSizeIsTreatedAsReadFailure() {
        assertNull(
            PhotoImportDecoder.decodeSampled(
                openStream = ::stream,
                probe = { null },
                decode = { _, _ -> "bitmap" },
                maxPixels = 24_000_000,
            )
        )
    }

    @Test
    fun unopenableStreamIsTreatedAsReadFailure() {
        assertNull(
            PhotoImportDecoder.decodeSampled(
                openStream = { null },
                probe = { PhotoImportDecoder.Size(4000, 3000) },
                decode = { _, _ -> "bitmap" },
                maxPixels = 24_000_000,
            )
        )
    }

    @Test
    fun smallImageIsNotDownsampled() {
        val samples = mutableListOf<Int>()
        PhotoImportDecoder.decodeSampled(
            openStream = ::stream,
            probe = { PhotoImportDecoder.Size(4000, 3000) },
            decode = { _, sample -> samples.add(sample); "bitmap" },
            maxPixels = 24_000_000,
        )
        assertEquals(listOf(1), samples)
    }

    @Test
    fun sampleSizeIsAlwaysPowerOfTwo() {
        assertEquals(1, PhotoImportDecoder.sampleSizeFor(0, 0, 24_000_000))
        assertEquals(1, PhotoImportDecoder.sampleSizeFor(4000, 3000, 24_000_000))
        assertEquals(2, PhotoImportDecoder.sampleSizeFor(8000, 6000, 24_000_000))
        assertEquals(4, PhotoImportDecoder.sampleSizeFor(16000, 12000, 24_000_000))
        // 降到采样后仍在上限之上就继续翻倍，不会停在中途
        assertEquals(8, PhotoImportDecoder.sampleSizeFor(30000, 20000, 24_000_000))
    }
}
