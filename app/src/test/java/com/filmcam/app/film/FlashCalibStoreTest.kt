package com.filmcam.app.film

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 外闪标定样本标记存储契约（11-22）。 */
class FlashCalibStoreTest {

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
        assertTrue(read("app/src/main/java/com/filmcam/app/film/FlashCalibStore.kt") != null)
    }

    @Test
    fun `标记文件独立于成片EXIF 不改照片`() {
        val s = read("app/src/main/java/com/filmcam/app/film/FlashCalibStore.kt") ?: return
        assertTrue("必须用独立标记文件", s.contains("flash_calib_marks.txt"))
        assertTrue("必须能读单个标记", s.contains("fun read("))
        assertTrue("必须能写标记", s.contains("fun write("))
        assertTrue("必须能读全部供归纳", s.contains("fun readAll("))
    }

    @Test
    fun `看图页绑定标定行与三个标记按钮`() {
        val s = read("app/src/main/java/com/filmcam/app/PhotoViewerActivity.kt") ?: return
        assertTrue("必须渲染标定行", s.contains("renderCalibRow"))
        assertTrue("必须绑定好样本", s.contains("R.id.btnCalibGood"))
        assertTrue("必须绑定差样本", s.contains("R.id.btnCalibBad"))
        assertTrue("必须绑定清除", s.contains("R.id.btnCalibClear"))
        assertTrue("必须写标记存储", s.contains("FlashCalibStore.write"))
    }

    @Test
    fun `布局含标定信息与标记按钮`() {
        val s = read("app/src/main/res/layout/activity_photo_viewer.xml") ?: return
        assertTrue("标定信息行", s.contains("@+id/tvCalibInfo"))
        assertTrue("标记按钮行", s.contains("@+id/calibMarkRow"))
    }
}
