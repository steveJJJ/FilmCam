package com.filmcam.app.filter

import com.filmcam.app.ui.AppLocale

/**
 * 胶卷数据模型
 *
 * 注：产品设计上是「光学取景器看不到成片效果」，预览不做 LUT 调色，
 * 因此这里不再持有预览用 LUT 资源（原 9 个 .cube 共 11MB 从未参与渲染）。
 * 成片调色由 Vulkan 引擎按 filmStockId 走 profiles 目录下的 json 配置。
 */
data class FilmRoll(
    val name: String,
    val iconResId: Int,
    /** 中文描述（胶卷名本身是英文，不参与翻译） */
    val description: String,
    /** 中文标签：彩色 / 反转 / 电影 / 黑白 */
    val tag: String,
    /** 英文描述；缺省与中文同值（未配英文的胶卷自动回落中文） */
    val descriptionEn: String = description,
    /** 英文标签：Color / Slide / Cine / B&W */
    val tagEn: String = tag,
    /**
     * 中文适用场景（v16）：右侧场景说明，回答「哪支卷适合什么场景」。
     * 比 tag（彩色/黑白分类）更实用 —— 用户问得最多的就是这一栏。
     * 短标签式，≤4 个汉字，如「人像·婚礼」「风光·建筑」「夜景·霓虹」。
     */
    val scene: String = "",
    /** 英文适用场景；缺省回落中文。 */
    val sceneEn: String = scene,
    /** 繁体中文（港台共用一套）描述/标签/场景；缺省回落英文 */
    val descriptionTw: String = descriptionEn,
    val tagTw: String = tagEn,
    val sceneTw: String = sceneEn,
    /** 日本語描述/标签/场景；缺省回落英文 */
    val descriptionJa: String = descriptionEn,
    val tagJa: String = tagEn,
    val sceneJa: String = sceneEn,
    /** 한국어描述/标签/场景；缺省回落英文 */
    val descriptionKo: String = descriptionEn,
    val tagKo: String = tagEn,
    val sceneKo: String = sceneEn,
    /**
     * 是否可选。默认全卷可选 —— 反转片（type=positive）是引擎一等支持的胶卷：
     * FilmStockMapper.isPositive → scan_film 直扫正片、NO_PRINT 不用相纸，
     * DevParamsDialog 对正片把相纸行置灰。🔴反转片必须可选、可拍摄，不得置灰或拦截
     * （11-21 用户明确纠正「这个不是bug，我需要反转片可以拍摄」）。
     * 本字段仅作个别卷临时下架的保留位，当前列表中没有任何卷为 false。
     */
    val available: Boolean = true
) {
    /**
     * 按语言档位返回展示副本。只替换 description/tag/scene，各语原值都留在对象里，
     * 因此多语言间来回切都能重新投影（见 FilmRollRepository.rolls）。
     */
    fun localized(mode: Int): FilmRoll = when (mode) {
        AppLocale.EN -> copy(description = descriptionEn, tag = tagEn, scene = sceneEn)
        AppLocale.ZH_TW -> copy(description = descriptionTw, tag = tagTw, scene = sceneTw)
        AppLocale.JA -> copy(description = descriptionJa, tag = tagJa, scene = sceneJa)
        AppLocale.KO -> copy(description = descriptionKo, tag = tagKo, scene = sceneKo)
        else -> this   // 跟随系统 / 简体中文：原值即简体
    }
}
