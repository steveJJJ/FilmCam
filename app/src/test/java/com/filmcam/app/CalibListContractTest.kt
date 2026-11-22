package com.filmcam.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 外闪标定样本列表页契约（11-22）。 */
class CalibListContractTest {

    private fun read(rel: String): String? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val d = dir ?: return null
            val root = File(d, rel)
            if (root.exists()) return root.readText()
            dir = d.parentFile
        }
        return null
    }

    @Test
    fun `sourceRoot 防假绿`() {
        assertTrue(read("app/src/main/java/com/filmcam/app/CalibListActivity.kt") != null)
    }

    @Test
    fun `暗房有标定列表入口`() {
        val g = read("app/src/main/java/com/filmcam/app/GalleryActivity.kt") ?: return
        assertTrue("必须绑定入口按钮", g.contains("R.id.btnCalibList"))
        assertTrue("必须跳转列表页", g.contains("CalibListActivity::class.java"))
        val l = read("app/src/main/res/layout/activity_gallery.xml") ?: return
        assertTrue("布局必须有入口", l.contains("@+id/btnCalibList"))
    }

    @Test
    fun `列表页扫描EXIF标定字段并按calibId关联标记`() {
        val s = read("app/src/main/java/com/filmcam/app/CalibListActivity.kt") ?: return
        assertTrue("必须读EXIF", s.contains("ExifReader.read"))
        assertTrue("必须筛calibId", s.contains("shot.calibId > 0L"))
        assertTrue("必须读标记", s.contains("FlashCalibStore.read"))
        assertTrue("点行必须进看图页", s.contains("PhotoViewerActivity::class.java"))
    }

    @Test
    fun `Manifest必须声明列表页`() {
        val m = read("app/src/main/AndroidManifest.xml") ?: return
        assertTrue(m.contains(".CalibListActivity"))
    }

    /**
     * 11-22 夜根因回归：「拍了但样本库里没有」——旧实现只扫 IS_PENDING=0 的
     * 已冲洗成片 EXIF，刚拍的帧是 IS_PENDING=1 的未冲洗底片，必然查不到。
     * 主数据源必须换成卷登记里逐帧的 shotJson，拍完即刻可见、无需冲洗。
     */
    @Test
    fun `拍完无需冲洗_列表以卷登记帧为主数据源`() {
        val s = read("app/src/main/java/com/filmcam/app/CalibListActivity.kt") ?: return
        assertTrue("必须扫卷登记全帧", s.contains("UndevelopedFilmStore.allFrames()"))
        assertTrue("必须解析逐帧 shotJson", s.contains("ShotMeta.fromJson(f.shotJson)"))
        assertTrue("样本必须按拍摄时间倒序", s.contains("sortedByDescending"))
        assertTrue("成片 EXIF 只能作为补充源并按 calibId 去重", s.contains("developed.containsKey") && s.contains("seen"))
        assertTrue("标记必须能在列表里直接写", s.contains("FlashCalibStore.write"))
        assertTrue("进看图页必须带逐帧快照", s.contains("PhotoViewerActivity.EXTRA_CALIB_SHOTS"))
        val store = read("app/src/main/java/com/filmcam/app/film/UndevelopedFilmStore.kt") ?: return
        assertTrue("登记层必须暴露全帧扫描", store.contains("fun allFrames()"))
        assertTrue("必须直读磁盘而非进程缓存", store.contains("RollRegistry.deserialize(raw)"))
        val zh = read("app/src/main/res/values/strings.xml") ?: return
        assertTrue("空态文案不得再要求冲洗", !zh.contains("拍照并冲洗后"))
        assertTrue("提示必须写明无需冲洗", zh.contains("无需冲洗"))
        assertTrue("必须有非 EXT 档诊断", s.contains("R.string.calib_list_no_ext") && zh.contains("calib_list_no_ext"))
    }

    @Test
    fun `列表行自带底片缩略图与好差清按钮`() {
        val l = read("app/src/main/res/layout/item_calib_row.xml") ?: return
        assertTrue(l.contains("@+id/ivCalibThumb"))
        assertTrue(l.contains("@+id/btnCalibRowGood"))
        assertTrue(l.contains("@+id/btnCalibRowBad"))
        assertTrue(l.contains("@+id/btnCalibRowClear"))
        val s = read("app/src/main/java/com/filmcam/app/CalibListActivity.kt") ?: return
        assertTrue(s.contains("R.id.btnCalibRowGood"))
        assertTrue(s.contains("R.id.btnCalibRowBad"))
        assertTrue(s.contains("R.id.btnCalibRowClear"))
        assertTrue("必须解码底片缩略图", s.contains("decodeThumb"))
    }

    @Test
    fun `未冲洗底片在看图页也能显示并标记标定行`() {
        val p = read("app/src/main/java/com/filmcam/app/PhotoViewerActivity.kt") ?: return
        assertTrue(p.contains("const val EXTRA_CALIB_SHOTS"))
        assertTrue(p.contains("fun calibShotAt("))
        assertTrue(p.contains("calibShotAt(position)"))
        assertTrue("成片 EXIF 优先、底片回落", p.contains("exifShot.calibId > 0L"))
    }

    /** 11-23：工程控制台加「标定记录范围」开关——默认仅 EXT，切 all 后任何档都发样本 id。 */
    @Test
    fun `工程模式可切标定记录范围_非EXT帧不填闪光专属量`() {
        val a = read("app/src/main/java/com/filmcam/app/AboutActivity.kt") ?: return
        assertTrue("控制台必须有记录范围行", a.contains("R.id.rowEngCalib") && a.contains("calibScope"))
        assertTrue("必须两态循环切换", a.contains("calibScope() == \"all\""))
        val l = read("app/src/main/res/layout/dialog_hidden_settings.xml") ?: return
        assertTrue(l.contains("@+id/rowEngCalib"))
        val m = read("app/src/main/java/com/filmcam/app/MainActivity.kt") ?: return
        assertTrue("calibId 必须按范围开关判定",
            m.contains("getString(\"calibScope\", \"ext\") == \"all\""))
        assertTrue("非 EXT 帧不得填功率/距离/环境（陈旧值）",
            m.contains("calibPwr = if (isExtFrame) snap.powerArg else -1"))
        val zh = read("app/src/main/res/values/strings.xml") ?: return
        assertTrue(zh.contains("eng_calib_scope") && zh.contains("calib_scope_all"))
    }
}
