package com.filmcam.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * 外闪三条调研路的契约（全部默认关闭，热调开启，零线上影响）。
 *
 * 背景：A2 氙气闪光的「BLE 触发 → 相机真正曝光」延迟在本机抖 49~565ms，
 * 编排/TTL/多帧覆盖/扫 offset 四条路都败在时序。全面复盘后挖出三条被
 * 遗漏的思路，做成独立热调开关，逐条真机验证，互不干扰：
 *  ① flash_stages.txt=3：复位到氙气档 + 三段式 Take（11-19 实证「点复位后每次氙气」）
 *  ② flash_ready_gate.txt=1：FEC8 回电状态门控（用硬件状态替代抖动预测）
 *  ③ flash_yuv_burst.txt=N：YUV 30fps 环形缓冲挑帧（复刻 iPhone 多帧合成）
 */
class ExternalFlashResearchContractTest {

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

    private fun cam(): String = read("java/com/filmcam/app/camera/Camera2Controller.kt")
        ?: throw AssertionError("Camera2Controller.kt 未找到")
    private fun driver(): String = read("java/com/filmcam/app/flash/LearnedProfileDriver.kt")
        ?: throw AssertionError("LearnedProfileDriver.kt 未找到")
    private fun iface(): String = read("java/com/filmcam/app/flash/ExternalFlashDriver.kt")
        ?: throw AssertionError("ExternalFlashDriver.kt 未找到")

    // ---------- 路①：复位 + 三段式 Take ----------

    @Test
    fun `路1三段式热调默认关闭且只在显式2到4时覆盖profile`() {
        val src = cam()
        assertTrue("必须定义 STAGES_HOTFILE", src.contains("STAGES_HOTFILE"))
        assertTrue("热调文件名必须是 flash_stages.txt", src.contains("flash_stages.txt"))
        assertTrue("缺文件默认单发(=1)", src.contains("if (!f.exists()) 1"))
        assertTrue("段数必须钳 1~4", src.contains("coerceIn(1, 4)"))
        assertTrue("只在显式开三段式时覆盖 profile stages",
            src.contains("if (researchStages >= 2)") && src.contains("flashStages = researchStages"))
        assertTrue("三段式启动前必须复位氙气档回调",
            src.contains("onBeforeStagedFlash?.invoke()"))
        assertTrue("复位后要有沉降时间再发第1发", src.contains("RESET_SETTLE_MS"))
        assertTrue("第2发延迟含沉降偏移", src.contains("stage1Delay + STAGE2_MS"))
        assertTrue("第3发延迟含沉降偏移", src.contains("stage1Delay + STAGE3_MS"))
    }

    @Test
    fun `路1不得改动A2_TRIGGER_STAGES契约常量`() {
        // 契约锁死 A2_TRIGGER_STAGES=1（FlashPairContractTest）。路①靠热调在
        // Camera2Controller 层覆盖 flashStages，绝不能直接改这个常量。
        val c = cam()
        assertTrue("Camera2Controller 不得直接写 A2_TRIGGER_STAGES 赋值",
            !c.contains("A2_TRIGGER_STAGES = 3"))
    }

    // ---------- 路②：FEC8 回电门控 ----------

    @Test
    fun `路2接口与驱动必须暴露回电就绪状态`() {
        val i = iface()
        assertTrue("接口必须有 isFlashReady()", i.contains("fun isFlashReady(): Boolean?"))
        val d = driver()
        assertTrue("驱动必须实现 isFlashReady()", d.contains("override fun isFlashReady(): Boolean?"))
        assertTrue("驱动必须有回电状态字段", d.contains("flashReadyState"))
    }

    @Test
    fun `路2必须订阅FEC8并解析回电位`() {
        val d = driver()
        assertTrue("必须定义 FEC8 UUID 常量", d.contains("RECYCLE_NOTIFY_UUID"))
        assertTrue("UUID 必须是 fec8", d.contains("0000fec8"))
        assertTrue("必须订阅回电状态特征", d.contains("subscribeNotify(g, recycleChar"))
        assertTrue("只解析 A2 灯", d.contains("isA2Device(profile.displayName, profile.address)"))
        assertTrue("识别 F0 E0 帧头", d.contains("== 0xF0") && d.contains("== 0xE0"))
        assertTrue("value[3]==1 判回电中", d.contains("value[3].toInt() and 0xFF) == 1"))
    }

    @Test
    fun `路2门控默认关闭且有超时兜底`() {
        val src = cam()
        assertTrue("必须定义 READY_GATE_HOTFILE", src.contains("READY_GATE_HOTFILE"))
        assertTrue("热调文件名必须是 flash_ready_gate.txt", src.contains("flash_ready_gate.txt"))
        assertTrue("缺文件默认关闭", src.contains("f.exists() && f.readText().substringBefore(\"//\").trim() == \"1\""))
        assertTrue("门控只在外闪档生效", src.contains("readyGate && externalFlashSync"))
        assertTrue("必须有超时兜底常量", src.contains("READY_GATE_MAX_WAIT_MS"))
        assertTrue("超时必须放行(防卡死)", src.contains("elapsed >= READY_GATE_MAX_WAIT_MS"))
        assertTrue("回电中轮询等待", src.contains("postDelayed({ gateOnFlashReady"))
    }

