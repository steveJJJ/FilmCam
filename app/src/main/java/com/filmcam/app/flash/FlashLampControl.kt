package com.filmcam.app.flash

/**
 * 外闪「造型灯」控制通道（品牌无关能力，v2 档案新增）。
 *
 * 2026-11-18 nRF Sniffer 真机抓包破解 Godox A2（详见
 * `tmp/a2_packetlogger/A2-BLE协议破解报告.md`）：A2 有双通道 ——
 * FFF1 文本口（拍照触发 `数字,Test`，档案 triggerHex 已覆盖）+ FEC7
 * 二进制命令口（造型灯 LED / 亮度 / 色温）。此前 LED 帧写入成功但灯无反应，
 * 根因是校验字节算错；CRC8-Maxim 修正后 38/38 帧全中（抓包 ground truth）。
 *
 * 本层零 Android 依赖（沿袭 [FlashTriggerCommand] 的 JVM 单测纪律）：
 * 协议组帧在这里，GATT 写在驱动里（[LearnedProfileDriver] 实现 [FlashLampControl]）。
 */
interface FlashLampControl {

    /** 命令通道是否就绪（连上且找到命令特征）。未就绪时所有操作返回 false。 */
    val ready: Boolean

    /** 造型灯模式开关（内部用：滑杆 0 ↔ 非 0 自动切换，UI 上不暴露）。 */
    fun setLed(on: Boolean): Boolean

    /**
     * 把灯强行拉回「纯闪光」态 —— 给真机复位用。
     *
     * 11-19 事故：`03 01` 会把灯锁进一个只闪 LED 的模式，且**断电重启也解不开**
     * （App 回退无效）。这条按「官方关灯顺序 + 反向监听」重放，是唯一能自救的入口：
     * `06 01`（亮度压到最低）→ `05 00`（退出造型灯）→ `0c 50`（拍摄参数复位）
     * → `03 00`（退出快门监听）。
     */
    fun resetFlashMode(): Boolean

    /** 造型灯亮度 0–100（命令字 0x06；0 = 关灯，走 0x05 00 退出模式）。 */
    fun setBrightnessPercent(pct: Int): Boolean

    /**
     * 造型灯色温 1–100（命令字 0x07，内部映射到官方 0–32 档）。
     * 100 = 白光，越小越暖 —— 刻度照抄官方 App，不是开尔文。
     */
    fun setColorTemp(pos: Int): Boolean

    /**
     * 闪光输出功率：A1 长帧的功率字节（0 = 1/1，70 = 1/128，每 10 = 一档）。
     *
     * 走的是**另一套帧族**（`F0 A1 07 ...`，见 [A2PowerProtocol]），跟造型灯那套
     * `A0 cmd arg` 短帧不是同一个 opcode 空间。默认返回 false 表示该驱动没有这个能力。
     */
    fun setFlashPowerLevel(powerByte: Int): Boolean = false

    /** 原样下发一帧到命令口（协议验证 / 调试用）。 */
    fun sendRawFrame(frame: ByteArray): Boolean = false

}

/**
 * FotorGear COB 配置帧组帧（FFE0/FFE1 透传口，纯 Kotlin 零 Android 依赖）。
 *
 * 2026-11-21 从极影相机（com.shenzhenfotor.fotor v5.0）反编译还原
 * （`tmp/fotorgear_app/`，smali `lf.3.smali` / `cq.3.smali`）：
 *  - `03 <lo> <hi>`：预闪时间，16 位小端毫秒（UI "before" / 预闪光时间）
 *  - `04 <lo> <hi>`：快门触发时间，16 位小端毫秒（UI "after" / 快门触发时间）
 *  - `05 00` / `05 01`：简单触发（唯一真机验证过的拍摄路径，**不在这里动**）
 *  - `07` + 8 字节小端 tick：定时触发（32.768kHz 时钟，极影主路径；未验证，暂不接入）
 *
 * ⚠️ 03/04 是**配置命令**：11-06 探测时「03 01 / 04 00 点不亮」正是因为它们
 * 不触发闪光。下发后对 05 xx 出光行为的影响尚未定量 —— 只允许从工程控制台
 * 手动下发，拍摄链路一字不动。
 */
object FotorGearProtocol {
    /** 预闪时间（ms）：灯身先闪的补光时长。 */
    const val CMD_PREFLASH_TIME = 0x03

    /** 快门触发时间（ms）：极影 UI 的 "Shutter trigger time"。 */
    const val CMD_SHUTTER_TRIGGER_TIME = 0x04

    /** 组帧：`cmd + 16 位小端毫秒`，钳位到 0–65535。 */
    fun timeFrame(cmd: Int, ms: Int): ByteArray {
        val v = ms.coerceIn(0, 0xFFFF)
        return byteArrayOf(
            cmd.toByte(),
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte()
        )
    }
}

/** CRC8-Dallas/Maxim：反射多项式 0x8c，初值 0。 */
object CRC8Maxim {
    fun compute(data: ByteArray): Int {
        var c = 0
        for (b in data) {
            c = c xor (b.toInt() and 0xFF)
            repeat(8) {
                c = if (c and 1 != 0) (c ushr 1) xor 0x8c else c ushr 1
            }
        }
        return c and 0xFF
    }
}

