package com.filmcam.app

import com.filmcam.app.develop.DeviceGeometryReport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v74 P0 + P2：守护「小米 13U INT 闪光灯拍完常亮」的修复契约。
 *
 * 根因（见 tmp/sample_1109/mi13u_flash_stuck/flash_stuck_root_cause.md）：
 *  预览 repeating 常驻 ON_ALWAYS_FLASH + 预闪 PRECACTURE_START 发完从不 CANCEL，
 *  setFlashEnabled(false) 只把 AE 拨回 ON 且 `if (!changed) return`，灯关不掉；
 *  进暗房 close 会话断电才灭。P0 只修收尾，P1（预览不挂 ALWAYS_FLASH）不在本轮。
 *
 * 这些是源码级契约：Camera2Controller 依赖真实 HAL，JVM 单测跑不了会话，
 * 所以守护「关键语句必须存在 / 必须被删 / 顺序不能反」，防回退。
 */
class FlashPrecaptureContractTest {
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

    private fun camera(): String? = read("java/com/filmcam/app/camera/Camera2Controller.kt")

    @Test
    fun `预闪 START 必须有配对的 CANCEL 且 CANCEL 请求带 FLASH_MODE_OFF`() {
        val s = camera() ?: return
        assertTrue("必须仍发 PRECAPTURE_START", s.contains("CONTROL_AE_PRECAPTURE_TRIGGER_START"))
        assertTrue("必须补 PRECAPTURE_CANCEL", s.contains("CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL"))
        assertTrue("必须有 resetFlashPrecapture", s.contains("fun resetFlashPrecapture"))

        val body = s.substringAfter("private fun resetFlashPrecapture", "")
            .substringBefore("/** v74 P2", "")
        assertTrue("CANCEL 请求必须带 FLASH_MODE_OFF", body.contains("FLASH_MODE_OFF"))
        assertTrue("CANCEL 请求必须带 PRECAPTURE_CANCEL", body.contains("CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL"))
        assertTrue("预览请求要把 trigger 放回 IDLE", body.contains("CONTROL_AE_PRECAPTURE_TRIGGER_IDLE"))
        assertFalse("复位逻辑绝不能建 STILL_CAPTURE 请求", body.contains("TEMPLATE_STILL_CAPTURE"))
    }

    @Test
    fun `切档关灯必须强制复位 早退只能用于从未起过 precapture 的关灯`() {
        val s = camera() ?: return
        // 只看函数体，别被注释里引用的旧代码误伤
        val setBody = s.substringAfter("fun setFlashEnabled(enabled: Boolean) {", "")
            .substringBefore("private fun applyManualSettings", "")
        assertTrue(
            "关闪光必须走显式复位",
            setBody.contains("resetFlashPrecapture(\"setFlashEnabled(false)\", force = true)")
        )
        assertTrue("切档要留 FlashDiag 日志", s.contains("setFlashEnabled(\$enabled) changed="))
        // v81：早退只能按 precapture 状态判断，不能退回 vc73 的裸 `if (!changed) return`
        assertTrue("早退必须守 precapturePending", setBody.contains("!precapturePending"))
        assertTrue("早退必须守本次会话起过 precapture",
            setBody.contains("flashPrecaptureStarts == 0"))
        val guard = setBody.substringAfter(
            "if (!precapturePending && flashPrecaptureStarts == 0) {", "")
            .substringBefore("}", "")
        assertTrue("早退前仍要刷新诊断", guard.contains("reportFlashState()"))
        assertFalse(
            "早退不得再附加 changed 条件：BUILT_IN→EXTERNAL 虽 changed=true，但没起过 precapture 也不该发帧",
            setBody.contains("if (!changed && !precapturePending")
        )
    }

    @Test
    fun `拍照完成或失败都要复位，stop 与 onPause 也必须兜底`() {
        val s = camera() ?: return
        assertTrue("拍照完成要配对 CANCEL", s.contains("resetFlashPrecapture(\"stillCaptureCompleted\")"))
        assertTrue("拍照失败也要复位", s.contains("resetFlashPrecapture(\"stillCaptureFailed\")"))
        assertTrue("stop 关会话前要收灯", s.contains("resetFlashPrecapture(\"stop\", force = true)"))

        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val off = main.indexOf("cameraController?.setFlashEnabled(false)")
        val stop = main.indexOf("cameraController?.stop()", off.coerceAtLeast(0))
        assertTrue("onPause 必须先显式关灯再 stop", off > 0 && stop > off)
    }

