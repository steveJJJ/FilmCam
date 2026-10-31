package com.filmcam.app.flash

/**
 * 闪光灯模式
 *
 * 产品定位：App 本身就是完整胶片/CCD 相机，闪光灯是加分项。
 *  - OFF：不用闪光灯
 *  - BUILT_IN：用手机自带 LED（默认，始终可用，无需配件）
 *  - EXTERNAL：用 FotorGear BLE 外接闪光灯（可选配件）
 */
enum class FlashMode {
    OFF,
    BUILT_IN,
    EXTERNAL
}

/**
 * 闪光灯抽象接口
 *
 * 业务层（MainActivity）只面向这个接口，不关心是哪种灯。
 */
interface FlashDevice {

    /** 当前模式（默认 BUILT_IN，让 App 开箱即用） */
    var mode: FlashMode

    /** 开始扫描/连接（仅 EXTERNAL 模式需要） */
    fun startScan()

    /**
     * 触发闪光（拍前调用）
     * - BUILT_IN：点亮手机 LED
     * - EXTERNAL：BLE 写指令触发灯
     * - OFF：不做任何事
     */
    fun trigger()

    /** 释放资源 */
    fun release()

    /** 状态回调（连接状态 / 电量 / 模式） */
    var onStatusChange: ((String) -> Unit)?
}
