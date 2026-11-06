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
    fun `prepareGateDng 横拍变焦对齐加片门改写 ActiveArea`() {
        val dir = createTempDir("gate")
        val src = java.io.File(dir, "frame_01.dng").apply { writeBytes(dng160x120(intArrayOf(0, 0, 120, 160))) }
        val params = """{"overscan_active":true,"overscan_format":"135"}"""
        val work = SpektraProcessor.prepareGateDng(src.absolutePath, dir, params)
        assertTrue(work != src.absolutePath)          // 动了刀 → 缓存副本
        val geo = geometry(java.io.File(work).readBytes())!!
        // 160x120 先裁 1/1.52（→105x78，原点偶对齐 26,20）再裁 3:2（102=3*34, 68=2*34），
        // 中心组合偏移 (24,26)（偶对齐后）——视角与 JPEG(35mm 裁切) 一致
        assertEquals(102 to 68, geo.width to geo.height)
        assertEquals(intArrayOf(24, 26, 92, 128).toList(), geo.activeArea!!.toList())
        // 原底片保持原样
        assertEquals(160 to 120, geometry(src.readBytes())!!.let { it.width to it.height })
    }

    @Test
    fun `prepareGateDng 竖拍变焦对齐加竖置片门`() {
        val dir = createTempDir("gate")
        // TIFF 尺寸自洽的竖画面 120x160（旧用例的 AA(0,0,160,120) 配 160x120 标签越界，
        // 被 tiffGeometry 静默拒掉，测的其实是「无 AA」拒绝路径——这里换成真竖 DNG）
        val base = tiff(listOf(0x0100 to 120, 0x0101 to 160, 0xc68d to 0) to emptyList())
        val (out, aaOff) = withActiveArea(base, intArrayOf(0, 0, 160, 120))
        out.writeTagValue(0xc68d, aaOff)
        val src = java.io.File(dir, "portrait.dng").apply { writeBytes(out) }
        val params = """{"overscan_active":true,"overscan_format":"135"}"""
        val work = SpektraProcessor.prepareGateDng(src.absolutePath, dir, params)
        assertTrue(work != src.absolutePath)
        val geo = geometry(java.io.File(work).readBytes())!!
        // 120x160 先裁 1/1.52（→78x105）再裁竖置 2:3（68=2*34, 102=3*34）
        assertEquals(68 to 102, geo.width to geo.height)
        assertEquals(intArrayOf(26, 24, 128, 92).toList(), geo.activeArea!!.toList())
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
    fun `prepareGateDng 没开片框仍做变焦对齐`() {
        val dir = createTempDir("gate")
        val src = java.io.File(dir, "off.dng").apply { writeBytes(dng160x120(intArrayOf(0, 0, 120, 160))) }
        val params = """{"overscan_active":false,"overscan_format":"135"}"""
        // 片框关时只裁变焦（35mm 视角对齐）：160x120 → 105x78，原点 (20,26)
        val work = SpektraProcessor.prepareGateDng(src.absolutePath, dir, params)
        assertTrue(work != src.absolutePath)
        val geo = geometry(java.io.File(work).readBytes())!!
        assertEquals(105 to 78, geo.width to geo.height)
        assertEquals(intArrayOf(20, 26, 98, 131).toList(), geo.activeArea!!.toList())
    }

    @Test
    fun `prepareGateDng 按帧记录的变焦比补裁`() {
        val dir = createTempDir("gate")
        val src = java.io.File(dir, "frame_zoom.dng").apply { writeBytes(dng160x120(intArrayOf(0, 0, 120, 160))) }
        val params = """{"overscan_active":false,"overscan_format":"135"}"""
        // 帧记录 1.35（另一台机主摄等效焦距更长）→ 保留更多画面，与设备默认 1.52 不同。
        // 这正是「冲洗不能依赖进程内全局变量」的意义：同一卷里可能有别处拍的帧。
        val work = SpektraProcessor.prepareGateDng(src.absolutePath, dir, params, 1.35f)
        assertTrue(work != src.absolutePath)
        val geo = geometry(java.io.File(work).readBytes())!!
        // 160x120 中心裁 1/1.35 → 118x88，原点偶对齐 (20,16)
        assertEquals(118 to 88, geo.width to geo.height)
        assertEquals(intArrayOf(16, 20, 104, 138).toList(), geo.activeArea!!.toList())
        // 同一份底片按「老帧」（zoom=0）冲 → 回落设备默认 1.52 → 105x78
        val legacy = SpektraProcessor.prepareGateDng(src.absolutePath, dir, params, 0f)
        val geo2 = geometry(java.io.File(legacy).readBytes())!!
        assertEquals(105 to 78, geo2.width to geo2.height)
    }

    @Test
    fun `prepareGateDng 只改写 ActiveArea 不动其它 DNG 标签`() {
        // 撤回「固定日光 WB」的守护：那份实现按错误 tag（0xC625，实为
        // CameraCalibration2）覆写了 DNG 的 16 字节，可能写坏相机标定数据、
        // 让 RAW 会话失败掉兜底档。现在除 ActiveArea 的 LONG×4 外，
        // 文件其余字节必须一个都不变。
        val dir = createTempDir("gate_wb")
        val base = tiff(listOf(0x0100 to 160, 0x0101 to 120, 0xc68d to 0) to emptyList())
        val (bytes, aaOff) = withActiveArea(base, intArrayOf(0, 0, 120, 160))
        bytes.writeTagValue(0xc68d, aaOff)
        val src = java.io.File(dir, "frame.dng").apply { writeBytes(bytes) }
        val params = """{"overscan_active":true,"overscan_format":"135"}"""
        val work = SpektraProcessor.prepareGateDng(src.absolutePath, dir, params)
        assertTrue(work != src.absolutePath)
        assertEquals("原底片必须保持原样", bytes.toList(), src.readBytes().toList())
        val after = java.io.File(work).readBytes()
        assertEquals(bytes.size, after.size)
        val changed = (bytes.indices).filter { bytes[it] != after[it] }
        assertTrue(
            "只应改 ActiveArea 的 16 字节，实际改动位置=$changed",
            changed.all { it in aaOff until aaOff + 16 }
        )
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
        // 真机标定：变焦对齐 + 片门后引擎解码即 2682x1788（35mm 视角 3:2）
        val dir = createTempDir("gate")
        val params = """{"overscan_active":true,"overscan_format":"135"}"""
        val work = SpektraProcessor.prepareGateDng(f.absolutePath, dir, params)
        assertTrue(work != f.absolutePath)
        val geo = geometry(java.io.File(work).readBytes())!!
        // 4080x3060 先 1/1.52 变焦对齐（→2684x2013）再 3:2（2682=3*894, 1788=2*894），
        // 与 JPEG 通路（HAL 裁 1.52 后再片门裁）最终覆盖的传感器区域一致
        assertEquals(2682 to 1788, geo.width to geo.height)
        assertEquals(intArrayOf(634, 698, 2422, 3380).toList(), geo.activeArea!!.toList())
    }

    @Test
    fun `白名单不含 io geometry 整组`() {
        // io.geometry.* 真机实测整组被引擎拒收：crop_* 在 spk_open 报错，
        // quarter_turns 让竖拍帧掉兜底档 —— 全部不收录
        val keys = FilmStockMapper.ENGINE_KNOWN_KEYS
        assertTrue(keys.none { it.startsWith("io.geometry") })
    }
}
