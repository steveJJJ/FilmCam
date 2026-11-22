package com.filmcam.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 编排式同步触发帧守卫契约（11-22 真机根因 + 正解）。
 *
 * 失败史：删掉 flash_orchestrated.txt 启用编排后仍全黑，日志出现
 * 「取证：指令→曝光开始=4092/9612/7696ms」荒谬值 —— onCaptureStarted 对
 * 预览每帧都回调，编排触发在预览帧就 fireExternalFlashOnce 了，
 * flashTriggerPosted 防重导致真正拍照帧再也发不出灯（几秒前就闪完）。
 *
 * 两次伪解法（时间闸门 1200ms）已证伪：提交拍照后预览帧仍持续回调，挡不住。
 *
 * 正解：给 TEMPLATE_STILL_CAPTURE builder setTag(STILL_CAPTURE_TAG)，
 * onCaptureStarted 里只认 request.tag == STILL_CAPTURE_TAG 的正式拍照帧发灯。
 */
class OrchestratedFrameGuardTest {

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

    private fun camera(): String? = sourceRoot()?.let {
        File(it, "java/com/filmcam/app/camera/Camera2Controller.kt")
            .takeIf { f -> f.isFile }?.readText()
    }

    @Test
    fun `sourceRoot 真的解析到源码 防止空跑假绿`() {
        val s = camera()
        assertTrue("必须读到 Camera2Controller.kt", s != null)
        assertTrue("源码必须有 tag 常量", s!!.contains("STILL_CAPTURE_TAG"))
    }

    @Test
    fun `拍照帧tag常量必须定义`() {
        val s = camera() ?: return
        assertTrue("必须定义 STILL_CAPTURE_TAG 常量",
            s.contains("private const val STILL_CAPTURE_TAG"))
    }

    @Test
    fun `正式拍照builder必须打tag`() {
        val s = camera() ?: return
        assertTrue("TEMPLATE_STILL_CAPTURE builder 必须 setTag(STILL_CAPTURE_TAG)",
            s.contains("setTag(STILL_CAPTURE_TAG)"))
    }

    @Test
    fun `onCaptureStarted只认带tag的拍照帧`() {
        val s = camera() ?: return
        assertTrue("必须用 request.tag 判定帧身份",
            s.contains("request.tag == STILL_CAPTURE_TAG"))
        assertTrue("必须在判定为真时发灯",
            s.contains("fireExternalFlashOnce(\"编排·拍照帧曝光开始\")"))
        assertTrue("非拍照帧必须跳过（预览帧）", s.contains("跳过非拍照帧"))
    }

    @Test
    fun `旧时间闸门伪解法必须已清除`() {
        val s = camera() ?: return
        assertFalse("时间闸门字段必须删除（两次证伪，不许回退）",
            s.contains("flashStillArmedAtMs"))
        assertFalse("时间闸门常量必须删除",
            s.contains("ORCHESTRATED_ARM_WINDOW_MS"))
    }
}
