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
    fun `分享入口融入信息面板随面板显隐且只暴露 IS_PENDING 为 0 的成片`() {
        val xml = read("res/layout/activity_photo_viewer.xml") ?: return
        // 分享是面板内的一个淡描边文字胶囊，与「拍摄同款」同排
        assertTrue(xml.contains("android:id=\"@+id/btnShare\""))
        assertTrue(xml.contains("android:text=\"@string/viewer_share\""))
        assertTrue(xml.contains("@drawable/bg_chip_share"))
        // 关键契约：btnShare 必须定义在 panelInfo 之后（即成为面板的子 view），
        // 面板 gone 时它一起隐藏 —— 看照片时没有常驻红色标志打扰，
        // 点照片展开 EXIF 面板时分享一起出现（vc102 用户拍板）。
        val panelAt = xml.indexOf("android:id=\"@+id/panelInfo\"")
        val shareAt = xml.indexOf("android:id=\"@+id/btnShare\"")
        assertTrue("btnShare 必须落在 panelInfo 内", panelAt >= 0 && shareAt > panelAt)
        assertTrue("信息面板默认收起", xml.contains("android:visibility=\"gone\""))
        // 不再有右上角常驻圆钮：无 top|end gravity / elevation / 暗红圆钮背景 / 图标
        assertFalse("不得再有常驻右上角按钮", xml.contains("android:layout_gravity=\"top|end\""))
        assertFalse("不得再引用暗红圆钮背景", xml.contains("@drawable/bg_share_btn"))
        assertFalse("不得再引用分享图标", xml.contains("@drawable/ic_share"))
        val shareBg = read("res/drawable/bg_chip_share.xml") ?: return
        assertTrue("分享胶囊要用淡描边", shareBg.contains("#66FFD7C4"))
        assertFalse("分享胶囊不得用暗红填充", shareBg.contains("C93A30"))
        val kt = read("java/com/filmcam/app/PhotoViewerActivity.kt") ?: return
        assertTrue(kt.contains("Intent.ACTION_SEND"))
        assertTrue(kt.contains("Intent.EXTRA_STREAM"))
        assertTrue(kt.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(kt.contains("Intent.createChooser"))
        assertTrue("必须复核成片状态", kt.contains("IS_PENDING}=0"))
        assertTrue("必须限定 DCIM/FilmCam 路径", kt.contains("DCIM/FilmCam/"))
        assertTrue("分享按钮要有确认档触感", kt.contains("btnShare.hapticClick(ms = 30, amp = 210)"))
        assertFalse("按钮移入面板后不再需要状态栏 inset 补偿", kt.contains("requestApplyInsets(btnShare)"))
        assertFalse("不得单独控制分享按钮显隐（应随面板一起）", kt.contains("btnShare.visibility"))
        assertBothLocales(listOf("viewer_share"))
    }

    @Test
    fun `EXIF 面板只显示一个 ISO 且外闪诊断行不丢`() {
        val kt = read("java/com/filmcam/app/PhotoViewerActivity.kt") ?: return
        // 唯一 ISO 来自 tvShooting 的胶卷模拟感光度
        assertTrue("拍摄参数行仍写胶卷 ISO", kt.contains("shootParts += \"ISO "))
        // 面板不得再写第二处「实拍 ISO」（vc103 用户拍板：两个 ISO 只留一个）
        assertFalse("不得再写 pf_capture_iso", kt.contains("R.string.pf_capture_iso"))
        // 快门 / 外闪灯名 / 诊断两行全部保留，外闪复盘能力不丢
        assertTrue("实拍快门保留", kt.contains("R.string.pf_capture_shutter"))
        assertTrue("外闪灯名保留", kt.contains("R.string.pf_capture_ext_flash"))
        assertTrue("外闪诊断行保留", kt.contains("R.string.pf_capture_channel"))
        val layout = read("res/layout/activity_photo_viewer.xml") ?: return
        assertTrue("tvCapture 行保留", layout.contains("android:id=\"@+id/tvCapture\""))
        assertTrue("tvCaptureDetail 行保留", layout.contains("android:id=\"@+id/tvCaptureDetail\""))
        // EXIF 写入侧的传感器真值不受影响
        val writer = read("java/com/filmcam/app/develop/ContaxExif.kt")
        if (writer != null) {
            assertTrue("sensorIso 仍落盘", writer.contains("TAG_ISO_SPEED"))
        }
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
