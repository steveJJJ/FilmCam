package com.filmcam.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 三项 UI 改进的源码级契约：钉住「改到了该改的地方，且没碰隐私/命名铁律」。
 * 只做单行包含断言，避免 CRLF / 跨行锚造成的静默失配。
 */
class UiImprovementsContractTest {

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

    private fun assertBothLocales(keys: List<String>) {
        listOf("values/strings.xml", "values-en/strings.xml").forEach { f ->
            val s = read("res/$f") ?: return@forEach
            keys.forEach { k -> assertTrue("$f 缺 $k", s.contains("<string name=\"$k\">")) }
        }
    }

    @Test
    fun `空态铭牌三项要素齐备且中文错字已修`() {
        val xml = read("res/layout/activity_gallery.xml") ?: return
        assertTrue(xml.contains("android:id=\"@+id/tvEmpty\""))
        assertTrue(xml.contains("android:id=\"@+id/emptyTitle\""))
        assertTrue(xml.contains("android:id=\"@+id/emptyHint\""))
        assertTrue(xml.contains("@drawable/bg_empty_ring"))
        assertTrue(xml.contains("@string/gallery_empty_title"))
        assertTrue(xml.contains("@string/gallery_empty_hint"))
        assertTrue(xml.contains("@string/gallery_undeveloped_hint"))
        val kt = read("java/com/filmcam/app/GalleryActivity.kt") ?: return
        assertTrue("成片/prints 必须高亮成暖黄", kt.contains("ForegroundColorSpan(0xFFFFC107"))
        assertTrue(kt.contains("SpannableString(text)"))
        assertBothLocales(listOf("gallery_empty_title", "gallery_empty_hint", "gallery_undeveloped_hint"))
        val zh = read("res/values/strings.xml") ?: return
        assertTrue(zh.contains("空空的不是坏了"))
        assertFalse(zh.contains("空空的不量坏了"))
    }

    @Test
    fun `分享按钮走系统分享且只暴露 IS_PENDING 为 0 的成片`() {
        val xml = read("res/layout/activity_photo_viewer.xml") ?: return
        assertTrue(xml.contains("android:id=\"@+id/btnShare\""))
        assertTrue(xml.contains("android:layout_gravity=\"top|end\""))
        assertTrue(xml.contains("android:contentDescription=\"@string/viewer_share\""))
        assertTrue(xml.contains("@drawable/bg_share_btn"))
        assertTrue(xml.contains("@drawable/ic_share"))
        assertTrue(xml.contains("app:tint=\"@null\""))
        assertTrue("分享按钮必须有 elevation 压在 ViewPager2 之上", xml.contains("android:elevation=\"2dp\""))
        val icon = read("res/drawable/ic_share.xml") ?: return
        val strokes = Regex("android:strokeColor=\"#FFD7C4\"").findAll(icon).count()
        assertTrue("每条 path 都要自带 strokeColor，实际 $strokes 条", strokes >= 3)
        val vectorHeader = icon.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .substringBefore("<path")
        assertFalse("strokeColor 写在 <vector> 根上会被忽略，图标不渲染",
            vectorHeader.contains("strokeColor"))
        val bg = read("res/drawable/bg_share_btn.xml") ?: return
        assertTrue("填充在黑底上要有对比度", bg.contains("#66C93A30"))
        assertTrue(bg.contains("1.5dp"))
        val kt = read("java/com/filmcam/app/PhotoViewerActivity.kt") ?: return
        assertTrue(kt.contains("Intent.ACTION_SEND"))
        assertTrue(kt.contains("Intent.EXTRA_STREAM"))
        assertTrue(kt.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(kt.contains("Intent.createChooser"))
        assertTrue("必须复核成片状态", kt.contains("IS_PENDING}=0"))
        assertTrue("必须限定 DCIM/FilmCam 路径", kt.contains("DCIM/FilmCam/"))
        assertTrue("分享按钮要有确认档触感", kt.contains("btnShare.hapticClick(ms = 30, amp = 210)"))
        assertTrue("必须补 systemBar top inset，否则按钮缩进状态栏",
            kt.contains("getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()).top"))
        assertTrue("必须把 inset 叠加到原 margin 上", kt.contains("baseTopMargin + topInset"))
        assertTrue(kt.contains("ViewCompat.requestApplyInsets(btnShare)"))
        assertBothLocales(listOf("viewer_share"))
    }

    @Test
    fun `权限拒绝铭牌在两处回调都接线且宽度契约不退化`() {
        val helper = read("java/com/filmcam/app/ui/PermissionNameplate.kt") ?: return
        assertTrue(helper.contains("enum class Kind"))
        listOf("CAMERA", "BLUETOOTH", "STORAGE", "NOTIFICATION").forEach {
            assertTrue("缺权限组 $it", helper.contains(it))
        }
        assertTrue(helper.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS"))
        assertTrue(helper.contains("widthPixels * 0.78f"))
        assertTrue(helper.contains("400 * dm.density"))
        assertTrue("必须先 decorView 再 setLayout", helper.contains("decorView") && helper.contains("setLayout(w,"))
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        assertTrue(main.contains("PermissionNameplate.show(this, kind)"))
        assertTrue("拒绝后不得反复拉系统权限框", main.contains("permissionDeniedHandled"))
        assertFalse("不得再靠一句 Toast 打发", main.contains("main_toast_permission_required"))
        val dev = read("java/com/filmcam/app/develop/DevelopmentActivity.kt") ?: return
        assertTrue(dev.contains("onRequestPermissionsResult"))
        assertTrue(dev.contains("REQ_POST_NOTIF"))
        assertTrue(dev.contains("PermissionNameplate.Kind.NOTIFICATION"))
        assertBothLocales(
            listOf(
                "perm_dlg_camera_title", "perm_dlg_camera_body",
                "perm_dlg_bt_title", "perm_dlg_bt_body",
                "perm_dlg_storage_title", "perm_dlg_storage_body",
                "perm_dlg_notif_title", "perm_dlg_notif_body",
                "perm_dlg_go_settings", "perm_dlg_dismiss",
            )
        )
    }
}
