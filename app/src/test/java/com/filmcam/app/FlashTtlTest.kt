package com.filmcam.app

import com.filmcam.app.camera.FlashTtl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 方案③ TTL（预闪差分）的数学与接线契约（11-14 23:3x，11-15 k=1.0 校准）。
 *
 * 定量锚（11-14 真机两卷取证，tmp/ttl_1114/verdict.html）：
 *  - 近距手掌 30cm 预闪 P_f = 2733~3984（历史可接受主体量级 3600 附近）
 *  - 半身人像 P_f = 11998~13557、远距 P_f = 29334~48411（第三轮方案B 解禁后
 *    P_f 直采，帽 20810 = ISO250×83.24ms 快门包线，vc87 实测该档远距追平 FIMO）
 *  - 第一轮 k=9.4 全部撞帽 + 手掌白板已判死；第二轮 k=1.0 成立；第三轮解帽
 *  - 第三轮实测：远距 20810/83.24ms → mean 83.9 追平 FIMO；手掌离群 7234→白板
 *  - 第四轮（11-15 B2 真机）：`ratio≥20` 当近距判据错误（量环境亮不量距离，
 *    5 张一次未触发），改**真实距离上限** `P ≤ 40000×d²`（d<0.8m 才介入）
 */
class FlashTtlTest {

    // ---------- 数学 ----------

    @Test
    fun `采样缺失或差分不足回退 null`() {
        assertNull(FlashTtl.solve(0, 0.04f, 23, 0.0167f))
        assertNull(FlashTtl.solve(3000, 0.04f, 0, 0f))
        assertNull(FlashTtl.solve(3000, 0.04f, 3000, 0.04f)) // ratio=1 预闪没照亮
        assertNull(FlashTtl.solve(3000, 0.04f, 2200, 0.04f)) // ratio<2 差分不可信
    }

    @Test
    fun `目标量直接采用预闪量级 与距离无关`() {
        // 未触帽/未触底的两档预闪测光：P = k×P_f 恒等于 k=1 —— 预闪 AE 的量级
        // 本身就是该灯该距离的正确曝光量
        val near = FlashTtl.solve(3000, 0.04f, 2750, 0.001f)!!  // Pf=2750, ratio≈43.6
        val far = FlashTtl.solve(3000, 0.04f, 10000, 0.001f)!!  // Pf=10000, ratio=12
        assertEquals(FlashTtl.TTL_GAIN, near.targetProduct / near.flashProduct, 0.01f)
        assertEquals(FlashTtl.TTL_GAIN, far.targetProduct / far.flashProduct, 0.01f)
        assertTrue("预闪测光越大（越远）曝光量越多", far.targetProduct > near.targetProduct)
    }

    @Test
    fun `近距锚定第一轮实测 手掌量级原样落回`() {
        // 11-14 实测手掌 30cm：P_f=2733 → P 应为 2733（不再乘 9.4 撞帽白板）
        val s = FlashTtl.solve(1235, 0.04f, 250, 0.011f)!! // Pf=2750, ratio≈17.9
        assertEquals(2750f, s.targetProduct, 1f)
    }

    @Test
    fun `硬帽等于 ISO250 乘快门包线`() {
        // 远距超大 flashProduct：P_f 超帽 → 必须被 20810 夹住，且帽值 =
        // ISO250×83.24ms（vc87 实测该档远距 mean 100.3 追平 FIMO）
        assertEquals(250f * 83.24f, FlashTtl.MAX_PRODUCT, 1f)
        val s = FlashTtl.solve(6000, 0.04f, 2933, 0.01f)!! // Pf=29334, ratio=8.2
        assertTrue(s.targetProduct <= FlashTtl.MAX_PRODUCT)
        assertEquals(FlashTtl.MAX_PRODUCT, s.targetProduct, 0.01f)
    }

    @Test
    fun `半身量级 P_f 直采不再被 5609 掐`() {
        // 11-15 实测半身 ~1m：P_f=11998/13557（第三轮方案B 前）被 5609 掐掉
        // 1.0~1.1EV；解禁后应原样落回
        val s = FlashTtl.solve(3539, 0.04f, 3000, 0.004f)!! // Pf=12000, ratio=11.8
        assertEquals(12000f, s.targetProduct, 1f)
    }

