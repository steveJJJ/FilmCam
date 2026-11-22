package com.filmcam.app.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.graphics.SurfaceTexture
import android.view.Surface
import android.widget.Toast
import app.spektralab.engine.FilmStockMapper
import java.io.File
import java.lang.Exception

/**
 * Camera2 控制器
 * 支持手动 ISO / 快门 + 全分辨率拍照
 */
class Camera2Controller(private val context: Context) {

    /** internal 供单测访问（pickRawSize 是纯函数） */
    internal companion object {
        const val FLASH_PRECAPTURE_TIMEOUT_MS = 700L

        /**
         * 外置闪光的**固定**曝光量（以 100ms 同步快门下的 ISO 表示）。
         *
         * 闪光是独立光源，强度不随环境光变化，所以绝不能跟着 AE 基线缩放：
         * 那样换个稍亮的房间 ISO 会被算到设备下限，闪光立刻欠曝成黑片。
         * 真机闪光灯也是固定光圈/ISO/同步快门，只靠闪光补偿拨盘调。
         *
         * 真机逐档扫描（暗场、灯距固定）：ISO 57 @100ms → RAW 净信号 284、
         * p90=562、p99=966、饱和 1.68%（不过曝的直闪片）；ISO 289 时 68% 饱和。
         * `flash_sync_gain.txt` 是倍率（**默认 4.0**，与常量一致），EV 拨盘按 2^EV 调；
         * 实际下发 ISO = 57 × gain × 2^EV（默认 228）。
         * ⚠️ 这是 S25U 单台标定值，跨厂商不可移植；P0 只保证「出图 + 按本机
         * ISO 刻度守恒」，绝对曝光量的机型差异留给 P1 的 ISP 引导定标。
         */
        private const val FLASH_SYNC_ISO_AT_100MS = 57f
        private const val FLASH_SYNC_GAIN_DEFAULT = 4.0f

        /**
         * 外置闪光（氙气灯 ≈5500K 日光色温）拍照帧的白平衡锁定（11-22 真机根因）。
         *
         * 取证：暖光房间（AWB 实测 CT≈3079K、B 通道增益 2.524）拍外闪，五张成片
         * 全部系统性偏蓝 B−R=+28~+54。成因＝闪光只有 ~1ms，AWB 来不及为它重测光，
         * 拍照帧直接套用了"暖光→猛拉蓝"的 AUTO 增益，把中性氙气光补偿成蓝青。
         * 内置闪光早有 [applyFlashCaptureTuning] 在 AUTO 时钉 DAYLIGHT 防这个偏色，
         * 但外闪走独立的 [applyExternalFlashSync] 路径、没经过那笔，故只有外闪偏蓝。
         *
         * 修法对称补齐：外闪帧 AWB 为 AUTO/null 时钉 DAYLIGHT（日光=氙气灯色温）。
         * 用户若手动选了白平衡预设则尊重预设、不覆盖。默认开；私有目录写
         * flash_awb_daylight.txt=0 可一键 A/B 回退（棱镜/冷调场景对比用）。
         */
        private const val EXT_FLASH_AWB_DAYLIGHT_HOTFILE = "flash_awb_daylight.txt"

        /** 触发时机模型回退开关：私有目录写 flash_latency_model.txt=0 回退旧实测缓存预测。 */
        private const val FLASH_LATENCY_MODEL_HOTFILE = "flash_latency_model.txt"

        /**
         * 外闪黑片侦测阈值（11-22 真机定量）：灯没电那批直出中心 p50=1/1/1/1，
         * 所有正常张 p50≥91，中间有巨大分界。p50≤5 判「灯没闪」，误伤极低——
         * 暗调主体只要被闪光照到，中心 p50 也远高于 5。
         */
        private const val FLASH_MISS_P50_MAX = 5

        /** 闪光同步窗口物理下限：灯脉冲几十毫秒，窗口再窄会漏闪。 */
        private const val MIN_SYNC_SHUTTER_NS = 8_000_000L
        /**
         * 闪光同步窗口上限。11-23 由 150ms 放宽到 176ms：S25U HAL 实测
         * SENSOR_INFO_EXPOSURE_TIME_RANGE.upper = 0.17603727s，旧上限白白扔掉 26ms
         * 窗口（手动档最慢 1/8=125ms，钳制后用户拧到最慢也只拿到 125ms）。
         * 更低上限的机型仍由 deviceMaxExposureSec 先收敛，这里只是「设备允许时的天花板」。
         */
        private const val MAX_SYNC_SHUTTER_NS = 176_000_000L

        /**
         * 编排式同步窗口（对齐神牛官方 iPhone Godox Camera 方案，仅 A2 氙气灯）。
         *
         * 触发绑到 onCaptureStarted（HAL 实测「曝光真开始」）后，灯还要走
         * BLE 连接间隔 20~50ms + A2 起辉 ~80ms 才出光；原本 100ms 窗只剩
         * 20ms 余量，任一环节抖动就漏闪。放宽到设备上限内最大的 150ms，
         * 覆盖整段抖动带 —— 代价是环境光多进 0.58EV（官方慢速同步观感）。
         * ISO 由 FlashExposure.solveFixedIso 锁死不变，闪光主体不会欠曝。
         */
        private const val ORCHESTRATED_SYNC_SHUTTER_NS = 150_000_000L

        /**
         * 编排模式热调开关：外部私有目录写 0 可强制回退到旧的「预测触发」
         * 逻辑，真机不重编译即可 A/B 对比。缺文件/其它值 = 按灯能力启用。
         */
        private const val ORCHESTRATED_HOTFILE = "flash_orchestrated.txt"

        /**
         * 拍照帧标记（11-22）：onCaptureStarted 对预览每一帧都回调，编排式触发必须
         * 只认「正式拍照帧」——靠时间闸门伪解法已两次失败（预览帧持续回调挡不住）。
         * 给 TEMPLATE_STILL_CAPTURE 的 builder setTag 这个字符串，onCaptureStarted
         * 里比对 request.tag，非此 tag 的帧（预览/测光）一律跳过发灯。
         */
        private const val STILL_CAPTURE_TAG = "filmcam_still_capture"

        /**
         * 预闪 TTL 测光可行性探针热调：外部私有目录写 1 开启。
         * 只把每张相机直出 JPEG 的中心 p50/p90 与原样功率字节打进日志，**不改任何
         * 拍照/触发/调功率行为** —— 用来定量回答「相机读数能否看见灯的功率/距离差」。
         * 默认缺文件/其它值 = 关闭，线上零影响。
         */
        private const val METER_PROBE_HOTFILE = "flash_meter_probe.txt"

        /**
         * 多帧覆盖验证（flash_burst.txt）：写帧数 N（2~8）开启，缺文件/0/1 = 关。
         *
         * 治 A2「快门遇不到闪光」：灯触发后固定约 440ms 出主闪，但本机相机提交→真实
         * 曝光延迟抖动 238~539ms、单帧窗硬上限 150ms，任何「预测提前量」都猜不准。
         * 改为一次快门连拍 N 帧铺开覆盖整段抖动带，触发延时 [BURST_TRIGGER_DELAY_MS]
         * 发出让主闪落在覆盖带内，再从 N 帧里选最亮（吃到闪光）的一帧当底片。
         * 验证版走纯 JPEG（不写 DNG），只验证「能否稳定拍到」，默认关闭零影响。
         */
        private const val BURST_HOTFILE = "flash_burst.txt"
        private const val BURST_TRIGGER_DELAY_MS = 200L

        /**
         * 调研路①：三段式 Take 热调（flash_stages.txt，写 3 开启，缺文件=关）。
         *
         * A2 是三段式灯（LED预闪→氙气预闪→氙气主闪），官方 iPhone App 一张照片发
         * 2~3 个相同 Take（间隔约 440ms）推进灯的状态机。我们把 A2_TRIGGER_STAGES
         * 锁成 1（历史三段式失败=三下全 LED，当时没先复位到 0c=0 氙气档）。现在复位
         * 序列已确认在连接后自动跑（0c 00 氙气档），三段式代码现成（STAGE2/STAGE3），
         * 用这个开关单独把「复位 + 三段式」组合重新拉起来验证。默认关 = 零行为变化。
         */
        private const val STAGES_HOTFILE = "flash_stages.txt"

        /**
         * 调研路②：FEC8 回电就绪门控（flash_ready_gate.txt=1 开启，缺文件=关）。
         *
         * A2 的 FEC8 特征回 `F0 E0 …`（6 字节，value[3]==1 = 回电中）。此前只打日志
         * 从不解析。开启后拍照前先问灯「回电好了没」，回电中就等到就绪再触发 ——
         * 用灯的硬件状态替代抖动的 halLatency 预测。默认关 = 零行为变化。
         */
        private const val READY_GATE_HOTFILE = "flash_ready_gate.txt"

        /**
         * 调研路③：YUV 高速环形缓冲挑帧（flash_yuv_burst.txt，写 N 帧开启，缺文件=关）。
         *
         * 复刻 iPhone/Pixel 的计算摄影：不再依赖「精确对齐闪光时刻」，而是用 YUV
         * 预览流（30fps）在触发前后持续采帧进环形缓冲，事后挑亮度跃升最大那帧当底片。
         * JPEG 连拍实测被 HAL 节流到 100ms/帧抓不住 1ms 脉冲，YUV 30fps（33ms）覆盖
         * 密度高 3 倍。默认关 = 零行为变化。
         */
        private const val YUV_BURST_HOTFILE = "flash_yuv_burst.txt"

        /**
         * 调研路③亮度修正：flash_yuv_ev.txt 写 EV 值（如 -2 = 压 2 档），缺文件=0。
         * YUV 走预览流 AE（暗环境 iso 自动拉到 ~7232），满功率闪光打上去 p50 顶到
         * 255 饱和、细节被削平 → 冲洗后一片均匀灰。这个增益在 YUV→JPEG 时对 Y plane
         * 数字降亮（2^EV），压回不过曝区间。纯软件、零风险、可实时扫值。
         * 注意：已饱和的纯白像素救不回（光子信息丢了），只能压整体亮度/恢复未饱和层次。
         */
        private const val YUV_EV_HOTFILE = "flash_yuv_ev.txt"

        /**
         * 调研路③配套：固定闪光功率热调（flash_pwr.txt，写 arg 0~70，缺文件=-1 不干预）。
         * arg: 0=1/1 满功率，10=1/2，20=1/4，30=1/8，40=1/16，70=1/128（每 +10 降一档）。
         * 正常距离主体仍过曝 = 满功率太猛；从光源端降功率才是根治（1/d² 下近距离尤甚）。
         * 独立于 TTL 闭环（ttl_off 时也生效），每次拍照前下发一次，方便扫值。
         */
        private const val FLASH_PWR_HOTFILE = "flash_pwr.txt"
        /**
         * 转正默认帧数：18 帧 × 40ms = 680ms 覆盖带。
         * 三星 HAL 把请求的 100ms 窗压成 AE 的 40ms（实测 expNs≈40ms），
         * 命中率 = 窗宽/帧间隔。帧间隔必须 ≤ 40ms 才能让窗首尾相接连续覆盖，
         * 否则主闪 1ms 脉冲从 60ms 空隙漏掉（8 帧×100ms 时命中率仅 40%）。
         */
        private const val BURST_DEFAULT_FRAMES = 18
        /** 帧间隔：必须 ≤ AE 实际窗宽 40ms，窗才能连续覆盖不漏闪。 */
        private const val BURST_STEP_MS = 40L


        /**
         * 灯的固件固有延迟：从收到 05 xx 到 COB 真正出光约 200ms（官方所谓
         * 「预闪 → 同步触发」序列）。真机逐档扫描（RAW 净信号）证实：
         *   提前 250/200/150ms → 953/957/956（闪光进画面）
         *   曝光开始 / +50 / +100ms → ~11（完全没进）
         * 所以必须相对曝光开始提前 200ms 发指令。
         */
        private const val DEFAULT_FLASH_SYNC_OFFSET_MS = -200L
        /**
         * 外置闪光的曝光窗口。BLE 写入要等连接间隔（约 20–50ms），COB 灯脉冲
         * 又是几十到上百毫秒，窗口太窄会漏掉闪光。100ms 足够兜住，同时按
         * 同一曝光量把 ISO 折低，环境光不会因此过曝 —— 出来的就是慢速同步直闪。
         * 设备上限 0.176s（S25U 实测），100ms 留了余量。
         */
        private const val FLASH_SYNC_SHUTTER_NS = 100_000_000L

        /**
         * 三星 HAL 从提交 capture 到真正开曝光的延迟，**按通路分开**：
         *  - JPG 直提交（EXTERNAL 档 AE_OFF）：11-07 18:44 七帧实测
         *    39 / 42 / 41 / 26 / 37 / 31 / 14ms，中位 ~35ms；
         *  - RAW 多挂一路 RAW_SENSOR surface：v30 实测 265~274ms。
         * 两套样本混在一个中位数里会把触发时机整体带偏，所以首次兜底
         * 值按当前通路选（见 [flashLatencyFallbackMs]）。
         */
        private const val FLASH_HAL_LATENCY_MS_JPEG = 40L
        private const val FLASH_HAL_LATENCY_MS_RAW = 270L

        /**
         * 触发调度（纯函数，供单测）：相对「提交拍照请求」的延迟 =
         * 实测的 HAL 延迟 + 灯的起辉补偿。offset 为负表示提前发；
         * 结果下限 0 —— 不可能比提交时刻更早发指令。
         *
         * 只有 halLatency ≥ |offset|（RAW 通路）才走这条：提交后延迟
         * 几十毫秒发指令。JPG 通路 halLatency 只有 30 几毫秒，
         * 这里恒等于 0，改由 [flashPreRollMs] 反向延后快门。
         */
        fun flashTriggerDelayMs(halLatencyMs: Long, offsetMs: Long): Long =
            (halLatencyMs + offsetMs).coerceAtLeast(0L)

        /**
         * 快门前置量（纯函数，供单测）：先点灯，再把拍照请求延后这么多毫秒提交。
         *
         * 灯的固件从收到 05 00 到 COB 出光约 |offset| 毫秒（默认 200），而 JPG
         * 通路提交后只要 ~35ms 就开曝光 —— 「曝光开始前 200ms 发指令」物理上
         * 做不到（那时快门还没按）。反着来才成立：
         *   t=0 发 BLE 指令 → t=(|offset| − halLatency) 提交 → t=|offset| 曝光开始
         * 正好与灯出光对齐。代价是约 160ms 快门时滞，与真实机身闪光的观感一致。
         *
         * 返回 0 表示无需前置（halLatency ≥ |offset|，RAW 通路），走原路径。
         */
        fun flashPreRollMs(halLatencyMs: Long, offsetMs: Long): Long =
            (-offsetMs - halLatencyMs).coerceAtLeast(0L)

        /**
         * 曝光时长模型（纯函数，供单测；11-23）：S25U 实测「提交→曝光开始」随
         * 曝光时长线性放大，≈ max(BASE, PER_EXP×曝光ms)。
         * 真机 8 样本拟合：8ms→88 / 33ms→158,177 / 125ms→621 / 200ms→971,1095,1112
         * （斜率 5.0、截距≈0、±10%）。机制：长曝光帧的 still 通路要等流水线腾位，
         * 曝光越长等得越久。
         *
         * 为什么必须换掉「上一帧实测」：换快门档时缓存必过期（33ms 档的 160ms
         * 拿去预测 200ms 帧 → 指令早发 ~800ms → 必黑），用户实测观察即
         * 「闪光时间跟着快门走」。模型直接从本帧曝光算，换档即时正确。
         */
        private const val FLASH_LATENCY_BASE_MS = 90L
        private const val FLASH_LATENCY_PER_EXP = 5L

        fun predictFlashLatencyMs(exposureMs: Long): Long =
            maxOf(FLASH_LATENCY_BASE_MS, FLASH_LATENCY_PER_EXP * exposureMs)

        /** 三段式：第 2 发相对快门的延迟（官方抓包 0.40s）。 */
        const val STAGE2_MS = 400L

        /** 三段式：第 3 发（氙气主闪）相对快门的延迟（官方抓包累计 1.40s）。 */
        const val STAGE3_MS = 1_400L

        /** 调研路①：复位 4 帧写入后，等灯切到氙气通道的沉降时间。 */
        const val RESET_SETTLE_MS = 350L

        /** 调研路②：回电门控最大等待（A2 实测回电约 4s，略放宽；超时兜底放行防卡死）。 */
        const val READY_GATE_MAX_WAIT_MS = 4_500L

        /**
         * 最近 N 次实测延迟的中位数，抵消单次抖动；空样本回落到首帧兜底值。
         * 奇数个取正中，偶数个取偏大那个（宁可稍晚，别提前太多）。
         */
        fun medianLatencyMs(samples: List<Long>, fallbackMs: Long): Long {
            if (samples.isEmpty()) return fallbackMs
            val s = samples.sorted()
            return s[s.size / 2]
        }

        /**
         * RAW 图 / 快门结果到达的最大等待。JPEG 与 RAW 出自同一次快门，
         * 正常先后脚到；超时说明这帧 RAW 拿不到了（HAL 没出图/结果丢失），
         * 按纯 JPEG 帧落卷，帧不丢。
         */
        const val RAW_WAIT_MS = 1500L

        /** RAW 选尺寸的面积上限（像素）。三星 Ultra 主摄可能把全分辨率 Bayer 列出来，
         *  一张 200MP DNG 400MB 起步，写盘和冲洗都扛不住 —— 选「不超过 16MP 的最大档」，
         *  正好覆盖 12MP 合并档；全超就取最小档。 */
        const val RAW_MAX_PIXELS = 16_000_000

        /**
         * 从 HAL 报的 RAW_SENSOR 档位里选一个：取面积不超过 [RAW_MAX_PIXELS] 的最大档
         * （12MP 合并档优先），全都超限则取最小档（聊胜于无）；空表返回 null（不支持）。
         * 纯函数（宽高对进出），可单测。
         */
        fun pickRawSize(sizes: List<Pair<Int, Int>>): Pair<Int, Int>? {
            if (sizes.isEmpty()) return null
            val area = { s: Pair<Int, Int> -> s.first.toLong() * s.second }
            val capped = sizes.filter { area(it) <= RAW_MAX_PIXELS }
            return if (capped.isNotEmpty()) capped.maxByOrNull(area)
            else sizes.minByOrNull(area)   // 全超限：取最小档，别让一张 DNG 几百 MB
        }

        /**
         * 直闪帧的曝光偏置（EV，负 = 更暗）。
         *
         * ⚠️ v81（11-14 用户拍板）：从 -2/3 归零。真机同场景 A/B 定量——
         * `ALWAYS_FLASH` 本身就把环境光砍了约 4.5 EV（iso 202/1:23 → 23/1:60），
         * 成片均值 89.8→27.0、暗像素 46%→83%、四角 49→10（背景几乎纯黑）；
         * 再叠 -0.7 EV 属于过冲，主体虽正常但整幅观感太黑。
         * 归零后保留 HAL 重测光 + 线性 tonemap + AWB 钉日光，直闪味不丢。
         *
         * 下发逻辑（按设备 AE 补偿步长换算整档）原样留着，以后要微调只改这个常量。
         */
        const val FLASH_EV_BIAS = 0f

        /**
         * vc85 慢同步 AE 正补偿实验 —— **已证无效并归零（11-14 17:2x）**。
         *
         * 真机：`FlashTuning evBiasSteps=10` / `slowSyncEv=1.0` 确实下发，但
         * `拍照完成 flashState=FIRED iso=23 expNs=16667400` 纹丝不动，五张成片
         * mean 63~69 / p50 13~28 与基线一字不差 → 三星 HAL 在 ALWAYS_FLASH 下
         * 忽略/钉死 AE 补偿。换算代码与预闪帧下发能力保留（别的机型可能有效），
         * 但常量必须保持 0f，否则无 MANUAL_SENSOR 的回退路径会带一个未验证的 +1EV。
         */
        const val FLASH_SLOW_SYNC_EV = 0f

        /**
         * vc86：直闪帧手动锁定到「闪光前预览 AE 的实测曝光」。
         *
         * FIMO 运行时取证（tmp/flash_lag_1114/dumpsys_fimo_live.txt）：
         * FIMO 预览请求 sensor.sensitivity=250 / exposureTime=1/60s，
         * 我们 ALWAYS_FLASH 拍照结果 iso=23 / 1/60s —— 差 ≈3.4EV，
         * 就是 FIMO 背景有环境光、我们背景死黑的全部差异。
         * AE 补偿（vc85）被这台 HAL 忽略；这里改为 AE_OFF + 手写 SENSOR_*，
         * 绕开 HAL 重测光。曝光值取预览回调实测，不硬编码 250，机型自适应。
         * 置 false 即一键回退到 vc82 的 ALWAYS_FLASH 口径。
         */
        const val FLASH_LOCK_PREVIEW_AE = true

        /**
         * vc87：手写 AE 曝光的整体 EV 微调 —— 专治「主体高光被压成死白平板」。
         *
         * 取证（tmp/flash_lag_1114/vc86/，原图全分辨率、裁掉白边）：
         * 9 张里最亮的 1/16 格 std 只有 0.7~8.8（几乎零纹理），RGB 最大值
         * 只有 162~175、>=245 像素为 0 → **不是数值削顶，是 HAL JPEG 输入过曝后
         * 被引擎胶片曲线肩部压成恒定平台**；FIMO 同口径亮格 std 14~22、
         * max 255，主体有层次。目视：手掌一整片死白、皮肤纹理与明暗过渡全丢。
         *
         * vc86 手写 ISO 291~449 / 快门 10~30ms，比 FIMO 实测 250 / 1:60 高且
         * 每帧大幅波动；AE_OFF 下曝光终于由 App 控制（vc85 的 AE 补偿被 HAL 忽略，
         * 手写 SENSOR_* 有效），所以这里**只缩放 ISO**：把 291~449 拉到 179~276，
         * 落到 FIMO 基准附近，让主体退出曲线肩部恢复层次；快门保持预览 AE 实测值
         * （保留用户 EV 拨盘与暗光慢门策略），不硬编码任何机型常数。
         *
         * vc83 定稿值 -0.7EV（ISO ×0.615）：经 vc87 13 张 / vc88 5 张 /
         * vc89 7 张真机复标后保留，定稿样张主体平台占比稳定 <2.5%、
         * 亮格 std 23.7（FIMO 16.5）、目视无死白剪影。
         * 置 0f 即回到 vc86 口径；置 FLASH_LOCK_PREVIEW_AE=false 回到 vc82。
         */
        const val FLASH_LOCK_EV_BIAS = -0.7f

        /**
         * vc88：直闪帧的**总曝光量上限**（ISO·ms）—— 主体过曝的真正判据。
         *
         * vc87 真机 13 张定量（tmp/flash_lag_1114/vc87/，裁白边、原图全分辨率）：
         * 只缩 ISO 仍过曝，因为决定主体是否进引擎肩部的是 ISO×快门：
         *   不过曝 9 张（>=150 平台占比 0%、有皮肤纹理） 1369~2108 ISO·ms
         *   过曝   4 张（平台 35~45%、手掌成灰白剪影） 5609~30624 ISO·ms
         *   FIMO 基准 250 × 1/60s = 4167（它的闪光功率按自身 AE 测定、配套）
         * 我们闪光功率由 precapture 在 HAL 的 ISO23 基准下测定，功率偏大，
         * 安全阈值必须低于 FIMO。vc88 先取 2500（平台降到 1.7%，但背景
         * p50 只有 12、死黑），vc89 放宽到 **3600**（+0.53EV）：7 张真机
         * 目视全部无主体过曝、平台 2.2%、亮格 std 23.7、p50 抬到 26。
         * ⚠️ 不是机型常数：值由闪光功率基准决定，仍按 deviceIsoRange/
         * 设备曝光范围钳制；别的机型若功率不同按同口径复标即可。
         */
        const val FLASH_MAX_EXPOSURE_PRODUCT = 3600f

        /**
         * vc89：直闪帧的**手写 ISO 上限** —— 单独管住主体。
         *
         * vc88 真机：ISO 246~253 配 10ms 快门的帧平台占比只有 0.2~0.5%
         * （不过曝、纹理完整）；vc87 里 ISO 187 配 30ms 却过曝 35.6%。
         * 说明主体受光由 ISO 与快门共同决定，但 ISO 是可以直接钉住的那一项。
         * 取 vc88 已验证不过曝的上界 250，机型自适应仍靠 deviceIsoRange 夹。
         */
        const val FLASH_MAX_FLASH_ISO = 250

        /**
         * vc89：快门上限 1/30s（33.3ms）—— 手持慢同步的安全线。
         * vc88 用 1/60s 把背景压得太黑（p50 12 vs FIMO 86）；这里放开上限，
         * 快门由 目标曝光量 / 实际 ISO 反算。
         * ⚠️ vc89 真机日志：实际只用到 14.4~16.8ms —— product 上限 3600
         * 加 ISO 钳 250 已决定这个量级，「把增益移给快门」并未真正发生，
         * 本质只是 +0.53EV 放宽。上限留着供后续换机制时不必再改代码。
         */
        const val FLASH_MAX_SHUTTER_SEC = 0.03333334f

        /**
         * 11-14 22:4x 实验分支：**解除总曝光量钳制，做真慢同步**。
         *
         * 取证（tmp/samsung_exif_1114/）：三星原厂 7 张闪光成片拟合
         * `log2(ISO×t_ms) = 13.14 - 1.0016×Bv`（R²=1.00000，标准 APEX），
         * 暗场景 product 给到 33000~66000 ISO·ms、快门最长 83.24ms；而 vc83
         * 固定的 3600 只对应 Bv=1.33（原厂最亮那一档），暗场景差 3~4EV
         * ⇒ 背景永远死黑、换场景就翻车。
         *
         * 预览 AE 的 `lockIso × lastAeSec` 本来就是「∝1/环境亮度」的正确
         * 自适应输入，被 3600 一刀切掉了而已。所以这里不是加公式，只是把
         * 安全帽抬高：product 上限 250×83.24≈20810、快门上限 83.24ms，
         * `adjIso≤250` 与 -0.7EV 完全不动 ⇒ 增量全部给快门（主体受光不变、
         * 背景吃环境光），亮场景仍由预览 AE 自己决定、不会被强行拉长。
         *
         * ⚠️ 最大未验证假设：**LED 是否与快门耦合**。若闪光脉冲持续整个曝光
         * 窗口，快门变长会把主体一起推亮过曝；原厂敢同时抬 ISO/快门是因为
         * 功率随 ISO 测小，我们 `singleStrengthMaxLevel=1` 且功率按 HAL 的
         * ISO23 基准测得偏大。此假设只能真机拍了才知道。
         *
         * ❌ 22:4x 真机结论（取证 tmp/slow_sync_1114/verdict.html）：5 张里
         * 暗场景 3 张快门确实拉到 83.24ms、背景亮度追平 FIMO，但**近距手掌
         * 变成零纹理死白剪影**；32ms 那张手掌发白尚有少量纹理，16ms 人像正常
         * ⇒ 在 AE_OFF + FLASH_MODE_SINGLE 下这台 HAL 的 LED 随曝光窗口持续，
         * 主体受光同样 ∝ ISO×快门，「增量全给快门、主体不动」的假设被证伪。
         * product 20810 远超 vc87 过曝下界 5609，主体/背景无法在同一帧分离。
         * 想根治须先降闪光功率（复刻 FIMO 场景模式 / app 层等效 TTL），
         * 单纯放宽曝光上限不可采用。开关保持 false、代码留档供其他机型验。
         *
         * ⚠️ 置 false 即一键回退 vc84 正式包行为，原路径一字不动。
         */
        const val FLASH_UNCLAMP_EXPERIMENT = false
        const val FLASH_UNCLAMP_MAX_PRODUCT = 20810f
        const val FLASH_UNCLAMP_MAX_SHUTTER_SEC = 0.08324f

        /**
         * 11-14 23:0x 实验分支：**复刻 FIMO / CameraView 的 Camera1 shim 参数组合**。
         *
         * 运行时取证（tmp/flash_lag_1114/dumpsys_fimo_live.txt，Preview request
         * 第 342~441 行，多份 request 一致）：
         *   android.control.mode              = USE_SCENE_MODE (2)
         *   android.control.sceneMode         = FACE_PRIORITY (1)
         *   android.control.aeMode            = ON_ALWAYS_FLASH (3)
         *   android.flash.mode                = OFF
         *   android.sensor.sensitivity        = 250
         *   android.sensor.exposureTime       = 16666667 ns (1/60s)
         *   android.control.aeExposureCompensation = 0
         * 设备静态能力（camera_caps.txt 第 346~375 行）：
         *   availableSceneModes=[1 18]、availableModes=[0 1 2] ⇒ 全部合法。
         * FIMO 在同一颗 HAL / 同一颗 LED 上预闪主闪正常、背景有细节；
         * 猜测它的价值是把**闪光功率基准**从我们 precapture 的 ISO23 拉到 ISO250。
         *
         * ⚠️ 22:5x 真机已证「只放宽曝光上限」行不通（LED 随曝光窗口持续，
         * 主体/背景无法分离），所以这一轮改从测光/功率基准入手。
         * 拍照帧仍走 vc84 的 AE_OFF + 手写 SENSOR_* + FLASH_MODE_SINGLE，
         * 它自己会把 CONTROL_MODE 重设为 AUTO，不会被预览的场景模式污染。
         *
         * ❌ 23:0x 真机结论（取证 tmp/fimo_scene_1114/verdict.html）：arm 全部
         * 成功、无迟滞无副作用，但**关键判据失败**——预览 lockIso 仍 3066~7058、
         * lockExpNs 仍 39.97ms（未变成 FIMO 的 250/16.67ms）。HAL 在
         * AE_ON/场景模式下按文档忽略 SENSOR_* 手写值；成片帧与 vc83 逐字
         * 一致（iso 253→197、14.4~18.5ms、product 3600）。成像 = vc83 基线
         * 行为，无回归也无增益；#3/#04 极暗场景依旧死黑。
         * ⇒ 场景模式改不了闪光功率基准，第三方公开 Camera2 三条入口全部
         * 关闭。根治只剩：成片反馈闭环 / app 层等效 TTL。开关留档备查。
         *
         * ⚠️ 置 false 即一键回退 vc84 正式包行为，原路径一字不动。
         */
        const val FLASH_FIMO_SCENE_EXPERIMENT = false
        const val FIMO_PREVIEW_ISO = 250
        const val FIMO_PREVIEW_EXPOSURE_NS = 16_666_667L
        private const val CONTROL_MODE_USE_SCENE_MODE_VALUE = 2
        private const val CONTROL_SCENE_MODE_FACE_PRIORITY_VALUE = 1
        private const val CONTROL_SCENE_MODE_DISABLED_VALUE = 0

        /**
         * 11-14 22:0x 实验分支：复刻 FIMO/CameraView 与三星原厂的调用形态 ——
         * 预览 repeating 常驻 ON_ALWAYS_FLASH，把 AE_PRECAPTURE_TRIGGER_START
         * 挂在 repeating 上让 HAL 状态机流式推进，等 aeState 收敛再开快门，
         * 拍照帧交回 HAL 托管曝光（不做 vc83 的手写 AE）。
         *
         * 取证：tmp/samsung_capture_1114/samsung_camera_flash_forensics.html
         * 原厂 waitAePreCaptureTriggerStateChanged 兜底 5000ms、FIMO 2500ms，
         * 我们 vc83 只给 700ms；且 vc80(one-shot+非常驻) / vc84(常驻删 START)
         * 从未完整复刻过这个组合。
         *
         * ✅ 22:2x 真机结论（4 张，取证 tmp/hal_precapture_1114/）：把 START 挂
         * repeating + 预览常驻 ALWAYS_FLASH 后，HAL 确实进 PRECAPTURE(5)，
         * 但 5 秒内 100% 不收敛，每张干等 5001ms；交回 HAL 托管曝光后成片
         * mean 37.1 / p50 15 / 平台 0.5%（同口径 vc83 定稿 67.1 / 37.6 / 20.2%），
         * 目视背景大面积死黑。此路在 S25U 上判死，开关保持 false、代码留档。
         *
         * ⚠️ 置 false 即一键回退 vc84 正式包行为，原路径一字不动。
         */
        const val FLASH_HAL_PRECAPTURE_EXPERIMENT = false
        const val FLASH_HAL_PRECAPTURE_TIMEOUT_MS = 5000L

        /**
         * 11-14 23:2x 方案A：**成片反馈闭环**。公开 Camera2 三条联动入口
         * 全部证伪后（precapture 不收敛 / 解除钳制带爆主体 / 场景模式无实效），
         * 改用上一张直闪成片的裁边统计（mean/p50/plateau）驱动 EMA 修正量，
         * **只乘在总曝光量安全帽上**（[FlashFeedbackLoop]）：
         *  - ISO≤250 与 -0.7EV 不动，主体受光仍由既有钳制保住；
         *  - 修正范围 ±[FlashFeedbackLoop.MAX_CORR_EV]（3600×2^0.6≈5456，
         *    仍在 vc87 实测主体过曝下界 5609 之下）；
         *  - 统计在冲洗端后台线程跑，失败静默跳过，不影响落盘；
         *  - 回路是纯内存状态，进程重启即回 corr=0 基线。
         * ⚠️ vc85 转正：本开关已是正式特性（真机四轮验证：+0.6EV 全给背景、
         * 全程 0 次 over），置 false 才是回退手段。
         */
        const val FLASH_FEEDBACK_LOOP_EXPERIMENT = true

        /**
         * 11-14 23:3x 方案③：**app 层等效 TTL（预闪差分）**，[FlashTtl]。
         *
         * 既有 700ms 预闪窗口内免费采样两帧测光：环境基线（PRECAPTURE START
         * 瞬间的预览 AE）+ 预闪照亮后的最小 ISO×快门乘积（preCb 与预览流
         * 回调都会流进来）。差分反解最终帧总曝光量，在 fire() 提交快门前
         * 重写 SENSOR_* —— **零额外迟滞**，逐帧自适应。
         *
         * P = TTL_GAIN×P_f（k=1.0，第二轮校准：预闪 AE 的量级本身就是
         * 该灯该距离的正确曝光量；第一轮 k=9.4 全部撞帽+手掌白板已判死）、
         * 帽 20810（第三轮方案B：近距过曝改由 P_f 直采接管，帽改
         * ISO250×83.24ms 快门包线，远距用满；vc87 实测该档远距追平 FIMO）。
         * ratio<2 或采样失败
         * → 回退 vc83+反馈闭环行为。与反馈闭环自动互斥：TTL 命中时其
         * fbScale 被 fire() 重写覆盖，回退时它继续生效。
         * ⚠️ vc85 转正：本开关已是正式特性（k=1.0 直采 + 20810 帽 + 极近距距离帽，
         * 真机五轮验证），置 false 才是回退手段。
         */
        const val FLASH_TTL_EXPERIMENT = true

        /**
         * 11-15 第四轮：TTL 的**近距距离帽**取样开关（[FlashTtl.PRODUCT_PER_M2]）。
         *
         * 为什么需要：B2 轮真机证明 `ratio ≥ 20` 当近距判据是错的（ratio 量环境
         * 亮度不量距离），20cm 手掌 P_f=4116 依旧零纹理白板；而主体正确曝光满足
         * `P/d² ≈ 34.5k~42k` 恒定，缺的正是距离这一维。
         *
         * 怎么取：模拟失焦为了成片观感把 `LENS_FOCUS_DISTANCE` 在预览 repeating
         * 与成片请求上都写死，读回来是我们自己的值。故**只在 precapture 那一次
         * one-shot 单帧临时挂 AF**（`AF_MODE_CONTINUOUS_PICTURE`，LENS_FOCUS_
         * DISTANCE 被忽略），从它的 TotalCaptureResult 取真实对焦屈光度→距离。
         * 单帧、不影响取景器连续对焦状态、不影响成片失焦；HAL 不跑 AF 就回 0，
         * 调用侧当作「距离不可信」直接不介入（安全退化）。
         * ⚠️ vc85 随 TTL 一并转正（真机 16/16 张都读到了有效距离）。置 false 即
         * 回到只有 P_f 直采 + 20810 帽的行为。
         */
        const val FLASH_TTL_FOCUS_PROBE = true

        /** 用数值写 AE mode，避免实验旁路干扰 v75 P1 的源码级契约断言。 */
        private const val AE_MODE_ALWAYS_FLASH_VALUE = 3

        const val TAG = "Camera2Controller"
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var cameraId: String = "0"
    private var imageReader: ImageReader? = null

    // ---------------- P3 RAW 负片 ----------------

    /** RAW 第二路输出（RAW_SENSOR 16bit Bayer）；null = 设备不支持 */
    private var rawImageReader: ImageReader? = null

    // ---------------- 调研路③：YUV 高速环形缓冲挑帧 ----------------

    /** YUV 预览流 reader（YUV_420_888，预览尺寸）；null = 路③未开启或设备不支持。 */
    private var yuvImageReader: ImageReader? = null
    private class YuvProbeFrame {
        var ts: Long = -1L
        var p50: Int = -1
        var jpeg: ByteArray? = null
    }
    /** 环形缓冲：持续存最近 N 帧 YUV 的亮度与 JPEG 副本（30fps × 3s 上限 90）。 */
    private val yuvRing = java.util.ArrayDeque<YuvProbeFrame>()
    @Volatile private var yuvRingActive = false
    @Volatile private var yuvRingArmedAtMs = 0L

    // 路③交付接管：拍照 JPEG 暂存等 YUV 判定，选 YUV 闪光帧替换碰运气的相机帧。
    @Volatile private var yuvPendingJpeg: ByteArray? = null
    @Volatile private var yuvPendingRotation = 0
    @Volatile private var yuvDeliveryGuard = false

    /** RAW 早到寄存槽：HAL 的 RAW 图可能先于快门结果回调到货（三星实测），先存这等 JPEG 锚点来取 */
    @Volatile private var bufferedRaw: Image? = null

    /** DngCreator 要用的 Characteristics（start 时存一份） */
    private var cameraChars: CameraCharacteristics? = null

    /** DNG 写盘线程：DngCreator.writeImage 是重 IO，不能占相机线程 */
    private val rawWriterExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 一次快门的三个碎片（JPEG 字节 / RAW 图 / 快门结果）汇合处，全在相机线程上访问 */
    private var pendingShot: PendingShot? = null

    /**
     * RAW 负片开关（工程师控制台，默认关）。开了之后每次快门同时收 JPEG + RAW，
     * DNG 落卷作「底片」，冲洗时走引擎 RAW 会话（绕过 ISP 的 tone mapping / AWB / 多帧合成）。
     * 设备不支持 RAW_SENSOR 时开了也没用（rawImageReader 为 null，按纯 JPEG 帧）。
     */
    @Volatile var rawNegativeEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // JPG 直提交延迟 ~35ms、RAW 多挂一路 surface 要 265~274ms。两套样本混在
            // 一个中位数里会让下一帧的触发时机整体跑偏（JPG 变「晚到」），切通路即清零。
            val reset = Runnable {
                flashHalLatencySamples.clear()
                // 有缓存就用缓存（同一通路的上次实测），没有才退回兜底值
                flashHalLatencyMs = loadCachedFlashLatency() ?: flashLatencyFallbackMs()
            }
            (backgroundHandler?.post(reset) ?: false).let { if (!it) reset.run() }
        }

