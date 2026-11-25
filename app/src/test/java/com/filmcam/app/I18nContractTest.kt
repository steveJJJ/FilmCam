package com.filmcam.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 多语言改造契约（vc113）：
 * 六档语言（跟随系统 / 简中 / 繁中港台共用 / 英 / 日 / 韩）一次点选；
 * 四套新增资源与简体资源键、占位符、不可翻译项严格对齐；
 * 25 支胶卷的描述/标签/场景六语齐，切档可重新投影。
 */
class I18nContractTest {

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

    private fun stringsOf(rel: String): Map<String, Pair<Boolean, String>> {
        val s = read(rel) ?: return emptyMap()
        val out = LinkedHashMap<String, Pair<Boolean, String>>()
        val m = java.util.regex.Pattern
            .compile("<string name=\"([^\"]+)\"([^>]*)>(.*?)</string>", java.util.regex.Pattern.DOTALL)
            .matcher(s)
        while (m.find()) {
            out[m.group(1)] = Pair(m.group(2).contains("translatable=\"false\""), m.group(3))
        }
        return out
    }

    @Test
    fun `六档语言常量与系统语言映射锁死`() {
        val s = read("java/com/filmcam/app/ui/AppLocale.kt") ?: return
        listOf("SYSTEM = 0", "ZH = 1", "ZH_TW = 2", "EN = 3", "JA = 4", "KO = 5",
            "MAX_MODE = KO").forEach { assertTrue("缺常量 $it", s.contains(it)) }
        listOf("\"zh-Hans\"", "\"zh-TW\"", "\"en\"", "\"ja\"", "\"ko\"").forEach {
            assertTrue("localesFor 缺 $it", s.contains(it))
        }
        assertTrue("跟随系统要按中英日韩与繁体脚本投影",
            s.contains("fun contentMode()") && s.contains("\"Hant\"") &&
                s.contains("\"TW\", \"HK\", \"MO\""))
    }

    @Test
    fun `设置面板语言行走一次点选铭牌 不留循环切换`() {
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val block = main.substringAfter("row(R.id.rowSetLanguage).hapticClick", "")
            .substringBefore("\n        }", "")
        assertTrue("语言行要弹选择列表", block.contains("LanguagePicker.show"))
        assertTrue("语言行要点选设档", block.contains("AppLocale.set"))
        assertFalse("语言行不得再循环切档", block.contains("AppLocale.cycle"))

        val picker = read("java/com/filmcam/app/ui/LanguagePicker.kt") ?: return
        listOf("langRowSystem", "langRowZh", "langRowZhTw", "langRowEn", "langRowJa", "langRowKo")
            .forEach { assertTrue("语言列表缺 $it", picker.contains(it)) }
        assertTrue("列表弹窗保留守卫槽与 show 前定尺寸",
            picker.contains("guard(dialog) {}") && picker.contains("win?.setLayout"))
        // 11-26 用户反馈原版太简陋、点击无震动无选中感 → 铭牌行 + 按压 + 落位动画
        assertTrue("点选要走机身按键震动",
            picker.contains("Haptics.vibrate(activity, 24, 185)"))
        assertTrue("行要有机械按压反馈", picker.contains("attachPressFeedback(0.97f)"))
        assertTrue("选中要播落位动画再切档",
            picker.contains("ValueAnimator.ofFloat(0f, 1f)") &&
                picker.contains("onAnimationEnd") &&
                picker.contains("paint(1f)") &&
                picker.contains("Motion.TICK * 2L") &&
                picker.contains("if (picked != current) onPick(picked)"))
        assertTrue("选中态要有琥珀刻记与竖条",
            picker.contains("\"SET\"") && picker.contains("ColorUtils.blendARGB") &&
                picker.contains("r.bar.setBackgroundColor"))
        val row = read("res/layout/item_language_row.xml") ?: return
        listOf("langBar", "langCode", "langName", "langSub", "langMark").forEach {
            assertTrue("铭牌行缺 $it", row.contains(it))
        }
        val dlg = read("res/layout/dialog_language.xml") ?: return
        assertTrue("弹窗要有 T3 字标与蚀刻码表头",
            dlg.contains("@drawable/ic_t3_logo") && dlg.contains("LANGUAGE · 06"))
    }

    @Test
    fun `四套新语言资源键与占位符对齐简体`() {
        val base = stringsOf("res/values/strings.xml")
        assertTrue("简体资源读取失败", base.isNotEmpty())
        listOf("zh-rTW", "zh-rHK", "ja", "ko").forEach { loc ->
            val target = stringsOf("res/values-$loc/strings.xml")
            assertEquals("$loc 键集合不一致：缺 ${base.keys - target.keys} 多 ${target.keys - base.keys}",
                base.keys, target.keys)
            base.forEach { (key, meta) ->
                val tm = target[key]!!
                assertEquals("$loc/$key translatable 标记不一致", meta.first, tm.first)
                if (meta.first) {
                    assertEquals("$loc/$key 不可翻译项被改动", meta.second, tm.second)
                }
                val ph = Regex("%[0-9]+\\$[sdf]").findAll(meta.second).map { it.value }.toList()
                val tph = Regex("%[0-9]+\\$[sdf]").findAll(tm.second).map { it.value }.toList()
                assertEquals("$loc/$key 占位符不一致", ph, tph)
            }
        }
    }

    @Test
    fun `25 支胶卷六语文案齐且按档位投影`() {
        val rolls = read("java/com/filmcam/app/filter/FilmRollRepository.kt") ?: return
        val n = Regex("FilmRoll\\(").findAll(rolls).count()
        assertEquals("胶卷数量", 25, n)
        listOf("descriptionEn", "descriptionTw", "descriptionJa", "descriptionKo",
            "sceneEn", "sceneTw", "sceneJa", "sceneKo", "tagEn", "tagTw", "tagJa", "tagKo")
            .forEach { field ->
                assertEquals("$field 未覆盖全部胶卷", n,
                    Regex("\\b$field = \"").findAll(rolls).count())
            }

        val model = read("java/com/filmcam/app/filter/FilmRoll.kt") ?: return
        listOf("AppLocale.EN", "AppLocale.ZH_TW", "AppLocale.JA", "AppLocale.KO")
            .forEach { assertTrue("localized 缺 $it 分支", model.contains(it)) }
        assertTrue("未选中语言保持简体原值", model.contains("else -> this"))
    }
}
