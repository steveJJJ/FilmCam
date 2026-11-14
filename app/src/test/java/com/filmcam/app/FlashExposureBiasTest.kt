package com.filmcam.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置直闪成片的曝光偏置契约（v81 → vc85）。
 *
 * 11-14 真机同场景 A/B（关闪 vs INT，相隔 4s）：
 *   HAL `ON_ALWAYS_FLASH` 重测光 iso 202/1:23 → 23/1:60，环境光掉 ≈4.5 EV；
 *   成片均值 89.8 → 27.0、暗像素 46% → 83%、四角 49 → 10。
 * v81 用户拍板把 [Camera2Controller.FLASH_EV_BIAS] 从 -2/3 归零，只去掉
 * 「App 额外压暗」这一层，保留直闪味。
 *
 * vc85：FIMO 同场景定量（tmp/flash_lag_1114/fimo/）后试过 +1EV 慢同步补偿，
 * 真机证明三星 HAL 在 ALWAYS_FLASH 下忽略 AE 补偿，已归零；换算能力保留。
 * vc87：vc86 手动锁曝光解决了背景死黑，但主体高光被引擎肩部压成无纹理白板
 * （亮格 std 0.7~8.8 vs FIMO 14~22）。新增 [Camera2Controller.FLASH_LOCK_EV_BIAS]
 * 只缩放手写 ISO，让主体退出曲线肩部。
 */
class FlashExposureBiasTest {

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
    fun `直闪曝光偏置归零 不许退回负 EV`() {
        val s = camera() ?: return
        assertTrue("FLASH_EV_BIAS 必须是 0f", s.contains("const val FLASH_EV_BIAS = 0f"))
        assertFalse("不许再出现旧的 -2/3 常量",
            s.contains("const val FLASH_EV_BIAS = -2f / 3f"))
        // 注释里的历史定量可以提负数，但常量定义只能有一个
        val defs = Regex("const val FLASH_EV_BIAS\\s*=").findAll(s).count()
        assertTrue("偏置常量只能定义一次，实际 $defs 处", defs == 1)
    }

    @Test
    fun `按 AE 步长换算下发的能力要留着 且夹在设备真实范围内`() {
        val s = camera() ?: return
        val fn = s.substringAfter("private fun applyFlashCaptureTuning", "")
            .substringBefore("\n    }", "")
        assertTrue("仍按设备 AE 步长换算整档（含慢同步补偿）",
            fn.contains("Math.round((FLASH_EV_BIAS + FLASH_SLOW_SYNC_EV) / stepEv)"))
        assertTrue("非零档才写 AE_EXPOSURE_COMPENSATION",
            fn.contains("if (evBiasSteps != 0)"))
        assertTrue("写的是用户 EV + 偏置档数",
            fn.contains("(exposureComp + evBiasSteps)"))
        assertTrue("仍要夹在设备真实 AE 范围内",
            fn.contains("coerceIn(aeCompRange.first, aeCompRange.last)"))
    }

    @Test
    fun `vc85 慢同步实验已归零 预闪帧换算能力保留`() {
        val s = camera() ?: return
        assertTrue("+1EV 实测无效（HAL 忽略 AE 补偿），常量必须归零",
            s.contains("const val FLASH_SLOW_SYNC_EV = 0f"))
        assertFalse("不该把历史 FLASH_EV_BIAS 改成负值或挪作他用",
            s.contains("const val FLASH_EV_BIAS = -"))
        val pre = s.substringAfter("val preBuilder = camera.createCaptureRequest", "")
            .substringBefore("// v74 P0", "")
        assertTrue("预闪测光帧必须下发 AE 补偿，否则 HAL 不会重新决定快门/ISO/闪光功率",
            pre.contains("CONTROL_AE_EXPOSURE_COMPENSATION"))
        assertTrue("预闪帧必须用同一个慢同步常量",
            pre.contains("FLASH_SLOW_SYNC_EV"))
        assertTrue("预闪帧也要夹在设备真实 AE 范围内",
            pre.contains("coerceIn(aeCompRange.first, aeCompRange.last)"))
        assertTrue("PRECAPTURE START 日志要带慢同步值便于定量",
            s.contains("slowSyncEv=\$FLASH_SLOW_SYNC_EV"))
    }

