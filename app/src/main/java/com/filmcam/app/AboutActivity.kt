package com.filmcam.app

import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 关于页：产品介绍 + 赞赏码 + 版本号。
 * 版本号连点 10 次进入「工程师模式」（调试跳过冲洗 / 模拟失焦概率）——
 * 与主流 App 的彩蛋入口一致，把开发调试项从普通设置里藏起来。
 */
class AboutActivity : AppCompatActivity() {

    private var tapCount = 0
    private var versionText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        versionText = "FILMCAM v" + (try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        })

        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        tvVersion.text = versionText
        tvVersion.setOnClickListener {
            tapCount++
            when {
                tapCount in 4..9 -> tvVersion.text = "$versionText · 再点 ${10 - tapCount} 次"
                tapCount >= 10 -> {
                    tapCount = 0
                    tvVersion.text = versionText
                    showHiddenSettings()
                }
            }
        }
    }

    private fun showHiddenSettings() {
        val prefs = getSharedPreferences("filmcam", MODE_PRIVATE)
        val view = layoutInflater.inflate(R.layout.dialog_hidden_settings, null)

        val swSkip = view.findViewById<android.widget.Switch>(R.id.swSkipDevelop)
        val sbProb = view.findViewById<SeekBar>(R.id.sbSoftFocusProb)
        val tvProb = view.findViewById<TextView>(R.id.tvSoftFocusProb)

        swSkip.isChecked = prefs.getBoolean("debug_instant_develop", false)
        // 概率缺省：老用户开过模拟失焦 → 40%（旧版硬编码值）；否则 0（关）
        val prob = prefs.getInt(
            "softFocus_prob",
            if (prefs.getBoolean("softFocus", false)) 40 else 0
        )
        sbProb.progress = prob
        tvProb.text = getString(R.string.percent_fmt, prob)
        sbProb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                tvProb.text = getString(R.string.percent_fmt, value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("工程师模式")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                prefs.edit()
                    .putBoolean("debug_instant_develop", swSkip.isChecked)
                    .putInt("softFocus_prob", sbProb.progress)
                    .putBoolean("softFocus", sbProb.progress > 0)
                    .apply()
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
