package com.filmcam.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 编排式外闪同步（对齐神牛官方 iPhone Godox Camera 方案）契约。
 *
 * 背景：A2 氙气灯「有时快门遇不到闪光」的根因是触发时刻绑在
 * `flashTriggerDelayMs(halLatency, -80)` —— halLatency 用中位数预测，
 * 真机 240~389ms 抖动，任一偏差灯就在开快门前闪完。官方是「宽窗等灯」：
 * App 自持曝光、窗口敞开等主闪。Android 侧落地 = 触发绑到 onCaptureStarted
 * （HAL 实测曝光真开始，删掉预测）+ 宽窗覆盖 BLE/起辉抖动 + 锁 ISO 不缩窗。
 *
 * 仅 A2 氙气灯启用；COB 保持原「先点灯、延后快门」预测逻辑；
 * 触发帧 1000,Test、0c / Take 一律不碰（历史否决项）。
 */
class OrchestratedFlashSyncContractTest {

    /** Gradle test 工作目录通常是 app/；向上找带 app/src/main 的项目根。 */
    private fun read(rel: String): String? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val d = dir ?: return null
            val main = File(d, "app/src/main")
            if (main.isDirectory) {
                val f = File(main, rel)
                if (f.isFile) return f.readText()
            }
            dir = d.parentFile
        }
        return null
    }

    private fun cam() = read("java/com/filmcam/app/camera/Camera2Controller.kt")

    // ---------------- 1. 定量：solveFixedIso 锁 ISO、窗口不缩 ----------------

    @Test
    fun `编排曝光锁 ISO 不变 且 150ms 窗口不被守恒补偿缩短`() {
        // 设备能力富余：ISO 12~3200、快门 8~150ms。标定 57*gain(4)=228 @100ms。
        val r = FlashExposure.solveFixedIso(
            fixedIso = 228f,
            targetShutterNs = 150_000_000L,
            minIso = 12, maxIso = 3200,
            minShutterNs = 8_000_000L, maxShutterNs = 150_000_000L,
        )
        assertEquals("闪光主体只认 ISO，编排必须锁死标定值", 228, r.iso)
        assertEquals("宽窗要覆盖 BLE+起辉抖动，必须真的放宽到 150ms",
            150_000_000L, r.shutterNs)
        assertFalse("能力富余时不应被判为截断", r.clamped)

        // 与常规 solve 对比：solve 在 ISO 越界时会改 t 去补 —— 编排要的正是「不缩窗」
        val solve = FlashExposure.solve(
            baseIso = 228f, baseShutterNs = 100_000_000L,
            minIso = 12, maxIso = 3200,
            minShutterNs = 8_000_000L, maxShutterNs = 150_000_000L,
        )
        assertEquals("常规路径窗口仍锁 100ms", 100_000_000L, solve.shutterNs)
        assertEquals("常规路径 ISO 仍 228", 228, solve.iso)
    }

    @Test
    fun `ISO 越界时 编排仍钳 ISO 且窗口固定 target 不受守恒补偿改动`() {
        // 标定 ISO 极高（> 设备上限）：solve 会拉长 t 把 ISO 拉回；
        // solveFixedIso 则钳 ISO、窗口固定 target（只受设备快门上限约束）。
        val r = FlashExposure.solveFixedIso(
            fixedIso = 9000f,
            targetShutterNs = 150_000_000L,
            minIso = 12, maxIso = 3200,
            minShutterNs = 8_000_000L, maxShutterNs = 150_000_000L,
        )
        assertEquals("ISO 钳到设备上限", 3200, r.iso)
        assertEquals("窗口不受 ISO 越界影响，仍是 150ms", 150_000_000L, r.shutterNs)
    }

    @Test
    fun `设备快门上限不足时 编排窗收敛到上限并标记 clamped`() {
        val r = FlashExposure.solveFixedIso(
            fixedIso = 228f,
            targetShutterNs = 150_000_000L,
            minIso = 12, maxIso = 3200,
            minShutterNs = 8_000_000L, maxShutterNs = 80_000_000L,
        )
        assertEquals(80_000_000L, r.shutterNs)
        assertEquals(228, r.iso)
        assertTrue("窗口被设备上限收敛要标记，供诊断", r.clamped)
        assertTrue(r.note.contains("收敛"))
    }

    @Test
    fun `编排窗口常量 150ms 且不超过设备上限 MAX_SYNC_SHUTTER_NS`() {
        val s = cam() ?: return
        assertTrue("编排宽窗常量必须是 150ms",
            s.contains("ORCHESTRATED_SYNC_SHUTTER_NS = 150_000_000L"))
        // 11-23：同步上限放宽到 S25U HAL 真实上限 176ms（外闪手动档 1/5 需要宽窗），
        // 编排窗本身保持 150ms 不变。
        assertTrue("宽窗不得超过设备上限常量（S25U HAL 实测 176ms）",
            s.contains("MAX_SYNC_SHUTTER_NS = 176_000_000L"))
        assertTrue("常规路径 100ms 窗口保持不变（COB 行为零变化）",
            s.contains("FLASH_SYNC_SHUTTER_NS = 100_000_000L") &&
                s.contains("baseShutterNs = FLASH_SYNC_SHUTTER_NS"))
    }

    // ---------------- 2. 能力下发链：仅 A2 启用，COB 默认 false ----------------

    @Test
    fun `驱动接口默认不启用编排 且 A2 档案单一口径判定`() {
        val iface = read("java/com/filmcam/app/flash/ExternalFlashDriver.kt") ?: return
        assertTrue("接口必须暴露编排能力", iface.contains("val orchestratedSync: Boolean"))
        assertTrue("接口默认 false（FotorGear / 假驱动零改动）",
            Regex("val orchestratedSync: Boolean\\s+get\\(\\) = false").containsMatchIn(iface))

        val profile = read("java/com/filmcam/app/flash/FlashProfile.kt") ?: return
        assertTrue("A2 判定必须复用 isA2Device 单一口径（名字或已知 MAC）",
            Regex("val orchestratedSync: Boolean[\\s\\S]{0,120}get\\(\\) = isXenonA2").containsMatchIn(profile))
        assertTrue("isXenonA2 仍绑 isA2Device", profile.contains("isA2Device(deviceName, address)"))
    }

    @Test
    fun `自学习驱动与控制器把编排能力透传到相机`() {
        val learned = read("java/com/filmcam/app/flash/LearnedProfileDriver.kt") ?: return
        assertTrue(Regex("override val orchestratedSync[^\\n]*profile\\.orchestratedSync").containsMatchIn(learned))
        val ctrl = read("java/com/filmcam/app/flash/FlashController.kt") ?: return
        assertTrue("控制器要转发灯能力", ctrl.contains("fun useOrchestratedSync(): Boolean"))
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        assertTrue("applyExposure 单一路径下发第三参数",
            Regex("setExternalFlashSync\\([\\s\\S]{0,200}flash\\.useOrchestratedSync\\(\\)").containsMatchIn(main))
    }

    @Test
    fun `相机签名保住契约前缀且第三参数默认 false`() {
        val s = cam() ?: return
        // ExternalFlashRegistryTest 锁了这段连续前缀，不能换行/改名
        assertTrue("setExternalFlashSync 前缀必须保持（既有契约正则）",
            Regex("fun setExternalFlashSync\\(enabled: Boolean, profileOffsetMs: Int\\?").containsMatchIn(s))
        assertTrue("编排参数默认 false（未接线的调用零行为变化）",
            s.contains("orchestrated: Boolean = false"))
        assertTrue("能力随灯与档位下发",
            s.contains("orchestratedFlashCapable = enabled && orchestrated"))
    }

    @Test
    fun `本帧编排标记 仅在 EXT 档 + A2 灯 + 热调未关闭时生效`() {
        val s = cam() ?: return
        assertTrue("本帧标记要三重与运算，保证 COB/切档立即回退",
            s.contains("orchestratedFlashSync = externalFlashSync && orchestratedFlashCapable && !hotOff"))
        assertTrue("热调文件写 0 可真机即时回退",
            s.contains("ORCHESTRATED_HOTFILE = \"flash_orchestrated.txt\"") &&
                s.contains("f.readText().substringBefore(\"//\").trim() == \"0\""))
    }

    // ---------------- 3. 时序：触发绑 onCaptureStarted，删掉预测 ----------------

    @Test
    fun `编排模式提交拍照不再走预测式快门前置 与三段式`() {
        val s = cam() ?: return
        val body = s.substringAfter("private fun submitStillCapture(", "")
            .substringBefore("三段式灯（A2）", "")
        assertTrue("编排分支必须在三段式/快门前置之前短路",
            body.contains("if (orchestratedFlashSync && !slowShutter)"))
        assertTrue("编排分支直接提交宽窗拍照（不预触发、不延后快门）",
            body.contains("submitStillCaptureNow(captureBuilder, slowShutter)") &&
                body.contains("return"))
        assertTrue("日志要点明「不再预测 halLatency」", body.contains("不再预测 halLatency"))
    }

    @Test
    fun `编排触发绑到 onCaptureStarted 曝光真开始 且只发一次`() {
        val s = cam() ?: return
        val started = s.substringAfter("override fun onCaptureStarted(", "")
            .substringBefore("override fun onCaptureCompleted", "")
        assertTrue("曝光真开始这一刻发单发触发（拍照帧 tag 判定）",
            started.contains("if (orchestratedFlashSync)") &&
                started.contains("request.tag == STILL_CAPTURE_TAG") &&
                started.contains("fireExternalFlashOnce(\"编排·拍照帧曝光开始\")"))
        assertTrue("旧 0/正偏移分支必须加 !orchestratedFlashSync 守卫防重复发灯",
            started.contains("if (!orchestratedFlashSync && externalFlashSync && flashTriggerOffsetMs >= 0)"))
        assertTrue("fireExternalFlashOnce 仍带 flashTriggerPosted 防重",
            s.substringAfter("private fun fireExternalFlashOnce(", "").substringBefore("}", "")
                .contains("if (flashTriggerPosted) return"))
    }

    @Test
    fun `编排模式禁止排预测触发 否则会重复发灯`() {
        val s = cam() ?: return
        val fn = s.substringAfter("private fun scheduleFlashPreTrigger(", "")
            .substringBefore("val delay", "")
        assertTrue("scheduleFlashPreTrigger 必须因编排模式直接 return",
            fn.contains("orchestratedFlashSync") && fn.contains("return"))
    }

    @Test
    fun `A2功率帧真值a0_01短帧 驱动改用真值帧组帧`() {
        val s = cam() ?: return
        assertTrue("TTL 热调文件 flash_ttl_off.txt 存在",
            s.contains("flash_ttl_off.txt") && s.contains("ttlHotOff"))
        assertTrue("灯侧TTL时相机ISO锁标定（ttlGain 恒1，不再调ISO）",
            s.contains("val ttlGain = 1f") &&
                s.contains("相机 ISO 锁标定 228 不动"))
        assertTrue("诊断上报功率字节与 TTL 热调状态",
            s.contains("flash.powerByte") && s.contains("flash.ttlHotOff"))
        // 驱动必须用抓包真值帧 A2ShortPower，不再是 A1 长帧
        val drv = read("java/com/filmcam/app/flash/LearnedProfileDriver.kt") ?: return
        assertTrue("setFlashPowerLevel 改用 a0 01 真值帧",
            drv.contains("A2ShortPower.frame(powerByte)") &&
                !drv.contains("A2PowerProtocol.manualFrame(powerByte)"))
        // 抓包 7 帧 CRC 全吻合
        for ((arg, hex) in mapOf(0 to "A0010032", 0x0A to "A0010A4C",
            0x14 to "A00114CE", 0x1E to "A0011EB0", 0x3C to "A0013C2F", 0x46 to "A00146A9")) {
            val f = com.filmcam.app.flash.A2ShortPower.frame(arg)
            assertEquals("arg=$arg", hex, f.joinToString("") { "%02X".format(it.toInt() and 0xFF) })
        }
    }

    @Test
    fun `普通 EXT 档也打开 TTL 分闸 非外闪档关掉`() {
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        assertTrue("applyExposure 必须在 EXT 档打开 TTL 分闸（手动标定档除外）",
            main.contains("val isExt = flash.mode == FlashMode.EXTERNAL") &&
                main.contains("cameraController?.externalTtlEnabled = isExt && !manualCalib"))
        assertTrue("不得有反射/绕弯的 dummy 代码",
            !main.contains("getDeclaredField"))
    }

    @Test
    fun `TTL 重开后编排帧吃闭环增益 且历史残留清零`() {
        val s = cam() ?: return
        assertTrue("TTL 总开关必须开启（用户 11-22 拍板方案1）",
            s.contains("private val EXTERNAL_TTL_ENABLED = true"))
        assertTrue("灯侧TTL：相机ISO锁标定，ttlGain恒1（不再用externalTtlEv调ISO）",
            s.contains("val ttlGain = 1f") &&
                !s.contains("val ttlGain = if (orchestratedFlashSync) 1f"))
        assertTrue("会话启动清零历史残留 +2EV，从 0EV 开始收敛",
            s.contains("if (EXTERNAL_TTL_ENABLED) {") &&
                s.contains("externalTtlEv = 0f") &&
                s.contains("历史残留增益已清零"))
        assertTrue("gain 仍是 flashSyncGain x evGain x ttlGain（热调口子保留）",
            s.contains("val gain = flashSyncGain * evGain * ttlGain"))
    }

    @Test
    fun `编排下走跨张收敛而非双闪测光帧 且用真实 ISO 反解`() {
        val s = cam() ?: return
        assertTrue("双闪测光帧分支必须排除编排（它会 setManualIso 后被编排覆盖白算）",
            Regex("EXTERNAL_TTL_ENABLED && externalFlashSync && externalTtlEnabled[\\s\\S]{0,160}!orchestratedFlashSync")
                .containsMatchIn(s))
        assertTrue("灯侧TTL：成片测量走 analyzeMeteringLuma + flashPowerAdjust 调功率",
            s.contains("analyzeMeteringLuma(bytes)") && s.contains("TtlMeter.flashPowerAdjust"))
        assertTrue("功率经 onFlashPowerRequest 回调下发到灯",
            s.contains("onFlashPowerRequest?.invoke(target)"))
    }

    @Test
    fun `COB 原预测逻辑完整保留 一字不动`() {
        val s = cam() ?: return
        assertTrue("flashPreRollMs 纯函数仍在（COB 先点灯延后快门）",
            s.contains("fun flashPreRollMs(halLatencyMs: Long, offsetMs: Long): Long"))
        assertTrue("flashTriggerDelayMs 纯函数仍在",
            s.contains("fun flashTriggerDelayMs(halLatencyMs: Long, offsetMs: Long): Long"))
        assertTrue("快门前置分支仍在", s.contains("fireExternalFlashOnce(\"快门前置\")"))
        assertTrue("applyExternalFlashSync 常规分支仍调 solve",
            s.contains("FlashExposure.solve("))
    }

    // ---------------- 4. 零协议改动 + 诊断可追 ----------------

    @Test
    fun `触发帧与关键字零改动 历史否决项不复活`() {
        val profile = read("java/com/filmcam/app/flash/FlashProfile.kt") ?: return
        assertTrue("唯一验证能出氙气的 1000,Test 帧不变",
            profile.contains("A2_TRIGGER_HEX = \"313030302C54657374\""))
        assertTrue("Take 关键字仍被明令禁止",
            profile.contains("不要改成") && profile.contains("Take"))
        val s = cam() ?: return
        // 编排相关的新代码里不得出现自动下发 0c / 03 / Take
        val orchLines = s.lineSequence().filter {
            it.contains("编排") || it.contains("orchestrated") || it.contains("ORCHESTRATED")
        }.joinToString("\n")
        assertFalse("编排链路不得自动下发 0c 命令", orchLines.contains("0c 00") || orchLines.contains("0c 50"))
        assertFalse("编排链路不得改用 Take", orchLines.contains("Take"))
    }

    @Test
    fun `诊断报告带 orchestrated 字段 便于真机区分两种模式`() {
        val s = cam() ?: return
        assertTrue(s.contains("\"flash.orchestrated\" to orchestratedFlashSync"))
        assertTrue("固定曝光日志要标编排状态", s.contains("orchestrated=\$orchestratedFlashSync"))
    }
}
