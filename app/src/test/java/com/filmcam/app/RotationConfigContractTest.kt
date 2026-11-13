package com.filmcam.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * vc76 B+D：守护「旋转不重建 Activity、相机不关、layout-land 仍会切换」的源码契约。
 *
 * 为什么是源码级：Activity 重建 / onConfigurationChanged / View 重挂需要真实窗口，
 * JVM 单测跑不了；但下面几条是这次修复最容易被动掉的骨架，必须用测试钉住：
 *  1. Manifest 必须声明 orientation 等 configChanges，且**不能**顺手把
 *     density / uiMode / locale 也吞掉（那会让已 inflate 的 dp 与新配置不一致）；
 *  2. 声明了 configChanges 之后系统不会再重选 layout-land，所以必须有
 *     swapChromeForOrientation 手动换机身 chrome；
 *  3. 换 chrome 时绝不碰 glSurfaceView（碰了就等同于相机重启，B 白做）；
 *  4. Guard 的滞回 / 去抖不能退化成旧的单一布尔判断。
 */
class RotationConfigContractTest {

    /** Gradle test 的工作目录通常是 app/；向上找带 app/src/main 的项目根。 */
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

    private fun manifest(): String? = read("AndroidManifest.xml")
    private fun main(): String? = read("java/com/filmcam/app/MainActivity.kt")
    private fun guard(): String? = read("java/com/filmcam/app/ui/ReversePortraitGuard.kt")

    @Test
    fun `MainActivity 必须声明旋转类 configChanges`() {
        val s = manifest() ?: return
        val block = s.substringAfter("android:name=\".MainActivity\"", "")
            .substringBefore("/>", "")
        assertTrue("必须声明 configChanges，否则旋转仍会重建 Activity",
            block.contains("android:configChanges"))
        for (flag in listOf("orientation", "screenSize", "smallestScreenSize", "screenLayout")) {
            assertTrue("configChanges 缺 $flag", block.contains(flag))
        }
        // 这三项故意不声明：显示大小 / 深色 / 语言仍交给框架重建
        for (flag in listOf("density", "uiMode", "locale")) {
            assertFalse("configChanges 不该吞掉 $flag", block.contains(flag))
        }
        assertTrue("screenOrientation 必须仍是 fullSensor",
            block.contains("android:screenOrientation=\"fullSensor\""))
    }

    @Test
    fun `onConfigurationChanged 只换 chrome 不碰相机`() {
        val s = main() ?: return
        assertTrue("必须覆写 onConfigurationChanged",
            s.contains("override fun onConfigurationChanged(newConfig"))
        val body = s.substringAfter("override fun onConfigurationChanged(newConfig", "")
            .substringBefore("private fun swapChromeForOrientation", "")
        assertTrue("必须先 super，AppCompat 才会更新资源 Configuration",
            body.contains("super.onConfigurationChanged(newConfig)"))
        assertTrue("必须手动换 layout-land 的机身 chrome",
            body.contains("swapChromeForOrientation()"))
        assertTrue("换完必须重新绑定机身按键", body.contains("wireChrome()") ||
            s.substringAfter("private fun swapChromeForOrientation", "").contains("wireChrome()"))
        assertTrue("换完必须重设边到边 inset 基准", body.contains("setupEdgeToEdge()"))
        assertTrue("换完必须重刷机身配色", body.contains("applyBodyTheme()"))
        assertTrue("换完必须重压 LCD 读数", body.contains("updateLcd()"))
        assertFalse("旋转路径绝不能关相机", body.contains("cameraController?.stop()"))
        assertFalse("旋转路径绝不能重开相机", body.contains("startCamera("))
        assertFalse("旋转路径绝不能重建 Activity", body.contains("recreate()"))
    }

    @Test
    fun `swapChrome 只搬三块 chrome 且保留 GL Surface`() {
        val s = main() ?: return
        val body = s.substringAfter("private fun swapChromeForOrientation()", "")
            .substringBefore("/** 机身控件监听", "")
        assertTrue("必须按当前 Configuration 重新解析 activity_main",
            body.contains("layoutInflater.inflate(R.layout.activity_main, null)"))
        for (id in listOf("R.id.topBar", "R.id.bottomBar", "R.id.brandLogo")) {
            assertTrue("chrome 缺 $id", body.contains(id))
        }
        assertTrue("必须重新 bind，否则 binding 仍指向旧 View",
            body.contains("ActivityMainBinding.bind(root)"))
        assertFalse("绝不能 setContentView（会重建 GL Surface）",
            body.contains("setContentView"))
        assertFalse("绝不能移除/替换 glSurfaceView",
            body.contains("removeView(binding.glSurfaceView") ||
                body.contains("removeView(gl"))
        assertFalse("绝不能碰相机会话", body.contains("cameraController"))
        assertTrue("新 chrome 必须插在取景框之后、遮罩之前",
            body.contains("root.indexOfChild(frame) + 1"))
    }

    @Test
    fun `Guard 必须保留滞回与去抖`() {
        val s = guard() ?: return
        for (token in listOf("ENTER_MIN", "ENTER_MAX", "EXIT_MIN", "EXIT_MAX",
            "DEBOUNCE_MS", "fun nextReverse", "cancelPending()", "postDelayed")) {
            assertTrue("Guard 缺 $token", s.contains(token))
        }
        val body = s.substringAfter("fun install(app: Application)", "")
            .substringBefore("private fun cancelPending", "")
        assertFalse("实际决策不能再走无滞回的 isReversePortrait",
            body.contains("isReversePortrait("))
        assertTrue("实际决策必须走 nextReverse", body.contains("nextReverse(reverse"))
    }
}