    // ---------- 路③：YUV 环形缓冲挑帧 ----------

    @Test
    fun `路3热调默认关闭且reader只在开启时创建`() {
        val src = cam()
        assertTrue("必须定义 YUV_BURST_HOTFILE", src.contains("YUV_BURST_HOTFILE"))
        assertTrue("热调文件名必须是 flash_yuv_burst.txt", src.contains("flash_yuv_burst.txt"))
        assertTrue("缺文件默认关闭(=0)", src.contains("if (!f.exists()) 0 else"))
        assertTrue("帧数钳 0~90", src.contains("coerceIn(0, 90)"))
        assertTrue("reader 只在开启时创建", src.contains("if (yuvFramesAtOpen >= 2)"))
        assertTrue("用 YUV_420_888 格式", src.contains("ImageFormat.YUV_420_888"))
    }

    @Test
    fun `路3抓到闪光时用YUV帧替换底片且一次快门只交一次`() {
        val src = cam()
        assertTrue("必须有环形缓冲结构", src.contains("class YuvProbeFrame"))
        assertTrue("必须有 YUV 帧处理函数", src.contains("fun onYuvFrame(image: Image)"))
        assertTrue("必须有挑帧函数", src.contains("fun pickYuvFlashFrame()"))
        assertTrue("峰值判定日志保留", src.contains("闪光被YUV捕获"))
        // 唯一交付出口 + 防重守卫：保证一次快门只交一张底片
        assertTrue("必须有统一交付出口", src.contains("private fun deliverFinalJpeg"))
        assertTrue("交付出口必须有防重守卫",
            src.contains("if (yuvDeliveryGuard && yuvBurstFrames >= 2) return"))
        assertTrue("抓到闪光用 YUV 帧替换交付",
            src.contains("用 YUV 闪光帧替换底片"))
        assertTrue("没抓到回退相机原帧", src.contains("回退相机原帧交付"))
        assertTrue("JPEG 到货时暂存等 YUV 判定",
            src.contains("相机帧暂存") && src.contains("yuvPendingJpeg = bytes"))
        assertTrue("有超时兜底防丢卷", src.contains("YUV 判定超时，回退相机原帧交付"))
        // 不得在 pickYuvFlashFrame 里直接调 onPhotoCaptured（必须经 deliverFinalJpeg）
        val pickBody = src.substringAfter("private fun pickYuvFlashFrame()")
            .substringBefore("// 复刻康泰时 T3")
        assertFalse("不得在 pickYuvFlashFrame 里直接调 onPhotoCaptured",
            pickBody.contains("onPhotoCaptured"))
    }

    @Test
    fun `路3亮度修正EV热调默认关闭且只压Y平面`() {
        val src = cam()
        assertTrue("必须定义 YUV_EV_HOTFILE", src.contains("YUV_EV_HOTFILE"))
        assertTrue("热调文件名必须是 flash_yuv_ev.txt", src.contains("flash_yuv_ev.txt"))
        assertTrue("缺文件默认0", src.contains("if (!f.exists()) 0f"))
        assertTrue("EV 钳 -4~+2", src.contains("coerceIn(-4f, 2f)"))
        assertTrue("增益=2^EV", src.contains("Math.pow(2.0, yuvEvComp.toDouble())"))
        assertTrue("只压 Y 平面不动 UV", src.contains("val yLen = w * h"))
        assertTrue("钳位 0..255", src.contains("coerceIn(0, 255).toByte()"))
    }

    @Test
    fun `固定闪光功率热调默认关闭且独立于TTL`() {
        val src = cam()
        assertTrue("必须定义 FLASH_PWR_HOTFILE", src.contains("FLASH_PWR_HOTFILE"))
        assertTrue("热调文件名必须是 flash_pwr.txt", src.contains("flash_pwr.txt"))
        assertTrue("缺文件默认-1不干预", src.contains("if (!f.exists()) -1"))
        assertTrue("功率钳 0~70", src.contains("it.coerceIn(0, 70)"))
        assertTrue("固定功率独立于TTL下发",
            src.contains("if (fixedPowerArg >= 0 && externalFlashSync)"))
        assertTrue("走 onFlashPowerRequest 通道", src.contains("onFlashPowerRequest?.invoke(fixedPowerArg)"))
    }

    @Test
    fun `路3生命周期必须释放YUV reader清缓冲`() {
        val src = cam()
        assertTrue("stop 时必须关 YUV reader", src.contains("yuvImageReader?.close()"))
        assertTrue("stop 时必须停用环形缓冲", src.contains("yuvRingActive = false"))
    }
}
