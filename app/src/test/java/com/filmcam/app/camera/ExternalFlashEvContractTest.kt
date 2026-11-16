package com.filmcam.app.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * 外闪细档补偿（vc86+）源码契约 + 曝光量定量。
 *
 * 真机取证：EXT 档拍照帧被写死成 t=100ms、ISO = 57×gain×2^EV；
 * EV 0 → ISO 228 过曝白板，EV −3 → ISO 29 死黑，中间只有整数档选不到。
 * 用户拍板：切到 EXT 后机顶 EV 拨盘就地变成 0.5 EV/档的闪光灯细档补偿，
 * 快门仍锁 100ms 同步窗口，默认 0 EV 不改变原有出片。
 */
class ExternalFlashEvContractTest {

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

    @Test
    fun `切到 EXT 后 EV 拨盘改走 0_5 EV 细档 且两套值分开存`() {
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        assertTrue("必须有独立的细档字段", main.contains("private var flashCompEv = 0f"))
        assertTrue("默认 0 EV，不能擅自改变既有出片", main.contains("private var flashCompEv = 0f"))
        assertTrue("步进必须是 0.5 EV", main.contains("0.5f") && main.contains("0.5 EV/档"))
        assertTrue("四条拨盘入口要统一分流", main.contains("private fun stepEv(") &&
            main.contains("binding.btnEvPlus.hapticClick { stepEv(1) }") &&
            main.contains("binding.btnEvMinus.hapticClick { stepEv(-1) }") &&
            main.contains("private fun cycleEvValue()") &&
            main.contains("applyFlashCompUi(persist = false)"))
        assertTrue("细档值要持久化，切档不与整数 EV 互相覆盖",
            main.contains("prefs.getFloat(\"flashEv\", 0f)") &&
                main.contains("prefs.putFloat(\"flashEv\", flashCompEv)"))
        assertTrue("切档时必须把细档下发相机",
            main.contains("cameraController?.setExternalFlashComp(flashCompEv)"))
        assertTrue("拨盘语义变化要在机顶 LCD 告知用户",
            main.contains("lcd_notice_flash_ev_fine"))
    }

    @Test
    fun `外闪固定曝光只认细档补偿 快门仍锁 100ms`() {
        val cam = read("java/com/filmcam/app/camera/Camera2Controller.kt") ?: return
        assertTrue("相机要暴露细档下发接口", cam.contains("fun setExternalFlashComp(ev: Float)"))
        assertTrue("增益必须用细档补偿而非整数 EV",
            cam.contains("Math.pow(2.0, externalFlashCompEv.toDouble())") &&
                !cam.contains("Math.pow(2.0, exposureComp.toDouble())"))
        assertTrue("日志要把 flashEv 与常规 ev 分开打", cam.contains("flashEv=\$externalFlashCompEv ev=\$exposureComp"))
        assertTrue("同步窗口仍是 100ms（灯起辉 ~200ms，缩短会漏闪）",
            cam.contains("FLASH_SYNC_SHUTTER_NS = 100_000_000L") &&
                cam.contains("baseShutterNs = FLASH_SYNC_SHUTTER_NS"))
        assertTrue("细档范围必须钳在 ±3 EV", cam.contains("ev.coerceIn(-3f, 3f)"))
    }

    @Test
    fun `半档能读到 5 且随帧写进 EXIF 快照`() {
        val vf = read("java/com/filmcam/app/ui/ViewfinderFrameView.kt") ?: return
        val lcd = read("java/com/filmcam/app/ui/T3LcdView.kt") ?: return
        val meta = read("java/com/filmcam/app/film/ShotMeta.kt") ?: return
        assertTrue("取景器 EV 指示要能显示小数", vf.contains("val evSteps: Float = 0f") &&
            vf.contains("%.1f"))
        assertTrue("机顶 LCD 半档要有点标记", lcd.contains("abs - absI >= 0.5f"))
        assertTrue("细档补偿要随帧落盘（冲洗时并进 EXIF UserComment）",
            meta.contains("val flashCompEv: Float = 0f") &&
                meta.contains("put(\"fev\"") &&
                meta.contains("o.optDouble(\"fev\", 0.0)"))
    }

    @Test
    fun `拍摄同款能还原外闪细档补偿`() {
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val viewer = read("java/com/filmcam/app/PhotoViewerActivity.kt") ?: return
        assertTrue("同款要把细档写进 intent",
            viewer.contains("putExtra(MainActivity.EXTRA_SAME_FLASH_EV, shot.flashCompEv)"))
        assertTrue("取景器要接收并下发细档",
            main.contains("getFloatExtra(EXTRA_SAME_FLASH_EV, Float.NaN)") &&
                main.contains("applyFlashCompUi(persist = false)"))
        assertTrue("同款 extras 清理表要包含新键",
            main.contains("EXTRA_SAME_FLASH_EV, EXTRA_SAME_STYLE"))
    }

    @Test
    fun `半档补偿真的把固定曝光 ISO 压下来而不动快门`() {
        // S25U 量级：ISO 12~3200、快门 8~150ms；基准 57×4 = 228 @100ms
        val caps = Triple(12, 3200, 100_000_000L)
        fun solve(compEv: Float) = FlashExposure.solve(
            baseIso = 57f * 4f * 2f.pow(compEv),
            baseShutterNs = 100_000_000L,
            minIso = caps.first,
            maxIso = caps.second,
            minShutterNs = 8_000_000L,
            maxShutterNs = 150_000_000L,
        )
        val ev0 = solve(0f)
        val evHalf = solve(-0.5f)
        val evOneHalf = solve(-1.5f)
        val ev3 = solve(-3f)
        assertTrue("EV 0 应落在真机实测的 ISO 228 附近", kotlin.math.abs(ev0.iso - 228) <= 2)
        assertTrue("EV −3 应落在真机实测的 ISO 29 附近", kotlin.math.abs(ev3.iso - 29) <= 2)
        assertTrue("每半档都要真的改变 ISO", ev0.iso > evHalf.iso && evHalf.iso > evOneHalf.iso &&
            evOneHalf.iso > ev3.iso)
        assertTrue("−1.5 EV 必须落在过曝(228)与死黑(29)之间", evOneHalf.iso in 30..227)
        assertTrue("细档补偿期间快门恒 100ms（同步窗口不动）",
            ev0.shutterNs == 100_000_000L && evHalf.shutterNs == 100_000_000L &&
                evOneHalf.shutterNs == 100_000_000L && ev3.shutterNs == 100_000_000L)
    }
}
