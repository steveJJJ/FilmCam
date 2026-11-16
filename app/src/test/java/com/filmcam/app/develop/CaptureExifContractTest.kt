package com.filmcam.app.develop

import com.filmcam.app.film.CaptureExifFormat
import com.filmcam.app.film.ShotMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 成片 EXIF 的「实拍参数」契约 + 定量校验。
 *
 * 背景：EXIF 里原有的 ISO 400 是**胶卷模拟感光度**，不是传感器实拍值；
 * 外闪过曝复盘需要看到真实 ISO / 快门 / 用灯身份。
 * AndroidX ExifInterface 1.3.7 把 ISOSpeedRatings 与 PhotographicSensitivity
 * 合并成同一个 tag 0x8827（不能双写），所以用户拍板后 0x8827 直接写真实
 * 传感器 ISO（系统相册读它），0x8833 留作扩展副本。
 *
 * ⚠️ 不在 JVM 单测里真跑 ContaxExif.write：ExifInterface.setAttribute 依赖
 * android.util.Pair 的构造函数，mockable android.jar 把它 stub 成空实现
 * （guess.first == null → NPE）。真机成片用 pull 后解析 EXIF 校验。
 */
class CaptureExifContractTest {

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
    fun `ShotMeta 实拍字段 round-trip 且老帧兼容`() {
        val shot = ShotMeta(
            flash = "EXTERNAL",
            ev = 0,
            flashCompEv = -1.5f,
            sensorIso = 81,
            sensorShutterSec = 0.1f,
            flashDevice = "FG-COB-FLASH",
            flashAddress = "BE:B0:00:00:00:01",
            flashChannel = "0000ffe1-0000-1000-8000-00805f9b34fb",
            flashTriggerHex = "05 01",
            aperture = 1.8f,
            focalMm = 4.3f,
        )
        val back = ShotMeta.fromJson(shot.toJson())
        assertEquals(shot, back)

        // 升级前的老帧没有这些键，全部回落默认值，不能把冲洗整卷搞崩
        val old = ShotMeta.fromJson("{\"flash\":\"BUILT_IN\",\"wb\":1,\"ev\":2,\"sec\":0.5}")!!
        assertEquals(0, old.sensorIso)
        assertEquals(0f, old.sensorShutterSec, 0f)
        assertEquals("", old.flashDevice)
        assertEquals("", old.flashTriggerHex)
        assertEquals(0.5f, old.shutterSec, 0f)   // 慢门秒数与实拍快门刻意分开
        assertEquals(0f, old.aperture, 0f)
        assertEquals(0f, old.focalMm, 0f)
    }

