package com.filmcam.app

import app.spektralab.engine.FilmStockMapper
import app.spektralab.engine.JpegNormalizer
import com.filmcam.app.camera.FilmExposure

/**
 * 新装 / 清除数据后，设置面板里七项默认档位的**唯一口径**
 * （用户 2026-11-13 指定：白边相框 / 淡 / 失焦 2% / Frontier 店扫 / 慢门自然 /
 * 真实派轻随机 / 日期水印关）。
 *
 * ## 为什么是「读时回落」而不是「首次启动写进 prefs」
 * 只在 `SharedPreferences` 里没有该键时生效，所以：
 *  - 老用户已经存过的档位**绝不被覆盖**（不会把人家手动切回的印相档又改成店扫）；
 *  - 清数据 / 新装 / 侧载测试包立刻看到新默认；
 *  - 工程师滑杆、机身设置面板、冲洗参数组装三处读的是同一批常量，不会各说各话。
 *
 * ⚠️ 引擎层（app.spektralab.engine）不能反向依赖 app 层，所以三项引擎默认
 * 定义在 [FilmStockMapper.DEFAULT_FRAME_FORMAT] / [FilmStockMapper.DEFAULT_OUTPUT_STYLE] /
 * [JpegNormalizer.DEFAULT_LEVEL_STRENGTH]，这里只是聚合引用；慢门两项同理放在
 * [FilmExposure.DEFAULT_MODE] / [FilmExposure.DEFAULT_TRIGGER]。
 *
 * ⚠️ 模拟失焦的概率滑杆文案是「0% = 关闭」，滑杆 >0 时会同步把 `softFocus` 写 true。
 * 所以「默认 2%」必须同时把 [softFocusEnabled] 设为 true，否则面板显示 2% 却永远不会触发。
 */
object DefaultSettings {

    /** 胶片相框：白边相框（不下发 overscan_*，由 WhiteBorderRenderer 落盘前外扩）。 */
    val frameFormat: FilmStockMapper.FrameFormat get() = FilmStockMapper.DEFAULT_FRAME_FORMAT

    /** 色彩浓度：淡（滑杆满档）。0.7「标准」仍是三档之一，可手动切回。 */
    val colorStrength: Float get() = JpegNormalizer.DEFAULT_LEVEL_STRENGTH

    /** 模拟失焦概率（0–100）。2% = 每百张约两张故意失焦。 */
    const val SOFT_FOCUS_PROB = 2

    /** 概率 >0 即视为开启，与工程师滑杆的写入语义保持一致。 */
    const val SOFT_FOCUS_ENABLED = true

    /** 输出风格：Frontier 店扫（暗部偏冷、对比强、高光柔）。 */
    val outputStyle: FilmStockMapper.OutputStyle get() = FilmStockMapper.DEFAULT_OUTPUT_STYLE

    /** 慢门档位：自然。 */
    val slowMode: FilmExposure.Mode get() = FilmExposure.DEFAULT_MODE

    /** 慢门触发机制：真实派 + 轻随机（必然慢门，再按档位概率额外慢一档）。 */
    val slowTrigger: FilmExposure.Trigger get() = FilmExposure.DEFAULT_TRIGGER

    /**
     * 日期水印：11-13 用户指定**默认关闭**（成片右下角不再印 `'yy MM dd`）。
     * 只是默认档，设置面板的开关和 DateStampRenderer 绘制链路一行没删，
     * 用户想开随时能就地翻转；老用户手动存过的 true 也绝不被覆盖。
     * ⚠️ 拍摄侧（MainActivity 字段 / restoreSettings）与冲洗侧
     * （DevelopmentService.isDateStampEnabled）必须同时引用这个常量，否则会出现
     * 「面板显示关、冲洗却还在画日期戳」。
     */
    const val DATE_STAMP_ENABLED = false

    /** 便于自检 / 单测：七项档位的名称与值。 */
    fun summary(): Map<String, Any> = linkedMapOf(
        "frameFormat" to frameFormat.key,
        "colorStrength" to colorStrength,
        "softFocusProb" to SOFT_FOCUS_PROB,
        "softFocusEnabled" to SOFT_FOCUS_ENABLED,
        "outputStyle" to outputStyle.wire,
        "slowMode" to slowMode.name,
        "slowTrigger" to slowTrigger.name,
        "dateStampEnabled" to DATE_STAMP_ENABLED,
    )
}