    @Test
    fun `v81 CANCEL 帧复用预览参数 不得新建缺参数的请求`() {
        val s = camera() ?: return
        val body = s.substringAfter("private fun resetFlashPrecapture", "")
            .substringBefore("/** v74 P2", "")
        assertFalse("复位不得再建 TEMPLATE_PREVIEW 请求（会往预览塞缺 EV/AWB/AF 的帧，切档闪一下）",
            body.contains("createCaptureRequest"))
        assertTrue("CANCEL 必须由常驻预览 builder 发出",
            body.contains("session.capture(builder.build(), null, handler)"))
        assertTrue("capture 必须用 try/finally 把 trigger 放回 IDLE",
            body.contains("} finally {") && body.contains("CONTROL_AE_PRECAPTURE_TRIGGER_IDLE"))
        assertTrue("CANCEL 必须排在 IDLE 之前",
            body.indexOf("CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL")
                < body.indexOf("CONTROL_AE_PRECAPTURE_TRIGGER_IDLE"))
    }

    @Test
    fun `v81 三个曝光 setter 同值不重发预览请求`() {
        val s = camera() ?: return
        listOf(
            "if (exposureComp == comp) return",
            "if (manualIso == iso) return",
            "if (manualExposure == exposureNs) return",
        ).forEach { guard ->
            assertTrue("缺同值早退：$guard", s.contains(guard))
        }
    }

    @Test
    fun `v75 P1 只改预览路径 拍照帧成像链路一字不动`() {
        val s = camera() ?: return
        // ALWAYS_FLASH 只允许出现在 STILL_CAPTURE 那一帧（且只出现一次）
        val still = Regex("applyStillFlashSettings\\(this\\)").findAll(s).count()
        assertTrue("拍照帧必须且只能有一处闪光参数下发，实际 $still 处", still == 1)
        val stillBlock = s.substringAfter("CameraDevice.TEMPLATE_STILL_CAPTURE", "")
            .substringBefore("submitStillCapture(", "")
        assertTrue("拍照帧必须保留 applyStillFlashSettings",
            stillBlock.contains("applyStillFlashSettings(this)"))
        assertTrue("拍照帧不能再走 preview 版",
            !stillBlock.contains("applyPreviewFlashSettings"))

        val stillFn = s.substringAfter("private fun applyStillFlashSettings", "")
            .substringBefore("\n    }", "")
        assertTrue("直闪效果来自拍照帧的 ALWAYS_FLASH（v81 SINGLE 试验已回退：用户要预闪手感）",
            stillFn.contains("CONTROL_AE_MODE_ON_ALWAYS_FLASH"))
        assertFalse("v81 回退：拍照帧不得再用 FLASH_MODE_SINGLE（真机定量：出图口径与 ALWAYS_FLASH 相同、无预闪、无收益）",
            stillFn.contains("Metadata.FLASH_MODE_SINGLE"))
        assertTrue("直闪成片调优仍在", s.contains("applyFlashCaptureTuning(this, slow)"))
        assertTrue("外置闪光同步仍在", s.contains("applyExternalFlashSync(this)"))
        assertTrue("慢门仍在闪光之后覆盖",
            s.contains("if (slow) applySlowShutter(this, slowExposureNs, slowIso)"))
    }

    @Test
    fun `vc80 预览与复位不得挂 ALWAYS_FLASH，one-shot precapture 必须挂`() {
        val s = camera() ?: return
        val banned = "CONTROL_AE_MODE_ON_ALWAYS_FLASH"

        // 1) 常驻预览 repeating builder：P1 成果，绝不能回退成 ALWAYS_FLASH
        val preview = s.substringAfter(
            "previewRequestBuilder = camera.createCaptureRequest", "")
            .substringBefore("camera.createCaptureSession", "")
        assertTrue("预览 builder 必须走 preview 版",
            preview.contains("applyPreviewFlashSettings(this)"))
        assertFalse("预览 builder 不得挂 ALWAYS_FLASH", preview.contains(banned))

        // 2) one-shot precapture：vc80 修复点，必须挂 ALWAYS_FLASH 才有完整预闪→主闪
        //    序列（v81 SINGLE 试验已回退：无预闪手感、出图口径相同、无收益）
        val pre = s.substringAfter("val preBuilder = camera.createCaptureRequest", "")
            .substringBefore("// v74 P0", "")
        assertTrue("precapture 必须走 precapture 专用版（S25U 真机取证：AE_ON 不打主闪）",
            pre.contains("applyPrecaptureFlashSettings(this)"))
        assertFalse("precapture 不能再走 preview 版",
            pre.contains("applyPreviewFlashSettings(this)"))

        // 3) CANCEL 之后的 repeating：v74 就是在这里把灯又打开的
        val reset = s.substringAfter("private fun resetFlashPrecapture", "")
            .substringBefore("/** v74 P2", "")
        assertTrue("复位必须把预览 AE 设回 ON", reset.contains("CONTROL_AE_MODE_ON"))
        assertFalse("复位绝不能再挂回 ALWAYS_FLASH", reset.contains(banned))

        // 4) 切到 INT 档时补挂预览请求，也必须是 preview 版
        val setBody = s.substringAfter("fun setFlashEnabled(enabled: Boolean) {", "")
            .substringBefore("private fun applyManualSettings", "")
        assertTrue("切档启用闪光走 preview 版",
            setBody.contains("applyPreviewFlashSettings(it)"))
        assertFalse("切档路径不得挂 ALWAYS_FLASH", setBody.contains(banned))
    }

