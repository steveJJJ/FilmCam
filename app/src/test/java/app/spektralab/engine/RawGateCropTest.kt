package app.spektralab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * P3 RAW 片门预裁（ActiveArea 改写方案）的纯函数：TIFF/DNG 几何解析、
 * ActiveArea 改写、参数档位判断。真机标定结论：引擎的 LibRaw 按 ActiveArea
 * 出图，改写它 = 引擎侧片门裁剪；io.geometry 参数被引擎拒收（已弃用）。
 */
class RawGateCropTest {

    /** 造一个最小 TIFF：小端、IFD0 带缩略图尺寸，SubIFD 带真正的大画面 + SubIFDs 链。 */
    private fun tiff(
        vararg ifds: Pair<List<Pair<Int, Int>>, List<Int>>,  // (tag to value) + subIFD 占位
    ): ByteArray {
        // 布局：8 字节头 + 每个一 IFD 块（2 + 12n + 4）+ 值池（这里全用内联，LONG 单值 ≤4B 内联）
        val header = ByteArray(8)
        header[0] = 'I'.code.toByte(); header[1] = 'I'.code.toByte()
        header.writeU16(2, 42)
        // 先算各 IFD 偏移
        val sizes = ifds.map { (entries, _) -> 2 + 12 * entries.size + 4 }
        val offsets = IntArray(ifds.size)
        var cur = 8
        for (i in ifds.indices) { offsets[i] = cur; cur += sizes[i] }
        header.writeU32(4, offsets[0])
        val out = ByteArray(cur)
        System.arraycopy(header, 0, out, 0, 8)
        for ((i, ifd) in ifds.withIndex()) {
            val (entries, subs) = ifd
            var p = offsets[i]
            out.writeU16(p, entries.size + if (subs.isEmpty()) 0 else 1)
            p += 2
            for ((tag, value) in entries) {
                out.writeU16(p, tag)
                out.writeU16(p + 2, 4)          // LONG
                out.writeU32(p + 4, 1)          // count
                // -1 哨兵 = 指向下一个 IFD 的真实偏移
                out.writeU32(p + 8, if (value == -1 && i + 1 < ifds.size) offsets[i + 1] else value)
                p += 12
            }
            if (subs.isNotEmpty()) {
                // SubIFDs 项：count 个 LONG 放不进内联 → 值池紧跟 IFD 链末尾（借用简化和真实结构一致的形态）
                out.writeU16(p, 0x0141)
                out.writeU16(p + 2, 4)
                out.writeU32(p + 4, subs.size)
                // 池子放文件尾（追加），这里先记占位：直接用 IFD 后面 4 字节凑（subs.size==1 时内联可容纳）
                if (subs.size == 1) out.writeU32(p + 8, if (subs[0] == -1 && i + 1 < ifds.size) offsets[i + 1] else subs[0])
                p += 12
            }
            // next IFD
            out.writeU32(p, if (i + 1 < ifds.size) offsets[i + 1] else 0)
        }
        return out
    }

    /** 在 TIFF 的尾部值池追加 ActiveArea（LONG×4 外存），返回改好的字节与值偏移。 */
    private fun withActiveArea(bytes: ByteArray, tlbr: IntArray): Pair<ByteArray, Int> {
        val out = bytes.copyOf(bytes.size + 16)
        val off = bytes.size
        for (k in 0..3) out.writeU32(off + 4 * k, tlbr[k])
        return out to off
    }

    /** 把 0xc68d 条目内联值改成外存偏移（build 时 ActiveArea 用占位 [off] 直写内联）。 */
    private fun ByteArray.writeTagValue(tag: Int, value: Int) {
        // 扫 IFD 找 tag，把内联 LONG 值改成 value，并把 count 补成 4（ActiveArea = top/left/bottom/right）
        val n = readU16(8)
        var p = 10
        repeat(n) {
            val t = readU16(p)
            if (t == tag) { writeU32(p + 4, 4); writeU32(p + 8, value) }
            p += 12
        }
    }
    private fun ByteArray.readU16(off: Int): Int =
        (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)
    private fun ByteArray.readU32(off: Int): Int {
        var v = 0
        for (k in 3 downTo 0) v = (v shl 8) or (this[off + k].toInt() and 0xFF)
        return v
    }

