package com.filmcam.app.flash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc86 驱动注册表契约：
 *  1. 纯逻辑：按广播名 / MAC 匹配、认不出回 null、default 兜底 = FotorGear；
 *  2. 源码契约：FlashController 不得再直接 new 具体品牌驱动（必须走注册表）、
 *     FotorGearFlash 必须实现 ExternalFlashDriver、死掉的 mode 字段不得复活。
 *
 * 这些锁的是「加新品牌不改业务代码」的架构承诺 —— 破坏任何一条，新品牌
 * 就得改 FlashController / MainActivity，通用化即失败。
 */
class ExternalFlashRegistryTest {

    /** 测试用假驱动（驱动接口零 Android 依赖，JVM 可直接构造）。 */
    private class DummyDriver : ExternalFlashDriver {
        override var onStatusChange: ((String) -> Unit)? = null
        override fun startScan() {}
        override fun trigger() {}
        override fun release() {}
    }

    @Test
    fun `内置 FotorGear 条目按广播名匹配`() {
        ExternalFlashRegistry.resetForTest()
        val e = ExternalFlashRegistry.pick("FG-COB-FLASH", null)
        assertNotNull(e)
        assertEquals("fotorgear", e!!.brandId)
        assertEquals(FlashCapability.DUMB, e.capability)
        // 前缀匹配：同款灯固件广播名可能带后缀
        assertEquals("fotorgear", ExternalFlashRegistry.pick("fg-cob-flash ", null)!!.brandId)
    }

    @Test
    fun `内置 FotorGear 条目按已知 MAC 匹配 且大小写不敏感`() {
        ExternalFlashRegistry.resetForTest()
        assertEquals("fotorgear", ExternalFlashRegistry.pick(null, "DA:BA:CD:E2:BE:B0")!!.brandId)
        assertEquals("fotorgear", ExternalFlashRegistry.pick(null, "da:ba:cd:e2:be:b0")!!.brandId)
    }

    @Test
    fun `认不出的设备返回 null 由调用方决定兜底`() {
        ExternalFlashRegistry.resetForTest()
        assertNull(ExternalFlashRegistry.pick("Godox-XYZ", "AA:BB:CC:DD:EE:FF"))
        assertNull(ExternalFlashRegistry.pick(null, null))
        // 兜底 = 第一个注册的内置驱动（现状 FotorGear，兼容按已知 MAC 直连）
        assertEquals("fotorgear", ExternalFlashRegistry.default().brandId)
    }

    @Test
    fun `注册新品牌即生效 优先于名字前缀互不干扰`() {
        ExternalFlashRegistry.resetForTest()
        // 先触发内置注册（保证 default 兜底顺序稳定：fotorgear 永远第一）
        assertEquals("fotorgear", ExternalFlashRegistry.default().brandId)
        ExternalFlashRegistry.register(
            ExternalFlashRegistry.Entry(
                brandId = "testbrand",
                displayName = "Test Brand",
                capability = FlashCapability.TTL_READY,
                deviceNames = setOf("TB-LIGHT"),
                knownAddresses = setOf("11:22:33:44:55:66"),
                factory = { DummyDriver() }
            )
        )
        assertEquals("testbrand", ExternalFlashRegistry.pick("TB-LIGHT", null)!!.brandId)
        assertEquals("testbrand", ExternalFlashRegistry.pick(null, "11:22:33:44:55:66")!!.brandId)
        assertEquals("fotorgear", ExternalFlashRegistry.pick("FG-COB-FLASH", null)!!.brandId)
        // default 仍是第一个注册的 FotorGear（兜底行为不随新增品牌漂移）
        assertEquals("fotorgear", ExternalFlashRegistry.default().brandId)
        ExternalFlashRegistry.resetForTest()
    }

    @Test
    fun `同 brandId 重复注册以后者覆盖`() {
        ExternalFlashRegistry.resetForTest()
        ExternalFlashRegistry.register(
            ExternalFlashRegistry.Entry("dup", "A", FlashCapability.DUMB, factory = { DummyDriver() })
        )
        ExternalFlashRegistry.register(
            ExternalFlashRegistry.Entry("dup", "B", FlashCapability.POWER, factory = { DummyDriver() })
        )
        val e = ExternalFlashRegistry.pick(null, null)
        assertNull("无名字无地址认不出 dup（它没有匹配表）", e)
        // 注册表里 dup 只剩一个（后注册的 B）
        ExternalFlashRegistry.resetForTest()
    }

    @Test
    fun `能力分级三档齐备 供后续功率与 TTL 通道用`() {
        assertEquals(3, FlashCapability.values().size)
        assertTrue(FlashCapability.valueOf("DUMB") == FlashCapability.DUMB)
        assertTrue(FlashCapability.valueOf("POWER") == FlashCapability.POWER)
        assertTrue(FlashCapability.valueOf("TTL_READY") == FlashCapability.TTL_READY)
    }

    // ---- 第二层：自学习档案条目（纯逻辑，无需 Context） ----

    private fun learnedProfile(
        address: String,
        name: String = "MY-LIGHT",
        pairedAt: Long = 100L,
        trigger: String = "01"
    ) = FlashProfile(
        brandId = "learned",
        displayName = name,
        deviceName = name,
        address = address,
        serviceUuid = "0000fff0-0000-1000-8000-00805f9b34fb",
        writeCharUuid = "0000fff1-0000-1000-8000-00805f9b34fb",
        triggerHex = trigger,
        pairedAt = pairedAt
    )