    @Test
    fun `vc80 precapture 专用函数只用于一次性请求且守卫齐全`() {
        val s = camera() ?: return
        val calls = Regex("applyPrecaptureFlashSettings\\(this\\)").findAll(s).count()
        assertTrue("precapture 专用版只能用在那一处一次性请求，实际 $calls 处", calls == 1)
        val fn = s.substringAfter("private fun applyPrecaptureFlashSettings", "")
            .substringBefore("\n    }", "")
        assertTrue("必须挂 ALWAYS_FLASH",
            fn.contains("CONTROL_AE_MODE_ON_ALWAYS_FLASH"))
        assertTrue("闪光灯单元守卫要在", fn.contains("if (!hasFlashUnit) return"))
        assertTrue("手动曝光守卫要在",
            fn.contains("manualIso != null || manualExposure != null"))
        assertFalse("专用版不能写 FLASH_MODE（ALWAYS_FLASH 下由 HAL 控制）",
            fn.contains("FLASH_MODE_OFF"))
        // v81 SINGLE 试验已回退；vc86 起 SINGLE 只允许出现在「AE_OFF 手动锁定曝光」
        // 那一个分支里（与 vc81 的同义替换不是一回事：那时 AE 仍由 HAL 重测光）
        assertTrue("vc86 手动锁定曝光必须用 SINGLE 点火",
            s.contains("android.hardware.camera2.CameraMetadata.FLASH_MODE_SINGLE"))
        assertTrue("SINGLE 必须和 AE_OFF 手动曝光成对出现",
            s.contains("CONTROL_AE_MODE_OFF") && s.contains("SENSOR_SENSITIVITY"))
    }

    @Test
    fun `v75 P1 preview 版只写 FLASH_MODE_OFF 与 AE_ON 且不覆写手动曝光`() {
        val s = camera() ?: return
        val fn = s.substringAfter("private fun applyPreviewFlashSettings", "")
            .substringBefore("\n    }", "")
        assertTrue("必须写 FLASH_MODE_OFF", fn.contains("FLASH_MODE_OFF"))
        assertTrue("自动曝光时写 AE_ON", fn.contains("CONTROL_AE_MODE_ON"))
        assertFalse("preview 版绝不含 ALWAYS_FLASH",
            fn.contains("CONTROL_AE_MODE_ON_ALWAYS_FLASH"))
        assertTrue("手动曝光时不得覆写 AE 模式",
            fn.contains("manualIso == null && manualExposure == null"))
    }

    @Test
    fun `P2 诊断报告有 flash 段且字段齐备`() {
        val s = camera() ?: return
        val report = read("java/com/filmcam/app/develop/DeviceGeometryReport.kt") ?: return
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        assertTrue("groupOrder 要含 flash", report.contains("\"camera\", \"flash\", \"gl\""))
        listOf(
            "flash.unitAvailable", "flash.builtInEnabled", "flash.previewAeMode",
            "flash.previewFlashMode", "flash.aeStateLast", "flash.precapturePending",
            "flash.precaptureStarts", "flash.precaptureCancels", "flash.resetLastReason",
        ).forEach { key ->
            assertTrue("缺诊断字段 $key", s.contains("\"$key\" to"))
        }
        assertTrue("UI 档位要上报", main.contains("flash.uiMode"))
        assertTrue("AE 状态变化才上报", s.contains("aeState != null && aeState != lastAeState"))
    }

    @Test
    fun `诊断报告能渲染 flash 段且排在 camera 与 gl 之间`() {
        DeviceGeometryReport.clear()
        DeviceGeometryReport.putAll(
            "camera.previewSize" to "1920x1440",
            "flash.unitAvailable" to true,
            "flash.builtInEnabled" to true,
            "flash.precapturePending" to false,
            "flash.precaptureStarts" to 1,
            "flash.precaptureCancels" to 1,
            "flash.resetLastReason" to "stillCaptureCompleted",
            "gl.mode" to 0f,
        )
        val text = DeviceGeometryReport.render()
        assertTrue(text.contains("[flash]"))
        assertTrue(text.contains("flash.unitAvailable = true"))
        assertTrue(text.contains("flash.resetLastReason = stillCaptureCompleted"))
        assertTrue("flash 段应排在 camera 之后", text.indexOf("[camera]") < text.indexOf("[flash]"))
        assertTrue("flash 段应排在 gl 之前", text.indexOf("[flash]") < text.indexOf("[gl]"))
        DeviceGeometryReport.clear()
    }
}
