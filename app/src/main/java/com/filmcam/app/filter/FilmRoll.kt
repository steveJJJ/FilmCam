package com.filmcam.app.filter

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
    val description: String,
    val tag: String,
    /**
     * 是否可选。反转片（type=positive）在 SpektraLab-Android 原项目中是明确拒绝的：
     * 选中即提示「反转片（幻灯）不能印到相纸上——本版暂只支持负片」并回退。
     * 这里保持一致：列表仍展示（让用户知道引擎有这支），但置灰且不可选中。
     */
    val available: Boolean = true
)
