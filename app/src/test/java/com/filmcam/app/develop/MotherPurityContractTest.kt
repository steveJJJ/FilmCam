package com.filmcam.app.develop

import app.spektralab.engine.FilmStockMapper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 🔴母片纯净契约（vc114，一加 13 用户反馈的根因修复）。
 *
 * 背景：`FilmStockMapper.buildParamsJson` 在 `extraEntries=null` 时会按
 * `settings.frameFormat` **自动补一份 overscan 片框**（齿孔/片基/暗边/帧号）。
 * v2 契约是「冲洗只出纯色彩母片，片框推迟到编辑导出」，但历史上机身
 * 「胶片相框」档位（旧版 `filmcam_frame_border` 开关升级迁移 → FULL）会顺着
 * 默认分支泄进母片参数 —— 齿孔在冲洗时直接烤进母片像素，之后编辑页选
 * 「无/白边」都去不掉，表现为「不管怎么设置都有相片齿孔」。
 *
 * 双重封死，缺一不可，本测试两路都锁：
 *  1) 机身档位钉死 OFF：`readFrameFormat` 不再读 prefs（历史 FULL 值彻底失效）；
 *  2) 所有喂母片的参数构建点显式传空表（`MOTHER_EXTRA` / `extraEntries = emptyList()`），
 *     即使将来有人把档位改回 FULL，母片也不带框。
 */
class MotherPurityContractTest {

    @Before
    fun setUp() {
        val f = File("src/main/assets/resources/stock_catalog.json")
        assertTrue("缺少 catalog: ${f.absolutePath}", f.exists())
        FilmStockMapper.installCatalog(
            FilmStockMapper.parseCatalog(f.readText(Charsets.UTF_8))
        )
    }

    // ---------- 1) 机身档位钉死 ----------

    /**
     * 源码扫描：readFrameFormat 必须钉死 OFF，不允许再读任何 prefs
     * （旧版迁移路径 filmcam_frame_border 是这次事故的入口）。
     */
    @Test
    fun `机身相框档位必须钉死 OFF 不再读 prefs`() {
        assertNotNull("源码扫描必须真的跑到，不能静默跳过", sourceRoot())
        val mapper = read("java/app/spektralab/engine/FilmStockMapper.kt") ?: return
        assertTrue(
            "readFrameFormat 必须直接返回 FrameFormat.OFF",
            mapper.contains("fun readFrameFormat") && mapper.contains("= FrameFormat.OFF")
        )
        assertFalse(
            "不得再读历史相框 prefs（PREF_FRAME_BORDER / PREF_FRAME_FORMAT 常量必须删除）",
            mapper.contains("PREF_FRAME_BORDER") || mapper.contains("PREF_FRAME_FORMAT")
        )
        assertFalse(
            "档位循环入口必须删除（设置行已移除）",
            mapper.contains("fun cycleFrameFormat") || mapper.contains("fun saveFrameFormat")
        )
    }

    // ---------- 2) 空表封死默认分支 ----------

    /** 即使 settings.frameFormat=FULL，空表 extraEntries 也必须盖掉自动补框的默认分支。 */
    @Test
    fun `extraEntries 空表必须盖掉 FULL 档的自动补框`() {
        val pure = JSONObject(
            FilmStockMapper.buildParamsJson(
                filmStockId = "kodak_portra_400",
                printStockId = "kodak_portra_endura",
                settings = FilmStockMapper.DevSettings(frameFormat = FilmStockMapper.FrameFormat.FULL),
                includeStyleMark = false,
                extraEntries = emptyList(),
            )
        )
        for (k in pure.keys()) {
            assertFalse("母片参数不许有 overscan 键：$k", k.startsWith("overscan"))
        }
    }

    /** 对照组：extraEntries=null 时默认分支仍在（引擎层行为未改，别的地方还依赖它）。 */
    @Test
    fun `extraEntries 缺省时 FULL 档仍自动补框 引擎层行为不变`() {
        val legacy = JSONObject(
            FilmStockMapper.buildParamsJson(
                filmStockId = "kodak_portra_400",
                printStockId = "kodak_portra_endura",
                settings = FilmStockMapper.DevSettings(frameFormat = FilmStockMapper.FrameFormat.FULL),
                includeStyleMark = false,
            )
        )
        assertTrue(
            "引擎层默认分支行为未变（FrameBorderTest 锁定）",
            legacy.getBoolean("overscan_active")
        )
    }

    // ---------- 3) 母片构建点全部传空表 ----------

    /**
     * DevelopmentService 里所有喂母片的 buildParamsJson 都必须带
     * `extraEntries = MOTHER_EXTRA`（带框变体显式传 frameBorderEntries，不在其列）。
     */
    @Test
    fun `冲洗侧母片参数构建点全部传 MOTHER_EXTRA`() {
        assertNotNull("源码扫描必须真的跑到，不能静默跳过", sourceRoot())
        val dev = read("java/com/filmcam/app/develop/DevelopmentService.kt") ?: return
        assertTrue("MOTHER_EXTRA 常量必须定义（含母片纯净契约注释）",
            dev.contains("private val MOTHER_EXTRA") &&
                dev.contains("母片纯净契约"))
        val sites = Regex("extraEntries = MOTHER_EXTRA").findAll(dev).count()
        assertTrue(
            "母片构建点不足：cleanSafe/paramsJson/safeJson/activeParamsJson/" +
                "flashParamsJson/兜底印相档共 6 处，实际 $sites",
            sites >= 6
        )
    }

    /** 导出侧「无框兜底」与编辑预览兜底也必须封死（缺省 recipeJson 的老卷走这两条）。 */
    @Test
    fun `导出与预览的无框兜底必须传空表`() {
        assertNotNull("源码扫描必须真的跑到，不能静默跳过", sourceRoot())
        val svc = read("java/com/filmcam/app/develop/EditExportService.kt") ?: return
        val core = read("java/com/filmcam/app/develop/EditRenderCore.kt") ?: return
        for ((name, src) in listOf("EditExportService" to svc, "EditRenderCore" to core)) {
            assertTrue(
                "$name 的 recipeJson.ifBlank 兜底必须传 extraEntries = emptyList()",
                src.contains("mother.recipeJson.ifBlank") && src.contains("extraEntries = emptyList()")
            )
        }
    }

    /** MainActivity 的相框档位 UI 死代码必须清除（frameBorderName / probeFrameGate）。 */
    @Test
    fun `机身相框 UI 死代码必须清除`() {
        assertNotNull("源码扫描必须真的跑到，不能静默跳过", sourceRoot())
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        for (symbol in listOf("frameBorderName", "probeFrameGate", "frameGateProbing")) {
            assertFalse("MainActivity 不得再有 $symbol（设置行已移除）", main.contains(symbol))
        }
    }

    // ---------- 源码定位（与 DefaultSettingsTest 同一套口径） ----------

    private fun sourceRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val d = dir ?: return null
            val main = File(d, "app/src/main")
            if (main.isDirectory) return main
            dir = d.parentFile
        }
        return null
    }

    private fun read(rel: String): String? {
        val root = sourceRoot() ?: return null
        return File(root, rel).takeIf { it.isFile }?.readText()
    }
}