    @Test
    fun `近距距离帽 只揪极近距 不碰 30cm 以上`() {
        // 主体正确曝光满足 P/d² ≈ 34.5k~42k，帽取上沿 40,000，且只对 d<0.25m 介入
        assertEquals(40000f, FlashTtl.PRODUCT_PER_M2, 0.01f)
        assertEquals(0.25f, FlashTtl.NEAR_RANGE_M, 0.001f)
        // 11-15 实测 22cm 手掌：P_f=3247 → 帽 40000×0.224² = 2007（真机纹理完整）
        val palm22 = FlashTtl.solve(5678, 0.01f, 3247, 0.001f, 0.224f)!!
        assertEquals(2007f, palm22.targetProduct, 2f)
        // 11-15 实测 20cm 手掌离群：P_f=4116 → 帽 1600（历史白板 → 回安全区）
        val palm20 = FlashTtl.solve(5165, 0.01f, 4116, 0.001f, 0.20f)!!
        assertEquals(1600f, palm20.targetProduct, 1f)
        // 30cm 手掌历史 3107~3984 本来就正常 ⇒ 不介入
        val palm30 = FlashTtl.solve(5194, 0.04f, 3767, 0.001f, 0.30f)!!
        assertEquals(3767f, palm30.targetProduct, 1f)
        // D 轮真机：37cm 自拍 P_f=16197 被旧帽砍到 5388 → 脸发黑（mean 25.7）；不介入后保持
        val selfie = FlashTtl.solve(4892, 0.04f, 16197, 0.001f, 0.367f)!!
        assertEquals(16197f, selfie.targetProduct, 1f)
    }

    @Test
    fun `距离不可信或超出近距范围时不介入`() {
        // 0.25m 以外：结果与不传距离完全一致
        val base = FlashTtl.solve(5194, 0.04f, 4116, 0.001f)!!
        assertEquals(4116f, base.targetProduct, 1f)
        assertEquals(base.targetProduct, FlashTtl.solve(5194, 0.04f, 4116, 0.001f, null)!!.targetProduct, 0.01f)
        assertEquals(base.targetProduct, FlashTtl.solve(5194, 0.04f, 4116, 0.001f, 0.03f)!!.targetProduct, 0.01f)
        assertEquals(base.targetProduct, FlashTtl.solve(5194, 0.04f, 4116, 0.001f, 1.2f)!!.targetProduct, 0.01f)
        // 远距照旧走 20810 帽
        val far = FlashTtl.solve(6000, 0.04f, 2933, 0.01f, 3.0f)!!
        assertEquals(FlashTtl.MAX_PRODUCT, far.targetProduct, 1f)
    }

    @Test
    fun `亮场景护栏 不许比环境测光超 0点26EV`() {
        // ambient=800，flash=300（ratio=2.67 差分有效）：1.0×300=300 →
        // 底护栏 900 先于亮场景护栏 960 生效
        val s = FlashTtl.solve(800, 0.001f, 300, 0.001f)!!
        assertEquals(900f, s.targetProduct, 0.01f)
    }

    @Test
    fun `下限护栏 极近距不许把快门压没`() {
        // flashProduct=60 → 1.0×60=60 → 抬到 900
        val s = FlashTtl.solve(3000, 0.04f, 12, 0.005f)!!
        assertEquals(FlashTtl.MIN_PRODUCT, s.targetProduct, 0.01f)
    }

    // ---------- 源码接线契约 ----------

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

    private fun read(rel: String): String =
        sourceRoot()?.let { File(it, rel).takeIf { f -> f.isFile }?.readText() } ?: ""

