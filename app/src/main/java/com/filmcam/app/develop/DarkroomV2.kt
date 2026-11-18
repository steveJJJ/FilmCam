package com.filmcam.app.develop

import android.content.Context

/**
 * v2 暗房改造的功能开关（2026-11-18 起**全量默认开启**）。
 *
 * 改造链路：冲洗只出母片（留私有目录）→ 暗房编辑选相框/日期 → 保存才入相册。
 * 母片登记、片边重渲、引擎原生日期印字、6×6 片门、编辑导出全链路已在
 * 11-14~11-18 真机逐项验证完毕（V38 定稿），用户拍板转正：所有用户强制开，
 * 忽略历史 prefs 里手动关过的值（不再提供关闭入口，避免"面板显示关、冲洗却走母片"）。
 *
 * ⚠️ 这是核心成像链路：开启后冲洗不再直接写入相册，必须进暗房编辑页点保存才出成片。
 */
object DarkroomV2 {

    /** 全量强制开：不再读 SharedPreferences，老用户存过的 false 也不生效。 */
    fun isEnabled(context: Context): Boolean = true
}