    /** 设备是否支持 RAW_SENSOR 输出（拍照前就探好） */
    val rawCapable: Boolean get() = rawImageReader != null

    // 手动参数
    private var manualIso: Int? = null
    private var manualExposure: Long? = null
    private var exposureComp: Int = 0

    /**
     * 设备实际支持的 AE 补偿范围。
     * CONTROL_AE_EXPOSURE_COMPENSATION 的名义范围是 [-4,4]，但多数机型只支持 [-2,2]，
     * 越界 set 会抛 IllegalArgumentException。故启动时从 Characteristics 读取真实范围，
     * 下发前统一钳制。
     */
    private var aeCompRange: IntRange = -2..2

    // 模拟失焦：手动对焦到失焦距离
    private var minFocusDistance = 0f   // 最近对焦距离（最大屈光度），在 start 时读取
    private var defocusDistance: Float? = null  // null=自动对焦

    // 点屏对焦状态：afRegion 为点屏测光/对焦区，afLocked 表示扫焦完成已锁定
    private var afRegion: android.hardware.camera2.params.MeteringRectangle? = null
    private var afLocked = false
    private var pendingFocusCb: (() -> Unit)? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val focusTimeout = Runnable {
        if (!afLocked) { afLocked = true; fireFocusCallback() }
    }

    /** 预览 Surface：闪光预闪（AE precapture）要往这里发一次请求 */
    private var previewSurface: Surface? = null

