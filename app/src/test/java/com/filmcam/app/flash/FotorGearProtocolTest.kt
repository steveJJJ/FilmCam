package com.filmcam.app.flash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FotorGear 03/04 配置帧契约（2026-11-21 极影相机反编译，`tmp/fotorgear_app/`）。
 *
 * 纪律：
 *  - `03/04` 是配置命令（预闪时间 / 快门触发时间，16 位小端 ms），不是触发；
 *    11-06 探测「03 01 / 04 00 点不亮」正是因为它不触发闪光。
 *  - 拍摄链路仍发唯一验证过的 `05 00`（[FlashTriggerCommand.DEFAULT]），任何实验不得移除。
 *  - `07 + tick` 定时触发未验证，本契约锁死「不得混进拍摄链路」。
 *  - 2026-11-21 用户拍板：参数面板走「连上 FG 灯点 LCD 弹层」，不进工程控制台。
 */
class FotorGearProtocolTest {

    @Test
    fun `03 预闪时间帧 16位小端毫秒`() {
        assertArrayEquals(
            byteArrayOf(0x03, 0xC8.toByte(), 0x00),
            FotorGearProtocol.timeFrame(FotorGearProtocol.CMD_PREFLASH_TIME, 200)
        )
        assertArrayEquals(
            byteArrayOf(0x03, 0x00, 0x00),
            FotorGearProtocol.timeFrame(FotorGearProtocol.CMD_PREFLASH_TIME, 0)
        )
    }

    @Test
    fun `04 快门触发时间帧 16位小端毫秒`() {
        assertArrayEquals(
            byteArrayOf(0x04, 0xE8.toByte(), 0x03),
            FotorGearProtocol.timeFrame(FotorGearProtocol.CMD_SHUTTER_TRIGGER_TIME, 1000)
        )
        assertArrayEquals(
            byteArrayOf(0x04, 0x10.toByte(), 0x27.toByte()),
            FotorGearProtocol.timeFrame(FotorGearProtocol.CMD_SHUTTER_TRIGGER_TIME, 10000)
        )
    }

    @Test
    fun `越界钳位到 0-65535 不产生负字节`() {
        assertArrayEquals(
            byteArrayOf(0x03, 0x00, 0x00),
            FotorGearProtocol.timeFrame(FotorGearProtocol.CMD_PREFLASH_TIME, -5)
        )
        assertArrayEquals(
            byteArrayOf(0x03, 0xFF.toByte(), 0xFF.toByte()),
            FotorGearProtocol.timeFrame(FotorGearProtocol.CMD_PREFLASH_TIME, 70000)
        )
    }

    @Test
    fun `拍摄链路触发帧仍是 05 00 不得被 03 04 07 替换`() {
        assertArrayEquals(byteArrayOf(0x05, 0x00), FlashTriggerCommand.DEFAULT)
        val fg = read("java/com/filmcam/app/flash/FotorGearFlash.kt") ?: return
        assertTrue(
            "FG trigger() 必须仍走 readTriggerCommand（内置 05 00 兜底）",
            Regex("override fun trigger\\(\\)[\\s\\S]{0,1400}readTriggerCommand\\(\\)").containsMatchIn(fg)
        )
        assertTrue(
            "FG 拍摄链路不得引入 07 定时触发帧（未验证能力只许弹层手动发）",
            !Regex("override fun trigger\\(\\)[\\s\\S]{0,1400}0x07").containsMatchIn(fg)
        )
    }

    @Test
    fun `学习档案驱动 命令口回落 触发口`() {
        val d = read("java/com/filmcam/app/flash/LearnedProfileDriver.kt") ?: return
        assertTrue(
            "ready 必须接受触发口（FotorGear 学习档案无独立命令口，11-21 真机未就绪根因）",
            d.contains("get() = gatt != null && (cmdChar != null || writeChar != null)")
        )
        assertTrue(
            "writeCmd 必须回落 writeChar（FG 的 03/04 与触发同根透传口）",
            d.contains("val char = (cmdChar ?: writeChar)")
        )
    }

    @Test
    fun `03 04 面板已移除 协议知识保留`() {
        val fg = read("java/com/filmcam/app/flash/FotorGearFlash.kt") ?: return
        val main = read("java/com/filmcam/app/MainActivity.kt") ?: return
        val zh = read("res/values/strings.xml") ?: return
        val en = read("res/values-en/strings.xml") ?: return
        val lamp = read("java/com/filmcam/app/flash/FlashLampControl.kt") ?: return
        val drv = read("java/com/filmcam/app/flash/LearnedProfileDriver.kt") ?: return
        assertTrue(
            "03/04 对拍摄出光无作用（2026-11-21 用户拍板）：面板 UI 必须移除，不得回挂",
            read("res/layout/dialog_fg_settings.xml") == null &&
                read("java/com/filmcam/app/flash/FgSettings.kt") == null &&
                !main.contains("maybeShowFgSettings") &&
                !main.contains("showFgSettings") &&
                !zh.contains("fg_set_") && !en.contains("fg_set_") &&
                !lamp.contains("onLampNotify") && !drv.contains("onLampNotify")
        )
        assertTrue(
            "协议组帧知识必须保留（timeFrame + 03/04 常量），未来接入 07+tick 或灯身按钮主从流程时复用",
            lamp.contains("fun timeFrame(cmd: Int, ms: Int): ByteArray") &&
                lamp.contains("CMD_PREFLASH_TIME = 0x03") &&
                lamp.contains("CMD_SHUTTER_TRIGGER_TIME = 0x04") &&
                fg.contains("override fun sendRawFrame")
        )
        assertTrue(
            "学习档案驱动保留 FlashNotify 取证订阅（tag 不变，07+tick 校准调研仍要用）",
            drv.contains("FlashNotify") && drv.contains("setCharacteristicNotification")
        )
    }

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
}