    @Test
    fun `归零只去偏置 直闪另外三层调优一字不动`() {
        val s = camera() ?: return
        assertTrue("AWB 钉日光还在", fnHas(s, "CONTROL_AWB_MODE_DAYLIGHT"))
        assertTrue("线性 tonemap 还在", fnHas(s, "TONEMAP_MODE_CONTRAST_CURVE"))
        assertTrue("拍照帧仍挂 applyFlashCaptureTuning",
            s.contains("applyFlashCaptureTuning(this, slow)"))
        assertTrue("拍照帧仍挂 ALWAYS_FLASH",
            s.contains("applyStillFlashSettings(this)"))
        assertTrue("守卫不变：手动曝光/慢门不调优",
            s.contains("if (slow || manualIso != null || manualExposure != null) return"))
    }

    /**
     * vc86：FIMO 运行时取证发现 ISO 250（FIMO）vs 23（我们）差 ≈3.4EV，
     * 是背景死黑的根因。直闪帧手动锁定到「闪光前预览 AE 实测曝光」绕开
     * HAL 的 ALWAYS_FLASH 重测光。曝光值必须取设备实测、不硬编码，且要
     * 有 MANUAL_SENSOR 能力守卫（LEGACY 机型手写 AE_OFF 会整帧失败）。
     */
    @Test
    fun `vc86 手动锁定曝光取预览实测值 且有机型能力守卫`() {
        val s = camera() ?: return
        assertTrue("实验开关常量要在（置 false 一键回退 vc82）",
            s.contains("const val FLASH_LOCK_PREVIEW_AE = true"))
        val fn = s.substringAfter("private fun applyFlashCaptureTuning", "")
            .substringBefore("\n    }", "")
        assertTrue("必须有 MANUAL_SENSOR 能力守卫",
            fn.contains("FLASH_LOCK_PREVIEW_AE && hasManualSensor"))
        assertTrue("ISO 必须取预览回调实测值，不能硬编码 250",
            fn.contains("val lockIso = lastAeIso"))
        assertTrue("快门必须取预览回调实测值",
            fn.contains("lastAeSec * 1_000_000_000f"))
        assertFalse("不许把 FIMO 的 ISO 250 硬编码进来",
            fn.contains("SENSOR_SENSITIVITY, 250"))
        assertTrue("必须关 AE 由 app 接管曝光", fn.contains("CONTROL_AE_MODE_OFF"))
        assertTrue("必须手写 ISO/快门", fn.contains("SENSOR_EXPOSURE_TIME") &&
            fn.contains("SENSOR_SENSITIVITY"))
        assertTrue("ISO 要夹在设备真实范围内",
            fn.contains("coerceIn(deviceIsoRange.first, deviceIsoRange.last)"))
        assertTrue("AE_OFF 下必须用 SINGLE 点火", fn.contains("FLASH_MODE_SINGLE"))
        assertTrue("日志要带锁定结果便于定量", s.contains("lockAe=\$lockAe"))
    }