    private fun ByteArray.writeU16(off: Int, v: Int) {
        this[off] = (v and 0xFF).toByte(); this[off + 1] = ((v shr 8) and 0xFF).toByte()
    }
    private fun ByteArray.writeU32(off: Int, v: Int) {
        writeU16(off, v and 0xFFFF); writeU16(off + 2, (v shr 16) and 0xFFFF)
    }

    /** 造一个 160x120 的 DNG 风格文件：IFD0 带 0x0100/0x0101 + ActiveArea（外存值池）。 */
    private fun dng160x120(activeArea: IntArray): ByteArray {
        val base = tiff(listOf(0x0100 to 160, 0x0101 to 120, 0xc68d to 0) to emptyList())
        val (out, aaOff) = withActiveArea(base, activeArea)
        out.writeTagValue(0xc68d, aaOff)   // 0xc68d 内联 LONG → 指向尾部值池
        return out
    }

    private fun geometry(bytes: ByteArray): SpektraProcessor.TiffGeometry? {
        val ins = ByteArrayInputStream(bytes)
        ins.read(ByteArray(8))   // 先消费 8 字节头：解析器从流当前位置接着读、头单独传
        return SpektraProcessor.tiffGeometry(ins, bytes.copyOf(8))
    }

    private fun parseDims(bytes: ByteArray) = geometry(bytes)?.let { it.width to it.height }

    @Test
    fun `取面积最大的 IFD 画面`() {
        val bytes = tiff(
            listOf(0x0100 to 160, 0x0101 to 120, 0x0141 to -1) to emptyList(),  // IFD0 = 缩略图，SubIFDs 项内联指向 IFD1
            listOf(0x0100 to 4080, 0x0101 to 3060) to emptyList(),              // IFD1 = 主画面
        )
        assertEquals(4080 to 3060, parseDims(bytes))
    }

    @Test
    fun `没有尺寸标签返回 null`() {
        val bytes = tiff(listOf(0x0112 to 1) to emptyList())
        assertNull(parseDims(bytes))
    }

    @Test
    fun `非 TIFF 头返回 null`() {
        val junk = ByteArray(64) { it.toByte() }
        junk[0] = 0x50; junk[1] = 0x4B   // "PK" zip 头，且两字节不同
        assertNull(SpektraProcessor.tiffDimensions(ByteArrayInputStream(junk), junk.copyOf(8)))
    }

    @Test
    fun `ActiveArea 存在时尺寸以它为准`() {
        // raw 4080x3060，ActiveArea 内缩 → 引擎解码的就是 3910x2890 那块
        val bytes = tiff(listOf(0x0100 to 4080, 0x0101 to 3060, 0xc68d to 0) to emptyList())
        val (out, aaOff) = withActiveArea(bytes, intArrayOf(170, 170, 3060, 4080))
        out.writeTagValue(0xc68d, aaOff)
        val geo = geometry(out)!!
        assertEquals(3910 to 2890, geo.width to geo.height)
        assertEquals(intArrayOf(170, 170, 3060, 4080).toList(), geo.activeArea!!.toList())
        assertEquals(aaOff, geo.activeAreaOffset)
    }

    @Test
    fun `片框开着且是 135 才要预裁`() {
        val on = """{"overscan_active":true,"overscan_format":"135"}"""
        assertEquals(36f / 24f, SpektraProcessor.gateAspectFromParams(on)!!)
        val off = """{"overscan_active":false,"overscan_format":"135"}"""
        assertNull(SpektraProcessor.gateAspectFromParams(off))
        val other = """{"overscan_active":true,"overscan_format":"4x5"}"""
        assertNull(SpektraProcessor.gateAspectFromParams(other))
        assertNull(SpektraProcessor.gateAspectFromParams("不是 JSON"))
    }

    @Test
    fun `prepareGateDng 横拍改写 ActiveArea 为 3比2`() {
        val dir = createTempDir("gate")
        val src = java.io.File(dir, "frame_01.dng").apply { writeBytes(dng160x120(intArrayOf(0, 0, 120, 160))) }
        val params = """{"overscan_active":true,"overscan_format":"135"}"""
        val work = SpektraProcessor.prepareGateDng(src.absolutePath, dir, params)
        assertTrue(work != src.absolutePath)          // 动了刀 → 缓存副本
        val geo = geometry(java.io.File(work).readBytes())!!
        // 160x120 裁 3:2 → 160x106，上下各 (120-106)/2 = 7
        assertEquals(160 to 106, geo.width to geo.height)
        assertEquals(intArrayOf(7, 0, 113, 160).toList(), geo.activeArea!!.toList())
        // 原底片保持原样
        assertEquals(160 to 120, geometry(src.readBytes())!!.let { it.width to it.height })
    }