    @Test
    fun `EXIF rational 格式定量：兼容集合传小数 其余传 num-den`() {
        // ExposureTime / FNumber 在 sTagSetForCompatibility：传 "1/10" 会被
        // Double.parseDouble 抛异常后静默丢弃（真机成片里快门就是这样没的）。
        assertEquals("0.100000", CaptureExifFormat.exposureTimeValue(0.1f))
        assertEquals("2.500000", CaptureExifFormat.exposureTimeValue(2.5f))
        assertEquals("0", CaptureExifFormat.exposureTimeValue(0f))
        assertEquals("1.80", CaptureExifFormat.apertureValue(1.8f))
        // FocalLength / ExposureBiasValue 不在兼容集合，必须 num/den
        assertEquals("43/10", CaptureExifFormat.focalLengthRational(4.3f))
        assertEquals("-3/2", CaptureExifFormat.evRational(-1.5f))
        assertEquals("-6/2", CaptureExifFormat.evRational(-3f))

        assertEquals("1/10", CaptureExifFormat.shutterLabel("1/10"))
        assertEquals("1/10", CaptureExifFormat.shutterLabel("0.1"))
        assertEquals("2.0s", CaptureExifFormat.shutterLabel("200/100"))
        assertEquals("4.3", CaptureExifFormat.decimal("43/10"))
        assertEquals("2.8", CaptureExifFormat.decimal("28000/10000"))
        assertEquals("2.8", CaptureExifFormat.decimal("2.8"))
        assertEquals("", CaptureExifFormat.decimal(null))
        assertEquals("FFE1", CaptureExifFormat.shortUuid("0000ffe1-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun `ContaxExif 标准字段全部写真实参数`() {
        val src = read("java/com/filmcam/app/develop/ContaxExif.kt") ?: return
        assertTrue("系统相册读的 ISOSpeedRatings 要写真实传感器 ISO",
            src.contains("TAG_ISO_SPEED_RATINGS, realIso.toString()") &&
                src.contains("val realIso = shot?.sensorIso ?: 0"))
        assertTrue("模拟胶卷 ISO 只在拿不到实拍值时回落",
            src.contains("} else if (iso != null && iso > 0) {"))
        assertTrue("独立 ISOSpeed(0x8833) 也写真实 ISO",
            src.contains("ExifInterface.TAG_ISO_SPEED, s.sensorIso.toString()"))
        assertTrue("快门必须传小数（兼容集合会 Double.parseDouble）",
            src.contains("ExifInterface.TAG_EXPOSURE_TIME") &&
                src.contains("CaptureExifFormat.exposureTimeValue(s.sensorShutterSec)"))
        assertFalse("绝不能回退成 1/10 这种 rational 字符串",
            src.contains("CaptureExifFormat.shutterRational"))
        assertTrue("焦距用 rational、光圈用小数，都是真实值",
            src.contains("CaptureExifFormat.focalLengthRational(realFocal)") &&
                src.contains("CaptureExifFormat.apertureValue(realAperture)"))
        assertTrue("闪光状态写标准 Flash", src.contains("ExifInterface.TAG_FLASH"))
        assertTrue("曝光补偿写标准 ExposureBiasValue",
            src.contains("ExifInterface.TAG_EXPOSURE_BIAS_VALUE") &&
                src.contains("CaptureExifFormat.evRational"))
        assertTrue("EXT 档补偿取细档而不是整数 AE 补偿",
            src.contains("s.flashCompEv") && src.contains("s.ev.toFloat()"))
        assertFalse("不能写 PhotographicSensitivity（与 ISOSpeedRatings 同为 0x8827）",
            src.contains("TAG_PHOTOGRAPHIC_SENSITIVITY"))
        assertTrue("参数必须来自随帧快照", src.contains("ShotMeta.fromJson(shotJson)"))
    }

    @Test
    fun `相机层随帧记录真实曝光 EXT 用请求值 AE 用 CaptureResult`() {
        val src = read("java/com/filmcam/app/camera/Camera2Controller.kt") ?: return
        assertTrue("暴露只读实拍参数", src.contains("var lastCaptureIso: Int") &&
            src.contains("var lastCaptureShutterNs: Long"))
        assertTrue("EXT 手写曝光直接写入（早于 JPEG 到货）",
            src.contains("lastCaptureIso = solved.iso") &&
                src.contains("lastCaptureShutterNs = solved.shutterNs"))
        assertTrue("常规 AE 档由 TotalCaptureResult 回填",
            src.contains("?.takeIf { it > 0 }?.let { lastCaptureIso = it }") &&
                src.contains("?.takeIf { it > 0L }?.let { lastCaptureShutterNs = it }"))
        assertTrue("真实光圈/焦距：静态能力打底、拍照结果覆盖",
            src.contains("LENS_INFO_AVAILABLE_FOCAL_LENGTHS") &&
                src.contains("CaptureResult.LENS_APERTURE") &&
                src.contains("CaptureResult.LENS_FOCAL_LENGTH"))
    }

    @Test
    fun `落卷时补齐真值并刻下外闪灯档案`() {
        val src = read("java/com/filmcam/app/MainActivity.kt") ?: return
        assertTrue("落盘必须走合并入口", src.contains("shotJson = shotJsonWithCapture()"))
        assertTrue("合并真实 ISO 与快门", src.contains("cameraController?.lastCaptureIso") &&
            src.contains("ns / 1_000_000_000f"))
        assertTrue("真实光圈/焦距随帧落盘",
            src.contains("cameraController?.lastCaptureAperture") &&
                src.contains("cameraController?.lastCaptureFocalMm"))
        assertTrue("外闪灯信息来自注册表 + 档案",
            src.contains("ExternalFlashRegistry.preferredEntry()") &&
                src.contains("FlashProfileStore(this).list()"))
        assertTrue("灯名 / MAC / 通道 / 触发帧四项都随帧落盘",
            listOf("flashDevice =", "flashAddress =", "flashChannel =", "flashTriggerHex =")
                .all { src.contains(it) })
    }

    @Test
    fun `照片信息面板读回标准字段并显示实拍参数`() {
        val reader = read("java/com/filmcam/app/develop/ExifReader.kt") ?: return
        assertTrue("读回 ISOSpeed / ExposureTime / Flash / Bias",
            listOf("TAG_ISO_SPEED)", "TAG_EXPOSURE_TIME)", "TAG_FLASH)", "TAG_EXPOSURE_BIAS_VALUE)")
                .all { reader.contains(it) })
        assertTrue("焦距/光圈读回要归约 rational，不能拼出 43/10mm",
            reader.contains("CaptureExifFormat.decimal(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))") &&
                reader.contains("CaptureExifFormat.decimal(exif.getAttribute(ExifInterface.TAG_F_NUMBER))"))

        val viewer = read("java/com/filmcam/app/PhotoViewerActivity.kt") ?: return
        assertTrue("面板要读回格式化后的快门",
            viewer.contains("CaptureExifFormat.shutterLabel(info.exposureTime)"))
        assertTrue("面板要显示外闪灯名 / 通道 / 触发帧 / 补偿",
            listOf("pf_capture_ext_flash", "pf_capture_channel", "pf_capture_trigger", "pf_capture_flash_ev")
                .all { viewer.contains(it) })

        val layout = read("res/layout/activity_photo_viewer.xml") ?: return
        assertTrue("布局要有两行实拍参数",
            layout.contains("android:id=\"@+id/tvCapture\"") &&
                layout.contains("android:id=\"@+id/tvCaptureDetail\""))

        val zh = read("res/values/strings_dev.xml") ?: return
        val en = read("res/values-en/strings_dev.xml") ?: return
        assertTrue("中英文案成对",
            listOf("pf_capture_iso", "pf_capture_shutter", "pf_capture_ext_flash",
                "pf_capture_builtin", "pf_capture_channel", "pf_capture_trigger",
                "pf_capture_flash_ev").all { zh.contains("\"$it\"") && en.contains("\"$it\"") })
    }
}