/**
 * A2 命令帧组帧（FEC7 命令口，帧格式 `a0 cmd arg crc8` 4 字节）。
 * 命令字节与 CRC 来自 11-18 真机抓包（38/38 验证）。
 *
 * **语义按官方 App 的写入时序还原（11-19 第二次纠正，别再按单帧猜）**：
 *  `tmp/a2_snoop/a2_follow_live2.pcapng` 里三条铁律 ——
 *  1. 开造型灯永远是 `05 01` **紧跟 `06 = 100`**（58.98s / 68.37s / 82.29s 三次一致）
 *  2. `06` 拖到最低值 1 **紧跟 `05 00` 关灯**（63.65s / 81.23s）
 *  3. `07` 在 70–79s 单独扫 0→32→0→32，期间 `06` 全程停在 100 ⇒ 两根不同滑杆
 *  4. `0c` 只在**每次拍照前**出现（50/50/50/35/20），是拍摄侧参数，不是灯
 * 所以：**06 = 造型灯亮度（0 = 关），07 = 造型灯色温（0–32，32 = 白光），0c = 拍摄参数**
 * （0c 语义未完全确认，疑似闪光/LED 混合比或输出功率 —— 官方每次拍照前都设成 50，
 * 我们只在连上时恢复 50，绝不让 UI 去动它。11-19 把它压到 0/22 后，灯就退化成只闪 LED。）
 *
 * 命令表：
 *  - `a0 03 01/00` 进入/退出快门监听（连接初始化末步）
 *  - `a0 05 01/00` 造型灯模式开/关（不是用户开关，由滑杆值自动切换）
 *  - `a0 06 v`     造型灯亮度（0x01–0x64）
 *  - `a0 07 v`     造型灯色温档（0x00–0x20，0x20 = 白光）
 *  - `a0 0c v`     拍摄侧参数（官方拍照前设 50；仅用于恢复默认）
 */
object A2LampProtocol {
    const val CMD_SHUTTER_LISTEN = 0x03
    const val CMD_LAMP_MODE = 0x05
    const val CMD_LAMP_BRIGHTNESS = 0x06
    const val CMD_LAMP_CCT = 0x07
    /** 拍摄侧参数（非造型灯）。官方每次拍照前设成 [FLASH_PARAM_DEFAULT]。 */
    const val CMD_FLASH_PARAM = 0x0C

    /** 色温档位上限（官方滑条 0→32，32 = 白光）。 */
    const val CCT_STEP_MAX = 0x20

    /** 官方 App 拍照前固定下发的 0x0c 值，连上时用它把灯恢复成「正常闪光」状态。 */
    const val FLASH_PARAM_DEFAULT = 50

    /** 组一条带 CRC 的命令帧。arg 只取低字节（本协议 arg 全部 ≤ 0x64）。 */
    fun frame(cmd: Int, arg: Int): ByteArray {
        val body = byteArrayOf(0xA0.toByte(), cmd.toByte(), arg.toByte())
        return body + CRC8Maxim.compute(body).toByte()
    }
}

/**
 * 神牛闪光灯「组功率」帧族（A1 长帧）—— 11-19 从官方 `com.linking.godoxflash`
 * v1.3.2 的 `BluetoothSendData.getSingleGroupData()` 逐条还原（smali 级核对）。
 *
 * 帧格式（11 字节，服务 FEC0 / 特征 FEC7，WRITE_TYPE_DEFAULT）：
 * ```
 *  F0 A1 07 <group> <mode> <manualPower> <lampPower> <voice> <lampMode> <autoFec> <crc8>
 * ```
 *  - `group`：A→0x0A … F→0x0F（官方 `getGroupNum()` 把 "A" 映射成十进制 10 再转 hex）
 *  - `mode`：0x00 = TTL 自动、0x01 = M 手动、0x03 = 停用/隐藏
 *  - `manualPower`：`100 - decimalPower`，即 **0 = 1/1、70 = 1/128**（每 10 减一档）
 *    —— 与 pdamsten/flash-control 逆向引闪器得到的功率值域完全一致
 *  - `lampPower`：造型灯亮度 0 / 25(0x19) / 100(0x64)
 *  - `voice`：0/1；`lampMode`：0x00 关、0x01 PROP、0x02 常亮
 *  - `autoFec`：TTL 曝光补偿，单位 0.1EV，正数直填、负数 `0x80 + |n|`
 *    （-1.0EV → 0x8A，与官方 `getTCCommand()` 里的 "8a" 字面量吻合）
 *
 * **TTL 模式下 `manualPower` 官方硬编码 0x32(=50)** —— 这解释了真机抓包里
 * `A0 0C 32` 的 0x32 从哪来：它是 A1 帧 manualPower 字段在 TTL 模式下的固定填充值。
 * A2 短帧 `A0 <cmd> <arg>` 与 A1 长帧共用同一套数值语汇（0x64=100 亮度、0x32=50），
 * 说明两者是同一协议的不同封装。
 *
 * CRC 用 [CRC8Maxim]：官方 `CRC8Util` 用的是标准 CRC-8/MAXIM 查表（首项
 * 0,94,188,226…），与本实现的逐位展开结果一致；并用真机抓到的
 * `A0 05 01 57` / `A0 06 64 58` / `A0 0C 32 B9` / `A0 03 01 FD` 交叉验证通过。
 */