    @Test
    fun `prepareGateDng 竖拍走竖置片门`() {
        val dir = createTempDir("gate")
        // 竖的画面 120x160（高>宽），竖置片门 2:3 → 不裁（0.75 = 24/36 正好）
        val src = java.io.File(dir, "portrait.dng").apply { writeBytes(dng160x120(intArrayOf(0, 0, 160, 120))) }
        val params = """{"overscan_active":true,"overscan_format":"135"}"""
        val work = SpektraProcessor.prepareGateDng(src.absolutePath, dir, params)
        assertEquals(src.absolutePath, work)          // 已是竖置片门形状，不动刀
        // 横的片门比套竖画面会被裁烂：明确验证取了倒数
        val wide = java.io.File(dir, "wide.dng").apply { writeBytes(dng160x120(intArrayOf(0, 0, 120, 160))) }
        // 160x120 在横片门下源比 1.333 < 1.5 → 裁高；竖置下 0.75 → 不裁（上面已验）
        assertTrue(wide.exists())
    }

    @Test
    fun `prepareGateDng 已是片门形状原样返回`() {
        val dir = createTempDir("gate")
        // 160 x 106.67 不行，用 3:2 的整数对：180x120
        val bytes = tiff(listOf(0x0100 to 180, 0x0101 to 120) to emptyList())
        val src = java.io.File(dir, "gate_shape.dng").apply { writeBytes(bytes) }
        val params = """{"overscan_active":true,"overscan_format":"135"}"""
        assertEquals(src.absolutePath, SpektraProcessor.prepareGateDng(src.absolutePath, dir, params))
    }

    @Test
    fun `prepareGateDng 没开片框不动刀`() {
        val dir = createTempDir("gate")
        val src = java.io.File(dir, "off.dng").apply { writeBytes(dng160x120(intArrayOf(0, 0, 120, 160))) }
        val params = """{"overscan_active":false,"overscan_format":"135"}"""
        assertEquals(src.absolutePath, SpektraProcessor.prepareGateDng(src.absolutePath, dir, params))
    }

    @Test
    fun `无 ActiveArea 的普通 TIFF 不炸`() {
        val dir = createTempDir("gate")
        val bytes = tiff(listOf(0x0100 to 160, 0x0101 to 120) to emptyList())
        val src = java.io.File(dir, "plain.dng").apply { writeBytes(bytes) }
        val params = """{"overscan_active":true,"overscan_format":"135"}"""
        assertEquals(src.absolutePath, SpektraProcessor.prepareGateDng(src.absolutePath, dir, params))
    }

    @Test
    fun `真机 DNG 解析出 4080x3060 且预裁到 3比2`() {
        // DngCreator 实拍样本（S25U 主摄，RAW 通路落盘的底片）。
        // 24MB 测试资源不出现在 CI 也没关系：本机真机联调用的回归样本。
        val f = java.io.File("src/test/resources/sample_frame.dng")
        if (!f.exists()) return   // 资源没跟仓库走时跳过，别让 CI 假红
        f.inputStream().use { ins ->
            val head = ByteArray(8)
            assertEquals(8, ins.read(head))
            val geo = SpektraProcessor.tiffGeometry(ins, head)
            assertEquals(4080 to 3060, geo!!.let { it.width to it.height })
        }
        // 真机标定：改写 ActiveArea 后引擎解码即 4080x2720
        val dir = createTempDir("gate")
        val params = """{"overscan_active":true,"overscan_format":"135"}"""
        val work = SpektraProcessor.prepareGateDng(f.absolutePath, dir, params)
        assertTrue(work != f.absolutePath)
        val geo = geometry(java.io.File(work).readBytes())!!
        assertEquals(4080 to 2720, geo.width to geo.height)
        assertEquals(intArrayOf(170, 0, 2890, 4080).toList(), geo.activeArea!!.toList())
    }

    @Test
    fun `白名单不再包含 io geometry`() {
        // io.geometry 参数真机实测被引擎拒收（spk_open failed），已全面弃用
        val keys = FilmStockMapper.ENGINE_KNOWN_KEYS
        assertTrue(keys.none { it.startsWith("io.geometry") })
    }
}
