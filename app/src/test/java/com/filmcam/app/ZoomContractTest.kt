package com.filmcam.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 原图级放大（暗房成片查看器）源码级契约。
 *
 * JVM 单测摸不到真实手势/解码，这里钉住最容易被悄悄改回去的接线口径：
 *  - ZoomableImageView 真的用 Matrix + 双 detector + RegionDecoder 实现；
 *  - 倍率上限/双击目标没被改小；
 *  - 1x 单击切面板、横向滑动翻页、放大后拖动平移三套手势分工不被破坏。
 */
class ZoomContractTest {

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

    @Test
    fun `缩放 View 存在且手势与矩阵实现齐备`() {
        assertNotNull("源码根路径要能解析到 app/src/main", sourceRoot())
        val z = read("java/com/filmcam/app/ui/ZoomableImageView.kt")
        assertNotNull("必须有 ZoomableImageView.kt", z)
        z!!
        assertTrue("必须继承 AppCompatImageView", z.contains("AppCompatImageView"))
        assertTrue("必须接 ScaleGestureDetector 做捏合", z.contains("ScaleGestureDetector"))
        assertTrue("必须接 GestureDetector 做双击/单击", z.contains("GestureDetector"))
        assertTrue("必须有 onDoubleTap", z.contains("onDoubleTap"))
        assertTrue("必须用 imageMatrix / setImageMatrix 承载变换",
            z.contains("setImageMatrix") && z.contains("Matrix"))
        assertTrue("缩放上限必须是 4f", z.contains("MAX_SCALE = 4f"))
        assertTrue("双击目标必须是 2f", z.contains("DOUBLE_TAP_SCALE = 2f"))
        assertTrue("必须暴露 currentScale", z.contains("fun currentScale()"))
        assertTrue("必须暴露 resetZoom", z.contains("fun resetZoom()"))
        assertTrue("必须暴露 onTap / onScaleChanged / onSwipePage",
            z.contains("var onTap") && z.contains("var onScaleChanged") && z.contains("var onSwipePage"))
        assertTrue("必须有 setImageUri(uri, lifecycleOwner)", z.contains("fun setImageUri"))
    }

    @Test
    fun `DOWN 就断父拦截且 1x 横向滑动自管翻页`() {
        val z = read("java/com/filmcam/app/ui/ZoomableImageView.kt") ?: return
        assertTrue("ACTION_DOWN 必须无条件 requestDisallowInterceptTouchEvent(true)",
            z.contains("MotionEvent.ACTION_DOWN -> {\n                parent?.requestDisallowInterceptTouchEvent(true)"))
        assertTrue("MOVE 也要续拦截禁止，防止父容器中途抢回",
            z.contains("MotionEvent.ACTION_MOVE -> {\n                // 每个 MOVE 都续一次"))
        assertTrue("UP 必须按 dx/dy 判横向翻页并回调 onSwipePage",
            z.contains("onSwipePage?.invoke(direction)") &&
                z.contains("abs(dx) >= swipeMinDistance") && z.contains("abs(dx) > abs(dy) * SWIPE_SLOPE"))
        assertTrue("捏合手势不得误触发翻页", z.contains("!scaleGestureSeen"))
        assertTrue("翻页一次手势只触发一次", z.contains("swipeConsumed = true"))

        val a = read("java/com/filmcam/app/PhotoViewerActivity.kt") ?: return
        assertTrue("Activity 必须提供 onSwipePage", a.contains("fun onSwipePage"))
        assertTrue("onSwipePage 必须驱动 setCurrentItem", a.contains("viewPager.setCurrentItem(next, true)"))
        assertTrue("Fragment 必须把 onSwipePage 接到 Activity",
            a.contains("onSwipePage = { dir -> (activity as? PhotoViewerActivity)?.onSwipePage(dir) }"))
    }

