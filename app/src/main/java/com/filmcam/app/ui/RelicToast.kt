package com.filmcam.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import com.filmcam.app.R

/**
 * Relic Cam 铭牌风 Toast（v15）：暗底 + 琥珀刻字 + 细刻线边框，取代系统灰底白字。
 * 与暗房/编辑页红光铭牌语言一致，避免「已保存 N 张到相册」这种系统 toast 破坏氛围。
 */
object RelicToast {
    fun show(context: Context, text: String, long: Boolean = false) {
        val view = LayoutInflater.from(context).inflate(R.layout.toast_relic, null)
        view.findViewById<TextView>(R.id.tvToastRelic).text = text
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).apply {
            this.view = view
            show()
        }
    }
}
