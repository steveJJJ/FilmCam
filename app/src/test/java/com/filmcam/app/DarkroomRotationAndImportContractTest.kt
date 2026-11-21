package com.filmcam.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 11-21 契约：暗房四页锁竖屏 + 导入照片走 Photo Picker 相册首屏。
 *
 * 真机摇手机是否转屏属于系统行为，JVM 单测跑不了；但这两处最容易被人
 * 「顺手改回 fullSensor / 换回 SAF」的地方，必须在源码级钉死：
 *  1. 相册 / 冲洗 / 编辑 / 看大图 = portrait（相册页顶部就是「暗房 DARKROOM」标题，
 *     用户心智里它是暗房的入口）；取景器 / 设置 / 配对向导 = fullSensor；
 *  2. 导入用 PickMultipleVisualMedia + AlbumsTab，不再是 OpenMultipleDocuments；
 *  3. 后续「选胶卷 → 装卷 → 冲洗」链路一字不动。
 */
class DarkroomRotationAndImportContractTest {

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

    private fun orientationOf(manifest: String, simpleName: String): String? {
        val m = Regex("""android:name="[\w.]*$simpleName"""").find(manifest) ?: return null
        val tail = manifest.substring(m.range.last, minOf(m.range.last + 240, manifest.length))
        return Regex("""android:screenOrientation="([^"]+)"""").find(tail)?.groupValues?.get(1)
    }

    @Test
    fun `暗房四页锁竖屏 取景器设置向导仍跟随传感器`() {
        val s = read("AndroidManifest.xml") ?: return
        // 用户心智里的「暗房」从相册页开始：相册页顶部标题就是「暗房 DARKROOM」，
        // 后面冲洗/编辑/看大图都是暗房的一部分，四页一律锁竖屏（11-21 修正）。
        for (act in listOf(
            "GalleryActivity", "PhotoViewerActivity", "DevelopmentActivity", "EditActivity"
        )) {
            assertTrue("$act 必须锁竖屏", orientationOf(s, act) == "portrait")
        }
        for (act in listOf("MainActivity", "AboutActivity", "FlashPairActivity")) {
            assertTrue("$act 仍应 fullSensor", orientationOf(s, act) == "fullSensor")
        }
    }

    @Test
    fun `锁竖屏不得被 Play 推断为必需硬件功能`() {
        val s = read("AndroidManifest.xml") ?: return
        assertTrue("必须显式声明 android.hardware.screen.portrait 为可选，否则 Play Console 报分发受限",
            s.contains("""<uses-feature android:name="android.hardware.screen.portrait" android:required="false" />"""))
        // 暗房四页仍必须锁竖屏（uses-feature 只影响商店过滤，不改运行时行为）
        for (act in listOf(
            "GalleryActivity", "PhotoViewerActivity", "DevelopmentActivity", "EditActivity"
        )) {
            assertTrue("$act 必须锁竖屏", orientationOf(s, act) == "portrait")
        }
    }

    @Test
    fun `运行时方向守卫必须豁免暗房四页`() {
        // 真正的坑：Manifest 写 portrait 也会被 ReversePortraitGuard 在 resume 时
        // 写成 FULL_SENSOR 覆盖掉。只锁 Manifest 而漏掉这一层，真机照样转。
        val s = read("java/com/filmcam/app/ui/ReversePortraitGuard.kt") ?: return
        assertTrue("guard 必须声明暗房四页豁免集合", s.contains("FIXED_PORTRAIT_PAGES"))
        assertTrue("apply 必须短路豁免页",
            s.contains("if (isFixedPortrait(activity.componentName.className))"))
        assertTrue("豁免页必须显式写回 PORTRAIT",
            s.contains("activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT"))
        for (act in listOf(
            "com.filmcam.app.GalleryActivity",
            "com.filmcam.app.PhotoViewerActivity",
            "com.filmcam.app.develop.DevelopmentActivity",
            "com.filmcam.app.develop.EditActivity"
        )) {
            assertTrue("豁免集合缺 $act", s.contains("\"$act\""))
        }
    }

    @Test
    fun `导入走 Photo Picker 相册首屏 且保留后续链路`() {
        val s = read("java/com/filmcam/app/GalleryActivity.kt") ?: return
        assertFalse("btnImport 不再 launch SAF 的 image/* 类型数组",
            s.contains("pickPhotos.launch(arrayOf(\"image/*\"))"))
        assertTrue("必须用多选 Photo Picker",
            s.contains("PickMultipleVisualMedia(MAX_IMPORT_FRAMES)"))
        assertTrue("必须指定相册 Tab 首屏",
            s.contains("DefaultTab.AlbumsTab"))
        assertTrue("只选图片", s.contains("PickVisualMedia.ImageOnly"))
        // 后续链路不变
        assertTrue("选完仍弹出选胶卷", s.contains("askFilmAndImport(uris)"))
        assertTrue("装卷冲洗链路在", s.contains("importAsRoll"))
    }

    @Test
    fun `activity 依赖版本必须支持 AlbumsTab`() {
        val root = sourceRoot() ?: return
        val gradle = File(root, "../../build.gradle.kts").takeIf { it.isFile }?.readText() ?: return
        val m = Regex("""androidx\.activity:activity-ktx:([\d.]+)""").find(gradle) ?: return
        val parts = m.groupValues[1].split('.').map { it.toIntOrNull() ?: 0 }
        val ok = parts[0] > 1 || (parts[0] == 1 && parts[1] >= 10)
        assertTrue("AlbumsTab 需要 androidx.activity >= 1.10.0，当前 ${m.groupValues[1]}", ok)
    }
}
