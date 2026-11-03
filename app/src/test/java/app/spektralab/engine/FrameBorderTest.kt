package app.spektralab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.io.File

/**
 * 胶片相框（引擎 `film_render.overscan.*`）：把片基、齿孔、暗边文字一起扫进成片。
 *
 * 测试点：
 *  1) 片框关着时参数里一个 overscan 键都不许有（引擎不认的键会整卷失败，多一个都不行）；
 *  2) 开着时每个键都必须在引擎白名单里；
 *  3) 只有 135 一格片门（36×24，3:2），扫的是 `135` 版式，画面按片门裁；
 *  4) 帧号逐帧递增（底片暗边本来就印着 1…36，每帧一个数）；
 *  5) 片门裁切：4:3 取景套 36×24 片门要裁成精确 3:2，已经是片门形状的一格不许动；
 *  6) 旧包存的半格档位（135_half）升级后归到 135，不能把片框悄悄关掉。
 */
class FrameBorderTest {

    @Before
    fun setUp() {
        val f = File("src/main/assets/resources/stock_catalog.json")
        assertTrue("缺少 catalog: ${f.absolutePath}", f.exists())
        FilmStockMapper.installCatalog(
            FilmStockMapper.parseCatalog(f.readText(Charsets.UTF_8))
        )
    }

    private fun json(format: FilmStockMapper.FrameFormat): JSONObject = JSONObject(
        FilmStockMapper.buildParamsJson(
            filmStockId = "kodak_portra_400",
            printStockId = "kodak_portra_endura",
            settings = FilmStockMapper.DevSettings(frameFormat = format),
            includeStyleMark = false,
        )
    )

    @Test
    fun frameOff_carriesNoOverscanKeys() {
        val o = json(FilmStockMapper.FrameFormat.OFF)
        for (k in o.keys()) {
            assertFalse("相框关着时不该有 $k", k.startsWith("overscan"))
        }
    }

    @Test
    fun frameOn_onlyUsesKeysTheEngineKnows() {
        for (format in FilmStockMapper.FrameFormat.values().filter { it.isOn }) {
            val o = json(format)
            val overscanKeys = o.keys().asSequence().filter { it.startsWith("overscan") }.toList()
            assertTrue("相框开着就该有 overscan 参数", overscanKeys.isNotEmpty())
            for (k in overscanKeys) {
                assertTrue(
                    "「$k」不是引擎参数，引擎会报 unknown parameter",
                    k in FilmStockMapper.ENGINE_KNOWN_KEYS
                )
            }
        }
    }

    @Test
    fun frameOn_scansTheWholeFilmBaseWithEdgeText() {
        val o = json(FilmStockMapper.FrameFormat.FULL)
        assertTrue(o.getBoolean("overscan_active"))
        // 取值贴着引擎默认：整条片基 + 自动片门，别自己发明。
        assertEquals("strip", o.getString("overscan_mode"))
        assertEquals(FilmStockMapper.OVERSCAN_FORMAT_FULL, o.getString("overscan_format"))
        assertEquals("auto", o.getString("overscan_gate"))
        assertFalse("不双联", o.getBoolean("overscan_pair"))
        assertEquals("不漏光", 0, o.getInt("overscan_leaks"))
        // 暗边印刷字：真卷片基边缘都印着胶卷型号
        assertTrue(
            "暗边该印着胶卷名，留空就没有底片感",
            o.getString("overscan_edge_text").isNotBlank()
        )
    }

    @Test
    fun fullFrame_asksForThe135Gate() {
        // 「135 边框」= 36×24 那一格：版式串必须是 135，
        // 但引擎会拿输入尺寸对着片门校验，所以画面要先裁到 3:2（见 gateCrop 用例）
        val o = json(FilmStockMapper.FrameFormat.FULL)
        assertEquals(FilmStockMapper.OVERSCAN_FORMAT_FULL, o.getString("overscan_format"))
        assertEquals("135", o.getString("overscan_format"))
    }

    @Test
    fun legacyHalfFrameChoice_landsOnThe135Gate() {
        // 旧包只给过半格这一档；升级后只剩 135，老用户不该被"悄悄关掉片框"
        assertEquals(
            FilmStockMapper.FrameFormat.FULL,
            FilmStockMapper.FrameFormat.fromKey(FilmStockMapper.FrameFormat.LEGACY_KEY_HALF)
        )
        // 引擎版式串过来的老数据同理
        assertEquals(
            FilmStockMapper.FrameFormat.OFF,
            FilmStockMapper.FrameFormat.fromOverscanFormat("135_half")
        )
    }

    @Test
    fun frameNumberAdvancesPerFrame() {
        val base = FilmStockMapper.buildParamsJson(
            "kodak_portra_400", "kodak_portra_endura",
            FilmStockMapper.DevSettings(frameFormat = FilmStockMapper.FrameFormat.FULL),
            includeStyleMark = false,
        )
        assertTrue("第一帧就该带帧号", JSONObject(base).getInt("overscan_frame_number") >= 1)
        val seventh = JSONObject(FilmStockMapper.withFrameNumber(base, 7))
        assertEquals(7, seventh.getInt("overscan_frame_number"))
        // 覆盖不能把别的参数弄丢
        assertEquals("kodak_portra_400", seventh.getString("film_stock"))
        assertTrue(seventh.getBoolean("overscan_active"))
    }

