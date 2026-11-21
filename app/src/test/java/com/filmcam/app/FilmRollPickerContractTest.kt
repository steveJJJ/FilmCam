package com.filmcam.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 11-21 换卷体验契约（🔴方向经用户拍板纠正：「这个不是bug，我需要反转片可以拍摄」）：
 *  - 反转片是一等胶卷，必须可选、可拍摄：不得标 available=false / 置灰 / 拦截；
 *    引擎侧要有 4 支映射 + isPositive → 正片直接显影（不用相纸）链路；
 *  - 当前装机卷在列表有琥珀铭牌 + 图标描边；
 *  - 选中先卡入微动再回调、被拒先顶回抖动再回调；
 *  - 换卷成功后底部胶卷图标翻牌；
 *  - 唯一拦截口径是「当前卷有未冲洗帧」，走 filmRejectReason 单一来源。
 */
class FilmRollPickerContractTest {

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

    private fun read(rel: String): String? =
        sourceRoot()?.let { File(it, rel).takeIf { f -> f.isFile }?.readText() }

    @Test
    fun `源码扫描必须真的跑到`() {
        assertNotNull(sourceRoot())
        assertTrue(read("java/com/filmcam/app/filter/FilmRollAdapter.kt") != null)
        assertTrue(read("java/com/filmcam/app/filter/FilmRollRepository.kt") != null)
        assertTrue(read("java/app/spektralab/engine/FilmStockMapper.kt") != null)
    }

    @Test
    fun `四支反转片必须可选可拍摄`() {
        val repo = read("java/com/filmcam/app/filter/FilmRollRepository.kt") ?: return
        for (name in listOf("KODACHROME 64", "PROVIA 100F", "VELVIA 100", "EKTACHROME 100")) {
            val block = repo.substringAfter("name = \"$name\"", "")
                .substringBefore("\n        ),")
            assertTrue("$name 必须在胶卷列表里", block.isNotBlank())
            assertTrue("$name 不得被标成 available=false（用户要求反转片可以拍摄）",
                !block.contains("available = false"))
        }
        val model = read("java/com/filmcam/app/filter/FilmRoll.kt") ?: return
        assertTrue("available 默认必须是 true", model.contains("val available: Boolean = true"))
        assertTrue("模型注释要明确反转片可选可拍", model.contains("反转片必须可选、可拍摄"))
    }

    @Test
    fun `引擎侧反转片正片显影链路必须存在`() {
        val mapper = read("java/app/spektralab/engine/FilmStockMapper.kt") ?: return
        for (name in listOf("KODACHROME 64", "PROVIA 100F", "VELVIA 100", "EKTACHROME 100")) {
            assertTrue("$name 必须有引擎 filmStock 映射", mapper.contains("\"$name\""))
        }
        assertTrue("要有 isPositive 判定", mapper.contains("fun isPositive("))
        assertTrue("正片不使用相纸、直接显影", mapper.contains("NO_PRINT"))
        assertTrue("按 isPositive 分支决定显影方式", mapper.contains("if (isPositive(filmStockId))"))
        val dev = read("java/com/filmcam/app/develop/DevParamsDialog.kt") ?: return
        assertTrue("暗房参数页对正片把相纸行置灰", dev.contains("rowPaper.isEnabled = !positiveStock"))
    }

    @Test
    fun `任何一层都不得再按胶卷类型拦截反转片`() {
        val adapter = read("java/com/filmcam/app/filter/FilmRollAdapter.kt") ?: return
        assertTrue("列表项不得按 available 置灰", !adapter.contains("roll.available"))
        assertTrue("不得残留反转片禁用文案", !adapter.contains("film_slide_no_print"))
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        assertTrue("filmRejectReason 不得按 available 拦截",
            !main.substringAfter("private fun filmRejectReason", "").substringBefore("\n    }", "")
                .contains("roll.available"))
        assertTrue("不得再引用 lcd_notice_slide_no_print", !main.contains("lcd_notice_slide_no_print"))
        val gallery = read("java/com/filmcam/app/GalleryActivity.kt") ?: return
        assertTrue("画廊导入场景所有卷可选", gallery.contains("canPick = { true }"))
        val strings = read("res/values/strings.xml") ?: return
        assertTrue("误导文案 film_slide_no_print 已删除", !strings.contains("film_slide_no_print"))
    }

    @Test
    fun `列表项要有当前卷标记与暖白按压`() {
        val xml = read("res/layout/item_film_roll.xml") ?: return
        assertTrue("要有装机中铭牌 TextView", xml.contains("@+id/tvRollCurrent"))
        val adapter = read("java/com/filmcam/app/filter/FilmRollAdapter.kt") ?: return
        assertTrue("当前卷铭牌按名字比对", adapter.contains("roll.name == currentName"))
        assertTrue("当前卷图标描边", adapter.contains("setStroke("))
        assertTrue("列表项接暖白按压", adapter.contains("view.attachRowPress()"))
    }

    @Test
    fun `选中卡入与拦截顶回必须动画后才回调`() {
        val adapter = read("java/com/filmcam/app/filter/FilmRollAdapter.kt") ?: return
        assertTrue("要有 snapIn", adapter.contains("private fun snapIn"))
        assertTrue("要有 bumpBack", adapter.contains("private fun bumpBack"))
        assertTrue("卡入用 PRESS_DOWN", adapter.contains("Motion.PRESS_DOWN"))
        assertTrue("顶回左右抖动", adapter.contains("3f * dp, -2f * dp"))
        assertTrue("拦截走顶回而非直接回调",
            adapter.indexOf("bumpBack(holder.itemView)") < adapter.indexOf("onRejectAnimationEnd(roll)"))
    }

    @Test
    fun `换卷拦截口径单一来源且翻牌在换卷成功之后`() {
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        assertTrue("要有 filmRejectReason", main.contains("private fun filmRejectReason"))
        val pickerCall = main.substringAfter("private fun showFilmRollPicker()", "")
            .substringBefore("\n    }", "")
        assertTrue("canPick 走统一口径", pickerCall.contains("filmRejectReason(roll) == null"))
        assertTrue("onReject 走统一口径", pickerCall.contains("val reason = filmRejectReason(roll)"))
        val sel = main.substringAfter("private fun selectFilmRoll", "").substringBefore("\n    }", "")
        assertTrue("selectFilmRoll 也走统一口径", sel.contains("filmRejectReason(roll)"))
        assertTrue("翻牌用 scale 0.88", sel.contains("scaleX(0.88f)"))
        assertTrue("翻牌在 fromUser 分支（恢复设置不翻）",
            sel.indexOf("if (fromUser)") < sel.indexOf("scaleX(0.88f)"))
    }

    @Test
    fun `唯一换卷拦截是未冲洗帧且口径单一`() {
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val body = main.substringAfter("private fun filmRejectReason", "").substringBefore("\n    }", "")
        assertTrue("按 UndevelopedFilmStore 判定未冲洗帧", body.contains("UndevelopedFilmStore.shotCount"))
        assertTrue("拒绝文案是冲洗后才能换卷", body.contains("lcd_notice_develop_before_reload"))
        assertTrue("不得再按 available 拦截反转片", !body.contains("roll.available"))
    }
}