    @Test
    fun `开关与快门前重调接线`() {
        val s = read("java/com/filmcam/app/camera/Camera2Controller.kt")
        assertTrue("单一实验开关", s.contains("const val FLASH_TTL_EXPERIMENT = true"))
        assertTrue("fire() 里必须紧跟提交快门之前重调（CRLF 安全的正则）",
            Regex("applyFlashTtlRetune\\(captureBuilder\\)\\s*\n\\s*" +
                "session\\.capture\\(captureBuilder\\.build\\(\\), stillCaptureCallback")
                .containsMatchIn(s))
    }

    @Test
    fun `环境快照与两路采样都要接`() {
        val s = read("java/com/filmcam/app/camera/Camera2Controller.kt")
        assertTrue("PRECAPTURE START 处要拍环境快照（闪光前无污染）",
            s.contains("ttlAmbientIso = lastAeIso"))
        assertTrue(
            "one-shot 结果回调 + 预览流回调 + 函数定义本身，recordTtlSample 至少 3 处",
            Regex("recordTtlSample\\(").findAll(s).count() >= 3
        )
        assertTrue("预览流采样必须以 precapturePending 为窗口闸",
            s.contains("if (precapturePending) recordTtlSample(isoAe, exposureSec)"))
    }

    @Test
    fun `重调只覆盖 SENSOR 两个 key 且有手动能力守卫`() {
        val s = read("java/com/filmcam/app/camera/Camera2Controller.kt")
        val fn = s.substringAfter("private fun applyFlashTtlRetune", "")
            .substringBefore("private fun applyFlashCaptureTuning", "")
        assertTrue("必须有 hasManualSensor 守卫", fn.contains("hasManualSensor"))
        assertTrue("求解失败静默回退", fn.contains("FlashTtl.solve") && fn.contains("return"))
        assertTrue("重写 ISO/快门两个 key",
            fn.contains("CaptureRequest.SENSOR_SENSITIVITY") &&
                fn.contains("CaptureRequest.SENSOR_EXPOSURE_TIME"))
        assertTrue("日志要带完整差分画像便于校准",
            fn.contains("TTL RETUNE") && fn.contains("ratio=") && fn.contains("flashProd="))
    }

    @Test
    fun `预闪帧焦点探针要接`() {
        val s = read("java/com/filmcam/app/camera/Camera2Controller.kt")
        assertTrue("单一开关", s.contains("const val FLASH_TTL_FOCUS_PROBE = true"))
        assertTrue("precapture one-shot 请求上要挂探针",
            Regex("applyPrecaptureFlashSettings\\(this\\)[\\s\\S]{0,300}applyTtlFocusProbe\\(this\\)")
                .containsMatchIn(s))
        assertTrue("one-shot 结果回调要读对焦距离",
            Regex("recordTtlSample\\(ttlIso, ttlSec\\)[\\s\\S]{0,120}recordTtlFocus\\(result\\)")
                .containsMatchIn(s))
        assertTrue("求解必须带距离入参",
            Regex("FlashTtl\\.solve\\(\\s*ttlAmbientIso, ttlAmbientSec, ttlFlashIso, ttlFlashSec,")
                .containsMatchIn(s))
        assertTrue("探针要有对焦能力守卫", s.contains("if (minFocusDistance <= 0f) return"))
    }

    @Test
    fun `TTL 对象零引擎依赖 常量齐备`() {
        val s = read("java/com/filmcam/app/camera/FlashTtl.kt")
        assertTrue(
            s.contains("const val TTL_GAIN") && s.contains("const val MAX_PRODUCT") &&
                s.contains("const val MIN_RATIO") && s.contains("const val AMBIENT_HEADROOM") &&
                s.contains("const val PRODUCT_PER_M2") && s.contains("const val NEAR_RANGE_M") &&
                s.contains("const val MIN_VALID_DISTANCE_M")
        )
        assertTrue("ratio 当近距判据已被证伪，不许残留",
            !s.contains("NEAR_MAX_PRODUCT") && !s.contains("MIN_NEAR_RATIO"))
        assertEquals("不许 import 引擎层（spektra / FilmStockMapper）",
            0, Regex("^import .*(pektra|ilmStockMapper)", RegexOption.MULTILINE).findAll(s).count())
    }
}