    @Test
    fun `原图级清晰度靠 RegionDecoder 分块解码且有内存与代次保护`() {
        val z = read("java/com/filmcam/app/ui/ZoomableImageView.kt") ?: return
        assertTrue("必须按可视矩形解原图：BitmapRegionDecoder.decodeRegion",
            z.contains("BitmapRegionDecoder.decodeRegion"))
        assertTrue("必须开 RegionDecoder", z.contains("BitmapRegionDecoder.newInstance"))
        assertTrue("必须读 EXIF 方向", z.contains("ExifInterface.TAG_ORIENTATION"))
        assertTrue("必须由当前矩阵反算可视矩形", z.contains("visibleRawRect") && z.contains("finalMatrix.invert"))
        assertTrue("解码必须走 IO 线程", z.contains("Dispatchers.IO"))
        assertTrue("必须有代次作废防旧结果盖回", z.contains("decodeGeneration"))
        assertTrue("必须限制单次解码位图长边（内存控制）", z.contains("regionCap") && z.contains("coerceIn(960, 2880)"))
        assertTrue("必须回收旧 bitmap", z.contains("recycle()"))
        assertTrue("回到 1x 必须恢复预览", z.contains("showPreview"))
    }

    @Test
    fun `缩放回调恒关 ViewPager2 触摸且 1x 单击仍切面板`() {
        val a = read("java/com/filmcam/app/PhotoViewerActivity.kt") ?: return
        assertTrue("Activity 必须有 onZoomChanged", a.contains("fun onZoomChanged"))
        assertTrue("1x 与放大态都不能把触摸交还 ViewPager2（DOWN 即 CANCEL 的真机 bug）",
            a.contains("viewPager.isUserInputEnabled = false"))
        assertTrue("容差 1.01f 要在场", a.contains("1.01f"))
        assertTrue("onPhotoTapped 契约不能动", a.contains("fun onPhotoTapped()"))

        val z = read("java/com/filmcam/app/ui/ZoomableImageView.kt") ?: return
        assertTrue("放大时单击不回调 onTap（不动 EXIF 面板）",
            z.contains("if (scale <= NEAR_ONE) onTap?.invoke()"))

        // PhotoFragment 用 ZoomableImageView，而不是普通 ImageView
        assertTrue("PhotoFragment 必须 new ZoomableImageView",
            a.contains("ZoomableImageView(requireContext())"))
        assertTrue("1x 单击仍接回 Activity.onPhotoTapped",
            a.contains("onTap = { (activity as? PhotoViewerActivity)?.onPhotoTapped() }"))
        assertTrue("缩放必须回传 Activity",
            a.contains("onScaleChanged = { s -> (activity as? PhotoViewerActivity)?.onZoomChanged(s) }"))
        assertFalse("不得再 import 普通 android.widget.ImageView",
            a.contains("import android.widget.ImageView"))
        assertFalse("不得再 new 普通 ImageView",
            Regex("(?<![A-Za-z])ImageView\\(").containsMatchIn(a))
        assertTrue("销毁视图必须释放解码器并交还翻页权", a.contains("zoomView?.release()"))
    }

    @Test
    fun `看图页从缩略图位置放大入场且切页有即时震感`() {
        val g = read("java/com/filmcam/app/GalleryActivity.kt") ?: return
        assertTrue("网格点击必须从被点缩略图位置放大入场（替代系统右滑）",
            g.contains("ActivityOptionsCompat.makeScaleUpAnimation("))
        assertTrue("放大起点必须取被点的 iv 矩形",
            g.contains("iv, 0, 0, iv.width, iv.height"))
        assertTrue("必须以 opts bundle 启动看图页（否则转场不生效）",
            g.contains("startActivity(intent, opts.toBundle())"))

        val a = read("java/com/filmcam/app/PhotoViewerActivity.kt") ?: return
        assertTrue("手指判向瞬间就要给确认震（不能只等 onPageSelected）",
            a.contains("Haptics.vibrate(this@PhotoViewerActivity, 18, 150)"))
        assertTrue("onPageSelected 必须保留轻 tick 收尾（契约锁点）",
            a.contains("Haptics.vibrate(this@PhotoViewerActivity, 8, 90)"))
        assertTrue("翻到头要有到头轻震",
            a.contains("Haptics.vibrate(this@PhotoViewerActivity, 10, 90)"))
        assertFalse("不得再用原来全工程最弱的 12/110 切页震",
            a.contains("Haptics.vibrate(this@PhotoViewerActivity, 12, 110)"))
    }
}
