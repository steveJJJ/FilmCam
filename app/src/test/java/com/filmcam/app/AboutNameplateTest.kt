package com.filmcam.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 关于页铭牌品牌名契约（v44 起显示名是 Relic Cam，包名/成片目录/日志 TAG 仍是 FilmCam）。
 *
 * 钉住的是「用户能看见的那个名字」：铭牌标题与图标描述必须是 RELIC CAM，
 * 且这一页不能再残留 FILMCAM 字面量。日志 TAG `FILMCAM`、反馈邮件里让用户
 * 跑 `adb logcat | findstr FILMCAM` 的说明不在本文件里，按约定保留。
 */
class AboutNameplateTest {

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

    @Test
    fun `关于页铭牌显示 RELIC CAM`() {
        assertNotNull("源码扫描必须真的跑到，不能静默跳过", sourceRoot())
        val xml = File(sourceRoot()!!, "res/layout/activity_about.xml").readText()
        assertTrue("铭牌标题必须是 RELIC CAM",
            xml.contains("android:text=\"RELIC CAM\""))
        assertTrue("启动图标描述也要跟着改（读屏用户听到的是这条）",
            xml.contains("android:contentDescription=\"RELIC CAM\""))
        assertFalse("关于页布局里不能残留 FILMCAM 字面量", xml.contains("FILMCAM"))
    }
}