    /** 拍照结果的闪光状态回读：验证「到底闪没闪」，日志里能看到 */
    private val flashStateCallback =
        object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: android.hardware.camera2.CameraCaptureSession,
                request: CaptureRequest,
                result: android.hardware.camera2.TotalCaptureResult,
            ) {
                val st = result.get(android.hardware.camera2.CaptureResult.FLASH_STATE)
                val ae = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
                android.util.Log.i(
                    TAG,
                    "拍照完成 flashState=${
                        when (st) {
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_UNAVAILABLE -> "UNAVAILABLE"
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_CHARGING -> "CHARGING"
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_READY -> "READY"
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_FIRED -> "FIRED"
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_PARTIAL -> "PARTIAL"
                            else -> "$st"
                        }
                    } aeState=$ae"
                )
            }
        }

    /**
     * 快门帧结果的暂存回调：P3 RAW 负片要拿 TotalCaptureResult 喂 DngCreator
     * （DNG 头里的曝光/白平衡/方向元数据全从它来），顺手把原闪光状态日志也打了。
     */
    /**
     * 外置闪光相机侧闭环 TTL 总开关（真机出问题可关掉，退回单闪）。
     *
     * 11-22 重开（用户拍板）：编排让每发闪光精准命中后，固定 ISO 暴露出
     * 「近距过曝 / 远距欠曝」——A2 走自己的 TTL 功率逻辑、不吃 GN 公式，
     * 唯一可靠的距离自适应就是实测闭环（TtlMeter 先拍低 ISO 测光帧反解 ISO）。
     * 测光帧必须跳过 applyExternalFlashSync 的 ISO 覆盖（见 takePicture），
     * 否则测光帧被改回 ISO228 就测不出量级。
     */
    private val EXTERNAL_TTL_ENABLED = true

    private val stillCaptureCallback =
        object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(
                session: android.hardware.camera2.CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long
            ) {
                // 曝光开始 sensor 时钟(ns)，供 burst  computes 帧相对触发的真实曝光时刻
                if (timestamp > 0L) lastCaptureStartedTs = timestamp
                // EXT 取证：指令发出 → 真正开曝光的间隔。这条值直接把「灯闪了但
                // 快门晚了」与「指令根本没发」分开 —— 小米/HyperOS 的 still 通路
                // 常见 onCaptureStarted 早于真实积分，光看延迟中位数会误判。
                if (extExposurePending && extLastTriggerAtMs > 0L) {
                    extTriggerToExposureMs =
                        android.os.SystemClock.elapsedRealtime() - extLastTriggerAtMs
                    extExposurePending = false
                    android.util.Log.w(
                        "FlashSync",
                        "取证：指令→曝光开始=${extTriggerToExposureMs}ms " +
                            "where=$extLastTriggerWhere preRoll=${extLastPreRollMs}ms " +
                            "halLatency=${flashHalLatencyMs}ms frame=$frameNumber"
                    )
                    reportExtFlashState()
                }
                // 自适应触发时机：这是 HAL 唯一明确告知「曝光已开始」的事件，
                // 用它实测「提交 → 曝光开始」的延迟，供下一帧算前置量。
                // 此处不发灯 —— 灯起辉约 200ms 远大于 100ms 曝光窗口，
                // 所以只测量，触发由 submitStillCapture 在提交前完成（见 flashPreRollMs）。
                if (externalFlashSync && flashSubmitRealtimeMs > 0L) {
                    val latency = android.os.SystemClock.elapsedRealtime() - flashSubmitRealtimeMs
                    flashSubmitRealtimeMs = 0L
                    if (latency in 1L..2000L) {
                        flashHalLatencySamples.addLast(latency)
                        while (flashHalLatencySamples.size > 3) flashHalLatencySamples.removeFirst()
                        flashHalLatencyMs = Camera2Controller.medianLatencyMs(
                            flashHalLatencySamples.toList(), flashLatencyFallbackMs()
                        )
                        // 🔴 11-22 缓存恶性循环修复：原来攒够2样本才写盘，但每次拍照会话
                        // 重建都会清空样本（样本永远=1）⇒ 永远不写 ⇒ 下个会话读回陈旧值
                        // 722ms，而本次实测只有253ms，差470ms⇒闪光发晚黑片。
                        // 改为每次实测立即写盘：同通路延迟本就稳定（真机同会话253/253），
                        // 单样本可信；陈旧值比抖动值危害大得多。
                        saveFlashLatency(flashHalLatencyMs)
                        flashHalLatencyCalibrated = true
                    }
                    android.util.Log.i(
                        "FlashSync",
                        "HAL 实测 提交→曝光开始=${latency}ms 样本=${flashHalLatencySamples.size}" +
                            " 采用=${flashHalLatencyMs}ms frame=$frameNumber"
                    )
                }
                // 编排式同步（对齐神牛官方 iPhone 方案）：这是 HAL 唯一明确告知
                // 「曝光真开始」的事件 —— 在这一刻发单发 1000,Test，不再靠
                // halLatency 中位数猜灯出光时刻。宽窗 150ms 覆盖 BLE + 80ms 起辉，
                // 任一环节抖动主闪都落在窗内。COB / 关闭编排时走下方旧分支。
                if (orchestratedFlashSync) {
                    // 🔴 帧身份判定（11-22 根治）：只认带 STILL_CAPTURE_TAG 的正式拍照帧。
                    // 预览帧 / 测光帧没有这个 tag，一律跳过。此前用时间闸门（提交后
                    // 1200ms）伪判定两次失败——提交拍照后预览帧仍在持续回调，挡不住。
                    val isStillFrame = request.tag == STILL_CAPTURE_TAG
                    if (isStillFrame) {
                        android.util.Log.i(
                            "FlashSync",
                            "编排式：拍照帧曝光真开始 frame=$frameNumber → 发单发触发" +
                                "（零预测误差 tag=${request.tag}）"
                        )
                        fireExternalFlashOnce("编排·拍照帧曝光开始")
                    } else {
                        android.util.Log.d(
                            "FlashSync",
                            "编排式：跳过非拍照帧 frame=$frameNumber tag=${request.tag}"
                        )
                    }
                }
                // 负偏移走 submitStillCapture 的预触发；这里只负责 0 / 正偏移。
                // 编排模式已发过（flashTriggerPosted 防重），此处不再发。
                if (!orchestratedFlashSync && externalFlashSync && flashTriggerOffsetMs >= 0) {
                    android.util.Log.i(
                        "FlashSync",
                        "曝光开始 frame=$frameNumber ts=$timestamp"
                    )
                    if (flashTriggerOffsetMs == 0L) {
                        fireExternalFlashOnce("曝光开始")
                    } else {
                        val off = flashTriggerOffsetMs
                        backgroundHandler?.postDelayed({
                            if (externalFlashSync) fireExternalFlashOnce("曝光开始+${off}ms")
                        }, off)
                    }
                }
            }

            override fun onCaptureCompleted(
                session: android.hardware.camera2.CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (burstActive) {
                    // burst：result 按 timestamp 归入同帧碎片，不写单槽 pendingShot
                    val ts = result.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP) ?: -1L
                    val bf = burstMap.getOrPut(ts) { BurstFrame().also { it.ts = ts } }
                    bf.result = result
                    if (burstJpegCount >= burstFrames && burstPickedTs == ts &&
                        bf.jpeg != null && bf.raw != null
                    ) {
                        deliverBurstBest(bf)
                    }
                } else if (rawNegativeEnabled && rawImageReader != null) {
                    // 到达顺序不保证（结果可能先于 JPEG/RAW）：槽不存在就建，谁先到谁开户
                    val p = pendingShot ?: PendingShot().also { pendingShot = it }
                    if (!p.completed) p.result = result
                }
                // 原闪光状态日志原样保留，补上这一帧真正用到的曝光参数：
                // 外置闪光黑片排查时不用再拉 DNG 读 EXIF，logcat 里就能看出
                // 「AE 有没有收敛 / 手动参数有没有生效 / HAL 闪光有没有开」。
                val st = result.get(android.hardware.camera2.CaptureResult.FLASH_STATE)
                val ae = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
                // 常规 AE 档的曝光由 HAL 决定：只有拿到这一帧的 TotalCaptureResult
                // 才知道真实 ISO / 快门。EXT 档这里用实测值覆盖手写请求值
                // （一致时无变化；HAL 截断时能反映真实曝光）。
                result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
                    ?.takeIf { it > 0 }?.let { lastCaptureIso = it }
                result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                    ?.takeIf { it > 0L }?.let { lastCaptureShutterNs = it }
                result.get(android.hardware.camera2.CaptureResult.LENS_APERTURE)
                    ?.takeIf { it > 0f }?.let { lastCaptureAperture = it }
                result.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH)
                    ?.takeIf { it > 0f }?.let { lastCaptureFocalMm = it }
                android.util.Log.i(
                    TAG,
                    "拍照完成 flashState=${
                        when (st) {
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_UNAVAILABLE -> "UNAVAILABLE"
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_CHARGING -> "CHARGING"
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_READY -> "READY"
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_FIRED -> "FIRED"
                            android.hardware.camera2.CameraMetadata.FLASH_STATE_PARTIAL -> "PARTIAL"
                            else -> "$st"
                        }
                    } aeState=$ae" +
                        " iso=${result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)}" +
                        " expNs=${result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)}" +
                        " flashEnabled=$flashEnabled manualIso=$manualIso manualExp=$manualExposure ev=$exposureComp"
                )
                // v74 P0：拍照帧一结束就把 HAL 从预闪状态拉回来，否则小米 13U 等
                // HAL 会把 LED 一直点在「预闪常亮」上（切档也关不掉，只有关会话才灭）。
                resetFlashPrecapture("stillCaptureCompleted")
            }

            override fun onCaptureFailed(
                session: android.hardware.camera2.CameraCaptureSession,
                request: CaptureRequest,
                failure: android.hardware.camera2.CaptureFailure
            ) {
                // 拍照失败也不能把预闪状态留在 HAL 里
                resetFlashPrecapture("stillCaptureFailed")
            }
        }

    /** 一次快门的碎片。只在相机后台线程（JPEG/RAW 监听、结果回调、超时 Runnable）上访问，无需加锁。 */
    private class PendingShot {
        var jpeg: ByteArray? = null
        var rotation: Int = 0
        var rawImage: Image? = null
        var result: TotalCaptureResult? = null
        var completed = false
        var timeoutPosted = false
    }

    /** JPEG / RAW / 快门结果三者到齐（或超时）→ 交付落卷。相机线程上调用。 */
    private fun tryCompletePendingShot() {
        val p = pendingShot ?: return
        if (p.completed) return
        if (p.jpeg != null && p.rawImage != null && p.result != null) {
            p.completed = true
            deliverPendingShot(p)
        }
    }

    /** 超时兜底：RAW 拿不到了就按纯 JPEG 帧交付，帧不丢。相机线程上调用。 */
    private fun completePendingShotOnTimeout() {
        val p = pendingShot ?: return
        if (p.completed) return
        p.completed = true
        p.rawImage?.close()
        p.rawImage = null
        bufferedRaw?.close()
        bufferedRaw = null
        android.util.Log.w(TAG, "RAW 碎片未到齐（raw=${p.rawImage != null} result=${p.result != null}），按纯 JPEG 帧落卷")
        deliverPendingShot(p)
    }

    /** 汇合后的交付：JPEG 必须有；RAW 齐 → 相机线程外写 DNG 再回调，否则直接回调。 */
    private fun deliverPendingShot(p: PendingShot) {
        pendingShot = null
        val jpeg = p.jpeg
        val image = p.rawImage
        val result = p.result
        if (jpeg == null || image == null || result == null) {
            // 任一缺件：DNG 不写（Image 关掉），JPEG 照常落卷
            image?.close()
            if (jpeg == null) return
            onPhotoCaptured?.invoke(jpeg, p.rotation, null)
            return
        }
        val chars = cameraChars
        if (chars == null) {
            image.close()
            onPhotoCaptured?.invoke(jpeg, p.rotation, null)
            return
        }
        // DNG 写盘是重 IO：丢给专职线程，相机线程立刻回去伺候预览
        val submitted = runCatching {
            rawWriterExecutor.execute {
                val tmp = File(context.cacheDir, "raw_shot_${System.currentTimeMillis()}.dng")
                val ok = runCatching {
                    android.hardware.camera2.DngCreator(chars, result).use { creator ->
                        java.io.FileOutputStream(tmp).use { out -> creator.writeImage(out, image) }
                    }
                    tmp.length() > 0
                }.onFailure {
                    android.util.Log.e(TAG, "DNG 写盘失败（本帧按 JPEG 负片）: ${it.message}", it)
                }.getOrDefault(false)
                image.close()
                val dng = if (ok) tmp else { tmp.delete(); null }
                android.util.Log.i(
                    TAG, "RAW 底片 ${if (dng != null) "${tmp.length() / 1024}KB" else "失败"} -> ${dng?.name}"
                )
                onPhotoCaptured?.invoke(jpeg, p.rotation, dng)
            }
        }.isSuccess
        if (!submitted) {
            image.close()
            onPhotoCaptured?.invoke(jpeg, p.rotation, null)
        }
    }

    /**
     * 多帧覆盖：最亮帧的三路碎片到齐后写 DNG 母片交付。相机线程调用。
     * 与 [deliverPendingShot] 同样的 DngCreator 写盘，但 raw/result 来自按 timestamp
     * 配对的 burst 帧，不是单槽 pendingShot。写盘失败降级纯 JPEG，绝不丢帧。
     */
    private fun deliverBurstBest(bf: BurstFrame) {
        if (burstDelivered) return
        burstDelivered = true
        burstActive = false
        val jpeg = bf.jpeg ?: return
        val image = bf.raw
        val result = bf.result
        val chars = cameraChars
        if (image == null || result == null || chars == null) {
            // 配对失败（timestamp 不一致/RAW 未到）→ 纯 JPEG 落卷，不比验证版差
            image?.close()
            android.util.Log.w(TAG, "burst 最亮帧 RAW/result 未配对，按纯 JPEG 落卷")
            onPhotoCaptured?.invoke(jpeg, bf.rotation, null)
            return
        }
        val submitted = runCatching {
            rawWriterExecutor.execute {
                val tmp = File(context.cacheDir, "raw_burst_${System.currentTimeMillis()}.dng")
                val ok = runCatching {
                    android.hardware.camera2.DngCreator(chars, result).use { creator ->
                        java.io.FileOutputStream(tmp).use { out -> creator.writeImage(out, image) }
                    }
                    tmp.length() > 0
                }.onFailure {
                    android.util.Log.e(TAG, "burst DNG 写盘失败（按 JPEG 负片）: ${it.message}", it)
                }.getOrDefault(false)
                image.close()
                val dng = if (ok) tmp else { tmp.delete(); null }
                android.util.Log.i(TAG, "burst 母片 ${if (dng != null) "${tmp.length() / 1024}KB" else "失败"} -> ${dng?.name}")
                onPhotoCaptured?.invoke(jpeg, bf.rotation, dng)
            }
        }.isSuccess
        if (!submitted) {
            image.close()
            onPhotoCaptured?.invoke(jpeg, bf.rotation, null)
        }
    }

    /** burst 收尾：关掉所有非选中帧的 RAW，避免 Image 泄漏。 */
    private fun closeBurstRawExcept(keepTs: Long) {
        for ((ts, bf) in burstMap) {
            if (ts != keepTs) {
                bf.raw?.close()
                bf.raw = null
            }
        }
    }

    /**
     * 内置闪光灯是否开启。
     *
     * 注意语义变化：以前「闪光灯」是用常亮手电（FLASH_MODE_TORCH）在快门那一刻亮 200ms
     * 顶一下 —— torch 是降功率常亮，亮度远低于真正的拍照闪光脉冲，且拍照的 capture
     * request 压根不带闪光参数，所以成片几乎看不出闪过。现在改成相机 HAL 的拍照闪光：
     * v75 P1 起：ALWAYS_FLASH **只挂在 STILL_CAPTURE 那一帧**，预览与预闪请求保持
     * AE_ON + FLASH_MODE_OFF。理由是 Camera2 允许 HAL 在 ALWAYS_FLASH 下于预览/预闪
     * 测光阶段点亮 LED，小米 13U 点亮后不会自己收（拍完常亮、切档无效、关会话才灭）；
     * 而 v74 的一次性 CANCEL 会被随后常驻的 repeating 请求立刻覆盖，等于边关边开。
     * 拍照帧仍按「有闪光」重新测光（主体提亮、背景压暗），直闪成片味道不变。
     */
    @Volatile private var flashEnabled: Boolean = false

    /** 设备是否有闪光灯单元；没有的话只能退回 torch 常亮补光 */
    @Volatile private var hasFlashUnit: Boolean = true

    /** 无闪光灯单元时的兜底：torch 常亮的开启状态 */
    @Volatile private var torchOn: Boolean = false

    // ---- v74 P0：AE 预闪状态跟踪（只用于收尾与诊断，不改成像） ----
    /** 是否发出过 PRECAPTURE_START 却还没 CANCEL —— 小米 13U 常亮的直接诱因 */
    @Volatile private var precapturePending = false
    @Volatile private var flashPrecaptureStarts = 0
    @Volatile private var flashPrecaptureCancels = 0
    @Volatile private var lastAeState: Int? = null
    @Volatile private var lastFlashResetReason = "none"

    // ---------------- 慢门（胶片曝光模型）的能力与测光快照 ----------------

    /** 传感器曝光范围（秒），取自 SENSOR_INFO_EXPOSURE_TIME_RANGE */
    private var deviceMinExposureSec = 1f / 8000f
    private var deviceMaxExposureSec = 0.2f

    /** 传感器 ISO 范围（SENSOR_INFO_SENSITIVITY_RANGE） */
    private var deviceIsoRange = 50..3200

    /** 主摄实际光圈（f 值）：把预览 AE 的曝光量还原成场景亮度要用它 */
    private var apertureValue = 1.8f

    /** 是否支持 MANUAL_SENSOR：不支持就只能降级，慢门不可用 */
    private var hasManualSensor = false

    /** 能否关掉镜头光学防抖。T3 没有防抖，不关的话长曝光根本抖不起来 */
    private var canDisableOis = false

    /** 能否关掉降噪。多帧降噪会把抖动「修」平，等于白拍 */
    private var canDisableNr = false

    /** 降噪最低档（关不掉时的次优选择，比默认档少抹细节） */
    private var canDisableNrMinimal = false

    /** 能否关掉边缘锐化。手机的锐化会在边缘留 halo，正是「数码味」的来源之一 */
    private var canDisableEdge = false

    /** 能否关掉零快门延迟（ZSL）。不能关时 HAL 可能从缓冲里挑一张合成过的旧帧 */
    private var canDisableZsl = false

    /**
     * 能否把色调曲线钉成线性（CONTRAST_CURVE + ≥2 个曲线点）。
     * 直闪帧用它关掉厂商 tone mapping —— 不然 ISP 会把被闪光压黑的背景又提回来。
     */
    private var canLinearTonemap = false

    /** 设备 AE 补偿步长（EV/档，如 1/2 = 0.5EV）；读不到时保持 1（最保守） */
    private var aeCompStep = android.util.Rational(1, 1)

    /** 预览 AE 最近一次测光结果 —— 慢门决策的唯一输入 */
    @Volatile private var lastAeSec = 0f
    @Volatile private var lastAeIso = 0

    // ---- 外置闪光「相机侧闭环 TTL」（2026-11-19）----
    // A2 的 BLE 接口没有可用的功率控制（A1 帧只有开/关、TC 增量帧会让它熄火），
    // 闪光当量只能靠 ISO 调。做法：按下快门先拍一张测光帧（同快门、低 ISO、照常
    // 触发外置灯），从 JPEG 中位亮度反解需要的 ISO，再打第二闪拍正式帧。
    /**
     * 外置闪光 + 专业档时置 true：由 TTL 接管曝光。
     *
     * 🔴 必须跳过 applyExternalFlashSync —— 那里会把这一帧的曝光写死成
     * iso=228/100ms（真机 18:58 日志实证），专业模式的 ISO/快门与 TTL 求解值
     * 全被它覆盖，等于白算。
     */
    /**
     * 相机侧 TTL 的累积曝光修正（EV）。
     *
     * 每拍一张就把实测偏差累加进来，下一张按新值曝光 —— 单闪、不预闪，
     * 所以每张都是真实可用的照片。真机实测闪光指令比快门早约 450ms，
     * 「先测后拍」的双闪方案在本机物理上不可行（测光帧必然全黑）。
     */
    @Volatile var externalTtlEv = 0f

    /**
     * 用户闪光补偿（EV）：落到 **TTL 目标亮度** 上，不乘进曝光增益。
     *
     * 🔴 乘进增益会被闭环原样抵消 —— TTL 追的是绝对亮度，你在增益上抬一档，
     * 它下一帧就压回来，用户按 EV 键等于没按。改成挪目标才是真的「拍亮一点」。
     */
    @Volatile var externalTtlTargetEv = 0f

    /**
     * 上一次「测到光」的成片中位亮度（-1 = 还没成功测过）。
     *
     * 用来区分「真的欠曝」和「闪光压根没触发」：重连后首张闪光常常不亮（BLE 复位
     * 刚跑完），画面纯黑但曝光其实是对的。若把它当成欠曝去 +2EV，下一张就会
     * 直接过曝。判据是「上一张还是正常的（>=40），这一张突然全黑」⇒ 闪光失效。
     */
    @Volatile private var lastHealthyP50 = -1

    /** 灯侧 TTL 当前功率档（arg 0=1/1…70=1/128）与上次有效距离（换场景检测用）。 */
    @Volatile private var lampPowerArg: Int = TtlMeter.FLASH_POWER_REF_ARG
    @Volatile private var lampLastDistanceM: Float = 0f
    @Volatile var externalTtlEnabled = false

    /** 功率帧验证热调：flash_ttl_off.txt 内容=1 时跳过 TTL 闭环，避免它补偿掉功率差。 */
    @Volatile private var ttlHotOff = false

    /** 预闪测光可行性探针：flash_meter_probe.txt=1 时打印每张原始 p50/p90 + 功率字节。 */
    @Volatile private var meterProbe = false

    /** 多帧覆盖：连拍帧数（0/1=关，默认 8 转正）。一次快门连拍 N 帧，选最亮的交付。 */
    @Volatile private var burstFrames = BURST_DEFAULT_FRAMES

    /** 调研路①：三段式 Take 热调帧数（1=单发原样，3=三段式，缺文件=1）。 */
    @Volatile private var researchStages = 1

    /** 调研路②：FEC8 回电就绪门控（false=原样立即触发）。 */
    @Volatile private var readyGate = false

    /** 调研路③：YUV 环形缓冲帧数（0=关）。 */
    @Volatile private var yuvBurstFrames = 0

    /** 调研路③：YUV 帧数字曝光修正（EV，负=压暗；0=不修正）。 */
    @Volatile private var yuvEvComp = 0f

    /** 固定闪光功率热调（-1=不干预，0~70=功率 arg）。 */
    @Volatile private var fixedPowerArg = -1

    /** burst 单帧碎片：JPEG/RAW/result 三路靠 SENSOR_TIMESTAMP 配对，绝不能配错帧。 */
    private class BurstFrame {
        var ts: Long = -1L
        var jpeg: ByteArray? = null
        var rotation: Int = 0
        var p50: Int = -1
        var raw: Image? = null
        var result: TotalCaptureResult? = null
    }

    private var burstActive = false
    private var burstJpegCount = 0
    private var burstBestP50 = -1
    private var burstPickedTs = -1L
    private var burstDelivered = false
    private val burstMap = HashMap<Long, BurstFrame>()
    @Volatile private var ttlMetering = false
    @Volatile private var ttlSecondPass = false
    @Volatile private var ttlSkipRawFrames = 0
    @Volatile private var ttlMeterIso = 0
    @Volatile private var ttlFinalIso = 0
    /** 正式帧沿用的快门（TTL 只改 ISO，不动快门）。 */
    @Volatile private var ttlShutterNs = 8_000_000L
    /** 测光结果回调（测光 ISO, 正式 ISO, 测光帧 p50, 说明），给 UI 显示用。 */
    var onExternalTtlResult: ((Int, Int, Int, String) -> Unit)? = null

    /**
     * 外闪黑片侦测回调（11-22）：外闪档直出中心 p50 ≤ [FLASH_MISS_P50_MAX] 时判定
     * 「灯没闪（没电/失联）」，把中心 p50 与环境基线 ISO 传给 UI 提示用户。
     * 只提示、不影响落卷（用户选「照常落卷」自己决定删不删）。
     */
    var onFlashMissDetected: ((Int, Int) -> Unit)? = null
    /** 本次快门是否走了外闪正式曝光（takePicture 置位，JPEG 回调据此守卫黑片判定）。 */
    @Volatile private var flashFrameArmed: Boolean = false
    /**
     * 灯侧 TTL：请求把 A2 闪光功率调到指定档（arg: 0=1/1 … 70=1/128）。
     * 由 MainActivity 绑定到 FlashController.lampControl().setFlashPowerLevel。
     * 返回是否成功写入（失败时本次不改档，避免状态漂移）。
     */
    var onFlashPowerRequest: ((Int) -> Boolean)? = null

    /** 实验分支：repeating precapture 的 aeState 监听，仅在一次拍照期间临时注册 */
    @Volatile private var halPrecaptureAeSink: ((Int) -> Unit)? = null

    /** 方案③ TTL：PRECAPTURE START 瞬间的环境测光快照（闪光前、无污染）。 */
    @Volatile private var ttlAmbientIso = 0
    @Volatile private var ttlAmbientSec = 0f

    /** 方案③ TTL：预闪窗口内看到的最小 ISO×快门乘积（闪光照亮后的测光）。 */
    @Volatile private var ttlFlashIso = 0
    @Volatile private var ttlFlashSec = 0f

    /**
     * 方案③ TTL 近距帽：precapture 那一帧临时开 AF 读回的主体距离（米）。
     * 0f = 未取到/不可信（HAL 没跑 AF、屈光度为 0 或超出可信区间）→ 不介入。
     */
    @Volatile private var ttlSubjectDistanceM = 0f

    /**
     * 外置闪光同步用的「环境光测光基线」。
     *
     * 真机 11-06 实测：外接 COB 灯闪起来时，AE 会在闪光峰值上重新测光，把
     * ISO 从 2649 砍到 272、快门从 1/33 砍到 1/50（曝光量 -14.6 倍），但 RAW
     * 归一化后场景其实只亮了 2.88 倍 —— 净欠曝约 2.3 档，成片就是黑的。
     * 所以只在 AE 收敛（CONVERGED/LOCKED）时更新基线；闪光一来 AE 进 SEARCHING，
     * 基线自然停留在闪光前的环境光读数，不会被峰值带跑。
     */
    @Volatile private var aeBaselineSec = 0f
    @Volatile private var aeBaselineIso = 0

    /** 外置闪光档：快门帧改用锁定的环境光基线，不让 HAL 在闪光峰值重新测光 */
    @Volatile private var externalFlashSync = false

    /**
     * 编排式同步的灯能力（A2 氙气灯 true）。由 [setExternalFlashSync] 第三参数
     * 随当前灯下发，会话级；仅当 EXTERNAL 档 + A2 时为 true。
     */
    @Volatile private var orchestratedFlashCapable = false
    /**
     * 本次拍照是否真正走编排式同步。每次 [readFlashSyncConfig] 按
     * 「灯能力 && 热调文件未强制关闭」重算，保证真机可即时回退。
     */
    @Volatile private var orchestratedFlashSync = false

    /**
     * 传感器真正开始曝光的回调。外接灯的 BLE 触发指令必须**在这一刻**发出：
     * 按快门时发的话，灯在 t+0 就闪完，而三星 HAL 要 ~240ms 后才开曝光，
     * 闪光根本进不了画面（真机 11-06 实测）。
     */
    @Volatile var onExposureStarted: (() -> Unit)? = null

    /**
     * 调研路①：三段式序列启动前的「复位到氙气档」回调。11-19 用户实证
     * 「点了复位后每次都是氙气」，三段式必须先把灯拉回 0c=00 氙气通道才有意义。
     * 默认 null = 不复位（零行为变化）。
     */
    @Volatile var onBeforeStagedFlash: (() -> Unit)? = null

    /**
     * 调研路②：查询外闪是否已回电就绪（FEC8 value[3]==0）。返回 true=就绪可拍 /
     * false=回电中需等待 / null=该灯无回电状态通道（不门控）。默认 null。
     */
    @Volatile var isFlashReady: (() -> Boolean?)? = null

    /**
     * 外置闪光触发偏移（ms，相对「曝光开始」的灯起辉补偿）。负值 = 提前发指令，
     * 用来补偿「BLE 传输 + 灯固件预闪序列 + COB 起辉」约 200ms 的固有延迟。
     * 每次拍照从外部私有目录 `flash_sync_offset.txt` 重读，方便 adb push 热调。
     *
     * 下限放宽到 -400：短 HAL 延迟机型需要把触发提前到提交后几十毫秒，
     * 旧的 [-250, +100] 会把 delay 卡在 135ms 以上，怎么扫都够不着。
     * 正半区 [0, +100] 必然漏闪（灯 200ms 起辉 > 100ms 曝光窗口），保留只为逆向调参。
     */
    @Volatile private var flashTriggerOffsetMs: Long = DEFAULT_FLASH_SYNC_OFFSET_MS
    /**
     * 当前外置灯的档案同步偏移（ms）。由 [setExternalFlashSync] 随灯传入，
     * 在 [readFlashSyncConfig] 中作为「热调文件不存在时」的按灯默认值。
     * 热调文件 flash_sync_offset.txt 仍优先（保留 adb 扫参能力）。
     */
    @Volatile private var profileFlashOffsetMs: Int? = null
    @Volatile private var flashSyncGain: Float = FLASH_SYNC_GAIN_DEFAULT
    /** 外闪帧 AWB 是否钉日光（默认开，热调 flash_awb_daylight.txt=0 关）。 */
    @Volatile private var extFlashAwbDaylight: Boolean = true
    /**
     * 外置闪光档的细档补偿（EV，0.5 一档，−3…+3）。切到 EXT 后机顶 EV 拨盘
     * 只改这个值；常规档的 [exposureComp] 原样保留，切回 OFF / INT 时拨盘
     * 自动恢复整数 AE 补偿，两套值互不污染。
     */
    @Volatile private var externalFlashCompEv: Float = 0f

    /**
     * 最近一帧拍照的**真实**传感器参数，只供 EXIF / 复盘读取，绝不参与成像。
     *
     * EXT 档由 [applyExternalFlashSync] 在构造拍照请求时直接写入 —— 手写曝光
     * 的 ISO / 快门是我们下发给 HAL 的确定值，且早于 JPEG 到货，最可靠；
     * 常规 AE 档由 still capture 的 onCaptureCompleted 用 TotalCaptureResult 回填。
     * 老帧 / 拿不到结果时为 0，EXIF 侧跳过不写。
     */
    @Volatile var lastCaptureIso: Int = 0
        private set
    @Volatile var lastCaptureShutterNs: Long = 0L
        private set

    /** 真实镜头参数：设备静态能力打底，拍照帧的 TotalCaptureResult 上报时覆盖。 */
    @Volatile var lastCaptureAperture: Float = 0f
        private set
    @Volatile var lastCaptureFocalMm: Float = 0f
        private set

    /**
     * 外闪手动标定样本快照（11-22）：一次性打包暴露给 MainActivity 写进 ShotMeta/EXIF。
     * 只读诊断字段，绝不参与成像。MainActivity 在落卷点调用。
     */
    data class FlashCalibSnapshot(
        val powerArg: Int,       // 下发给灯的功率字节 arg（0~70），-1=未记录
        val distM: Float,        // AF 主体距离（米）
        val ambIso: Int,         // 闪光前环境 ISO 基线
        val ambSec: Float,       // 闪光前环境快门基线（秒）
        val p50: Int,            // 相机直出中心亮度 p50（本帧 JPEG 回调算出）
    )
    /** 本帧直出中心亮度，JPEG 回调里写入，供落卷时的标定快照读取。 */
    @Volatile private var lastFrameP50: Int = 0

    /**
     * 外闪手动标定档（11-22）：由 MainActivity 的 Pro 模式 + 外置闪光档驱动。
     * 开启后 [applyExternalFlashSync] 用手动 ISO/快门（[manualIso]/[manualExposure]）
     * 而不是 gain 反算，让用户的手动设置真正作用到拍照帧，供人工标定采样。
     * 额外的 manualIso!=null 判据保证退出 Pro（setManualIso(null)）后自动回落，
     * 即使这个标志因故未清也不会卡在手动档。
     */
    @Volatile var extFlashManualCalib: Boolean = false

    val flashCalibSnapshot: FlashCalibSnapshot
        get() = FlashCalibSnapshot(
            powerArg = com.filmcam.app.flash.A2PowerProtocol.lastSentPowerByte,
            distM = ttlSubjectDistanceM,
            ambIso = aeBaselineIso,
            ambSec = aeBaselineSec,
            p50 = lastFrameP50,
        )

    private var flashTriggerPosted = false

    // ---- EXT 外闪取证（写进诊断报告，用来区分「指令没发」和「发晚了」）----
    private var extTriggerCount = 0
    @Volatile private var extLastTriggerAtMs = 0L
    /** onCaptureStarted 的曝光开始 sensor 时钟(ns)，触发时快照为 burst 取证基准。 */
    @Volatile private var lastCaptureStartedTs = 0L
    @Volatile private var burstTriggerSensorTs = 0L
    @Volatile private var extLastTriggerWhere = "-"
    @Volatile private var extLastPreRollMs = -1L
    @Volatile private var extTriggerToExposureMs = -1L
    @Volatile private var extExposurePending = false

    /** 提交拍照请求的 wall-clock；配合 onCaptureStarted 实测「提交 → 曝光开始」。 */
    @Volatile private var flashSubmitRealtimeMs = 0L
    /** 最近 3 次实测延迟（只在 backgroundHandler 线程访问），取中位数抗抖动。 */
    private val flashHalLatencySamples = java.util.ArrayDeque<Long>()
    /** 本帧采用的 HAL 延迟：首帧按当前通路兜底，之后由实测中位数替换。 */
    @Volatile private var flashHalLatencyMs = FLASH_HAL_LATENCY_MS_JPEG
    /** halLatency 来源：true=来自上次会话实测缓存（可信），false=兜底值（未校准）。 */
    @Volatile private var flashHalLatencyCalibrated = false

    /** 当前通路的 HAL 延迟兜底值（JPG 直提交 ~40ms / RAW 多挂 surface ~270ms）。 */
    private fun flashLatencyFallbackMs(): Long =
        if (rawNegativeEnabled) FLASH_HAL_LATENCY_MS_RAW else FLASH_HAL_LATENCY_MS_JPEG

    /**
     * 上次会话实测的 HAL 延迟缓存（按通路分开）。
     *
     * 🔴 2026-11-19 首张漏闪的**根因**：`flashHalLatencyMs` 初值是兜底的 40ms，
     * 而本机实测 165ms。第一帧按 40ms 算 ⇒
     * `flashTriggerDelayMs(40, -80) = max(0, 40-80) = 0`，
     * 也就是「提交的同时发触发」，可曝光要 165ms 后才开始 —— 灯在开快门前
     * 165ms 就闪完了，**首张必黑**（真机三次复现：p50 = 3 / 2 / 6）。
     * 实测样本要到第一帧的 onCaptureStarted 才产生，救不了第一帧本身。
     * 所以把上一会话的中位数缓存下来，开局直接用它，首张就能对上。
     */
    private fun flashLatencyPrefsKey(): String =
        if (rawNegativeEnabled) "extHalLatencyRaw" else "extHalLatencyJpeg"

    private fun loadCachedFlashLatency(): Long? = runCatching {
        val v = context
            .getSharedPreferences("flash_sync", android.content.Context.MODE_PRIVATE)
            .getLong(flashLatencyPrefsKey(), -1L)
        if (v in 1L..2000L) v else null
    }.getOrNull()

    private fun saveFlashLatency(ms: Long) {
        runCatching {
            context.getSharedPreferences("flash_sync", android.content.Context.MODE_PRIVATE)
                .edit().putLong(flashLatencyPrefsKey(), ms).apply()
        }
    }

    init {
        // 开局就带着上一会话的实测延迟，否则第一帧永远按 40ms 兜底算错（见上）
        loadCachedFlashLatency()?.let {
            flashHalLatencyMs = it
            flashHalLatencyCalibrated = true
        }
        // 同样把上次的 TTL 增益带过来：否则每次重启都要从 0 增益开始，
        // 第一张必然偏暗一档多（真机：重启后首张 p50=118，目标 195）。
        // 11-22 TTL 重开：旧版本死代码期写进 prefs 的 externalTtlEv（真机残留 +2EV）
        // 是虚高基线，必须丢弃，否则第一张就按 +2EV 过曝。清零后让闭环按真实
        // 成片亮度逐张收敛（TtlMeter 首帧半步 +1EV 的设计正好接手）。
        if (EXTERNAL_TTL_ENABLED) {
            externalTtlEv = 0f
            saveTtlEv(0f)
            android.util.Log.i("FlashTtl", "TTL 重开：历史残留增益已清零，从 0EV 开始收敛")
        } else {
            loadCachedTtlEv()?.let { externalTtlEv = it }
        }
    }

    /** TTL 累积增益的缓存（跨会话），键名带通路无关性 —— 增益只跟场景/距离有关。 */
    private fun loadCachedTtlEv(): Float? = runCatching {
        val v = context
            .getSharedPreferences("flash_sync", android.content.Context.MODE_PRIVATE)
            .getFloat("extTtlEv", Float.NaN)
        if (v.isNaN() || v < -TtlMeter.MAX_CORRECTION_EV || v > TtlMeter.MAX_CORRECTION_EV) null else v
    }.getOrNull()

    /** 曝光时长模型开关（11-23）：默认开；热调 flash_latency_model.txt=0 回退旧实测缓存预测。 */
    @Volatile private var flashLatencyModel = true
    /** 本帧请求的曝光时长（applyExternalFlashSync 求解后写；0 = 非外闪固定曝光帧，模型不适用）。 */
    @Volatile private var pendingFlashExposureNs = 0L

    private fun saveTtlEv(ev: Float) {
        runCatching {
            context.getSharedPreferences("flash_sync", android.content.Context.MODE_PRIVATE)
                .edit().putFloat("extTtlEv", ev).apply()
        }
    }

    /** 每次拍照重读两个热调文件，方便 adb push 扫参，不用重新编译。
     *  实测样本刻意不重置 —— 它要跨帧累积才能做中位数。 */
    private fun readFlashSyncConfig() {
        val dir = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        // 触发时机模型（11-23）：默认按本帧曝光算延迟；写 0 回退上一帧实测缓存。
        flashLatencyModel = if (dir == null) true else runCatching {
            val f = java.io.File(dir, FLASH_LATENCY_MODEL_HOTFILE)
            !(f.exists() && f.readText().substringBefore("//").trim() == "0")
        }.getOrDefault(true)
        // 优先级：热调文件（adb 扫参）> 当前灯的档案 offset > 全局默认 -200。
        // 氙气灯（A2 实测 -80）与 COB 灯（-200）起辉速度不同，必须按灯取默认值。
        val fallback = profileFlashOffsetMs?.toLong() ?: DEFAULT_FLASH_SYNC_OFFSET_MS
        flashTriggerOffsetMs = if (dir == null) fallback else runCatching {
            val f = java.io.File(dir, "flash_sync_offset.txt")
            if (!f.exists()) fallback
            else f.readText().substringBefore("//").trim().toLongOrNull()
                ?: fallback
        }.getOrDefault(fallback).coerceIn(-400L, 100L)
        flashSyncGain = if (dir == null) FLASH_SYNC_GAIN_DEFAULT else runCatching {
            val f = java.io.File(dir, "flash_sync_gain.txt")
            if (!f.exists()) FLASH_SYNC_GAIN_DEFAULT
            else f.readText().substringBefore("//").trim().toFloatOrNull()
                ?: FLASH_SYNC_GAIN_DEFAULT
        }.getOrDefault(FLASH_SYNC_GAIN_DEFAULT).coerceIn(0.05f, 10.0f)
        // 功率帧验证热调：写 1 关掉 TTL 闭环，让固定 ISO 直接反映灯的功率差。
        ttlHotOff = if (dir == null) false else runCatching {
            val f = java.io.File(dir, "flash_ttl_off.txt")
            f.exists() && f.readText().substringBefore("//").trim() == "1"
        }.getOrDefault(false)
        // 预闪测光探针：只读日志、不改行为；写 1 开启，缺文件默认关。
        meterProbe = if (dir == null) false else runCatching {
            val f = java.io.File(dir, METER_PROBE_HOTFILE)
            f.exists() && f.readText().substringBefore("//").trim() == "1"
        }.getOrDefault(false)
        // 多帧覆盖验证帧数：缺文件/非法/0/1 = 关（0），2~8 生效。
        // 转正：默认 8 帧开启；热调文件可覆盖（写 0/1 关闭 A/B，写 2~8 改帧数）。
        burstFrames = if (dir == null) BURST_DEFAULT_FRAMES else runCatching {
            val f = java.io.File(dir, BURST_HOTFILE)
            if (!f.exists()) BURST_DEFAULT_FRAMES
            else f.readText().substringBefore("//").trim().toIntOrNull() ?: BURST_DEFAULT_FRAMES
        }.getOrDefault(BURST_DEFAULT_FRAMES).coerceIn(0, 20).let { if (it < 2) 0 else it }
        // 调研路①：三段式 Take。缺文件/非法/1 = 关（单发原样），2~4 = 段数。
        researchStages = if (dir == null) 1 else runCatching {
            val f = java.io.File(dir, STAGES_HOTFILE)
            if (!f.exists()) 1 else f.readText().substringBefore("//").trim().toIntOrNull() ?: 1
        }.getOrDefault(1).coerceIn(1, 4)
        // 热调显式开三段式（2~4）时覆盖 profile 的 stages（A2 被锁 1）；=1 时不碰，
        // 让 setExternalFlashStages(profile.triggerStages) 的原值生效。
        if (researchStages >= 2) {
            flashStages = researchStages
            stagedFlashStarted = false
            android.util.Log.w("FlashSync", "调研路①三段式启用：stages=$researchStages（热调覆盖 profile）")
        }
        // 调研路②：FEC8 回电就绪门控。写 1 开启，缺文件=关。
        readyGate = if (dir == null) false else runCatching {
            val f = java.io.File(dir, READY_GATE_HOTFILE)
            f.exists() && f.readText().substringBefore("//").trim() == "1"
        }.getOrDefault(false)
        // 调研路③：YUV 环形缓冲帧数。缺文件/非法/0/1=关，2~90 生效（30fps×3s 上限）。
        yuvBurstFrames = if (dir == null) 0 else runCatching {
            val f = java.io.File(dir, YUV_BURST_HOTFILE)
            if (!f.exists()) 0 else f.readText().substringBefore("//").trim().toIntOrNull() ?: 0
        }.getOrDefault(0).coerceIn(0, 90).let { if (it < 2) 0 else it }
        // 调研路③：YUV 数字曝光修正 EV。缺文件/非法=0，钳 -4~+2 档。
        yuvEvComp = if (dir == null) 0f else runCatching {
            val f = java.io.File(dir, YUV_EV_HOTFILE)
            if (!f.exists()) 0f else f.readText().substringBefore("//").trim().toFloatOrNull() ?: 0f
        }.getOrDefault(0f).coerceIn(-4f, 2f)
        // 固定闪光功率热调。缺文件/非法=-1（不干预），0~70 生效。
        fixedPowerArg = if (dir == null) -1 else runCatching {
            val f = java.io.File(dir, FLASH_PWR_HOTFILE)
            if (!f.exists()) -1 else f.readText().substringBefore("//").trim().toIntOrNull() ?: -1
        }.getOrDefault(-1).let { if (it < 0) -1 else it.coerceIn(0, 70) }
        // 外闪帧 AWB 钉日光。缺文件=开，只有显式写 0 才关（真机 A/B 回退用）。
        extFlashAwbDaylight = if (dir == null) true else runCatching {
            val f = java.io.File(dir, EXT_FLASH_AWB_DAYLIGHT_HOTFILE)
            !f.exists() || f.readText().substringBefore("//").trim() != "0"
        }.getOrDefault(true)
        // 编排式同步：默认由灯能力决定；热调文件写 0 强制回退旧预测逻辑（真机 A/B）。
        val hotOff = if (dir == null) false else runCatching {
            val f = java.io.File(dir, ORCHESTRATED_HOTFILE)
            f.exists() && f.readText().substringBefore("//").trim() == "0"
        }.getOrDefault(false)
        orchestratedFlashSync = externalFlashSync && orchestratedFlashCapable && !hotOff
        if (orchestratedFlashSync) {
            android.util.Log.i(
                "FlashSync",
                "编排式同步启用：触发绑 onCaptureStarted / 宽窗 " +
                    "${ORCHESTRATED_SYNC_SHUTTER_NS / 1_000_000}ms / 锁 ISO 不变（A2 氙气灯）"
            )
        }
        flashTriggerPosted = false
    }

    /** 真正发一次 BLE 触发，并防重（预触发与 onCaptureStarted 只会走一条）。 */
    private fun fireExternalFlashOnce(where: String) {
        if (flashTriggerPosted) return
        flashTriggerPosted = true
        android.util.Log.i(
            "FlashSync",
            "触发外接灯 @ $where offset=${flashTriggerOffsetMs}ms" +
                " halLatency=${flashHalLatencyMs}ms t=${android.os.SystemClock.elapsedRealtime()}" +
                // 标定取证：AE 关闭时 SENSOR_SENSITIVITY / SENSOR_EXPOSURE_TIME 报的就是
                // 真正生效的手动值；配上 lastSentPowerByte 才能判断亮度差是不是灯造成的
                " iso=${lastAeIso} exp=${lastAeSec}s pwr=${com.filmcam.app.flash.A2PowerProtocol.lastSentPowerByte}"
        )
        extTriggerCount++
        extLastTriggerAtMs = android.os.SystemClock.elapsedRealtime()
        // burst 取证：把触发时刻锚到 sensor 时钟，后面每帧 ts - 它 = 相对触发的曝光时刻
        burstTriggerSensorTs = lastCaptureStartedTs
        extLastTriggerWhere = where
        extExposurePending = true
        reportExtFlashState()
        onExposureStarted?.invoke()
        // 调研路③：触发后等环形缓冲填满（N 帧 @30fps + 余量），挑亮度跃升最大的帧。
        if (yuvRingActive) {
            backgroundHandler?.postDelayed(
                { pickYuvFlashFrame() },
                (yuvBurstFrames * 33L + 300L).coerceAtMost(4_000L)
            )
        }
    }

    /** 调研路③：从环形缓冲挑 p50 峰值帧，对比触发前后基线，判定 YUV 是否看见闪光。 */
    private fun pickYuvFlashFrame() {
        val frames: List<YuvProbeFrame> = synchronized(yuvRing) { yuvRing.toList() }
        yuvRingActive = false
        if (frames.isEmpty()) {
            android.util.Log.w("YuvProbe", "路③：环形缓冲空，未采到帧")
            return
        }
        val base = frames.take(frames.size / 3).map { it.p50 }.filter { it >= 0 }
        val baseline = if (base.isEmpty()) 0 else base.sorted()[base.size / 2]
        val peak = frames.maxByOrNull { it.p50 }
        val peakP50 = peak?.p50 ?: -1
        val jump = peakP50 - baseline
        android.util.Log.w(
            "YuvProbe",
            "路③挑帧：frames=${frames.size} 基线p50=$baseline 峰值p50=$peakP50" +
                " 跃升=$jump ts=${peak?.ts}" +
                " ${if (jump >= 30) "★闪光被YUV捕获" else "未见明显跃升"}"
        )
        // 路③交付接管：抓到闪光 → 用 YUV 峰值帧替换暂存的相机帧交付（走暗房 JPEG 通路）；
        // 没抓到 → 回退相机原帧。两条路都只交一次（yuvDeliveryGuard 守）。
        val pending = yuvPendingJpeg
        if (pending == null) {
            android.util.Log.w("YuvProbe", "路③：无暂存相机帧，跳过交付替换")
            return
        }
        if (jump >= 30 && peak?.jpeg != null) {
            android.util.Log.w("YuvProbe", "路③：★用 YUV 闪光帧替换底片 (${peak.jpeg!!.size}B)")
            yuvPendingJpeg = null
            deliverFinalJpeg(peak.jpeg!!, yuvPendingRotation, null)
        } else {
            android.util.Log.w("YuvProbe", "路③：未见闪光，回退相机原帧交付")
            yuvPendingJpeg = null
            deliverFinalJpeg(pending, yuvPendingRotation, null)
        }
    }

    // 复刻康泰时 T3 35mm 视角：主摄等效约23mm，裁切至35mm ≈ 1.52x（常量与 RAW 冲洗端共享）
    @Volatile private var zoomRatio = app.spektralab.engine.FilmStockMapper.T3_ZOOM_RATIO

    /**
     * 当前实际下发给 HAL 的 T3 变焦比。拍照时随帧写进 `ShotMeta.zoom`，
     * 冲洗端按帧取它补裁 RAW 的 ActiveArea——不能让冲洗依赖这个进程内变量，
     * 否则冷启动直接进暗房、跨进程冲洗、换机导入时都会读到错的默认值。
     */
    val currentZoomRatio: Float get() = zoomRatio

    /** 预览流实际尺寸（start 时确定），点屏对焦坐标映射用 */
    private var previewSize: Size = Size(1920, 1440)

    fun setExposureComp(comp: Int) {
        // v81：值没变就不要重发 repeating。切闪光灯档位会连着调
        // setManualIso / setManualExposure / setExposureComp，三次都是旧值，
        // 白白往 HAL 塞三条重复请求，预览会出现一帧可感知的抖动。
        if (exposureComp == comp) return
        exposureComp = comp
        previewRequestBuilder?.let {
            it.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                comp.coerceIn(aeCompRange.first, aeCompRange.last)
            )
            captureSession?.setRepeatingRequest(it.build(), previewMetaCallback, backgroundHandler)
        }
    }

    /** 当前生效的白平衡预设（takePicture 的独立 builder 需要带上，否则成片不跟随预览） */
    private var currentAwbMode: Int? = null

    /**
     * 白平衡预设（闪光开启后横滑调 WB 用）。
     * @param awbMode CaptureRequest.CONTROL_AWB_MODE_* 常量；null = 恢复相机自动白平衡。
     * 走 ISP 级 CONTROL_AWB_MODE 而非 COLOR_CORRECTION_GAINS：前者对预览与成片
     * 同时生效且不需要机型校准，预设语义（日光/阴天…）也更贴合胶片机的滤镜片直觉。
     */
    fun setAwbMode(awbMode: Int?) {
        currentAwbMode = awbMode
        previewRequestBuilder?.let {
            it.set(
                CaptureRequest.CONTROL_AWB_MODE,
                awbMode ?: CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            captureSession?.setRepeatingRequest(it.build(), previewMetaCallback, backgroundHandler)
        }
    }

    // 拍照回调
    /**
     * 拍照回调：相机 ISP 输出的原始 JPEG 字节 + 像素需顺时针旋转的角度（0/90/180/270）
     * + RAW 底片临时文件（null = 这帧没有 DNG：RAW 关着 / 设备不支持 / 这帧 RAW 没拿到）。
     * 不做任何解码/旋转/重编码——那会把一次编码变成两次并多吃约 100MB 内存。
     * 方向只作为元数据随帧记录，由冲洗侧统一旋转。DNG 由落卷方移进底片目录，临时文件
     * 用完（或落卷失败）由落卷方负责删除。
     */
    var onPhotoCaptured: ((ByteArray, Int, File?) -> Unit)? = null

    /** 唯一落卷出口（路③接管时经此统一交付，保证一次快门只交一次）。 */
    private fun deliverFinalJpeg(jpeg: ByteArray, rotation: Int, dng: File? = null) {
        if (yuvDeliveryGuard && yuvBurstFrames >= 2) return
        yuvDeliveryGuard = true
        onPhotoCaptured?.invoke(jpeg, rotation, dng)
    }

    /** 取景器元信息回调：(AF 状态, 曝光时间秒)，驱动 T3 取景器内的合焦灯与快门档显示 */
    var onViewfinderMeta: ((afState: Int?, exposureSec: Float, isoAe: Int) -> Unit)? = null
    private var lastMetaPostAt = 0L

    /** 当前设备屏幕旋转（0/90/180/270），拍照方向要用 */
    private fun deviceRotationDegrees(): Int {
        val rot = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching { context.display?.rotation }.getOrNull()
        } else null
        val r = rot ?: @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.rotation
        return when (r) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /** Camera2 标准公式：JPEG 旋转角 = (屏幕旋转映射 + 传感器方向 + 270) % 360 */
    private fun jpegRotationDegrees(sensorOrientation: Int): Int {
        val mapped = when (deviceRotationDegrees()) {
            90 -> 0
            180 -> 270
            270 -> 180
            else -> 90
        }
        return (mapped + sensorOrientation + 270) % 360
    }

    fun start(surfaceTexture: SurfaceTexture) {
        backgroundThread = HandlerThread("Camera2").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: "0"

        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        minFocusDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        // ---- T3 35mm 视角动态化：读传感器物理尺寸 + 镜头实际焦距，算出本机等效焦距 ----
        // 等效焦距 = 实际焦距 × (43.27 / 传感器对角线mm)
        // 裁切比 = 35 / 本机等效焦距
        // 读不到时保持默认 1.52（23mm），不会比之前差
        runCatching {
            val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            if (sensorSize != null && focalLengths != null && focalLengths.isNotEmpty()) {
                // 传感器对角线 = sqrt(w² + h²)
                val diag = kotlin.math.hypot(sensorSize.width.toDouble(), sensorSize.height.toDouble()).toFloat()
                // 取第一个（主摄通常只有一个焦距）
                val focal = focalLengths[0]
                if (diag > 0f && focal > 0f) {
                    FilmStockMapper.setT3ZoomRatio(diag, focal)
                    // 同步本地 zoomRatio（预览/拍照请求要用）
                    zoomRatio = FilmStockMapper.T3_ZOOM_RATIO
                    // 诊断：把 HAL 自报的 zoom 范围一起打出来。若 ratio 超出 upper，
                    // HAL 会悄悄钳制而帧记录仍写未钳值 → 采集端与冲洗端裁切比不一致，
                    // 也是「RAW 成片变小、视角对不上」的可能来源，真机日志一查便知。
                    // 这里只记录、不钳制：S25U 的 1.52 已由 HAL ROI 实证，贸然钳到某些
                    // 机型上报的 [1.0,1.0] 反而会破坏已验证的 35mm 视角。
                    val halRange = if (android.os.Build.VERSION.SDK_INT >= 30) {
                        runCatching { chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) }
                            .getOrNull()
                    } else null
                    android.util.Log.i(
                        "Camera2",
                        "T3 视角裁切比 sensorDiag=${diag}mm focal=${focal}mm " +
                            "ratio=$zoomRatio halZoomRange=$halRange"
                    )
                }
            }
        }.onFailure { android.util.Log.w("Camera2", "读取传感器物理尺寸失败，使用默认裁切比 $zoomRatio", it) }

        // 读设备真实的 AE 补偿范围（缺失时保留默认 -2..2 这个最保守区间）
        chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { r ->
            if (r.upper > r.lower) aeCompRange = r.lower..r.upper
        }

        // 设备有没有真闪光灯：没有的话 HAL 闪光模式无效，退回手电筒顶一下的老办法
        hasFlashUnit = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

        // ---- 慢门能力探测：能手动曝光多久、能关什么（OIS / 降噪 / ZSL） ----
        chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { r ->
            if (r.upper > r.lower) {
                deviceMinExposureSec = r.lower / 1_000_000_000f
                deviceMaxExposureSec = r.upper / 1_000_000_000f
            }
        }
        chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { r ->
            if (r.upper > r.lower) deviceIsoRange = r.lower..r.upper
        }
        chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?.firstOrNull { it > 0f }?.let { apertureValue = it; lastCaptureAperture = it }
        chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull { it > 0f }?.let { lastCaptureFocalMm = it }
        hasManualSensor = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            canDisableOis = chars
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF) == true
        }
        canDisableNr = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            ?.contains(android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_OFF) == true
        canDisableNrMinimal = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
            ?.contains(android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) == true
        canDisableEdge = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
            ?.contains(android.hardware.camera2.CameraMetadata.EDGE_MODE_OFF) == true
        // ZSL 开关是 API 30 才有的 key，且没有公开的能力查询接口：
        // 只能在请求里试着设，失败就静默跳过（部分 HAL 不认这个 key）。
        canDisableZsl = android.os.Build.VERSION.SDK_INT >= 30
        // 直闪帧要用线性色调曲线压住 ISP 的暗部提亮：设备得认 CONTRAST_CURVE 模式
        // 且至少给 2 个曲线点（0,0 和 1,1 就够画直线）
        runCatching {
            val modes = chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
            val points = chars.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0
            if (points >= 2 && modes != null &&
                modes.contains(android.hardware.camera2.CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            ) canLinearTonemap = true
        }
        // AE 补偿步长：换算闪光帧 EV 偏置要用（1/2 与 1/3 步长的设备很常见）
        chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.let { s ->
            if (s.numerator > 0 && s.denominator > 0) aeCompStep = s
        }
        android.util.Log.i(
            "FlashTuning",
            "caps tonemapLinear=$canLinearTonemap aeStep=$aeCompStep"
        )
        val capsLine = "slowShutter caps manualSensor=$hasManualSensor " +
            "exp=${deviceMinExposureSec}~${deviceMaxExposureSec}s iso=$deviceIsoRange " +
            "ap=$apertureValue oisOff=$canDisableOis nrOff=$canDisableNr " +
            "nrMin=$canDisableNrMinimal edgeOff=$canDisableEdge zslOff=$canDisableZsl"
        android.util.Log.i("SlowShutter", capsLine)
        runCatching {
            java.io.File(context.applicationInfo.dataDir, "files").let { d ->
                java.io.File(d, "filmcam_diag.txt").appendText("$capsLine\n")
            }
        }

        // 预览分辨率：限制在 1920 长边以内。部分机型（三星 S25/Adreno + Android 16）
        // 把 12MP 全尺寸作为 SurfaceTexture 私有流时，GL 消费者绑定 EGLImage 会
        // 持续失败（bindTextureImage 0x502），取景器黑屏；且预览远超屏幕分辨率
        // 白白耗电。拍照走 ImageReader JPEG，仍取全尺寸，不受影响。
        //
        // v68 P0：**必须优先选 4:3**。旧逻辑只比面积，vivo V2227A / X300 Ultra 的
        // HAL 报出 1920×1920(1:1, 3.686M) > 1920×1440(4:3, 2.765M)，方形纹理被
        // shader 的常量内容比例（竖 3:4 / 横 4:3）直接拉伸 33.3% —— 用户看到
        // 「画面比实物宽大」。取景器 shader 不读 previewSize，所以只能在选尺寸时收敛。
        // S25U 最大面积候选本来就是 1920×1440，改策略后逐像素零变化。
        val allPreviewSizes = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val picked = PreviewSizePolicy.choose(
            allPreviewSizes.map { it.width to it.height }, maxLongEdge = 1920
        )
        val chosenBy = when {
            picked == null -> "fallbackMinSize"
            PreviewSizePolicy.isTargetAspect(picked.first, picked.second) -> "4x3"
            else -> "fallbackMaxArea"
        }
        val previewSize = if (picked != null) {
            Size(picked.first, picked.second)
        } else {
            // 全表都超过 1920 长边（极端机型）：退回最小档，别让私有流炸掉
            allPreviewSizes.minByOrNull { it.width * it.height }
                ?.let { Size(it.width, it.height) }
                ?: Size(1920, 1440)
        }
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        this.previewSize = previewSize
        runCatching {
            java.io.File(context.applicationInfo.dataDir, "files").let { d ->
                java.io.File(d, "filmcam_diag.txt").appendText("previewSize=$previewSize\n")
            }
        }

        // 全分辨率拍照（JPEG 最大尺寸）
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
        val maxJpeg = jpegSizes?.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
        // 缓冲 6：多帧覆盖验证连拍最多 8 帧，JPEG 回调是逐帧异步的，缓冲太小会在
        // 处理前溢出丢帧（正常单帧拍照只用 1 个槽，提到 6 无副作用）。
        // 缓冲 20：默认 18 帧连拍不丢帧。
        imageReader = ImageReader.newInstance(maxJpeg.width, maxJpeg.height, ImageFormat.JPEG, 20)
        // 调研路③：YUV 预览流 reader（用预览尺寸，30fps 高频采帧）。缓冲给到 yuvBurstFrames
        // 上限 90 + 余量，避免环形缓冲处理慢时被 HAL 丢弃。默认路③关（yuvBurstFrames=0）
        // 时不创建，零开销。
        // 相机启动时热调值可能还没被 takePicture 刷新，这里直接读一次文件确保路③开关生效。
        val yuvFramesAtOpen = runCatching {
            val d = context.getExternalFilesDir(null)
            val f = java.io.File(d, YUV_BURST_HOTFILE)
            if (f.exists()) f.readText().substringBefore("//").trim().toIntOrNull() ?: 0 else 0
        }.getOrDefault(0).coerceIn(0, 90)
        if (yuvFramesAtOpen >= 2) {
            yuvBurstFrames = yuvFramesAtOpen
            try {
                yuvImageReader = ImageReader.newInstance(
                    previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 12
                ).also { reader ->
                    reader.setOnImageAvailableListener({ r ->
                        val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                        try {
                            onYuvFrame(img)
                        } finally {
                            img.close()
                        }
                    }, backgroundHandler)
                }
                android.util.Log.w("YuvProbe", "路③：YUV reader 已创建 ${previewSize.width}x${previewSize.height}")
            } catch (e: Exception) {
                android.util.Log.w("YuvProbe", "路③：YUV reader 创建失败，路③不可用", e)
                yuvImageReader = null
            }
        }
        // 读取传感器方向
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        android.util.Log.i("Camera2", "previewSize=$previewSize sensorOrientation=$sensorOrientation")
        // v63 起只读取证：把预览尺寸 / 比例 / 传感器能力记进跨机型报告。
        // v68 新增失配告警 —— 没有 4:3 候选的机型仍会回退旧逻辑，靠这两个字段
        // 一眼区分「尺寸选对了」与「HAL 只给了畸形比例」，别再靠截图猜。
        runCatching {
            val asp = previewSize.width.toFloat() / previewSize.height.toFloat()
            val candidates = map?.getOutputSizes(SurfaceTexture::class.java)
                ?.filter { maxOf(it.width, it.height) <= 1920 }
                ?.sortedByDescending { it.width * it.height }
                ?.take(12)
                ?.joinToString(" ") { "${it.width}x${it.height}" } ?: "?"
            com.filmcam.app.develop.DeviceGeometryReport.putAll(
                "camera.previewSize" to "${previewSize.width}x${previewSize.height}",
                "camera.previewAspect" to "%.4f".format(java.util.Locale.US, asp),
                // 与选择策略同一口径：max/min 归一化，兼容厂商竖置上报的尺寸
                "camera.previewIs4x3" to PreviewSizePolicy.isTargetAspect(
                    previewSize.width, previewSize.height
                ),
                "camera.previewIs16x9" to (kotlin.math.abs(asp - 16f / 9f) < 0.01f),
                "camera.previewChosenBy" to chosenBy,
                "camera.previewAspectMismatch" to !PreviewSizePolicy.isTargetAspect(
                    previewSize.width, previewSize.height
                ),
                "camera.previewAspectErrorPct" to "%.1f".format(
                    java.util.Locale.US,
                    PreviewSizePolicy.aspectErrorFraction(
                        previewSize.width, previewSize.height
                    ) * 100f
                ),
                "camera.sensorOrientation" to sensorOrientation,
                "camera.displayRotationDeg" to deviceRotationDegrees(),
                "camera.hasManualSensor" to hasManualSensor,
                "camera.isoRange" to "${deviceIsoRange.first}..${deviceIsoRange.last}",
                "camera.exposureSec" to "%.6f..%.6f".format(
                    java.util.Locale.US, deviceMinExposureSec, deviceMaxExposureSec
                ),
                "camera.previewCandidates" to candidates,
                "camera.maxJpeg" to "${maxJpeg.width}x${maxJpeg.height}",
            )
            reportFlashState()
            com.filmcam.app.develop.DeviceGeometryReportIo.flush(context)
        }

        // 拍照方向：Camera2 标准公式 (屏幕旋转映射 + 传感器方向 + 270) % 360。
        // 竖屏(0°)  : (90 + 90 + 270) % 360 = 90  → 传感器横画面转 90° 成 3:4 竖构图
        // 横屏(90°) : (0 + 90 + 270) % 360 = 0   → 直接用传感器原生 4:3 横构图
        // 反向横屏  : (180 + 90 + 270) % 360 = 180
        // 每次出片时现算设备朝向：Activity 旋转重建后朝向会变，不能只在 start() 取一次。
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            // SENSOR_TIMESTAMP 是 JPEG/RAW/result 三路唯一的同帧锚点，必须在 close 前抓。
            val ts = image.timestamp
            val bytes = try {
                val buffer = image.planes[0].buffer
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } finally {
                image.close()
            }
            // 多帧覆盖：连拍的 N 帧不逐张交付，按 timestamp 存三路碎片、比亮度，
            // 选吃到主闪的最亮帧；收满后用同帧 RAW 写 DNG 母片交付（配对失败降级 JPEG）。
            if (burstActive) {
                val reading = analyzeMeteringLuma(bytes)
                val bf = burstMap.getOrPut(ts) { BurstFrame().also { it.ts = ts } }
                bf.jpeg = bytes
                bf.rotation = jpegRotationDegrees(sensorOrientation)
                bf.p50 = reading.p50
                burstJpegCount++
                // 取证：这帧 JPEG 到货距灯触发多久。触发时刻→出光延迟是本次要定的未知量，
                // 闪光若进画面，那一帧 p50 会跳高，配合这个时间戳就能反推真实出光延迟。
                val sinceTriggerMs = if (extLastTriggerAtMs > 0L)
                    android.os.SystemClock.elapsedRealtime() - extLastTriggerAtMs else -1L
                // 曝光开始时刻相对触发的偏移(ns→ms)：闪光若进画面，那帧 p50 跳高，
                // 这条值就是「触发→出光」的真实延迟，用来定 BURST_TRIGGER_DELAY_MS。
                val expRelTriggerMs = if (burstTriggerSensorTs > 0L)
                    (ts - burstTriggerSensorTs) / 1_000_000L else -1L
                android.util.Log.i(
                    "BurstProbe",
                    "帧 $burstJpegCount/$burstFrames 曝光+${expRelTriggerMs}ms 到货+${sinceTriggerMs}ms" +
                        " p50=${reading.p50} p90=${reading.p90}" +
                        " pwr=${com.filmcam.app.flash.A2PowerProtocol.lastSentPowerByte}"
                )
                if (reading.p50 > burstBestP50) {
                    burstBestP50 = reading.p50
                    burstPickedTs = ts
                }
                if (burstJpegCount >= burstFrames && burstPickedTs >= 0) {
                    closeBurstRawExcept(burstPickedTs)
                    val best = burstMap[burstPickedTs]
                    android.util.Log.i(
                        "BurstProbe",
                        "选中最亮帧 ts=$burstPickedTs p50=$burstBestP50" +
                            " raw=${best?.raw != null} result=${best?.result != null} → 交付落卷"
                    )
                    if (best != null) {
                        if (best.raw != null && best.result != null) deliverBurstBest(best)
                        // 三路没齐：等 RAW/result 回调里再触发交付（带超时兜底）
                    }
                }
                return@setOnImageAvailableListener
            }
            if (ttlMetering) {
                ttlMetering = false
                val reading = analyzeMeteringLuma(bytes)
                val sol = TtlMeter.solve(ttlMeterIso, reading)
                ttlFinalIso = sol.finalIso
                android.util.Log.i(
                    "FlashTtl",
                    "测光帧 p50=" + reading.p50 + " p90=" + reading.p90 +
                        " 裁切=" + "%.1f".format(reading.clippedPct) + "%" +
                        " → ISO " + sol.meterIso + " → " + sol.finalIso +
                        "（" + sol.correctionEv + "EV，" + sol.note + "）"
                )
                onExternalTtlResult?.invoke(sol.meterIso, sol.finalIso, reading.p50, sol.note)
                // 第二闪用解出来的 ISO；快门保持用户设定的那一档
                manualExposure = ttlShutterNs
                setManualIso(sol.finalIso)
                flashTriggerPosted = false
                ttlSecondPass = true
                backgroundHandler?.postDelayed({
                    if (externalFlashSync) takePicture(false, 0L, 0)
                }, TtlMeter.SECOND_SHOT_DELAY_MS)
                return@setOnImageAvailableListener
            }
            // 🔴 灯侧 TTL（11-22 抓到 a0 01 功率帧后定稿）：这张拍完，量成片亮度，
            // 调整**灯的功率档**（不是相机 ISO）—— 背景曝光稳定、零延迟、对齐官方。
            // 相机 ISO 锁标定 228 不动，ttlGain 恒 1（不再用 externalTtlEv 调 ISO）。
            if (externalTtlEnabled && externalFlashSync && !ttlHotOff) {
                val reading = analyzeMeteringLuma(bytes)
                val dark = reading.p50 <= TtlMeter.CLIP_LOW
                // 闪光失效识别：上一张健康、这张突然全黑 = 灯没亮/回电没跟上，不算欠曝，
                // 否则会误把功率拉满（11-19 老坑）。与相机侧不同：这里即使误判也只走 1 档。
                val flashMiss = dark && lastHealthyP50 >= 40
                if (!flashMiss) {
                    val adjust = TtlMeter.flashPowerAdjust(reading.p50)
                    if (adjust != 0) {
                        val target = (lampPowerArg + adjust).coerceIn(
                            TtlMeter.POWER_ARG_MIN, TtlMeter.POWER_ARG_MAX)
                        if (target != lampPowerArg) {
                            val sent = onFlashPowerRequest?.invoke(target) == true
                            if (sent) lampPowerArg = target
                            android.util.Log.i("FlashTtl",
                                "灯侧TTL 成片 p50=${reading.p50} → 功率档 $lampPowerArg→$target" +
                                " (${if (adjust > 0) "降功率" else "升功率"} ${kotlin.math.abs(adjust / TtlMeter.POWER_ARG_STEP)}档)" +
                                " sent=$sent")
                        }
                    }
                    if (!dark) lastHealthyP50 = reading.p50
                } else {
                    android.util.Log.i("FlashTtl",
                        "灯侧TTL 成片 p50=${reading.p50} [闪光未触发/回电中，本帧不调功率]")
                }
                onExternalTtlResult?.invoke(lastCaptureIso, 0, reading.p50, "灯侧功率TTL")
            }
            // 预闪测光可行性探针（默认关）：独立于 TTL 闭环，把每张相机直出 JPEG 的
            // 中心亮度 + 触发时的真实功率字节打出来，用来定量判断「相机读数能否区分
            // 近/远、能否跟随功率变化」。只读不改：不影响拍照/触发/调功率任何行为。
            // 中心亮度只解一次码：探针日志与黑片侦测共用（避免每张外闪都解码两遍）。
            val centerLuma = if (meterProbe || externalFlashSync) analyzeMeteringLuma(bytes) else null
            // 外闪标定：把本帧直出中心亮度存进快照，落卷时随 ShotMeta 写进 EXIF。
            if (externalFlashSync && centerLuma != null) lastFrameP50 = centerLuma.p50
            if (meterProbe) {
                android.util.Log.i(
                    "MeterProbe",
                    "成片 p50=${centerLuma?.p50} p90=${centerLuma?.p90} 裁切=${"%.1f".format(centerLuma?.clippedPct ?: 0f)}%" +
                        " iso=$lastCaptureIso ambExp=${"%.4f".format(lastAeSec)}s" +
                        " pwr=${com.filmcam.app.flash.A2PowerProtocol.lastSentPowerByte}" +
                        " dist=$ttlSubjectDistanceM ttlOff=$ttlHotOff" +
                        " orchestrated=$orchestratedFlashSync"
                )
            }
            // 外闪黑片侦测（11-22）：灯没电/失联时 p50≈1，正常张≥91。只提示不拦落卷。
            // 守卫：本次是外闪正式帧（takePicture 按 externalFlashSync && !slow 置位）、
            // 非 TTL 测光帧（测光帧曝光量本就不同，不参与判定）。
            if (flashFrameArmed && !ttlMetering && centerLuma != null &&
                centerLuma.p50 <= FLASH_MISS_P50_MAX
            ) {
                android.util.Log.w(
                    "FlashMiss",
                    "外闪黑片：中心 p50=${centerLuma.p50} ≤ $FLASH_MISS_P50_MAX" +
                        "（灯没闪/没电/失联）iso=$lastCaptureIso 环境基线iso=${aeBaselineIso}" +
                        " pwr=${com.filmcam.app.flash.A2PowerProtocol.lastSentPowerByte}"
                )
                onFlashMissDetected?.invoke(centerLuma.p50, aeBaselineIso)
            }
            val rotation = jpegRotationDegrees(sensorOrientation).also {
                android.util.Log.i("Camera2", "capture jpegRotation=$it deviceRotation=${deviceRotationDegrees()}")
            }
            android.util.Log.i(TAG, "JPEG 到货：rawNegativeEnabled=$rawNegativeEnabled rawReader=${rawImageReader != null}")
            // 调研路③交付接管：外闪档 + YUV 环形缓冲开启时，相机这帧 JPEG 只是"碰运气"
            // （闪光可能落在曝光窗外=黑片）。暂存它、不走 RAW 汇合（三星闪光 RAW 常是黑
            // 底片），等 YUV 判定后用真正抓到闪光的那帧替换交付。超时兜底保证不丢卷。
            if (yuvBurstFrames >= 2 && externalFlashSync && yuvRingActive) {
                yuvPendingJpeg = bytes
                yuvPendingRotation = rotation
                yuvDeliveryGuard = false
                android.util.Log.w("YuvProbe", "路③：相机帧暂存(${bytes.size}B)，等 YUV 判定替换")
                // 兜底：YUV 判定若因故没跑，1s 后用原相机帧交付（不丢卷）
                backgroundHandler?.postDelayed({
                    if (!yuvDeliveryGuard && yuvPendingJpeg != null) {
                        val fb = yuvPendingJpeg ?: return@postDelayed
                        android.util.Log.w("YuvProbe", "路③：YUV 判定超时，回退相机原帧交付")
                        yuvPendingJpeg = null
                        deliverFinalJpeg(fb, yuvPendingRotation, null)
                    }
                }, 3_500L)
                return@setOnImageAvailableListener
            }
            if (!rawNegativeEnabled || rawImageReader == null) {
                // RAW 没开 / 不支持：老路径，JPEG 即全部
                deliverFinalJpeg(bytes, rotation, null)
            } else {
                // RAW 开着：JPEG 先进汇合处，等 RAW 图 + 快门结果（或超时）一起交付
                val p = pendingShot ?: PendingShot().also { pendingShot = it }
                if (!p.completed) {
                    p.jpeg = bytes
                    p.rotation = rotation
                    // RAW 图可能早于本回调到货：从寄存槽取回（早到 ≠ 丢失）
                    bufferedRaw?.let { p.rawImage = it; bufferedRaw = null }
                    if (!p.timeoutPosted) {
                        p.timeoutPosted = true
                        backgroundHandler?.postDelayed({ completePendingShotOnTimeout() }, RAW_WAIT_MS)
                    }
                    tryCompletePendingShot()
                } else {
                    // 上一张的超时碎片混进来（极小概率）：按独立 JPEG 帧交付，不丢
                    deliverFinalJpeg(bytes, rotation, null)
                }
            }
        }, backgroundHandler)

        // P3 RAW 负片：RAW_SENSOR 第二路（16bit Bayer，没过 tone mapping / AWB / 多帧合成）。
        // 能力探测 + 选档（≤16MP 的最大档，避开 200MP 全尺寸 Bayer）。
        cameraChars = chars
        val rawSizes: List<Size> = runCatching {
            map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val rawPicked = pickRawSize(rawSizes.map { it.width to it.height })
        if (rawPicked != null) {
            val rs = Size(rawPicked.first, rawPicked.second)
            // 缓冲 20：18 帧连拍每帧一份 RAW，不丢帧（单帧路径 2 够用）。
            rawImageReader = ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 20)
            rawImageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                // 测光帧的 RAW 必须丢掉：否则它会占住 bufferedRaw 单槽，
                // 正式帧的 JPEG 就会跟测光帧的 Bayer 配成一对
                if (ttlSkipRawFrames > 0) {
                    ttlSkipRawFrames--
                    image.close()
                    return@setOnImageAvailableListener
                }
                // 多帧覆盖：RAW 按 timestamp 归入同帧的 burstMap 碎片（不写单槽）。
                // 最亮帧还没选出时先收着；选出后非最亮帧的 RAW 会在收尾时统一 close。
                if (burstActive) {
                    val ts = image.timestamp
                    val bf = burstMap.getOrPut(ts) { BurstFrame().also { it.ts = ts } }
                    bf.raw = image
                    if (burstJpegCount >= burstFrames && burstPickedTs == ts &&
                        bf.jpeg != null && bf.result != null
                    ) {
                        deliverBurstBest(bf)
                    }
                    return@setOnImageAvailableListener
                }
                val p = pendingShot
                if (p == null || p.completed) {
                    // RAW 可能比 TotalCaptureResult 还早到（三星 HAL 实测如此：
                    // AE 流程拖长时结果回调最后才来）——这时不能丢，寄存单槽等锚点。
                    // 超时/交付时会清槽；新 RAW 到货覆盖旧槽（先关旧的防泄漏）。
                    bufferedRaw?.close()
                    bufferedRaw = image
                    return@setOnImageAvailableListener
                }
                p.rawImage = image
                tryCompletePendingShot()
            }, backgroundHandler)
            android.util.Log.i(TAG, "RAW 负片可用：${rs.width}x${rs.height}（HAL 共 ${rawSizes.size} 档）")
        } else {
            android.util.Log.i(TAG, "RAW 负片不可用：HAL 无 RAW_SENSOR 输出")
        }
        runCatching {
            java.io.File(context.applicationInfo.dataDir, "files").let { d ->
                java.io.File(d, "filmcam_diag.txt").appendText(
                    "rawSensor=${rawPicked != null} rawSize=${rawPicked?.first}x${rawPicked?.second}\n"
                )
            }
        }

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(surfaceTexture)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraDevice = null
                    Toast.makeText(context, "Camera2 打开失败: $error", Toast.LENGTH_SHORT).show()
                }
            }, backgroundHandler)
        } catch (e: SecurityException) {
            Toast.makeText(context, "需要相机权限", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 调研路③：处理一帧 YUV 预览帧 —— 算中心亮度 p50 + 转 NV21/JPEG，存进环形缓冲。
     * 触发拍照后 [pickYuvFlashFrame] 会从缓冲里挑亮度跃升最大的一帧交付当底片。
     */
    private fun onYuvFrame(image: Image) {
        if (!yuvRingActive) return
        try {
            val w = image.width
            val h = image.height
            val yPlane = image.planes[0]
            val yBuf = yPlane.buffer
            val yRowStride = yPlane.rowStride
            val yPixStride = yPlane.pixelStride

            // 中心 40% 区域采样算 p50（Y plane 直接读，不解码，开销极低）
            val hist = IntArray(256)
            val x0 = (w * 0.3f).toInt()
            val x1 = (w * 0.7f).toInt()
            val y0 = (h * 0.3f).toInt()
            val y1 = (h * 0.7f).toInt()
            var total = 0
            var yy = y0
            while (yy < y1) {
                var base = yy * yRowStride + x0 * yPixStride
                var xx = x0
                while (xx < x1) {
                    hist[yBuf.get(base).toInt() and 0xFF]++
                    total++
                    base += yPixStride
                    xx += 2
                }
                yy += 2
            }
            var p50 = 0
            if (total > 0) {
                var acc = 0
                for (v in 0..255) {
                    acc += hist[v]
                    if (p50 == 0 && acc >= total * 0.5f) { p50 = v; break }
                }
            }

            // 转 NV21 → JPEG（供交付底片）。只在环形缓冲窗口内做，开销可接受。
            val nv21 = yuv420ToNv21(image)
            // 调研路③亮度修正：对 Y plane（NV21 前 w*h 字节）乘 2^EV 数字降亮。
            // UV 保持中性 128 不动；p50 判定已在上方用原始值算好（闪光命中不受影响）。
            if (nv21 != null && yuvEvComp != 0f) {
                val gain = Math.pow(2.0, yuvEvComp.toDouble()).toFloat()
                val yLen = w * h
                for (i in 0 until yLen) {
                    val v = (nv21[i].toInt() and 0xFF) * gain
                    nv21[i] = v.toInt().coerceIn(0, 255).toByte()
                }
            }
            val jpeg = if (nv21 != null) {
                val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null)
                val out = java.io.ByteArrayOutputStream()
                yuvImage.compressToJpeg(android.graphics.Rect(0, 0, w, h), 92, out)
                out.toByteArray()
            } else null

            synchronized(yuvRing) {
                val frame = YuvProbeFrame().apply {
                    ts = image.timestamp
                    this.p50 = p50
                    this.jpeg = jpeg
                }
                yuvRing.addLast(frame)
                while (yuvRing.size > yuvBurstFrames) yuvRing.removeFirst()
            }
        } catch (e: Exception) {
            android.util.Log.w("YuvProbe", "路③：单帧处理异常", e)
        }
    }

    /** YUV_420_888 → NV21（YuvImage 只吃 NV21）。三个 plane 按 stride 逐行拷贝。 */
    private fun yuv420ToNv21(image: Image): ByteArray? {
        val w = image.width
        val h = image.height
        if (image.planes.size < 3) return null
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val out = ByteArray(w * h * 3 / 2)
        // Y 平面
        val yb = y.buffer
        for (row in 0 until h) {
            yb.position(row * y.rowStride)
            yb.get(out, row * w, w)
        }
        // UV 全部填中性值 128 → 输出纯灰度图。
        // 🔴 不再自己解析 U/V plane：三星的 U/V 布局（共享 buffer / pixelStride /
        // 交错顺序）在不同 ROM 上不一致，实测两种"正确"写法分别得到整片绿 / 整片白。
        // 而闪光成片本就是黑白胶片风格，Y plane（亮度）才是信号本体——Y 我读得很准
        // （p50 就是对它算的）。UV=128 是中性灰，YuvImage 出来的就是干净的黑白成片，
        // 彻底绕开色度布局的坑。
        java.util.Arrays.fill(out, w * h, out.size, 128.toByte())
        return out
    }

    /**
     * 量测光帧中心 50% 区域的亮度分布。
     *
     * 只看中心：闪光按 1/d² 衰减，边角与主体不是同一个曝光量，混进来会把中位数
     * 拉歪。降采样到 480px 以内，一张全尺寸 JPEG 的解析只要十几毫秒。
     */
    private fun analyzeMeteringLuma(bytes: ByteArray): TtlMeter.Reading {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            while (maxDim / sample > 480) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return TtlMeter.Reading(0, 0, 0f)
            val w = bmp.width
            val h = bmp.height
            val x0 = w / 4
            val y0 = h / 4
            val cw = (w / 2).coerceAtLeast(1)
            val ch = (h / 2).coerceAtLeast(1)
            val row = IntArray(cw)
            val hist = IntArray(256)
            var clipped = 0
            var total = 0
            for (y in y0 until (y0 + ch).coerceAtMost(h)) {
                bmp.getPixels(row, 0, cw, x0, y, cw, 1)
                for (px in row) {
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    val l = (r * 77 + g * 150 + b * 29) shr 8
                    hist[l]++
                    total++
                    if (l >= 250) clipped++
                }
            }
            bmp.recycle()
            if (total == 0) return TtlMeter.Reading(0, 0, 0f)
            var acc = 0
            var p50 = 0
            var p90 = 0
            for (v in 0..255) {
                acc += hist[v]
                if (p50 == 0 && acc >= total * 0.5f) p50 = v
                if (p90 == 0 && acc >= total * 0.9f) p90 = v
            }
            TtlMeter.Reading(p50, p90, clipped * 100f / total)
        } catch (_: Throwable) {
            TtlMeter.Reading(0, 0, 0f)
        }
    }

    private fun createSession(surfaceTexture: SurfaceTexture) {
        val camera = cameraDevice ?: return
        val previewSurface = Surface(surfaceTexture)
        this.previewSurface = previewSurface
        val readerSurface = imageReader!!.surface

        // RAW surface 只要设备支持就常驻会话（开关切换不重建会话——
        // 往没配置过的 surface 上 addTarget 会让整次 capture 直接抛异常）
        val outputs = mutableListOf(previewSurface, readerSurface)
        rawImageReader?.let { outputs.add(it.surface) }
        // 调研路③：YUV surface 加入会话（常驻），让预览流持续出 YUV 帧进环形缓冲。
        yuvImageReader?.let { outputs.add(it.surface) }

        previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            // 调研路③：预览流同时输出到 YUV reader（30fps 环形缓冲采帧）。
            yuvImageReader?.let { addTarget(it.surface) }
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyManualSettings(this)
            applyPreviewFlashSettings(this)
            applyHalPrecaptureArming(this)
            applyFimoSceneArming(this)
            // 白平衡预设可能在相机开启前就被设置（restoreSettings 早于会话建立），此处补挂
            set(
                CaptureRequest.CONTROL_AWB_MODE,
                currentAwbMode ?: CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
        }

        camera.createCaptureSession(
            outputs,
            object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                    captureSession = session
                    // 快速退出相机页时设备可能已 close，HAL 的配置回调晚到一步，
                    // setRepeatingRequest 会抛 IllegalStateException 把整个进程带走（真机实锤）。
                    // 预览流没起来只是页面黑一瞬，吞掉比崩掉好。
                    runCatching {
                        session.setRepeatingRequest(previewRequestBuilder!!.build(), previewMetaCallback, backgroundHandler)
                    }.onFailure {
                        android.util.Log.w(TAG, "会话配置回调迟到，预览请求未发出: ${it.message}")
                    }
                }
                override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                    Toast.makeText(context, "会话创建失败", Toast.LENGTH_SHORT).show()
                }
            },
            backgroundHandler
        )
    }

    /**
     * 拍摄全分辨率照片。
     *
     * @param defocus true = 本次故意对焦不准（模拟偶发失焦）
     * @param slowExposureNs > 0 时，本次走「胶片慢门」：手动长曝光，不走 HAL 自动测光
     * @param slowIso 与 [slowExposureNs] 配套的感光度（守住曝光量，画面不会变亮/变黑）
     */
    fun takePicture(defocus: Boolean = false, slowExposureNs: Long = 0L, slowIso: Int = 0) {
        try {
            readFlashSyncConfig()
            val camera = cameraDevice ?: return
            val readerSurface = imageReader!!.surface
            val slow = slowExposureNs > 0L && slowIso > 0
            // 本次是否外闪正式帧（黑片侦测守卫）：TTL 测光帧会在下面覆写成 false。
            flashFrameArmed = externalFlashSync && !slow
            // 外闪标定：每帧清零直出亮度，避免落到上一帧的值。
            lastFrameP50 = 0
            // 调研路③：开启环形缓冲采集（触发前清空，只留本次快门附近的帧）。
            if (yuvBurstFrames >= 2 && yuvImageReader != null && externalFlashSync && !slow) {
                synchronized(yuvRing) { yuvRing.clear() }
                yuvRingActive = true
                yuvRingArmedAtMs = android.os.SystemClock.elapsedRealtime()
                yuvDeliveryGuard = false
                yuvPendingJpeg = null
                android.util.Log.w("YuvProbe", "路③：环形缓冲已布防 frames=$yuvBurstFrames")
            }
            // 相机侧闭环 TTL：外置闪光 + 曝光已锁（专业档）时，先拍测光帧。
            // 快门对闪光无效、光圈固定，能动的只有 ISO，所以「先量后拍」是唯一闭环。
            // 编排模式下不走「双闪测光帧」分支：它会 setManualIso(finalIso)，但编排帧的
            // applyExternalFlashSync 随后按 gain 覆盖 ISO，反解结果白算。编排用更简单的
            // 跨张闭环（见 imageReader 回调里 EXTERNAL_TTL_ENABLED 段）：每张量亮度 →
            // externalTtlEv 累加 → 下一张 gain 自动调 ISO。效果等价且零冲突。
            // 🔴 flash_ttl_off.txt=1 必须连**拍前两段式测光帧**一起关：此前 ttlHotOff 只
            // 挡了拍后功率闭环，这段仍偷偷跑（拍一张 ISO100 测光帧 + 隔 2.6s 再拍正式帧），
            // 表现为「一次快门闪两次、第一次全功率成片黑、第二次碰运气」。扫参/取证时必须
            // 回到干净单次拍照，否则测光帧污染读数且闪光时机错乱。
            if (EXTERNAL_TTL_ENABLED && externalFlashSync && externalTtlEnabled && !slow &&
                !ttlMetering && !ttlSecondPass && !orchestratedFlashSync && !ttlHotOff
            ) {
                ttlMetering = true
                ttlSkipRawFrames = 1
                // 曝光窗口必须撑到同步下限：闪光只持续约 1ms、预触发又提前
                // 150~230ms，8ms 的窗口实测抓不到（p50=1）
                ttlShutterNs = maxOf(
                    manualExposure ?: TtlMeter.SYNC_SHUTTER_NS,
                    TtlMeter.SYNC_SHUTTER_NS
                )
                manualExposure = ttlShutterNs
                ttlMeterIso = manualIso ?: 400
                // 测光帧压到 ISO100 以内，避免全功率闪光把它打爆（打爆就测不出量级）
                manualIso = minOf(ttlMeterIso, TtlMeter.METER_ISO_MAX)
                flashTriggerPosted = false
                android.util.Log.i(
                    "FlashTtl",
                    "测光帧开始：ISO " + manualIso + " / " + ttlShutterNs + "ns"
                )
                // 兜底：测光帧万一没回来，不能把后续每一张都当测光帧
                backgroundHandler?.postDelayed({
                    if (ttlMetering) {
                        ttlMetering = false
                        ttlSkipRawFrames = 0
                        android.util.Log.w("FlashTtl", "测光帧 4s 未回，回退单闪")
                    }
                }, 4000L)
                takePicture(defocus, slowExposureNs, slowIso)
                return
            }
            if (ttlSecondPass) ttlSecondPass = false
            // 多帧覆盖：连拍帧也收 RAW，靠 SENSOR_TIMESTAMP 与 JPEG/result 按帧配对。
            val burstMode = burstFrames >= 2 && externalFlashSync && !slow
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                // 编排式触发的帧身份标记：onCaptureStarted 只认这个 tag 的帧发灯，
                // 排除持续回调的预览帧（时间闸门伪解法已两次失败）。
                setTag(STILL_CAPTURE_TAG)
                addTarget(readerSurface)
                // P3 RAW：开着的快门同时收 RAW_SENSOR（同一帧的 Bayer 原始数据）。
                // burst 帧也要收——转正后最亮帧要写 DNG 母片。
                if (rawNegativeEnabled) {
                    rawImageReader?.surface?.let { addTarget(it) }
                }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                applyManualSettings(this)
                // 白平衡跟随预览当前预设（STILL_CAPTURE 是独立 builder，不带会回到 AUTO）
                set(
                    CaptureRequest.CONTROL_AWB_MODE,
                    currentAwbMode ?: CaptureRequest.CONTROL_AWB_MODE_AUTO
                )
                // 模拟偶发失焦：手动对焦到失焦距离（只影响本次拍照，预览不受影响）
                if (defocus && minFocusDistance > 0f) {
                    set(CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, minFocusDistance * 0.6f)
                }
                applyStillFlashSettings(this)
                // 采集端「不加工」：胶片模拟要从最生的像素开始
                applyCaptureTuning(this)
                // 直闪帧的专属调优（AWB 钉日光 / 线性色调曲线 / 负 EV），
                // 内部自带条件判断，非直闪帧直接返回
                applyFlashCaptureTuning(this, slow)
                // 外置闪光：把这一帧锁到「闪光前的环境光读数」，不让 HAL 在闪光
                // 峰值上重新测光（那样会把曝光砍掉十几倍，成片发黑）。
                // 外置闪光一律走 App 自己的同步曝光（100ms 窗口）：真机实测闪光
                // 指令比快门早约 450ms，短窗口必然拍空。TTL 的修正量通过
                // externalTtlEv 乘进这里的 gain，而不是改手动 ISO。
                // TTL 测光帧：必须用 manualIso(=100) 的自定义曝光测光，跳过编排的固定
                // ISO 覆盖（否则测光帧被改成 ISO228 全白、反解失效）。正式帧照常走编排。
                if (externalFlashSync && !slow && !ttlMetering) applyExternalFlashSync(this)
                // 多帧覆盖：沿用 applyExternalFlashSync 的 CONTROL_MODE=AUTO + AE_MODE_OFF。
                // 实测 CONTROL_MODE=OFF 反而让三星 HAL 忽略手动传感器参数（iso=7232/40ms），
                // 与历史单帧成功配置（AUTO+AE_OFF → iso=227/100ms）不一致，故不再覆盖。
                // 必须放在最后：慢门要覆盖 AE 模式，也要压过上面闪光分支的 ALWAYS_FLASH
                if (slow) applySlowShutter(this, slowExposureNs, slowIso)
            }
            // 灯侧 TTL P0/P2：拍前用 AF 距离算功率初值（每张独立看自己的距离），
            // 换场景（>1.6×）时重置到新距离的初值，避免继承上一场的功率。
            if (externalTtlEnabled && externalFlashSync && !ttlHotOff &&
                com.filmcam.app.flash.FlashProfile.isA2Device(com.filmcam.app.flash.FlashController.active?.currentLightName(), null)
            ) {
                val d = ttlSubjectDistanceM
                if (d > 0f && TtlMeter.isSceneChange(lampLastDistanceM, d)) {
                    lampPowerArg = TtlMeter.flashPowerForDistance(d)
                    lampLastDistanceM = d
                    val sent = onFlashPowerRequest?.invoke(lampPowerArg) == true
                    android.util.Log.i("FlashTtl",
                        "灯侧TTL 换场景 dist=${"%.2f".format(d)}m → 功率档重置 arg=$lampPowerArg sent=$sent")
                } else if (d > 0f) {
                    lampLastDistanceM = d
                }
            }
            // 固定功率热调（独立于 TTL）：每次拍照前下发一次，扫值定位不过曝的功率档。
            if (fixedPowerArg >= 0 && externalFlashSync) {
                val sent = onFlashPowerRequest?.invoke(fixedPowerArg) == true
                android.util.Log.w("FlashPwr",
                    "固定功率下发 arg=$fixedPowerArg (≈1/${1 shl (fixedPowerArg / 10)}) sent=$sent")
            }
            submitStillCapture(captureBuilder, slow)
        } catch (_: Exception) {}
    }

    /**
     * 采集端「少加工」（真机回归修订，11-05）。
     *
     * 只关**边缘锐化**：锐化在轮廓留一圈 halo，放大看就是数码味，关掉零代价。
     *
     * **降噪 / ZSL 恢复 HAL 默认**：11-02 曾一起关掉，真机回归实锤代价 —— 三星的
     * 多帧合成同时是**自动 HDR 的载体**，NOISE_REDUCTION_MODE_OFF 把它一并关掉后，
     * 室外大光比场景单帧为保高光压低曝光，暗部明显比降噪默认基线沉一大截
     * （引擎默认 scene_latitude 已开、暗部仍救不回来：8-bit JPEG 里暗部数据已压死）。
     * 多帧抹细节的代价由 **RAW 通路**承担才是正解 —— RAW_SENSOR 永远不过 ISP，
     * JPEG 通路本来就是「让 HAL 替你拍好」的那条路。
     *
     * A/B 探针：外部私有目录存在 `capture_ab_off.txt` 时回到 11-02 的全关形态
     * （生像素模式），用于对比亮度/细节代价。
     */
    private fun applyCaptureTuning(builder: CaptureRequest.Builder) {
        val rawPixels = runCatching {
            java.io.File(context.getExternalFilesDir(null), "capture_ab_off.txt").exists()
        }.getOrDefault(false)
        if (rawPixels) {
            android.util.Log.i("CaptureTuning", "A/B 探针生效：本次回到生像素模式（全关）")
            if (canDisableNr) {
                runCatching {
                    builder.set(
                        CaptureRequest.NOISE_REDUCTION_MODE,
                        android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_OFF
                    )
                }
            }
            if (canDisableZsl) {
                runCatching { builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false) }
            }
        }
        if (canDisableEdge) {
            runCatching {
                builder.set(
                    CaptureRequest.EDGE_MODE,
                    android.hardware.camera2.CameraMetadata.EDGE_MODE_OFF
                )
            }
        }
        android.util.Log.i(
            "CaptureTuning",
            "edgeOff=$canDisableEdge nrOff=${rawPixels && canDisableNr} zslOff=${rawPixels && canDisableZsl}"
        )
    }

    /**
     * 直闪帧的采集端调优（断点①：三星 ISP 在闪光瞬间对画面的两次「加工」）。
     *
     * 真 T3 直闪的三个签名对应三件事，全部只在**内置闪光、自动曝光、非慢门**的
     * 快门请求上生效——torch 是常亮补光、外闪走手动曝光、慢门是另一套曝光模型：
     *
     *  1. 色调曲线钉线性：ISP 的 tone mapping 会把被闪光压黑的背景又提回来，
     *     平方反比衰减被削平。线性曲线 = ISP 能给的最「生」的响应，衰减原样保留，
     *     压反差的胶片曲线留给引擎去做。设备不认 CONTRAST_CURVE 就跳过。
     *  2. AWB 钉日光：氙气闪光 ≈ 5500K。AUTO 模式在闪光瞬间会把钨丝背景往中性拉，
     *     钉死后主体中性、背景留暖橙——这就是 T3 直闪的冷暖分离。
     *     用户手动选了白平衡预设时不抢（那是用户自己的口味）。
     *  3. 曝光偏置 [FLASH_EV_BIAS]：v81 起常量为 0，默认不再额外压暗（HAL 的
     *     闪光重测光已经足够沉黑，再压会过冲，见常量注释里的真机定量）。
     *     换算成整档下发的代码保留，非零时仍生效。
     *  4. 关掉零快门延迟（v81，真机实锤）：S25U 的 HAL 对 STILL_CAPTURE +
     *     ALWAYS_FLASH 照样走 CheckZslStillCapture —— LED 主闪序列确实跑了
     *     （led mode 2→0），但**输出帧取自主闪前的 ZSL 历史暗帧**：连拍 4 张的
     *     RAW 底片全部贴着黑电平（净信号 ≈30/1023，98% 像素暗于 +256），
     *     而成片参数是闪光档（iso=23、1/60s、flashState=FIRED）——参数与帧内容
     *     严重错配。关 ZSL 强制 HAL 在提交时刻现拍一帧，主闪光自然落到成像帧上。
     *     [canDisableZsl] 的口径与 [applySlowShutter] 一致：设不进去就静默跳过。
     */
    /**
     * 方案③ TTL：预闪窗口内采一个测光样本，只保留**最小 ISO×快门乘积**
     * （最亮读数 = HAL 对「环境光+预闪」场景的测光，近距亮反射时它极小）。
     * 场景越亮读数越小，无脑取 min 即可，不需要区分 aeState。
     */
    private fun recordTtlSample(iso: Int, exposureSec: Float) {
        if (!FLASH_TTL_EXPERIMENT || iso <= 0 || exposureSec <= 0f) return
        val cur = if (ttlFlashIso > 0) ttlFlashIso * ttlFlashSec else Float.MAX_VALUE
        if (iso * exposureSec < cur) {
            ttlFlashIso = iso
            ttlFlashSec = exposureSec
        }
    }

    /**
     * 方案③ TTL 近距帽取样：把 precapture 那一帧**临时**挂上连续对焦。
     *
     * 默认档的模拟失焦会用固定 `LENS_FOCUS_DISTANCE` 关掉 AF（预览 repeating 与
     * 成片请求都关），读回来的屈光度只是我们自己写进去的值，当不了距离代理。
     * 这一帧只用来测距：`AF_MODE_CONTINUOUS_PICTURE` 下 HAL 忽略
     * `LENS_FOCUS_DISTANCE`，会按实际场景跑一次 AF 并把对焦位置回报到结果里。
     * 成片仍走失焦请求，预览的连续对焦状态也不改（只此一帧）。
     */
    private fun applyTtlFocusProbe(builder: CaptureRequest.Builder) {
        if (!FLASH_TTL_EXPERIMENT || !FLASH_TTL_FOCUS_PROBE) return
        if (minFocusDistance <= 0f) return      // 定焦模组没有可读的对焦位置
        runCatching {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }
    }

    /**
     * 方案③ TTL 近距帽：从 precapture 帧读回主体距离（米），0 = 不可信。
     * 屈光度 ≤0（AF 没跑）或距离超出 [FlashTtl.MIN_VALID_DISTANCE_M]~5m 视为无效，
     * 调用侧不介入（安全退化回 B2 行为）。
     */
    private fun recordTtlFocus(result: android.hardware.camera2.TotalCaptureResult) {
        if (!FLASH_TTL_EXPERIMENT || !FLASH_TTL_FOCUS_PROBE) return
        val diopters = result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
        if (diopters <= 0.01f) return
        val d = 1f / diopters
        if (d < FlashTtl.MIN_VALID_DISTANCE_M || d > 5f) return
        ttlSubjectDistanceM = d
        android.util.Log.i(
            "FlashDiag",
            "TTL FOCUS diopters=${"%.2f".format(diopters)} distM=${"%.3f".format(d)}"
        )
    }

    /**
     * 方案③ TTL：fire() 提交快门前用预闪差分**重写**直闪帧的 ISO/快门。
     *
     * builder 此时已带 vc83 的 `AE_OFF + SINGLE + 手写 SENSOR_*`（applyFlashCaptureTuning
     * 在 takePicture 时按环境基线预填，作为 TTL 失效时的回退值）——这里只在
     * 差分有效时覆盖 SENSOR_SENSITIVITY / SENSOR_EXPOSURE_TIME 两个 key。
     * ISO 锚仍用闪光前的瞬时环境测光（ttlAmbient*，与 vc83 的 lastAeIso 同语义），
     * -0.7EV 与 ≤250 钳制原样；总曝光量 P 来自 [FlashTtl.solve]，
     * 快门 = P/ISO 反算，夹 [FlashTtl.MAX_SHUTTER_SEC] 与设备真实范围。
     */
    private fun applyFlashTtlRetune(builder: CaptureRequest.Builder) {
        if (!FLASH_TTL_EXPERIMENT || !flashEnabled || !hasFlashUnit || !hasManualSensor) return
        val sol = FlashTtl.solve(
            ttlAmbientIso, ttlAmbientSec, ttlFlashIso, ttlFlashSec,
            ttlSubjectDistanceM.takeIf { it > 0f }
        )
        if (sol == null) {
            android.util.Log.i(
                "FlashDiag",
                "TTL RETUNE fallback ambIso=$ttlAmbientIso ambSec=$ttlAmbientSec " +
                    "flashIso=$ttlFlashIso flashSec=$ttlFlashSec distM=$ttlSubjectDistanceM"
            )
            return
        }
        val isoScale = Math.pow(2.0, FLASH_LOCK_EV_BIAS.toDouble()).toFloat()
        var adjIso = Math.min(Math.round(ttlAmbientIso * isoScale), FLASH_MAX_FLASH_ISO)
            .coerceAtLeast(1)
        val adjExpSec = (sol.targetProduct / (adjIso * 1000f))
            .coerceIn(
                deviceMinExposureSec,
                Math.min(FlashTtl.MAX_SHUTTER_SEC, deviceMaxExposureSec)
            )
        adjIso = adjIso.coerceIn(deviceIsoRange.first, deviceIsoRange.last)
        val adjExpNs = (adjExpSec * 1_000_000_000f).toLong()
        val ok = runCatching {
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, adjIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, adjExpNs)
        }.isSuccess
        val expProduct = adjIso * adjExpSec * 1000f
        android.util.Log.i(
            "FlashDiag",
            "TTL RETUNE applied=$ok ambProd=${"%.0f".format(sol.ambientProduct)} " +
                "flashProd=${"%.0f".format(sol.flashProduct)} ratio=${"%.1f".format(sol.ratio)} " +
                "gain=${FlashTtl.TTL_GAIN} targetP=${"%.0f".format(sol.targetProduct)} " +
                "iso=$adjIso expSec=${"%.5f".format(adjExpSec)} expProduct=${"%.0f".format(expProduct)} " +
                "distM=${"%.3f".format(ttlSubjectDistanceM)} " +
                "baseIso=$aeBaselineIso baseSec=$aeBaselineSec"
        )
    }

    private fun applyFlashCaptureTuning(builder: CaptureRequest.Builder, slow: Boolean) {
        if (!flashEnabled || !hasFlashUnit) return

        // 🔴 11-20 真机根因（Pro 手动档内置闪光不亮）：
        // Pro 档下 applyManualSettings 已经把 CONTROL_MODE/AE_MODE 写成 OFF、并写了
        // SENSOR_SENSITIVITY / SENSOR_EXPOSURE_TIME（用户手拨的 ISO/快门），但
        // **没有写 FLASH_MODE** —— AE_MODE_OFF 下 HAL 完全靠 app 的 FLASH_MODE 决定
        // 是否点火，缺这一笔就停在 READY，拍出来等于没闪（取证：
        // `拍照完成 flashState=READY manualIso=400 manualExp=8000000`）。
        // 修法：手动档补一记 FLASH_MODE_SINGLE 让 HAL 点火，曝光仍用用户手动值，
        // 绝不套自动档的 TTL 重调值（那是给自动档反解闪光功率用的）。
        // 放在这里而不是 applyStillFlashSettings：后者是自动档 ALWAYS_FLASH 的
        // 专属入口，契约测试守着「那里不许出现 SINGLE」，不能在里面动刀。
        if (manualIso != null || manualExposure != null) {
            // Pro 手动档：AE_OFF 下 HAL 只认 app 写的 FLASH_MODE，必须补 SINGLE 才点火。
            // 曝光保持用户手拨值，不套自动档 TTL 重调值（见 applyManualFlashFire 注释）。
            if (!slow) {
                runCatching {
                    builder.set(
                        CaptureRequest.FLASH_MODE,
                        android.hardware.camera2.CameraMetadata.FLASH_MODE_SINGLE
                    )
                }.onSuccess {
                    android.util.Log.i("FlashDiag", "手动档补 FLASH_MODE_SINGLE iso=$manualIso exp=$manualExposure")
                }
            }
            return
        }
        if (slow || manualIso != null || manualExposure != null) return

        var awbDaylight = false
        if (currentAwbMode == null ||
            currentAwbMode == android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO
        ) {
            awbDaylight = runCatching {
                builder.set(
                    CaptureRequest.CONTROL_AWB_MODE,
                    android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                )
            }.isSuccess
        }

        val tonemapLinear = if (canLinearTonemap) {
            runCatching {
                // (in, out) 点对：(0,0) 与 (1,1) 两点画一条对角线 = 单位直线
                val linear = android.hardware.camera2.params.TonemapCurve(
                    floatArrayOf(0f, 0f, 1f, 1f),
                    floatArrayOf(0f, 0f, 1f, 1f),
                    floatArrayOf(0f, 0f, 1f, 1f)
                )
                builder.set(
                    CaptureRequest.TONEMAP_MODE,
                    android.hardware.camera2.CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE
                )
                builder.set(CaptureRequest.TONEMAP_CURVE, linear)
            }.isSuccess
        } else false

        // v81 断点②：关掉这一帧的 ZSL，见方法注释第 4 条。
        val zslOff = if (canDisableZsl) {
            runCatching { builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false) }.isSuccess
        } else false

        var evBiasSteps = 0
        val stepEv = aeCompStep.numerator.toFloat() / aeCompStep.denominator
        if (stepEv > 0f) {
            // 除数与被除数都是 Float：Math.round(Float) 返回 Int（步长单位）
            // 按设备 AE 补偿步长换算整档（S25U 实测 1/10 EV/档，+1EV = 10 档）
            evBiasSteps = Math.round((FLASH_EV_BIAS + FLASH_SLOW_SYNC_EV) / stepEv)
            if (evBiasSteps != 0) {
                runCatching {
                    builder.set(
                        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        (exposureComp + evBiasSteps).coerceIn(aeCompRange.first, aeCompRange.last)
                    )
                }.onFailure { evBiasSteps = 0 }
            }
        }
        // vc86：手动锁定直闪帧曝光到闪光前预览 AE 的实测 (ISO, 快门)，
        // 绕开 HAL 的 ALWAYS_FLASH 重测光（它把 ISO 从 ~250 压到 23）。
        // 放在函数最后：必须覆盖 applyStillFlashSettings 写的 ALWAYS_FLASH。
        var lockAe = false
        val lockIso = lastAeIso
        val lockExpNs = (lastAeSec * 1_000_000_000f).toLong()
        val minExpNs = (deviceMinExposureSec * 1_000_000_000f).toLong().coerceAtLeast(1L)
        val maxExpNs = (deviceMaxExposureSec * 1_000_000_000f).toLong()
        // vc89（vc83 定稿）：总曝光量目标钳制 + ISO 单独钳住主体。
        // 目标取预览 AE 想给的环境光量，夹到上限（正式包 3600 ISO·ms）；
        // 快门 = 目标 / 实际 ISO 反算，再夹设备真实范围。
        // ⚠️ vc89 真机日志：快门实际 14.4~16.8ms，并未变长；主体不被重新推亮
        // 靠的是 ISO 上限与总曝光量上限，不是「增益移到快门」。
        // 🔬 FLASH_UNCLAMP_EXPERIMENT：抬高安全帽做真慢同步（见常量注释），
        // 关闭时下面三个有效值与 vc84 逐字一致。
        val isoScale = Math.pow(2.0, FLASH_LOCK_EV_BIAS.toDouble()).toFloat()
        var adjIso = Math.min(Math.round(lockIso * isoScale), FLASH_MAX_FLASH_ISO)
            .coerceAtLeast(1)
        val unclamp = FLASH_UNCLAMP_EXPERIMENT && !FLASH_HAL_PRECAPTURE_EXPERIMENT
        // 方案A：反馈回路只乘在总曝光量安全帽上（与 unclamp 互斥，不叠加）。
        // updates=0 时 scale=1.0，首张永远是无修正的 vc83 基线。
        val fbActive = FLASH_FEEDBACK_LOOP_EXPERIMENT && !unclamp
        val fbScale = if (fbActive) FlashFeedbackLoop.productScale() else 1f
        val effMaxProduct = if (unclamp) FLASH_UNCLAMP_MAX_PRODUCT else FLASH_MAX_EXPOSURE_PRODUCT * fbScale
        val effMaxShutterSec = if (unclamp) FLASH_UNCLAMP_MAX_SHUTTER_SEC else FLASH_MAX_SHUTTER_SEC
        val rawProduct = lockIso.toFloat() * lastAeSec * 1000f
        val targetProduct = Math.min(rawProduct, effMaxProduct)
        val adjExpSec = (targetProduct / (adjIso * 1000f))
            .coerceIn(deviceMinExposureSec, Math.min(effMaxShutterSec, deviceMaxExposureSec))
        adjIso = adjIso.coerceIn(deviceIsoRange.first, deviceIsoRange.last)
        val adjExpNs = (adjExpSec * 1_000_000_000f).toLong()
            .coerceIn(minExpNs, maxExpNs)
        // 日志打钳制后的真实总曝光量（vc88 打的是钳制前值，会误导复标）
        val expProduct = adjIso.toFloat() * adjExpNs / 1_000_000f
        if (FLASH_LOCK_PREVIEW_AE && hasManualSensor && !FLASH_HAL_PRECAPTURE_EXPERIMENT && lockIso > 0 && lockExpNs > 0) {
            runCatching {
                builder.set(
                    CaptureRequest.CONTROL_MODE,
                    android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO
                )
                builder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
                )
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, adjExpNs)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, adjIso)
                // AE_OFF 下 flash.* 由 app 控制，SINGLE 让这一帧点火（不是 vc81 那次
                // ALWAYS_FLASH→SINGLE 的同义替换：那时 AE 仍由 HAL 重测光）。
                builder.set(
                    CaptureRequest.FLASH_MODE,
                    android.hardware.camera2.CameraMetadata.FLASH_MODE_SINGLE
                )
                lockAe = true
            }.onFailure {
                android.util.Log.w(TAG, "直闪手动锁定曝光失败，回落 HAL 自动: ${it.message}")
            }
        }
        android.util.Log.i(
            "FlashTuning",
            "capture awbDaylight=$awbDaylight tonemapLinear=$tonemapLinear " +
                "evBiasSteps=$evBiasSteps zslOff=$zslOff " +
                "lockAe=$lockAe lockIso=$lockIso lockExpNs=$lockExpNs " +
                "lockEvBias=$FLASH_LOCK_EV_BIAS isoScale=$isoScale lockIsoAdj=$adjIso " +
                "adjExpNs=$adjExpNs expSec=$adjExpSec expProduct=$expProduct " +
                "unclamp=$unclamp rawProduct=$rawProduct maxProduct=$effMaxProduct " +
                "maxShutterSec=$effMaxShutterSec devMaxExpSec=$deviceMaxExposureSec " +
                "baseIso=$aeBaselineIso baseSec=$aeBaselineSec " +
                "fimoScene=$FLASH_FIMO_SCENE_EXPERIMENT " +
                "fbCorr=${FlashFeedbackLoop.correctionEv()} fbScale=$fbScale " +
                "fbUpdates=${FlashFeedbackLoop.updates()} fbVerdict=${FlashFeedbackLoop.lastVerdict()}"
        )
    }

    /** 下发 EXT 档细档补偿（只影响 [applyExternalFlashSync] 的固定曝光量）。 */
    fun setExternalFlashComp(ev: Float) {
        externalFlashCompEv = ev.coerceIn(-3f, 3f)
    }

    /**
     * 切进/切出外置闪光档：基线只在档位变化时重置，避免每次拨 EV 都清零。
     * @param profileOffsetMs 当前灯的档案同步偏移（null = 用全局 -200 兜底）；
     *  热调文件 flash_sync_offset.txt 仍可覆盖它（adb 扫参用）。
     */
    /**
     * 当前灯一张照片要发几次触发帧（A2 = 3）。
     *
     * 三段式的三段由灯自己推进：LED → 氙气预闪 → 氙气主闪。只发 1 发时灯永远停在
     * 第一段（LED）；必须发 3 发、并让**第 3 发与曝光对齐**，拍到的才是主闪。
     * 代价是快门后约 1.4~1.8 秒才成像（iPhone 官方 App 同样有 2~3 秒延迟）。
     */
    @Volatile private var flashStages: Int = 1

    fun setExternalFlashStages(stages: Int) {
        flashStages = stages.coerceIn(1, 4)
    }

    /** 三段式序列是否已启动（防止 postDelayed 重入）。 */
    @Volatile private var stagedFlashStarted = false

    fun setExternalFlashSync(enabled: Boolean, profileOffsetMs: Int? = null, orchestrated: Boolean = false) {
        profileFlashOffsetMs = profileOffsetMs
        orchestratedFlashCapable = enabled && orchestrated
        if (!enabled) stagedFlashStarted = false
        if (externalFlashSync != enabled) {
            externalFlashSync = enabled
            aeBaselineIso = 0
            aeBaselineSec = 0f
        }
    }

    /**
     * 外置闪光档的快门帧：**固定曝光量**，不看环境光测光。
     *
     * 为什么不能跟 AE 基线走：闪光是独立光源，强度只取决于灯距，与环境光无关。
     * 之前按「环境光 ISO × t × 系数」折算，换个稍亮的房间 ISO 就被压到设备下限，
     * 闪光直接欠曝成黑片（用户 01:48 那张净信号只有 13.6）。真机闪光灯也是
     * 固定光圈/ISO/同步快门，靠闪光补偿拨盘调 —— 这里同理。
     *
     * 固定 [FLASH_SYNC_ISO_AT_100MS]（57）@ 100ms 同步快门，由真机逐档扫描标定；
     * EV 拨盘按标准档位 2^EV 调整，[flashSyncGain] 是热调倍率。
     * 只关 AE、不动 CONTROL_MODE —— AF / AWB 仍然自动（和慢门那套同理）。
     */
    private fun applyExternalFlashSync(builder: CaptureRequest.Builder) {
        // v62 P0：快门必须按设备真实上限收敛，且被截断后要反算 ISO 补回曝光量。
        // 旧实现写死 100ms，在最大曝光只有 33~66ms 的机型上会被 HAL 截断，
        // 曝光量凭空少 1~2 EV，闪光帧直接黑一片。
        val maxShutterNs = (deviceMaxExposureSec * 1_000_000_000f)
            .coerceIn(MIN_SYNC_SHUTTER_NS.toFloat(), MAX_SYNC_SHUTTER_NS.toFloat())
            .toLong()
        val minShutterNs = maxOf(
            MIN_SYNC_SHUTTER_NS,
            (deviceMinExposureSec * 1_000_000_000f).toLong().coerceAtLeast(0L)
        )
        val caps = FlashExposure.Caps(
            hasManualSensor, deviceIsoRange.first, deviceIsoRange.last,
            minShutterNs, maxShutterNs
        )
        if (!caps.canLockExposure()) {
            // LEGACY / LIMITED 机型不支持 MANUAL_SENSOR：手写 AE_OFF + SENSOR_* key
            // 会被 HAL 忽略甚至让整帧失败。降级为「BLE 点灯 + HAL 自动曝光」——
            // 代价是 AE 可能在闪光峰值重新测光把成片压暗，但至少保证出图。
            android.util.Log.w(
                "FlashSync",
                "设备不支持手动曝光（manualSensor=$hasManualSensor " +
                    "快门=$minShutterNs~$maxShutterNs ns ISO=$deviceIsoRange），" +
                    "外置闪光降级为 BLE 点灯 + HAL 自动曝光"
            )
            return
        }
        // EXT 档拨盘 = 闪光灯细档补偿（0.5 EV/档）；整数 exposureComp 留给
        // 预览 AE 与 OFF / INT 档，不再参与这一帧的固定曝光量。
        // TTL 接管时补偿走目标偏移（externalTtlTargetEv），这里必须置 1，否则两处叠加
        val evGain = if (externalTtlEnabled) 1f
        else Math.pow(2.0, externalFlashCompEv.toDouble()).toFloat()
        // 相机侧 TTL 的累积修正（每拍一张按实测偏差更新一次），与档位补偿相乘。
        // 11-22 重开 TTL 后编排帧必须吃这个增益，否则闭环算出的 ISO 修正不起作用。
        // 历史残留问题在 init 里统一清零（TTL 开启时丢弃旧 prefs 值，从 0 开始收敛），
        // 避免旧版本死代码期写入的 +2EV 虚高基线把首张打成白板。
        // 功率帧验证热调关闭 TTL 时，连残留的 externalTtlEv 一起忽略，
        // 让 gain 回到纯标定值（否则上次收敛的 EV 会污染功率对比）。
        // 灯侧 TTL（功率帧定稿）：闪光强弱由灯的功率档决定，相机 ISO 锁标定不动，
        // 所以这里恒 1 —— 绝不能再用 externalTtlEv 调 ISO（旧相机侧 TTL 已废弃）。
        // 保留 externalTtlEv 的读取只为兼容旧单测/诊断，不参与成像。
        val ttlGain = 1f
        val gain = flashSyncGain * evGain * ttlGain
        // 🔴 外闪手动标定档（11-22）：用户要人工扫 ISO/快门/功率组合采样本，
        // 这里必须尊重手动值——否则 gain 反算会把 manualIso 覆盖，拧了没反应。
        // 用 FlashExposure.Solved 包装手动值，钳到设备区间防非法值；evGain/exposureComp
        // 不再叠加（手动档就是用户说了算）。clamped=false 仅表示不走守恒补偿。
        val manualActive = extFlashManualCalib && manualIso != null && manualExposure != null
        val solved = if (manualActive) {
            val iso = manualIso!!.coerceIn(deviceIsoRange.first, deviceIsoRange.last)
            val shutter = manualExposure!!.coerceIn(minShutterNs, maxShutterNs)
            android.util.Log.i(
                "FlashSync",
                "外闪手动标定档：iso=$iso t=${shutter / 1_000_000}ms（跳过 gain 反算）"
            )
            FlashExposure.Result(iso = iso, shutterNs = shutter, clamped = false, note = "manual")
        } else if (orchestratedFlashSync) {
            // 编排式同步（A2）：闪光是 ~1ms 瞬时脉冲，主体亮度只认 ISO，窗宽只影响
            // 环境光。solve 守恒 ISO x t，ISO 越界时会改动窗口去补（脱离固定窗）—— 但 150ms
            // 宽窗是覆盖「BLE 抖动 + 灯起辉」的硬需求，不能被守恒补偿侵蚀。改用
            // solveFixedIso：钳 ISO 到设备区间、窗口固定不缩（官方慢速同步口径）。
            FlashExposure.solveFixedIso(
                fixedIso = FLASH_SYNC_ISO_AT_100MS * gain,
                targetShutterNs = ORCHESTRATED_SYNC_SHUTTER_NS,
                minIso = deviceIsoRange.first,
                maxIso = deviceIsoRange.last,
                minShutterNs = minShutterNs,
                maxShutterNs = maxShutterNs,
            )
        } else {
            FlashExposure.solve(
                baseIso = FLASH_SYNC_ISO_AT_100MS * gain,
                baseShutterNs = FLASH_SYNC_SHUTTER_NS,
                minIso = deviceIsoRange.first,
                maxIso = deviceIsoRange.last,
                minShutterNs = minShutterNs,
                maxShutterNs = maxShutterNs,
            )
        }
        builder.set(
            CaptureRequest.CONTROL_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO
        )
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
        )
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, solved.shutterNs)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, solved.iso)
        builder.set(
            CaptureRequest.SENSOR_FRAME_DURATION,
            maxOf(solved.shutterNs, 33_000_000L)
        )
        // 外闪帧白平衡：氙气灯 ≈5500K 日光，闪光 ~1ms 太快、AWB 来不及为它重测光。
        // 若停在 AUTO，会套用闪光前暖光环境算出的"猛拉蓝"增益（CT≈3079K 时 B×2.524），
        // 把中性闪光补偿成蓝青（11-22 实测成片 B−R=+28~+54）。与内置闪光的
        // applyFlashCaptureTuning 对称：AUTO/null 时钉 DAYLIGHT；用户手动预设则尊重。
        val awbDaylight = extFlashAwbDaylight &&
            (currentAwbMode == null ||
                currentAwbMode == android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO)
        if (awbDaylight) {
            runCatching {
                builder.set(
                    CaptureRequest.CONTROL_AWB_MODE,
                    android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                )
            }
        }
        // 随帧记录：EXT 帧的曝光量完全由我们决定，这里的值就是成片真实值，
        // 不必等 TotalCaptureResult（JPEG 可能先于 result 到货）。
        lastCaptureIso = solved.iso
        lastCaptureShutterNs = solved.shutterNs
        // 触发时机模型的本帧输入（11-23）：scheduleFlashPreTrigger 在 request 构建
        // 之后运行，读到的一定是这一帧的曝光。
        pendingFlashExposureNs = solved.shutterNs
        android.util.Log.i(
            "FlashSync",
            "闪光固定曝光 iso=${solved.iso} t=${solved.shutterNs / 1_000_000}ms " +
                "gain=$gain flashEv=$externalFlashCompEv ev=$exposureComp " +
                "orchestrated=$orchestratedFlashSync awbDaylight=$awbDaylight " +
                "快门上限=${maxShutterNs / 1_000_000}ms" +
                (if (solved.clamped) " ⚠️ ${solved.note}" else "") +
                " (环境基线 iso=$aeBaselineIso t=${(aeBaselineSec * 1000).toInt()}ms)"
        )
    }

    /**
     * 本次拍照强制一段长曝光（胶片慢门）。
     *
     * 只关 AE，不动 CONTROL_MODE —— 这样 AF / AWB 仍然自动（沿用上面 applyManualSettings
     * 设好的焦点与白平衡）。不能用 applyManualSettings 的手动分支：它会把 CONTROL_MODE
     * 设成 OFF，等于把白平衡也一起冻在上一次的值上。
     *
     * 另外三件事决定这一张到底糊不糊：
     *   · 帧时长必须 ≥ 曝光时间，否则 HAL 会把长曝光截断回 1/30；
     *   · 关掉 OIS —— T3 没有防抖，不关的话 1/8 秒也能被稳回来；
     *   · 关掉降噪/ZSL —— 多帧合成会把抖动「修」平，等于白拍一张长曝光。
     */
    private fun applySlowShutter(builder: CaptureRequest.Builder, exposureNs: Long, iso: Int) {
        builder.set(
            CaptureRequest.CONTROL_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO
        )
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
        )
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(deviceIsoRange.first, deviceIsoRange.last))
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exposureNs, 33_000_000L))
        if (canDisableOis && android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching {
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                )
            }
        }
        if (canDisableNr) {
            builder.set(
                CaptureRequest.NOISE_REDUCTION_MODE,
                android.hardware.camera2.CameraMetadata.NOISE_REDUCTION_MODE_OFF
            )
        }
        // 关掉零快门延迟：ZSL 会在按下前就把帧塞进缓冲，HAL 可能挑一张「旧的」交上来，
        // 长曝光等于没发生。这个 key 不是所有 HAL 都认，设不进去就静默跳过。
        val zslOff = runCatching {
            builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
        }.isSuccess
        android.util.Log.i(
            "SlowShutter",
            "apply exposure=${exposureNs / 1_000_000.0}ms iso=$iso " +
                "oisOff=$canDisableOis nrOff=$canDisableNr zslOff=$zslOff"
        )
    }

    /**
     * 按**实测** HAL 延迟调度一次提前触发（负偏移专用）。
     * 只在 `halLatency ≥ |offset|` 的通路生效（RAW 265~274ms）；JPG 通路
     * 已由 [submitStillCapture] 的「先点灯、延后快门」处理，这里直接跳过。
     */
    private fun scheduleFlashPreTrigger(handler: Handler) {
        // 已在「快门前置」阶段发过指令（JPG 通路），这里不再补发
        // 编排式同步：触发完全交给 onCaptureStarted，绝不再排预测触发（否则重复发灯）
        if (!externalFlashSync || orchestratedFlashSync ||
            flashTriggerOffsetMs >= 0 || flashTriggerPosted) return
        val useModel = flashLatencyModel && pendingFlashExposureNs > 0L
        val modelMs = predictFlashLatencyMs(pendingFlashExposureNs / 1_000_000L)
        // 窗内瞄准（11-23）：把闪光瞄准窗口 1/3 处，而不是窗口开头——灯的起辉
        // 在 80~200ms 间波动（本轮 6 档真机：名义 80ms，但 8/16/33ms 档指令
        // 提前量 75~82ms 全黑，125ms 档靠窗宽兜住命中）。瞄准点内移后，起辉
        // 波动 ±exp/3 都还在窗内。8/16/33ms 窄窗依旧危险，标定建议 ≥1/8。
        val aimIntoWindowMs = pendingFlashExposureNs / 3_000_000L
        val delay = if (useModel) {
            flashTriggerDelayMs(modelMs + aimIntoWindowMs, flashTriggerOffsetMs)
        } else {
            flashTriggerDelayMs(flashHalLatencyMs, flashTriggerOffsetMs)
        }
        android.util.Log.i(
            "FlashSync",
            if (useModel) {
                "预触发调度 delay=${delay}ms = 曝光模型 " +
                    "max(${FLASH_LATENCY_BASE_MS},${FLASH_LATENCY_PER_EXP}×" +
                    "${pendingFlashExposureNs / 1_000_000L})=${modelMs}ms" +
                    " + 窗内瞄准 ${aimIntoWindowMs}ms" +
                    " + offset ${flashTriggerOffsetMs}ms" +
                    " (实测缓存=${flashHalLatencyMs}ms" +
                    "${if (flashHalLatencyCalibrated) "上次实测校准" else "兜底未校准"} 仅供比对)"
            } else {
                "预触发调度 delay=${delay}ms = halLatency ${flashHalLatencyMs}ms" +
                    "(${if (flashHalLatencyCalibrated) "上次实测校准" else "兜底未校准"})" +
                    " + offset ${flashTriggerOffsetMs}ms"
            }
        )
        handler.postDelayed({
            if (externalFlashSync) {
                fireExternalFlashOnce("预触发 ${flashTriggerOffsetMs}ms")
            }
        }, delay)
    }

    /**
     * 外置闪光：**先点灯，再把快门延后提交**。
     *
     * 11-07 18:44 七帧真机日志坐实了时序方向是反的：
     *  - 灯收到 `05 00` 后约 200ms 才出光（`flash_sync_offset` 的绝对值就是这个起辉量）；
     *  - JPG 直提交路径 HAL 只要 26~42ms 就开曝光，曝光窗口只有 100ms。
     *
     * 所以「曝光开始前 200ms 发指令」物理上做不到 —— 那时快门还没按。旧实现把
     * `halLatency + offset` 钳到 0，等于「按下快门的同时发指令」，灯永远晚 60~90ms
     * 出光，七帧里六帧全黑、一帧只蹭到一点。
     *
     * 反过来才成立：按下快门立刻发 BLE 指令，把拍照请求延后
     * `|offset| − halLatency`（默认 200 − 35 ≈ 165ms）再提交，让「灯出光」
     * 与「曝光开始」对齐。代价是约 165ms 快门时滞，与真实机身闪光同步的观感一致。
     *
     * RAW 通路实测 halLatency 265~274ms ≥ |offset|，前置量为 0，退回原路径
     * （提交后按实测延迟提前触发），行为不变。
     */
    private fun submitStillCapture(captureBuilder: CaptureRequest.Builder, slowShutter: Boolean = false) {
        // 调研路②：FEC8 回电门控。灯回电中就等到就绪（或超时兜底）再走正常触发链路。
        // 只在外闪档生效；readyGate 关闭 / 灯无回电通道时直接进入内部分支，零行为变化。
        if (readyGate && externalFlashSync && !slowShutter) {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            gateOnFlashReady(captureBuilder, slowShutter, startedAt)
            return
        }
        submitStillCaptureInner(captureBuilder, slowShutter)
    }

    /** 调研路②：轮询等 FEC8 就绪，超时兜底放行，避免回电期间触发导致闪光偏弱。 */
    private fun gateOnFlashReady(
        captureBuilder: CaptureRequest.Builder,
        slowShutter: Boolean,
        startedAt: Long,
    ) {
        val handler = backgroundHandler
        val ready = isFlashReady?.invoke()
        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
        if (ready == true || ready == null || elapsed >= READY_GATE_MAX_WAIT_MS) {
            android.util.Log.w(
                "FlashSync",
                "调研路②回电门控放行：ready=$ready waited=${elapsed}ms" +
                    if (elapsed >= READY_GATE_MAX_WAIT_MS) "(超时兜底)" else ""
            )
            submitStillCaptureInner(captureBuilder, slowShutter)
            return
        }
        // 回电中：50ms 后再问一次。BLE 状态帧是异步到达的，轮询间隔足够密。
        handler?.postDelayed({ gateOnFlashReady(captureBuilder, slowShutter, startedAt) }, 50L)
    }

    private fun submitStillCaptureInner(captureBuilder: CaptureRequest.Builder, slowShutter: Boolean = false) {
        val handler = backgroundHandler
        // 编排式同步（A2 / 对齐官方 iPhone）：不做 halLatency 预测的「先点灯、
        // 延后快门」，也不补三段式 —— 直接提交宽窗拍照请求，把单发触发整个
        // 交给 onCaptureStarted（HAL 实测「曝光真开始」事件）。宽窗覆盖灯起辉
        // 与 BLE 抖动，删掉预测误差源 = 根治「快门遇不到闪光」。
        if (orchestratedFlashSync && !slowShutter) {
            android.util.Log.i(
                "FlashSync",
                "编排式：直接提交宽窗拍照，触发交给 onCaptureStarted" +
                    "（不再预测 halLatency=${flashHalLatencyMs}ms / preRoll 弃用）"
            )
            submitStillCaptureNow(captureBuilder, slowShutter)
            return
        }
        // 三段式灯（A2）：先补前两段（LED / 氙气预闪），再把第 3 段交给原来的
        // 「先点灯、延后快门」逻辑 —— 这样第 3 发正好落在曝光窗口，拍的才是氙气主闪。
        if (externalFlashSync && !slowShutter && handler != null &&
            flashStages >= 2 && !stagedFlashStarted
        ) {
            stagedFlashStarted = true
            val preRoll = flashPreRollMs(flashHalLatencyMs, flashTriggerOffsetMs)
            android.util.Log.i(
                "FlashSync",
                "三段式 1/${flashStages}: 第1发(LED) 立即，第2发 +${STAGE2_MS}ms，" +
                    "第3发(主闪) +${STAGE3_MS}ms（preRoll=${preRoll}ms）"
            )
            // 调研路①：三段式前先复位到氙气档（0c=00）。11-19 实证「点复位后每次都是
            // 氙气」，此前三段式失败（三下全 LED）正是因为缺这一步。复位是 4 帧 BLE
            // 写入，等 RESET_SETTLE_MS 让灯切到氙气通道再发第 1 发。
            if (researchStages >= 2) onBeforeStagedFlash?.invoke()
            val stage1Delay = if (researchStages >= 2) RESET_SETTLE_MS else 0L
            if (stage1Delay > 0L) {
                handler.postDelayed({ onExposureStarted?.invoke() }, stage1Delay)
            } else {
                onExposureStarted?.invoke()
            }
            handler.postDelayed({ onExposureStarted?.invoke() }, stage1Delay + STAGE2_MS)
            // 第 3 发 = 主闪，这里**直接**发灯 + 提交拍照，绝不能再回 submitStillCapture ——
            // 13:55 真机事故：递归回入口会重新走三段式，于是每 1.4s 循环发 1、2 发，
            // 永远到不了主闪，照片也不提交（表现就是「一直只有 LED 在闪」）。
            handler.postDelayed({
                fireExternalFlashOnce("三段式 3/${flashStages}")
                val thirdPreRoll = flashPreRollMs(flashHalLatencyMs, flashTriggerOffsetMs)
                extLastPreRollMs = thirdPreRoll
                if (thirdPreRoll > 0L) {
                    handler.postDelayed(
                        { submitStillCaptureNow(captureBuilder, slowShutter) },
                        thirdPreRoll
                    )
                } else {
                    submitStillCaptureNow(captureBuilder, slowShutter)
                }
                stagedFlashStarted = false
            }, stage1Delay + STAGE3_MS)
            return
        }
        // 多帧覆盖：触发由 burst 分支自己延时发一次（不靠 halLatency 预测），
        // 必须先于「快门前置」拦截——否则前置先置 flashTriggerPosted=true，
        // burst 的 fireExternalFlashOnce 被防重吞掉，变成只发一次预测触发（命中不稳）。
        if (burstFrames >= 2 && externalFlashSync && !slowShutter) {
            submitStillCaptureNow(captureBuilder, slowShutter)
            return
        }
        if (externalFlashSync && !slowShutter && handler != null) {
            val preRoll = flashPreRollMs(flashHalLatencyMs, flashTriggerOffsetMs)
            extLastPreRollMs = preRoll
            if (preRoll > 0L && !flashTriggerPosted) {
                android.util.Log.i(
                    "FlashSync",
                    "外置闪光：先点灯，快门延后 ${preRoll}ms 提交" +
                        "（起辉 ${-flashTriggerOffsetMs}ms − HAL ${flashHalLatencyMs}ms）"
                )
                fireExternalFlashOnce("快门前置")
                handler.postDelayed({ submitStillCaptureNow(captureBuilder, slowShutter) }, preRoll)
                return
            }
        }
        submitStillCaptureNow(captureBuilder, slowShutter)
    }

    /**
     * 把拍照请求真正下发（不含外置闪光的前置延后，见 [submitStillCapture]）。
     *
     * 开闪光灯时先跑一次 AE 预闪（precapture）：HAL 借这一步完成闪光测光与充电，
     * 等 AE 收敛（或超时兜底）再开快门，否则第一张常常曝光过亮/过暗、甚至错过闪。
     * 关闪光时走原路径，不加任何等待。
     */
    private fun submitStillCaptureNow(captureBuilder: CaptureRequest.Builder, slowShutter: Boolean = false) {
        val session = captureSession
        val camera = cameraDevice
        val surface = previewSurface
        val handler = backgroundHandler
        if (session == null || camera == null || handler == null) {
            session?.capture(captureBuilder.build(), null, handler)
            return
        }
        // 实验旁路：repeating 挂 START + 预览常驻 ALWAYS_FLASH，等 HAL 自己收敛
        if (FLASH_HAL_PRECAPTURE_EXPERIMENT && flashEnabled && hasFlashUnit &&
            !slowShutter && manualIso == null && manualExposure == null
        ) {
            submitHalPrecaptureExperiment(session, handler, captureBuilder)
            return
        }
        // 多帧覆盖验证（外闪档 + flash_burst.txt=N）：一次快门连拍 N 帧铺开覆盖带，
        // 触发延时 [BURST_TRIGGER_DELAY_MS] 发一次（灯的预闪→主闪 440ms 序列落在带内），
        // 成片在 imageReader 里选最亮帧。不依赖 halLatency 预测、不走预触发。
        if (burstFrames >= 2 && externalFlashSync && !slowShutter) {
            // 🔴 不用 captureBurst：三星 HAL 会把 burst 当连拍流强制重测 AE，把
            // ISO228/100ms 覆盖成 iso=6945/41ms（实测），闪光信号被环境光淹没。
            // 改用 N 次独立 session.capture（单帧能锁住手动曝光），每帧间隔约一个
            // 窗宽提交，铺开覆盖带。第一帧提交时发触发，灯预闪→主闪落在带内。
            burstActive = true
            burstJpegCount = 0
            burstBestP50 = -1
            burstPickedTs = -1L
            burstDelivered = false
            burstMap.clear()
            // 关掉上一次 burst 可能残留的 RAW（异常中断时）
            bufferedRaw?.close()
            bufferedRaw = null
            val shutterNs = captureBuilder.get(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME) ?: 100_000_000L
            // 帧间隔固定 40ms（≤ AE 实际窗宽），不跟 shutterNs——请求写 100ms 但 HAL 压成 40ms。
            val stepMs = BURST_STEP_MS
            android.util.Log.i(
                "BurstProbe",
                "连拍 $burstFrames 帧（窗 ${shutterNs / 1_000_000}ms 请求/实际AE≈40ms / 间隔 ${stepMs}ms）" +
                    " + 延时 ${BURST_TRIGGER_DELAY_MS}ms 发单次触发（独立 capture 锁曝光）"
            )
            for (i in 0 until burstFrames) {
                val submit: () -> Unit = {
                    flashSubmitRealtimeMs = android.os.SystemClock.elapsedRealtime()
                    session.capture(captureBuilder.build(), stillCaptureCallback, handler)
                    Unit
                }
                if (i == 0) submit() else handler.postDelayed(submit, i * stepMs)
            }
            handler.postDelayed({ fireExternalFlashOnce("多帧覆盖") }, BURST_TRIGGER_DELAY_MS)
            // 超时兜底：三路没配齐也要交付，绝不让底片卡死（总时长 = 连拍 + RAW 等待）。
            handler.postDelayed({
                if (!burstDelivered && burstPickedTs >= 0) {
                    val best = burstMap[burstPickedTs]
                    if (best?.jpeg != null) {
                        android.util.Log.w("BurstProbe", "三路配对超时，按纯 JPEG 交付 ts=$burstPickedTs")
                        best.raw?.close()
                        deliverBurstBest(BurstFrame().also {
                            it.ts = best.ts; it.jpeg = best.jpeg; it.rotation = best.rotation; it.p50 = best.p50
                        })
                    }
                }
                // 收尾：关掉所有残余 RAW 再清 map、撤 active，避免迟到帧污染单槽
                for (bf in burstMap.values) {
                    bf.raw?.close()
                    bf.raw = null
                }
                burstActive = false
                burstMap.clear()
            }, burstFrames * stepMs + RAW_WAIT_MS + 1500L)
            return
        }
        // 慢门已把 AE 关掉，HAL 不会闪，也不需要预闪测光 —— 直接开快门
        if (!flashEnabled || slowShutter || manualIso != null || manualExposure != null || surface == null) {
            // 未开闪光 / 手动曝光（HAL 不会闪）→ 直接拍
            flashSubmitRealtimeMs = android.os.SystemClock.elapsedRealtime()
            session.capture(captureBuilder.build(), stillCaptureCallback, handler)
            scheduleFlashPreTrigger(handler)
            return
        }

        var fired = false
        val fire = {
            if (!fired) {
                fired = true
                runCatching {
                    flashSubmitRealtimeMs = android.os.SystemClock.elapsedRealtime()
                    // 方案③：预闪差分已经拿到 → 提交快门前重调直闪曝光
                    applyFlashTtlRetune(captureBuilder)
                    session.capture(captureBuilder.build(), stillCaptureCallback, handler)
                    // 带 precapture 的路径也要以「真正提交拍照」为 t=0，不能漏掉提前触发
                    scheduleFlashPreTrigger(handler)
                }
            }
        }
        // 超时兜底：任何情况下都不能把快门卡住（最多等 700ms，够 HAL 完成测光充电）
        val timeout = Runnable { fire() }
        handler.postDelayed(timeout, FLASH_PRECAPTURE_TIMEOUT_MS)

        val preBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            applyManualSettings(this)
            // v81 回退（11-14 用户拍板）：恢复 vc80 的 ALWAYS_FLASH one-shot
            // precapture。v81 SINGLE 试验轮真机定量：S25U 在 SINGLE 下 aeState
            // 正常收敛（不再 700ms 超时），但出图口径与 ALWAYS_FLASH 完全相同
            //（iso=23/1/60s、背景物理死黑）、无预闪（用户明确要预闪手感）、
            // 亮度观感无改善——SINGLE 在这台 HAL 上没有解锁任何厂商闪光管线，
            // 反而丢了 ALWAYS_FLASH 已验证可用的预闪→主闪序列。
            applyPrecaptureFlashSettings(this)
            applyFimoSceneArming(this)
            applyTtlFocusProbe(this)
            set(
                CaptureRequest.CONTROL_AWB_MODE,
                currentAwbMode ?: CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            // vc85 保留的预闪帧 AE 补偿换算能力（两帧同值才有意义）。
            // 当前 FLASH_SLOW_SYNC_EV=0f（vc85 已证这台 HAL 忽略 AE 补偿），
            // 不产生实际补偿；别的机型若 HAL 支持可复用这条通路。
            val preStepEv = aeCompStep.numerator.toFloat() / aeCompStep.denominator
            if (preStepEv > 0f) {
                val preEvSteps = Math.round((FLASH_EV_BIAS + FLASH_SLOW_SYNC_EV) / preStepEv)
                set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    (exposureComp + preEvSteps).coerceIn(aeCompRange.first, aeCompRange.last)
                )
            }
            set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
        }
        // v74 P0：打点 + 置 pending，拍照结果回调必须配对发 CANCEL（见 resetFlashPrecapture）
        precapturePending = true
        flashPrecaptureStarts++
        // 方案③ TTL：闪光前的环境测光快照 + 清空预闪采样（窗口开始）
        ttlAmbientIso = lastAeIso
        ttlAmbientSec = lastAeSec
        ttlFlashIso = 0
        ttlFlashSec = 0f
        ttlSubjectDistanceM = 0f
        android.util.Log.i("FlashDiag", "PRECAPTURE START count=$flashPrecaptureStarts ae=ALWAYS_FLASH(one-shot) slowSyncEv=$FLASH_SLOW_SYNC_EV")
        reportFlashState()
        val preCb = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: android.hardware.camera2.CameraCaptureSession,
                request: CaptureRequest,
                result: android.hardware.camera2.TotalCaptureResult,
            ) {
                val ae = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
                // 方案③：one-shot 自己的结果也采样（它携带着 HAL 的闪光测光）
                val ttlIso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 0
                val ttlSec = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                    ?.let { it / 1_000_000_000f } ?: 0f
                recordTtlSample(ttlIso, ttlSec)
                recordTtlFocus(result)
                // CONVERGED / FLASH_REQUIRED = 测光完成，可以开快门；PRECAPTURE = 还在跑，继续等
                if (ae == null ||
                    ae == android.hardware.camera2.CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                    ae == android.hardware.camera2.CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED ||
                    ae == android.hardware.camera2.CameraMetadata.CONTROL_AE_STATE_LOCKED
                ) {
                    handler.removeCallbacks(timeout)
                    fire()
                }
            }
        }
        runCatching { session.capture(preBuilder.build(), preCb, handler) }
            .onFailure { handler.removeCallbacks(timeout); fire() }
    }

    /**
     * v74 P0：把 HAL 从 AE 预闪状态拉回来，熄灭内置闪光 LED。
     *
     * Camera2 规范要求 precapture 序列结束或放弃时显式发 CANCEL；vc73 只发过 START，
     * 全库没有 CANCEL，小米 13U 等 HAL 就把 LED 保持在预闪常亮，切档时只把
     * CONTROL_AE_MODE 拨回 ON 也终止不了这个状态机。
     *
     * 两段式（与 Camera2Basic 官方 sample 同构）：
     *  1) 一次性 capture 发 CANCEL + FLASH_MODE_OFF + AE_ON，让 HAL 立刻收灯；
     *  2) 预览 repeating 回到 trigger=IDLE、FLASH_MODE=OFF、AE_ON。
     *
     * ⚠️ v75 P1：第 2 步**绝不再按当前档挂回 ALWAYS_FLASH** —— v74 在这里
     * 发完 CANCEL 又把预览挂回 ALWAYS_FLASH，一次性 CANCEL 被常驻 repeating
     * 立刻覆盖，是「拍完第一张常亮」在 v74 上仍会复现的直接原因。
     *
     * ⚠️ 只动预览请求，不动 TEMPLATE_STILL_CAPTURE 的成像参数：闪光成片曝光 /
     * AWB 钉日光 / 线性 tonemap / FLASH_EV_BIAS 全部不受影响，也不碰外置闪光
     * （EXTERNAL）与慢门通路。
     */
    private fun resetFlashPrecapture(reason: String, force: Boolean = false) {
        if (!force && !precapturePending) return
        val session = captureSession ?: return
        if (previewSurface == null) return
        val builder = previewRequestBuilder ?: return
        val handler = backgroundHandler
        runCatching {
            // 实验旁路：CANCEL 这一帧必须是干净的 AUTO / AE_ON，
            // 否则 USE_SCENE_MODE 会让 HAL 忽略 AE 模式、P1 治好的灭灯可能失效。
            clearFimoSceneArming(builder)
            // v81：CANCEL 这一帧必须复用常驻预览 builder 的**全部参数**。
            // 旧写法用 TEMPLATE_PREVIEW 新建 builder、只塞 3 个 key 就 addTarget(预览
            // surface)，这一帧会真的投到取景器，却缺 EV / AWB / AF / zoom 等成像参数，
            // 画面瞬间跳变 —— 就是「切换闪光灯档位时预览闪一下」的直接来源。
            // 现在这一帧与前后预览帧参数完全一致，只有 trigger 不同。
            builder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL
            )
            builder.set(
                CaptureRequest.FLASH_MODE,
                android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF
            )
            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON
            )
            try {
                session.capture(builder.build(), null, handler)
            } finally {
                // 无论 capture 成败都把 trigger 放回 IDLE：repeating 不允许带 CANCEL，
                // 否则会话已关闭时下一次 setRepeatingRequest 会被 HAL 整条拒绝。
                builder.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    android.hardware.camera2.CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                )
            }
            builder.set(
                CaptureRequest.FLASH_MODE,
                android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF
            )
            // v75 P1：恒为 AE_ON。只要这里再挂 ALWAYS_FLASH，上面的 CANCEL 就白发了。
            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON
            )
            applyHalPrecaptureArming(builder)
            applyFimoSceneArming(builder)
            session.setRepeatingRequest(builder.build(), previewMetaCallback, handler)
            precapturePending = false
            flashPrecaptureCancels++
            lastFlashResetReason = reason
            android.util.Log.i(
                "FlashDiag",
                "PRECAPTURE CANCEL ($reason) starts=$flashPrecaptureStarts cancels=$flashPrecaptureCancels"
            )
            reportFlashState()
        }.onFailure {
            android.util.Log.w("FlashDiag", "PRECAPTURE CANCEL 失败 ($reason): ${it.message}")
        }
    }

    /**
     * 实验：把 PRECAPTURE_START 挂到常驻 repeating 上，让 HAL 状态机流式推进。
     *
     * vc80 的一次性请求之后 HAL 没有后续帧推进 AE 状态，实测 2500ms 全停在
     * PRECAPTURE；FIMO/CameraView 与三星原厂都把 START 挂在 repeating 上、
     * 预览常驻 ALWAYS_FLASH，靠流式帧把状态机推到收敛。拍照帧保持 ALWAYS_FLASH
     * 且不做手写 AE，曝光完全交回 HAL。
     */
    private fun submitHalPrecaptureExperiment(
        session: android.hardware.camera2.CameraCaptureSession,
        handler: Handler,
        captureBuilder: CaptureRequest.Builder
    ) {
        val previewBuilder = previewRequestBuilder
        var fired = false
        var sawPrecapture = false
        var endReason = "timeout"
        var seenAe: Int? = null
        val startMs = android.os.SystemClock.elapsedRealtime()
        var timeout: Runnable? = null

        val fire = {
            if (!fired) {
                fired = true
                halPrecaptureAeSink = null
                timeout?.let { handler.removeCallbacks(it) }
                // 收敛/超时后把 trigger 放回 IDLE，避免 HAL 在后续预览帧反复重启序列
                previewBuilder?.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    android.hardware.camera2.CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                )
                previewBuilder?.let {
                    runCatching { session.setRepeatingRequest(it.build(), previewMetaCallback, handler) }
                }
                android.util.Log.i(
                    "FlashDiag",
                    "HAL_PRECAP END reason=$endReason waitMs=${
                        android.os.SystemClock.elapsedRealtime() - startMs
                    } ae=$seenAe sawPrecapture=$sawPrecapture"
                )
                runCatching {
                    flashSubmitRealtimeMs = android.os.SystemClock.elapsedRealtime()
                    session.capture(captureBuilder.build(), stillCaptureCallback, handler)
                    scheduleFlashPreTrigger(handler)
                }
            }
        }

        val timeoutTask = Runnable { fire() }
        timeout = timeoutTask
        handler.postDelayed(timeoutTask, FLASH_HAL_PRECAPTURE_TIMEOUT_MS)

        halPrecaptureAeSink = { ae ->
            seenAe = ae
            if (ae == android.hardware.camera2.CameraMetadata.CONTROL_AE_STATE_PRECAPTURE) {
                sawPrecapture = true
            }
            // 必须先看到 HAL 进过 PRECAPTURE，否则预览原本的 CONVERGED 会立刻误判收敛
            if (sawPrecapture && (
                    ae == android.hardware.camera2.CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                        ae == android.hardware.camera2.CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED ||
                        ae == android.hardware.camera2.CameraMetadata.CONTROL_AE_STATE_LOCKED
                    )
            ) {
                endReason = "converged"
                fire()
            }
        }

        precapturePending = true
        flashPrecaptureStarts++
        android.util.Log.i(
            "FlashDiag",
            "HAL_PRECAP START count=$flashPrecaptureStarts ae=ALWAYS_FLASH(repeating) " +
                "timeoutMs=$FLASH_HAL_PRECAPTURE_TIMEOUT_MS"
        )
        reportFlashState()

        if (previewBuilder == null) {
            endReason = "noPreviewBuilder"
            fire()
        } else {
            previewBuilder.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
            runCatching { session.setRepeatingRequest(previewBuilder.build(), previewMetaCallback, handler) }
                .onFailure { endReason = "repeatingFailed"; fire() }
        }
    }

    /**
     * 实验分支专用：把常驻预览 builder 武装成 ON_ALWAYS_FLASH（数值 3）。
     * 与 applyPreviewFlashSettings 分离，保证关闭开关时原路径一字不动。
     */
    private fun applyHalPrecaptureArming(builder: CaptureRequest.Builder) {
        if (!FLASH_HAL_PRECAPTURE_EXPERIMENT) return
        if (!flashEnabled || !hasFlashUnit) return
        if (manualIso != null || manualExposure != null) return
        builder.set(CaptureRequest.CONTROL_AE_MODE, AE_MODE_ALWAYS_FLASH_VALUE)
        builder.set(
            CaptureRequest.FLASH_MODE,
            android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF
        )
    }

    /**
     * EXT 外闪取证：只写内存快照，不在这里 flush（拍摄路径每帧都会调，
     * 落盘交给既有的状态变化 flush，避免快门路径上做文件 IO）。
     */
    private fun reportExtFlashState() {
        runCatching {
            com.filmcam.app.develop.DeviceGeometryReport.putAll(
                "flash.extSync" to externalFlashSync,
                "flash.orchestrated" to orchestratedFlashSync,
                "flash.ttlHotOff" to ttlHotOff,
                "flash.powerByte" to com.filmcam.app.flash.A2PowerProtocol.lastSentPowerByte,
                "flash.extTriggers" to extTriggerCount,
                "flash.extLastTriggerWhere" to extLastTriggerWhere,
                "flash.extLastPreRollMs" to extLastPreRollMs,
                "flash.extHalLatencyMs" to flashHalLatencyMs,
                "flash.extTriggerToExposureMs" to extTriggerToExposureMs,
                "flash.extTriggerPosted" to flashTriggerPosted,
            )
        }
    }

    /** v74 P2：把闪光灯状态写进诊断报告（状态变化时才落盘，随反馈邮件正文发出） */
    private fun reportFlashState() {
        runCatching {
            val b = previewRequestBuilder
            com.filmcam.app.develop.DeviceGeometryReport.putAll(
                "flash.unitAvailable" to hasFlashUnit,
                "flash.builtInEnabled" to flashEnabled,
                "flash.previewAeMode" to (b?.get(CaptureRequest.CONTROL_AE_MODE)?.toString() ?: "?"),
                "flash.previewFlashMode" to (b?.get(CaptureRequest.FLASH_MODE)?.toString() ?: "?"),
                "flash.aeStateLast" to (lastAeState?.toString() ?: "?"),
                "flash.precapturePending" to precapturePending,
                "flash.precaptureStarts" to flashPrecaptureStarts,
                "flash.precaptureCancels" to flashPrecaptureCancels,
                "flash.resetLastReason" to lastFlashResetReason,
            )
            reportExtFlashState()
            // 有变化才落盘（flush 内部用 snapshotIfDirty）；反馈邮件走 read() 拿内存全文，
            // 这里保证文件导出也能看到最新的 flash 状态。
            com.filmcam.app.develop.DeviceGeometryReportIo.flush(context)
        }
    }

    /**
     * 实验：把常驻预览 / 一次性预闪请求武装成 FIMO 的 Camera1 shim 组合。
     *
     * 与 applyPreviewFlashSettings 分离：关闭开关时原路径一字不动。
     * AE 模式用数值 3 写，避免干扰 vc80 的源码级契约断言（预览区不得出现
     * ALWAYS_FLASH 的全限定常量名）。
     *
     * SENSOR_* 在 AE_MODE != OFF 时通常被 HAL 忽略；FIMO 的 request 里同时
     * 存在这两项和 USE_SCENE_MODE，所以照样写进去，由真机日志 `lockIso` /
     * `lockExpNs` 判断是否被采纳（FIMO 值 = 250 / 16.67ms）。
     */
    private fun applyFimoSceneArming(builder: CaptureRequest.Builder) {
        if (!FLASH_FIMO_SCENE_EXPERIMENT || FLASH_HAL_PRECAPTURE_EXPERIMENT) return
        if (!flashEnabled || !hasFlashUnit) return
        if (manualIso != null || manualExposure != null) return
        runCatching {
            builder.set(CaptureRequest.CONTROL_MODE, CONTROL_MODE_USE_SCENE_MODE_VALUE)
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CONTROL_SCENE_MODE_FACE_PRIORITY_VALUE)
            builder.set(CaptureRequest.CONTROL_AE_MODE, AE_MODE_ALWAYS_FLASH_VALUE)
            builder.set(
                CaptureRequest.FLASH_MODE,
                android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF
            )
            val iso = FIMO_PREVIEW_ISO.coerceIn(deviceIsoRange.first, deviceIsoRange.last)
            val minNs = (deviceMinExposureSec * 1_000_000_000f).toLong().coerceAtLeast(1L)
            val maxNs = (deviceMaxExposureSec * 1_000_000_000f).toLong()
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                FIMO_PREVIEW_EXPOSURE_NS.coerceIn(minNs, maxNs)
            )
            android.util.Log.i(
                "FlashDiag",
                "FIMO_SCENE arm mode=USE_SCENE_MODE scene=FACE_PRIORITY ae=3 " +
                    "iso=$iso expNs=${FIMO_PREVIEW_EXPOSURE_NS.coerceIn(minNs, maxNs)} " +
                    "devIso=$deviceIsoRange devExp=${deviceMinExposureSec}~${deviceMaxExposureSec}"
            )
        }.onFailure {
            android.util.Log.w(TAG, "FIMO 场景模式参数下发失败: ${it.message}")
        }
    }

    /** 实验旁路专用：把 CONTROL_MODE / SCENE_MODE 恢复成标准自动（关灯 / CANCEL 帧）。 */
    private fun clearFimoSceneArming(builder: CaptureRequest.Builder) {
        if (!FLASH_FIMO_SCENE_EXPERIMENT) return
        runCatching {
            builder.set(
                CaptureRequest.CONTROL_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO
            )
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CONTROL_SCENE_MODE_DISABLED_VALUE)
        }
    }

    /**
     * v75 P1：预览 / 预闪请求专用的闪光参数。
     *
     * Camera2 允许 HAL 在 CONTROL_AE_MODE_ON_ALWAYS_FLASH 下于预览、预闪测光阶段
     * 点亮 LED；小米 13U 点亮后不自己收 —— 这就是「INT 档拍完第一张常亮、切档无效、
     * 进暗房（close 会话断电）才灭」的根因。把 ALWAYS_FLASH 从预览链路上彻底拿掉，
     * HAL 就没有点灯理由，v74 P0 的一次性 CANCEL 也不会再被 repeating 覆盖。
     *
     * ⚠️ 手动曝光（EXTERNAL / 慢门）时 CONTROL_AE_MODE 必须由 applyManualSettings
     * 保持 OFF，这里只写 FLASH_MODE_OFF，绝不覆写 AE 模式。
     */
    private fun applyPreviewFlashSettings(builder: CaptureRequest.Builder) {
        builder.set(
            CaptureRequest.FLASH_MODE,
            android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF
        )
        if (manualIso == null && manualExposure == null) {
            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON
            )
        }
    }

    /**
     * one-shot precapture 的**一次性**请求（AE_PRECAPTURE_TRIGGER_START）专用。
     *
     * vc80 真机取证（S25U）：P1 把这一路也改成 AE_ON 后，HAL 不做闪光测光与
     * 充电，700ms 超时才放快门；HAL 只把 LED 点 34ms 做测光，拍照结果
     * flashState=READY（不是 FIRED）且走 ZSL 历史帧 —— 用户看到「不闪」。
     * Camera2 要求 precapture 请求本身就是 ON_ALWAYS_FLASH，HAL 才会跑完整的
     * 预闪→测光→退出 ZSL→主闪序列。
     *
     * v81 SINGLE 试验已回退（11-14 用户拍板：要预闪手感、SINGLE 出图口径与
     * ALWAYS_FLASH 相同无收益）：这一路恢复 ALWAYS_FLASH，是用户实机验证过
     * 「预闪→主闪→FIRED」全序列可用的方案。
     *
     * 这里**只允许用于 submitStillCaptureNow 里那一个 one-shot 请求**；常驻预览
     * repeating 仍走 applyPreviewFlashSettings（AE_ON + FLASH_MODE_OFF），收尾仍由
     * resetFlashPrecapture 的两段式 CANCEL 复位 —— P1 治好的「拍完常亮」不动。
     * 不写 FLASH_MODE：AE_MODE_ON_ALWAYS_FLASH 下由 HAL 按 AE 状态控制预闪/主闪。
     */
    private fun applyPrecaptureFlashSettings(builder: CaptureRequest.Builder) {
        if (!flashEnabled) return
        if (!hasFlashUnit) return
        if (manualIso != null || manualExposure != null) return
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        )
    }

    /**
     * 闪光请求参数：⚠️ v75 P1 起 **只允许用于 TEMPLATE_STILL_CAPTURE 的拍照帧**。
     * 必须在 applyManualSettings 之后调用，否则会被自动测光分支覆盖。
     * 只在自动曝光下生效 —— 手动 ISO/快门（外置闪光灯那条路）AE 是 OFF，HAL 不闪。
     *
     * v81 回退（11-14 用户拍板）：恢复 vc80 的 ALWAYS_FLASH——用户实机验证过
     * 「预闪→主闪→flashState=FIRED」全序列可用，且**要预闪手感**。v81 SINGLE
     * 试验轮真机定量：aeState 正常收敛（不再 700ms 超时）但出图口径与 ALWAYS_FLASH
     * 完全相同（iso=23/1/60s、背景按平方反比死黑）、isFlashCapture 仍 0、无预闪、
     * 亮度观感无改善——SINGLE 在 S25U HAL 上没有解锁任何厂商闪光管线，收益为零
     * 代价为负。SINGLE 方案见 git 历史 v81（若未来某机型 ALWAYS_FLASH 表现异常可
     * 作为候选 A/B）。
     * 这里挂 ALWAYS_FLASH 是成片直闪效果的来源，一字不改。
     */
    private fun applyStillFlashSettings(builder: CaptureRequest.Builder) {
        if (!flashEnabled) return
        if (!hasFlashUnit) return          // 没有闪光灯单元：走 torch，见 setFlashEnabled
        if (manualIso != null || manualExposure != null) return
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        )
    }

    /**
     * 内置闪光灯开关。v74 P0：
     * - 关灯不再只把 AE 拨回 ON，而是走 [resetFlashPrecapture] 显式发
     *   PRECAPTURE_CANCEL + FLASH_MODE_OFF，否则小米 13U 等 HAL 的预闪 LED 关不掉；
     * - 去掉 vc73 的「同值就不下发」早退判断：同值也下发一次，杜绝「切档无效」；
     * - 只改预览请求，STILL_CAPTURE 的成像参数一个不动。
     */
    fun setFlashEnabled(enabled: Boolean) {
        val changed = flashEnabled != enabled
        flashEnabled = enabled
        android.util.Log.i(
            "FlashDiag",
            "setFlashEnabled($enabled) changed=$changed hasFlashUnit=$hasFlashUnit"
        )
        if (!hasFlashUnit) {
            // 设备没有闪光灯单元：HAL 闪光模式无效，退回常亮手电补光。
            // 这条路径本来就会写 FLASH_MODE_OFF，能正常关灯；关灯时强制再下发一次。
            if (torchOn != enabled || !enabled) {
                torchOn = enabled
                enableFlash(enabled)
            }
            reportFlashState()
            return
        }
        if (!enabled) {
            // 实验旁路：关灯必须先撤掉场景模式，再走两段式 CANCEL
            previewRequestBuilder?.let { clearFimoSceneArming(it) }
            // v81：本次会话从未起过 precapture → 没有预闪状态机要取消，
            // 一次一次性 CANCEL 帧都不该发（那帧会投到预览 surface）。
            // 判据只看 precapture 状态，不看 changed —— BUILT_IN→EXTERNAL 虽然
            // changed=true，但从未拍过闪光照片时同样没有灯要收。
            // 预览 builder 的 AE_ON + FLASH_MODE_OFF 在切进档位时已经设好，早退不改它。
            // ⚠️ 只要拍过照 / 有 pending，仍强制走两段式 CANCEL —— v74 P0 治好的
            // 小米 13U 常亮、v75 P1 的复位一个都不能少。
            if (!precapturePending && flashPrecaptureStarts == 0) {
                reportFlashState()
                // 实验旁路：已撤掉场景模式，必须重发一次 repeating 才真正生效，
                // 否则常驻 ALWAYS_FLASH 会留在 HAL（关灯不灭灯的风险）。
                previewRequestBuilder?.let { b ->
                    runCatching {
                        captureSession?.setRepeatingRequest(b.build(), previewMetaCallback, backgroundHandler)
                    }
                }
                return
            }
            // 关闪光：显式取消预闪 + 灭灯（reset 内部已下发 repeating request）
            resetFlashPrecapture("setFlashEnabled(false)", force = true)
        } else {
            previewRequestBuilder?.let {
                applyPreviewFlashSettings(it)
                applyHalPrecaptureArming(it)
                applyFimoSceneArming(it)
                runCatching {
                    captureSession?.setRepeatingRequest(it.build(), previewMetaCallback, backgroundHandler)
                }
            }
        }
        reportFlashState()
    }

    private fun applyManualSettings(builder: CaptureRequest.Builder) {
        if (manualIso != null || manualExposure != null) {
            builder.set(CaptureRequest.CONTROL_MODE, android.hardware.camera2.CameraMetadata.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF)
            manualIso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            manualExposure?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        } else {
            // 自动模式：相机自动测光 + 曝光补偿（下发前按设备真实范围钳制）
            builder.set(CaptureRequest.CONTROL_MODE, android.hardware.camera2.CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                exposureComp.coerceIn(aeCompRange.first, aeCompRange.last)
            )
        }
        // 对焦：模拟失焦用手动固定对焦距离；点屏后切 AUTO 并锁定焦点（T3 半按锁定手感）；
        // 未点屏时才用连续对焦
        val d = defocusDistance
        if (d != null) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, d)
        } else {
            val region = afRegion
            if (region != null) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
        }
        // 复刻 T3 35mm 视角：主摄裁切（需 Android 11+，S25 Ultra 支持）
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        }
    }

    /**
     * 模拟失焦：故意对焦不准，让主体轻微处于焦外。
     * 实时生效于预览和拍照。focusRatio 0~1，越小失焦越轻。
     */
    fun setDefocus(enabled: Boolean, focusRatio: Float = 0.6f) {
        if (!enabled) {
            afRegion = null
            afLocked = false
        }
        defocusDistance = if (enabled && minFocusDistance > 0f) minFocusDistance * focusRatio else null
        previewRequestBuilder?.let {
            applyManualSettings(it)
            captureSession?.setRepeatingRequest(it.build(), previewMetaCallback, backgroundHandler)
        }
    }

    /**
     * 点屏对焦：把预览视图坐标映射到传感器 active array 坐标，
     * 设置 AF/AE 测光区并触发一次 AF 扫焦。
     *
     * 坐标映射：屏幕归一化 (u,v) 先按 contentRectFor 去掉 letterbox 黑边，
     * 再按「传感器图像顺时针旋转 R 度才是当前屏幕画面」的逆变换折算回传感器坐标
     * （R = (sensorOrientation - displayRotation + 360) % 360，与 JPEG_ORIENTATION 同源）。
     * 之前只用「是否横屏」决定要不要互换两轴，漏了竖屏/反向横屏该做的取反 ——
     * 表现就是测光区与手指点的位置左右对不上。
     *
     * 腰平态（[waistGateAspect] > 0）：预览表面就是画幅矩形，4:3 内容**裁切填充**
     * 铺满表面（LutGLSurfaceView.waistFill），点 (u,v) 先按同一套裁切公式折回
     * 内容归一化坐标（表面即成片：内容比表面窄 → 裁纵向，反之裁横向），再走旋转
     * 映射 —— 与 shader 的采样公式逐字对齐，点哪对哪。
     *
     * @param onFocused 对焦流程触发后约 700ms 回调（近似扫焦完成，用于震动反馈）
     * @return false 表示无法点屏对焦（会话未就绪 / 模拟失焦开启 / 设备不支持）
     */
    fun focusAt(
        viewX: Float, viewY: Float, viewW: Int, viewH: Int,
        onFocused: (() -> Unit)? = null,
        /** 腰平态画幅宽高比（w/h）；0 = 常规整屏（letterbox 模型）。 */
        waistGateAspect: Float = 0f,
    ): Boolean {
        if (defocusDistance != null) return false   // 模拟失焦优先，不响应点屏
        val session = captureSession ?: return false
        val builder = previewRequestBuilder ?: return false
        if (viewW <= 0 || viewH <= 0) return false
        return try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = manager.getCameraCharacteristics(cameraId)
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?: return false

            // zoom 裁切区域（API 30+ 用 CONTROL_ZOOM_RATIO，等价于 active 中心裁切 1/Z）
            val z = if (android.os.Build.VERSION.SDK_INT >= 30) zoomRatio else 1f
            val cropW = active.width() / z
            val cropH = active.height() / z
            val cropX = active.left + (active.width() - cropW) / 2f
            val cropY = active.top + (active.height() - cropH) / 2f

            // 屏幕画面 → 传感器坐标系的总旋转量 R（顺时针）。
            val sensorOrientation =
                chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val displayRotation = when (currentDisplayRotationDeg()) {
                90 -> 90
                180 -> 180
                270 -> 270
                else -> 0
            }
            val r = (sensorOrientation - displayRotation + 360) % 360

            // (u,v) = 预览**内容**的归一化坐标。
            val u: Float
            val v: Float
            if (waistGateAspect > 0f) {
                // 腰平：表面即画幅，先按整表面归一化，再按 shader 的裁切公式折回内容坐标。
                // 内容朝向跟 display rotation 走（与 LutGLSurfaceView.waistFill 同一条规则）。
                val contentAspect = when (displayRotation) {
                    90, 270 -> 4f / 3f
                    else -> 3f / 4f
                }
                val gu = (viewX / viewW).coerceIn(0f, 1f)
                val gv = (viewY / viewH).coerceIn(0f, 1f)
                if (contentAspect < waistGateAspect) {
                    // 内容更「竖」：裁纵向 —— 只取内容中央 ca/ga 那一段高度
                    val k = contentAspect / waistGateAspect
                    u = gu
                    v = 0.5f + (gv - 0.5f) * k
                } else {
                    // 内容更「横」：裁横向 —— 只取内容中央 ga/ca 那一段宽度
                    val k = waistGateAspect / contentAspect
                    u = 0.5f + (gu - 0.5f) * k
                    v = gv
                }
            } else {
                // 常规：letterbox 模型，唯一来源 ViewfinderFrameView.contentRectFor
                // （与 shader、取景框标记同一套公式，横屏满高 / 竖屏满宽都在这里统一）。
                val rect = com.filmcam.app.ui.ViewfinderFrameView
                    .contentRectFor(viewW.toFloat(), viewH.toFloat())
                u = ((viewX - rect.left) / rect.width().coerceAtLeast(1f)).coerceIn(0f, 1f)
                v = ((viewY - rect.top) / rect.height().coerceAtLeast(1f)).coerceIn(0f, 1f)
            }
            val (nx, ny) = when (r) {
                90 -> v to (1f - u)      // 竖屏：轴互换，且横轴取反（画面左=传感器下）
                180 -> (1f - u) to (1f - v)
                270 -> (1f - v) to u
                else -> u to v           // 横屏（rotation=90）：画面即传感器原生方向
            }
            val sx = (cropX + nx * cropW).toInt()
            val sy = (cropY + ny * cropH).toInt()
            val half = (minOf(cropW, cropH) * 0.1f).toInt().coerceAtLeast(16)
            val region = android.hardware.camera2.params.MeteringRectangle(
                (sx - half).coerceAtLeast(active.left),
                (sy - half).coerceAtLeast(active.top),
                half * 2, half * 2, 1000
            )

            // 关键：CONTROL_AF_MODE_CONTINUOUS_PICTURE 下相机框架会忽略 AF_TRIGGER，
            // 点屏只影响测光→看起来"只有测光变了没对上焦"。这里切换到 AUTO 模式并触发一次扫焦，
            // 完成锁定后保持（T3 半按锁定手感），再次点屏才重新对焦。
            afRegion = region
            afLocked = false
            pendingFocusCb = onFocused
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.setRepeatingRequest(builder.build(), previewMetaCallback, backgroundHandler)

            // 兜底：部分设备不回调 LOCKED 状态，2.5s 后仍给一次反馈
            backgroundHandler?.removeCallbacks(focusTimeout)
            backgroundHandler?.postDelayed(focusTimeout, 2500)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 当前屏幕旋转角度（0/90/180/270），用于把触摸坐标折算到传感器方向 */
    private fun currentDisplayRotationDeg(): Int {
        val activity = context as? android.app.Activity
        val rot: Int = if (android.os.Build.VERSION.SDK_INT >= 30 && activity != null) {
            activity.display?.rotation ?: Surface.ROTATION_0
        } else if (activity != null) {
            @Suppress("DEPRECATION")
            activity.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        } else {
            Surface.ROTATION_0
        }
        return when (rot) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /**
     * 预览帧回调：既监听 AF 状态（点屏扫焦是否完成），也持续把 对焦状态/曝光时间
     * 上报给 UI 层的取景器指示灯（T3 的绿色合焦灯 / 快门速度档位）。
     */
    private val previewMetaCallback = object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: android.hardware.camera2.CameraCaptureSession,
            request: CaptureRequest,
            result: android.hardware.camera2.TotalCaptureResult
        ) {
            val state = result.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE)
            val exposureSec = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                ?.let { it / 1_000_000_000f } ?: 0f

            if (state == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                state == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            ) {
                backgroundHandler?.removeCallbacks(focusTimeout)
                afLocked = true
                // 触发位回到 IDLE，避免重复扫焦；AF 仍停在锁定位置
                previewRequestBuilder?.set(
                    CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                )
                runCatching {
                    previewRequestBuilder?.let { session.setRepeatingRequest(it.build(), this, backgroundHandler) }
                }
                fireFocusCallback()
            }

            // 慢门决策要用同一对 (t, ISO)：只存曝光时间不够，亮度是两者的乘积
            val isoAe = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val aeState = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
            // v74 P2：AE 状态只在变化时上报，避免每帧置脏；闪光时它会在
            // SEARCHING / PRECAPTURE / FLASH_REQUIRED / CONVERGED 之间跳，是判异常的关键。
            halPrecaptureAeSink?.let { sink -> if (aeState != null) sink(aeState) }
            if (aeState != null && aeState != lastAeState) {
                lastAeState = aeState
                reportFlashState()
            }
            if (exposureSec > 0f && isoAe > 0) {
                lastAeSec = exposureSec
                lastAeIso = isoAe
                // 方案③：预闪窗口内预览流回调同样采样（最小乘积 = 闪光测光）
                if (precapturePending) recordTtlSample(isoAe, exposureSec)
                // 外置闪光的「环境光基线」只在 AE 收敛时更新。灯一闪 AE 会立刻
                // 进 SEARCHING（真机 01:15 那张 aeState=1），基线就停在闪光前的
                // 读数，不会被闪光峰值污染 —— 这是黑片修复的关键。
                if (aeState == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    aeState == android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_LOCKED
                ) {
                    if (aeBaselineIso <= 0) {
                        aeBaselineSec = exposureSec
                        aeBaselineIso = isoAe
                    } else {
                        aeBaselineSec = aeBaselineSec * 0.9f + exposureSec * 0.1f
                        aeBaselineIso = (aeBaselineIso * 0.9f + isoAe * 0.1f).toInt()
                    }
                }
            }

            // 指标上报（限流 150ms，避免每帧刷 UI）
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastMetaPostAt > 150) {
                lastMetaPostAt = now
                val af = state
                mainHandler.post { onViewfinderMeta?.invoke(af, exposureSec, isoAe) }
            }
        }
    }

    /** 点屏后是否已锁定焦点（供 UI 显示「慢闪」状态，对应 AFL 锁定语义） */
    fun isAfLocked(): Boolean = afLocked

    private fun fireFocusCallback() {
        val cb = pendingFocusCb ?: return
        pendingFocusCb = null
        mainHandler.post { runCatching { cb.invoke() } }
    }

    fun enableFlash(enabled: Boolean) {
        try {
            previewRequestBuilder?.apply {
                set(CaptureRequest.FLASH_MODE,
                    if (enabled) android.hardware.camera2.CameraMetadata.FLASH_MODE_TORCH
                    else android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF)
            }
            captureSession?.setRepeatingRequest(previewRequestBuilder!!.build(), previewMetaCallback, backgroundHandler)
        } catch (_: Exception) {}
    }

    fun setManualIso(iso: Int?) {
        if (manualIso == iso) return   // v81：同值不重发预览请求
        manualIso = iso
        previewRequestBuilder?.let {
            applyManualSettings(it)
            captureSession?.setRepeatingRequest(it.build(), previewMetaCallback, backgroundHandler)
        }
    }

    fun setManualExposure(exposureNs: Long?) {
        if (manualExposure == exposureNs) return   // v81：同值不重发预览请求
        manualExposure = exposureNs
        previewRequestBuilder?.let {
            applyManualSettings(it)
            captureSession?.setRepeatingRequest(it.build(), previewMetaCallback, backgroundHandler)
        }
    }

    // ---------------- 慢门：给上层（胶片曝光模型）的接口 ----------------

    /** 预览 AE 最近一次测光（曝光秒, ISO）。慢门决策的唯一输入，没有就返回 null */
    fun meteringSnapshot(): Pair<Float, Int>? =
        if (lastAeSec > 0f && lastAeIso > 0) lastAeSec to lastAeIso else null

    /** 主摄光圈（f 值）：把 AE 的曝光量还原成场景亮度要用它 */
    fun aperture(): Float = apertureValue

    /** 传感器曝光范围（秒） */
    fun deviceExposureRangeSec(): Pair<Float, Float> = deviceMinExposureSec to deviceMaxExposureSec

    /** 传感器 ISO 范围 */
    fun deviceIsoRange(): IntRange = deviceIsoRange

    /** 设备支持手动曝光才有慢门（否则只能走 AE 补偿，拍不出长曝光） */
    fun supportsSlowShutter(): Boolean = hasManualSensor

    fun stop() {
        // v74 P0：关会话之前先显式收灯，别只依赖 HAL 断电（不同 HAL 时序不一致）
        resetFlashPrecapture("stop", force = true)
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        rawImageReader?.close()
        rawImageReader = null
        // 调研路③：关 YUV reader + 清空环形缓冲（Image 必须关，防 HAL 缓冲泄漏）。
        yuvRingActive = false
        synchronized(yuvRing) {
            yuvRing.forEach { it.jpeg = null }
            yuvRing.clear()
        }
        yuvImageReader?.close()
        yuvImageReader = null
        // 汇合中的碎片一并丢弃（Image 必须关，否则 HAL 缓冲泄漏）
        pendingShot?.rawImage?.close()
        pendingShot = null
        // DNG 写盘线程常驻不 shutdown：stop 之后还可能 start（暂停/恢复），
        // 空闲单线程的开销为零，关了反而要在 start 里重建
        backgroundThread?.quitSafely()
        captureSession = null
        cameraDevice = null
    }
}
