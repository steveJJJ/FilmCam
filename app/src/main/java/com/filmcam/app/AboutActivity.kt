package com.filmcam.app

import android.app.ActivityManager
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
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

        buildOssCredits()
        buildSelfLicense()
        bindFeedback()
        bindAppreciation()

        // 跟随机身配色（黑钛 / 钛银）
        com.filmcam.app.ui.BodyTheme.load(this)
        val root = findViewById<View>(android.R.id.content)
        com.filmcam.app.ui.BodyTheme.applyToViewTree(root)
        root.background = com.filmcam.app.ui.BodyTheme.bodyBackground()
        com.filmcam.app.ui.BodyTheme.applySystemBars(window)

        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        tvVersion.text = versionText
        tvVersion.setOnClickListener {
            tapCount++
            when {
                tapCount in 4..9 -> tvVersion.text =
                    getString(R.string.about_tap_more, versionText, 10 - tapCount)
                tapCount >= 10 -> {
                    tapCount = 0
                    tvVersion.text = versionText
                    showHiddenSettings()
                }
            }
        }
    }

    /**
     * 开源致谢列表：条目来自 res/values/strings.xml 的 oss_credits
     * （格式 名称|作者/组织|许可证|链接[|二次分发说明]），行内点击可打开对应许可/项目地址。
     */
    private fun buildOssCredits() {
        val container = findViewById<LinearLayout>(R.id.ossList)
        val density = resources.displayMetrics.density
        val pad = (14 * density).toInt()
        val entries = resources.getStringArray(R.array.oss_credits)

        container.background = GradientDrawable().apply {
            setColor(com.filmcam.app.ui.BodyTheme.cardBg)
            cornerRadius = 12f * resources.displayMetrics.density
            setStroke(
                (0.8f * resources.displayMetrics.density).toInt().coerceAtLeast(1),
                com.filmcam.app.ui.BodyTheme.cardStroke
            )
        }

        entries.forEachIndexed { index, raw ->
            val parts = raw.split("|")
            val name = parts[0]
            val meta = buildString {
                append(parts.getOrElse(1) { "" })
                append(" · ")
                append(parts.getOrElse(2) { "" })
            }
            val url = parts.getOrElse(3) { "" }
            // 可选第 5 段：二次分发说明（如 CC BY-SA 要求标注是否修改过）
            val note = parts.getOrElse(4) { "" }.trim()

            if (index > 0) {
                container.addView(View(this).apply {
                    setBackgroundColor(com.filmcam.app.ui.BodyTheme.cardStroke)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (0.7f * density).toInt().coerceAtLeast(1)
                    )
                })
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(pad, pad, pad, pad)
            }
            // 用主题自带水波纹做点击反馈（Foreground 更贴合列表）
            if (url.isNotBlank()) {
                val a = intArrayOf(android.R.attr.selectableItemBackground)
                val ta = obtainStyledAttributes(a)
                row.foreground = ta.getDrawable(0)
                ta.recycle()
                row.isClickable = true
                row.setOnClickListener {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            }

            row.addView(TextView(this).apply {
                text = name
                setTextColor(0xFFD8D4CC.toInt())
                textSize = 12.5f
            })
            row.addView(TextView(this).apply {
                text = meta
                setTextColor(0xFF7C7C80.toInt())
                textSize = 10.5f
                letterSpacing = 0.03f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (3 * density).toInt() }
            })
            if (note.isNotEmpty()) {
                row.addView(TextView(this).apply {
                    text = note
                    setTextColor(0xFF9A8C6A.toInt())
                    textSize = 10f
                    letterSpacing = 0.03f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (2 * density).toInt() }
                })
            }
            container.addView(row)
        }
    }

    /** 赞赏区下方的 PayPal 入口，点击在浏览器打开 PayPal 页面。
     *  微信赞赏码是纯图片展示，用户用微信扫一扫即可。 */
    private fun bindAppreciation() {
        val chip = findViewById<TextView>(R.id.tvPaypal)
        val paypal = getString(R.string.appreciate_paypal_url).trim()
        if (paypal.isEmpty()) {
            chip.visibility = View.GONE
        } else {
            chip.setOnClickListener { openExternal(chip, paypal) }
        }
    }

    /** 用系统浏览器打开外部链接，失败时给出提示。 */
    private fun openExternal(view: View, url: String) {
        val started = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.isSuccess
        if (!started) {
            Toast.makeText(this, R.string.about_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 标题栏右端的「问题反馈」入口：先给两个渠道选——
     *  · 邮箱反馈：预填 App 版本与设备信息，便于拿到能直接复现的诊断数据；
     *  · 加微信进群：复制微信号（LazyRofin）到剪贴板，用户去微信加群当面反馈。
     */
    private fun bindFeedback() {
        findViewById<TextView>(R.id.tvFeedback).setOnClickListener { showFeedbackOptions() }
    }

    private fun showFeedbackOptions() {
        val channels = arrayOf(
            getString(R.string.about_feedback_email_channel),
            getString(R.string.about_feedback_wechat_channel)
        )
        androidx.appcompat.app.AlertDialog.Builder(this, com.filmcam.app.ui.BodyTheme.dialogTheme)
            .setTitle(R.string.about_feedback)
            .setItems(channels) { _, which ->
                if (which == 0) sendFeedback() else copyWechatId()
            }
            .setNegativeButton(R.string.main_cancel, null)
            .show()
    }

    /** 复制进群微信号到剪贴板（微信号：LazyRofin） */
    private fun copyWechatId() {
        val wechatId = "LazyRofin"
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText(getString(R.string.about_wechat_id_label), wechatId)
        )
        Toast.makeText(
            this, getString(R.string.about_wechat_copied, wechatId), Toast.LENGTH_LONG
        ).show()
    }

    private fun sendFeedback() {
        val email = getString(R.string.app_feedback_email).trim()
        if (email.isEmpty()) {
            Toast.makeText(this, R.string.feedback_email_missing, Toast.LENGTH_SHORT).show()
            return
        }

        val pi = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val verName = pi?.versionName ?: "?"
        val verCode = pi?.let { info ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else info.versionCode
        } ?: 0
        val metrics = resources.displayMetrics
        val memInfo = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memInfo)
        val availMb = (memInfo.availMem / (1024 * 1024)).toInt()
        val totalMb = (memInfo.totalMem / (1024 * 1024)).toInt()

        val verLabel = "v$verName"
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")).apply {
            putExtra(
                Intent.EXTRA_SUBJECT, getString(
                    R.string.feedback_subject,
                    verLabel, Build.MANUFACTURER, Build.MODEL,
                    Build.VERSION.RELEASE, Build.VERSION.SDK_INT
                )
            )
            putExtra(
                Intent.EXTRA_TEXT, getString(
                    R.string.feedback_body,
                    verLabel, verCode, Build.MANUFACTURER, Build.MODEL,
                    Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                    metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                    availMb, totalMb
                )
            )
        }
        val started = runCatching { startActivity(intent) }.isSuccess
        if (!started) {
            Toast.makeText(this, R.string.feedback_no_mail_app, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 本项目自身的许可证卡片（置于开源致谢之后）。
     * 因为打包了 GPL-3.0 的 SpektraLab 引擎，本 App 同样以 GPL-3.0-or-later 分发；
     * 仓库未公开时用「源码在索取时提供」履行义务，一旦在 strings.xml 填入
     * app_source_email，这里会自动变成可点击的 mailto 索取入口。
     */
    private fun buildSelfLicense() {
        val container = findViewById<LinearLayout>(R.id.selfLicenseCard)
        val density = resources.displayMetrics.density
        val pad = (14 * density).toInt()

        container.background = GradientDrawable().apply {
            setColor(com.filmcam.app.ui.BodyTheme.cardBg)
            cornerRadius = 12f * density
            setStroke(
                (0.8f * density).toInt().coerceAtLeast(1),
                com.filmcam.app.ui.BodyTheme.cardStroke
            )
        }
        container.setPadding(pad, pad, pad, pad)

        container.addView(TextView(this).apply {
            text = getString(R.string.about_self_title)
            setTextColor(0xFFD8D4CC.toInt())
            textSize = 12.5f
        })
        container.addView(TextView(this).apply {
            text = getString(R.string.about_self_body)
            setTextColor(0xFF7C7C80.toInt())
            textSize = 10.5f
            letterSpacing = 0.03f
            setLineSpacing(0f, 1.45f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (5 * density).toInt() }
        })

        val email = getString(R.string.app_source_email).trim()
        if (email.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.about_source_request, email)
                setTextColor(0xFF9A8C6A.toInt())
                textSize = 10.5f
                letterSpacing = 0.03f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
                val a = intArrayOf(android.R.attr.selectableItemBackground)
                val ta = obtainStyledAttributes(a)
                foreground = ta.getDrawable(0)
                ta.recycle()
                isClickable = true
                setOnClickListener {
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")).putExtra(
                                Intent.EXTRA_SUBJECT, getString(R.string.about_source_subject)
                            )
                        )
                    }
                }
            })
        }

        // GPL 全文链接
        container.addView(TextView(this).apply {
            text = getString(R.string.about_gpl_full)
            setTextColor(0xFF7C7C80.toInt())
            textSize = 10.5f
            letterSpacing = 0.03f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
            val a = intArrayOf(android.R.attr.selectableItemBackground)
            val ta = obtainStyledAttributes(a)
            foreground = ta.getDrawable(0)
            ta.recycle()
            isClickable = true
            setOnClickListener {
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.gnu.org/licenses/gpl-3.0.html"))
                    )
                }
            }
        })
    }

    private fun showHiddenSettings() {
        val prefs = getSharedPreferences("filmcam", MODE_PRIVATE)
        val view = layoutInflater.inflate(R.layout.dialog_hidden_settings, null)
        com.filmcam.app.ui.BodyTheme.applyToViewTree(view)

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

        androidx.appcompat.app.AlertDialog.Builder(this, com.filmcam.app.ui.BodyTheme.dialogTheme)
            .setTitle(R.string.hidden_title)
            .setView(view)
            .setPositiveButton(R.string.hidden_save) { _, _ ->
                prefs.edit()
                    .putBoolean("debug_instant_develop", swSkip.isChecked)
                    .putInt("softFocus_prob", sbProb.progress)
                    .putBoolean("softFocus", sbProb.progress > 0)
                    .apply()
                Toast.makeText(this, R.string.hidden_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.main_cancel, null)
            .show()
    }
}