    /**
     * vc87：主体高光压平（亮格 std 0.7~8.8、RGB max 168、零削顶像素）
     * 是 HAL JPEG 输入过曝 + 引擎肩部导致的死白平板。AE_OFF 下曝光由 App 控制，
     * 用负 EV 只缩放手写 ISO 把主体拉回线性区；快门必须保持预览实测值，
     * 不能连快门一起缩（会丢掉用户 EV 拨盘与暗光慢门策略）。
     */
    @Test
    fun `vc87 主体高光压制 只缩放手写 ISO`() {
        val s = camera() ?: return
        assertTrue("EV 微调常量必须是负值（首轮 -0.7EV）",
            s.contains("const val FLASH_LOCK_EV_BIAS = -0.7f"))
        assertTrue("必须用 2^EV 换算增益而不是硬编码系数",
            s.contains("Math.pow(2.0, FLASH_LOCK_EV_BIAS.toDouble())"))
        val fn = s.substringAfter("private fun applyFlashCaptureTuning", "")
            .substringBefore("\n    }", "")
        assertTrue("下发的手写 ISO 必须是缩放后的值",
            fn.contains("SENSOR_SENSITIVITY, adjIso"))
        assertTrue("缩放后仍要夹在设备真实 ISO 范围内",
            fn.contains("coerceIn(deviceIsoRange.first, deviceIsoRange.last)"))
        assertTrue("快门仍由预览实测值推导（vc89 起按目标曝光量反算）",
            fn.contains("(adjExpSec * 1_000_000_000f)"))
        assertTrue("日志要带缩放前后 ISO 便于定量复标",
            s.contains("lockIsoAdj=\$adjIso") && s.contains("isoScale=\$isoScale"))
    }

    /**
     * vc88：vc87 只缩 ISO 仍过曝，因为判据是**总曝光量 ISO×快门**：
     * 不过曝 9 张 1369~2108 ISO·ms，过曝 4 张 5609~30624，FIMO 4167。
     * 暗场景预览 AE 会给 30ms 快门，光缩 ISO 挡不住；必须钳总曝光量，
     * 并把快门稳定到 1/60s（同时治 10/30ms 跳变导致的异常帧）。
     */
    @Test
    fun `vc89 总曝光量目标钳制 ISO 单独钳主体 快门反算`() {
        val s = camera() ?: return
        assertTrue("总曝光量目标 3600（vc88 的 2500 +0.53EV，仍低于过曝下界 5609）",
            s.contains("const val FLASH_MAX_EXPOSURE_PRODUCT = 3600f"))
        assertTrue("ISO 必须单独钉到 250（vc88 已验证不过曝上界）",
            s.contains("const val FLASH_MAX_FLASH_ISO = 250"))
        assertTrue("快门上限放开到 1/30s 让暗场景背景吃到环境光",
            s.contains("const val FLASH_MAX_SHUTTER_SEC = 0.03333334f"))
        val fn = s.substringAfter("private fun applyFlashCaptureTuning", "")
            .substringBefore("\n    }", "")
        assertTrue("ISO 必须按预览实测换算后单独钳到上限",
            fn.contains("Math.min(Math.round(lockIso * isoScale), FLASH_MAX_FLASH_ISO)"))
        assertTrue("背景目标必须取预览 AE 想给的环境光量并夹到上限",
            fn.contains("lockIso.toFloat() * lastAeSec * 1000f") &&
                fn.contains("FLASH_MAX_EXPOSURE_PRODUCT"))
        assertTrue("快门必须由目标曝光量 / 实际 ISO 反算（增益移给快门）",
            fn.contains("targetProduct / (adjIso * 1000f)"))
        assertTrue("下发的手写快门/ISO 必须是重分配后的值",
            fn.contains("SENSOR_EXPOSURE_TIME, adjExpNs") &&
                fn.contains("SENSOR_SENSITIVITY, adjIso"))
        assertTrue("仍要夹在设备真实曝光/ISO 范围内",
            fn.contains("coerceIn(minExpNs, maxExpNs)") &&
                fn.contains("coerceIn(deviceIsoRange.first, deviceIsoRange.last)"))
        assertTrue("日志要带钳制后的真实总曝光量（修 vc88 口径）",
            s.contains("expProduct=\$expProduct") &&
                s.contains("adjIso.toFloat() * adjExpNs / 1_000_000f"))
    }

    private fun fnHas(s: String, key: String): Boolean =
        s.substringAfter("private fun applyFlashCaptureTuning", "")
            .substringBefore("\n    }", "").contains(key)
}
