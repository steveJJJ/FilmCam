package com.filmcam.app.flash

/**
 * 外置闪光灯驱动接口（品牌无关）。
 *
 * 架构分层（vc86 重构，行为零变化）：
 *  - 业务层（MainActivity / 快门时序）只面向 [FlashDevice]（三档 OFF/BUILT_IN/EXTERNAL）；
 *  - [FlashController] 持有 EXTERNAL 档的实现，但**不再直接 new 具体品牌的灯**，
 *    而是问 [ExternalFlashRegistry] 要驱动 —— 加新品牌 = 注册一个 Entry，业务代码零改动；
 *  - 每个品牌一个 [ExternalFlashDriver] 实现（第一个是 [FotorGearFlash]）。
 *
 * 设计纪律（沿袭 [FlashTriggerCommand]）：
 *  - 本接口**零 Android 依赖**，JVM 单测可自由构造假驱动；
 *  - mode 归 [FlashController] 管，驱动只管「连灯 / 触发 / 释放」，不知道自己在
 *    哪个档位 —— 这是从旧 FotorGearFlash（直接实现 FlashDevice、自带 mode 字段
 *    但从未被读过）迁移时顺手清掉的死概念。
 */
interface ExternalFlashDriver {

    /** 开始扫描 / 连接（实现自己维护重连）。 */
    fun startScan()

    /**
     * 触发闪光（曝光回调里调用，见 Camera2Controller 的同步提前量）。
     * - 典型实现：往 GATT 可写特征写触发帧（帧内容见各驱动的协议注释）。
     * - 未就绪时实现自己负责上报状态并调度重连，调用方不做重试。
     */
    fun trigger()

    /** 释放资源（关 GATT、撤 handler 回调）。 */
    fun release()

    /** 状态回调（连接状态 / 电量 / 触发结果），格式沿用既有 "READY" / "TRIGGER_SENT" 等字符串契约。 */
    var onStatusChange: ((String) -> Unit)?
}

/**
 * 外闪能力分级。注册驱动时如实声明，决定 App 能对它做什么：
 *  - [DUMB]：只能触发。曝光控制全靠相机侧（快门/ISO 补偿），当前唯一档。
 *  - [POWER]：可调功率档。预闪可用低功率档，正式闪按计算结果选档 ——
 *    TTL 精度预期优于内置 LED（LED 功率被 HAL 锁死只能靠快门补偿）。
 *  - [TTL_READY]：支持预闪指令（App 发预闪 → 采 AE 差分 → 下发正式功率），
 *    可直接接 [com.filmcam.app.camera.FlashTtl] 的预闪差分算法。
 */
enum class FlashCapability {
    DUMB,
    POWER,
    TTL_READY
}