    @Test
    fun withFrameNumber_keepsJsonIntactWhenItCannotParse() {
        // 解析失败要原样返回：宁可帧号不对，也不能因为一个键让整卷冲不出来
        assertEquals("not json", FilmStockMapper.withFrameNumber("not json", 3))
    }

    @Test
    fun gateCrop_cuts4x3ToThe135Gate() {
        // 1440×1080（4:3）套 36×24 片门：上下各裁 60px，出来的必须是精确 3:2
        val c = FilmStockMapper.gateCrop(FilmStockMapper.FrameFormat.FULL, 1440, 1080)
        assertTrue("4:3 要裁", c.cropped)
        assertEquals(1440, c.w)
        assertEquals(960, c.h)
        assertEquals(0, c.x)
        assertEquals(60, c.y)          // 居中：(1080-960)/2
        assertEquals(1.5f, c.w.toFloat() / c.h.toFloat(), 1e-6f)
    }

    @Test
    fun gateCrop_usesTheUprightGateForPortraitFrames() {
        // 竖拍绝不能套横置片门（那会把竖照片压成横的、裁掉一半高度）：
        // 1080×1440（3:4）走 24×36 竖置片门，左右各裁 60px，出来的精确 2:3
        val c = FilmStockMapper.gateCrop(FilmStockMapper.FrameFormat.FULL, 1080, 1440)
        assertTrue(c.cropped)
        assertEquals(960, c.w)
        assertEquals(1440, c.h)
        assertEquals(60, c.x)
        assertEquals(0, c.y)
        assertEquals(2f / 3f, c.w.toFloat() / c.h.toFloat(), 1e-6f)
        // 裁掉的比例和横拍一样（各约 5.6%），只是裁的是左右两条边
        assertEquals(0.111f, (1080 - c.w).toFloat() / 1080f, 0.002f)
    }

    @Test
    fun outputGateIsStillFullFrameWhenTheBorderIsOff() {
        // 片框关着也要按 135 全画幅出片 —— 取景 4:3、成片 3:2 是机身的画幅，与片框无关
        assertEquals(
            FilmStockMapper.FrameFormat.FULL,
            FilmStockMapper.DevSettings(frameFormat = FilmStockMapper.FrameFormat.OFF).outputGate
        )
        assertEquals(
            FilmStockMapper.FrameFormat.FULL,
            FilmStockMapper.DevSettings(frameFormat = FilmStockMapper.FrameFormat.FULL).outputGate
        )
    }

    @Test
    fun gateCrop_leavesAFrameThatAlreadyMatchesAlone() {
        // 已经是 3:2 的一格都不许动
        val full = FilmStockMapper.gateCrop(FilmStockMapper.FrameFormat.FULL, 1440, 960)
        assertFalse("3:2 不用裁", full.cropped)
        // 竖帧正好是竖置片门形状时同样不动
        val upright = FilmStockMapper.gateCrop(FilmStockMapper.FrameFormat.FULL, 960, 1440)
        assertFalse("2:3 竖帧不用裁", upright.cropped)
    }

    @Test
    fun gateCrop_trimsTheWideSideWhenTheSourceIsWiderThanTheGate() {
        // 导入的 16:9 照片套 3:2：裁宽而不是裁高，比例仍要精确
        val c = FilmStockMapper.gateCrop(FilmStockMapper.FrameFormat.FULL, 1920, 1080)
        assertTrue(c.cropped)
        assertEquals(1080, c.h)
        assertEquals(1620, c.w)
        assertEquals(150, c.x)
        assertEquals(0, c.y)
        assertEquals(1.5f, c.w.toFloat() / c.h.toFloat(), 1e-6f)
    }

    @Test
    fun cycleGoesOffThenFullFrame() {
        // 设置面板只剩两档：关 ↔ 全画幅
        assertEquals(FilmStockMapper.FrameFormat.FULL, FilmStockMapper.FrameFormat.next(
            FilmStockMapper.FrameFormat.OFF
        ))
        assertEquals(FilmStockMapper.FrameFormat.OFF, FilmStockMapper.FrameFormat.next(
            FilmStockMapper.FrameFormat.FULL
        ))
        assertEquals(2, FilmStockMapper.FrameFormat.values().size)
    }

    @Test
    fun selfCheckOnlyEverAsksForThe135Gate() {
        // 只剩一格版式：自检不该再冒出 135_half 那种候选
        val c = FilmStockMapper.overscanCandidates(FilmStockMapper.FrameFormat.FULL)
        assertEquals(listOf(FilmStockMapper.OVERSCAN_FORMAT_FULL), c)
        assertEquals(
            listOf(FilmStockMapper.OVERSCAN_FORMAT_FULL),
            FilmStockMapper.overscanCandidates(FilmStockMapper.FrameFormat.OFF)
        )
    }
}