    @Test
    fun `同步档案后 learned 条目可按名字与 MAC 认出`() {
        ExternalFlashRegistry.resetForTest()
        ExternalFlashRegistry.syncLearned(listOf(learnedProfile("AA:00:00:00:00:01")))
        assertEquals(
            "learned:AA:00:00:00:00:01",
            ExternalFlashRegistry.pick("MY-LIGHT", null)!!.brandId
        )
        assertEquals(
            "learned:AA:00:00:00:00:01",
            ExternalFlashRegistry.pick(null, "aa:00:00:00:00:01")!!.brandId
        )
        ExternalFlashRegistry.resetForTest()
    }

    @Test
    fun `preferredEntry 优先后学习档案 无档案时兜底 FotorGear`() {
        ExternalFlashRegistry.resetForTest()
        // 无档案：兜底内置 FotorGear
        assertEquals("fotorgear", ExternalFlashRegistry.preferredEntry().brandId)
        // 有档案：learned 优先
        ExternalFlashRegistry.syncLearned(listOf(learnedProfile("AA:00:00:00:00:01")))
        assertEquals("learned:AA:00:00:00:00:01", ExternalFlashRegistry.preferredEntry().brandId)
        ExternalFlashRegistry.resetForTest()
    }

    @Test
    fun `多条档案取最近配对的 删除后立即生效`() {
        ExternalFlashRegistry.resetForTest()
        ExternalFlashRegistry.syncLearned(
            listOf(
                learnedProfile("AA:00:00:00:00:01", pairedAt = 100L),
                learnedProfile("AA:00:00:00:00:02", pairedAt = 200L),
                learnedProfile("AA:00:00:00:00:03", pairedAt = 50L)
            )
        )
        // 最近配对 = pairedAt 最大
        assertEquals("learned:AA:00:00:00:00:02", ExternalFlashRegistry.preferredEntry().brandId)
        // 模拟删除 02 后重新同步：剩下最近的是 01
        ExternalFlashRegistry.syncLearned(
            listOf(
                learnedProfile("AA:00:00:00:00:01", pairedAt = 100L),
                learnedProfile("AA:00:00:00:00:03", pairedAt = 50L)
            )
        )
        assertEquals("learned:AA:00:00:00:00:01", ExternalFlashRegistry.preferredEntry().brandId)
        // 同步空列表 = 全删，兜底回 FotorGear
        ExternalFlashRegistry.syncLearned(emptyList())
        assertEquals("fotorgear", ExternalFlashRegistry.preferredEntry().brandId)
        ExternalFlashRegistry.resetForTest()
    }

    // ---- 源码级契约（CRLF 文件，只用单行断言） ----

    private fun read(rel: String): String? {
        var dir = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val main = java.io.File(dir, "app/src/main")
            if (main.isDirectory) {
                val f = java.io.File(main, rel)
                if (f.isFile) return f.readText()
            }
            dir = dir.parentFile ?: return null
        }
        return null
    }

    @Test
    fun `FlashController 不再硬编码具体品牌驱动`() {
        val s = read("java/com/filmcam/app/flash/FlashController.kt") ?: return
        assertTrue(
            "EXTERNAL 档必须经注册表构造驱动（preferred 优先学习档案）",
            Regex("ExternalFlashRegistry\\.createPreferred\\(context\\)").containsMatchIn(s)
        )
        assertTrue(
            "建会话前必须先同步自学习档案",
            Regex("syncLearned\\(\\s*FlashProfileStore\\(context\\)\\.list\\(\\)\\s*\\)").containsMatchIn(s)
        )
        assertTrue(
            "不许再直接 new FotorGearFlash（代码行里不得出现赋值构造）",
            !Regex("bleFlash\\s*=\\s*FotorGearFlash\\(").containsMatchIn(s) &&
                !Regex("[^/*\\s]\\s*=\\s*FotorGearFlash\\(context\\)").containsMatchIn(s)
        )
        assertTrue(
            "驱动字段类型必须是接口而非具体类",
            Regex("bleFlash\\s*:\\s*ExternalFlashDriver\\?").containsMatchIn(s)
        )
    }

    @Test
    fun `FotorGearFlash 实现驱动接口 且 mode 死字段不复活`() {
        val s = read("java/com/filmcam/app/flash/FotorGearFlash.kt") ?: return
        assertTrue(
            "必须实现 ExternalFlashDriver",
            Regex("class FotorGearFlash\\([\\s\\S]{0,120}\\)\\s*:\\s*ExternalFlashDriver").containsMatchIn(s)
        )
        assertTrue(
            "mode 归 FlashController 管，驱动里不得再出现 override var mode",
            !Regex("override\\s+var\\s+mode").containsMatchIn(s)
        )
    }

    @Test
    fun `注册表内置条目与驱动实现解耦到只剩工厂一处引用`() {
        val reg = read("java/com/filmcam/app/flash/ExternalFlashRegistry.kt") ?: return
        assertTrue(
            "内置 FotorGear 条目：名字 / 地址 / 能力档三要素齐备",
            Regex("brandId = \"fotorgear\"").containsMatchIn(reg) &&
                Regex("deviceNames = setOf\\(\"FG-COB-FLASH\"\\)").containsMatchIn(reg) &&
                Regex("knownAddresses = setOf\\(\"DA:BA:CD:E2:BE:B0\"\\)").containsMatchIn(reg) &&
                Regex("capability = FlashCapability.DUMB").containsMatchIn(reg)
        )
        // 工厂是唯一允许出现具体驱动类的地方
        val count = Regex("FotorGearFlash\\(").findAll(reg).count()
        assertEquals("注册表对具体驱动的引用只允许出现在工厂一处", 1, count)
    }
}