object A2PowerProtocol {
    const val GROUP_A = 0x0A
    const val MODE_TTL = 0x00
    const val MODE_MANUAL = 0x01
    const val MODE_OFF = 0x03

    /** TTL 模式下 manualPower 字段的官方固定值（0x32 = 50）。 */
    const val TTL_POWER_SENTINEL = 0x32

    /** 功率字节：0 = 1/1（满功率），70 = 1/128（A2 最低档）。 */
    const val POWER_FULL = 0
    const val POWER_MIN = 70

    /** TTL 曝光补偿范围：±3.0EV，单位 0.1EV。 */
    const val FEC_LIMIT = 30

    /**
     * 最近一次成功写入的功率字节（-1 = 未知）。
     *
     * 🔴 2026-11-19 真机实测：`byte 70` 打出来是**过曝**，`byte 0` 反而**几无光**，
     * 与官方 godoxflash 那套「0 = 1/1、70 = 1/128」正好相反。所以这个值现在只是
     * 「灯里当前是什么」的取证锚点，**不能直接当档位用**，映射必须靠实拍标定。
     */
    @Volatile var lastSentPowerByte: Int = -1

    fun frame(mode: Int, powerByte: Int, autoFec: Int = 0): ByteArray {
        val fec = if (autoFec >= 0) autoFec else 0x80 - autoFec
        val body = byteArrayOf(
            0xF0.toByte(),
            0xA1.toByte(),
            0x07,
            GROUP_A.toByte(),
            mode.toByte(),
            powerByte.toByte(),
            0x00,               // lampPower：不在功率帧里动造型灯
            0x00,               // voice：静音
            0x00,               // lampMode：造型灯不参与
            fec.toByte()
        )
        return body + CRC8Maxim.compute(body).toByte()
    }

    /** M 手动模式：功率字节 0（1/1）… 70（1/128）。 */
    fun manualFrame(powerByte: Int): ByteArray =
        frame(MODE_MANUAL, powerByte.coerceIn(0, 100))

    /** TTL 模式 + 曝光补偿（0.1EV 为单位，±30）。 */
    fun ttlFrame(fecTenthEv: Int): ByteArray =
        frame(MODE_TTL, TTL_POWER_SENTINEL, fecTenthEv.coerceIn(-FEC_LIMIT, FEC_LIMIT))

    /** 功率字节 → 分母（0 → 1/1，10 → 1/2，70 → 1/128），UI 显示用。 */
    fun denominator(powerByte: Int): Int {
        var den = 1
        repeat(powerByte / 10) { den *= 2 }
        return den
    }
}

/**
 * 官方 `BluetoothSendData.getTCCommand()` 的 14 字节帧（smali 级逐字段还原）：
 * ```
 *  F0 A0 0A FF <声音> <造型灯> <功率Δ> <屏闪> <次数> <赫兹> <屏闪功率> <待机> <步进> <crc8>
 * ```
 * 与 A1 帧的关键差别：**功率字段是增量命令，不是绝对值**，因此可按 0.1EV 步进连续微调
 * —— 这正是 TTL 闭环需要的形态（A1 帧真机实测只表现为开/关两态）。
 *
 * 官方字面量表：`01`=+0.1EV、`81`=−0.1EV、`03`=+0.3EV、`83`=−0.3EV、
 * `0A`=+1.0EV、`8A`=−1.0EV。
 */
object A2TcProtocol {

    /**
     * @param deltaTenthsEv 增量，单位 0.1EV（±1/±3/±10 有效，其余按 0 处理）
     * @param step 官方「all 步进计数」字段，逐帧自增
     */
    fun powerDelta(deltaTenthsEv: Int, step: Int): ByteArray {
        val code = when (deltaTenthsEv) {
            1 -> 0x01
            -1 -> 0x81
            3 -> 0x03
            -3 -> 0x83
            10 -> 0x0A
            -10 -> 0x8A
            else -> 0x00
        }
        val body = byteArrayOf(
            0xF0.toByte(),           // [0] 帧头
            0xA0.toByte(),           // [1]
            0x0A,                    // [2]
            0xFF.toByte(),           // [3] 频道 ff
            0x00,                    // [4] 声音：关
            0x00,                    // [5] 造型灯：关
            code.toByte(),           // [6] 功率增量
            0x00,                    // [7] 屏闪：关
            0x00,                    // [8] 屏闪次数
            0x00,                    // [9] 屏闪赫兹
            0x00,                    // [10] 屏闪功率
            0x00,                    // [11] 待机：关
            (step and 0xFF).toByte() // [12] all 步进计数
        )
        return body + CRC8Maxim.compute(body).toByte()
    }
}
