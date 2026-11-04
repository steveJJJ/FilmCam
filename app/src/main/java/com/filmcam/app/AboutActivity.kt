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
import kotlin.math.roundToInt

/**
 * 关于页：产品介绍 + 赞赏码 + 版本号。
 * 版本号连点 10 次解锁「工程师模式」（慢门档位 / 输出风格 / 冲洗参数 / 调试开关），
 * 与主流 App 的彩蛋入口一致，把开发旋钮从普通设置里藏起来。解锁状态会记住 ——
 * 之后点一下就能进，想重新藏起来在控制台里关掉开关。
 */
class AboutActivity : AppCompatActivity() {

    private var tapCount = 0
    private var versionText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        val pi = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val verName = pi?.versionName ?: "?"
        val verCode = pi?.let { info ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
            else info.versionCode
        } ?: 0
        versionText = "v$verName ($verCode)"

        buildOssCredits()
        buildSelfLicense()
        bindFeedback()
        bindAppreciation()
        paintNameplateChips()

        // 跟随机身配色（黑钛 / 钛银）
        com.filmcam.app.ui.BodyTheme.load(this)
        val root = findViewById<View>(android.R.id.content)
        com.filmcam.app.ui.BodyTheme.applyToViewTree(root)
        root.background = com.filmcam.app.ui.BodyTheme.bodyBackground()
        com.filmcam.app.ui.BodyTheme.applySystemBars(window)

        // 铭牌规格表里的版本号（等宽刻字）：进工程师模式。
        // 第一次要连点 10 次（彩蛋入口，普通用户不会误触）；解锁过之后点一下就能进，
        // 开发期一天要进好几次，每次都连点 10 次是纯折磨。想重新藏起来就在控制台里关掉开关。
        val tvVersion = findViewById<TextView>(R.id.tvSpecVersion)
        tvVersion.text = versionText
        tvVersion.setOnClickListener {
            if (EngineerMode.isOn(this)) {
                tapCount = 0
                showEngineerConsole()
                return@setOnClickListener
            }
            tapCount++
            when {
                tapCount in 4..9 -> {
                    tvVersion.text = getString(
                        R.string.about_tap_more, versionText, EngineerMode.UNLOCK_TAPS - tapCount
                    )
                    // 提示是临时的：几秒后恢复成版本刻字，不让铭牌一直挂着"再点 N 次"
                    tvVersion.removeCallbacks(restoreVersionText)
                    tvVersion.postDelayed(restoreVersionText, 1500L)
                }
                tapCount >= EngineerMode.UNLOCK_TAPS -> {
                    tapCount = 0
                    tvVersion.text = versionText
                    EngineerMode.setOn(this, true)
                    Toast.makeText(this, R.string.eng_mode_on_toast, Toast.LENGTH_SHORT).show()
                    showEngineerConsole()
                }
            }
        }
    }

    /** 把版本号刻字恢复回来（连点提示超时） */
    private val restoreVersionText = Runnable {
        tapCount = 0
        findViewById<TextView>(R.id.tvSpecVersion).text = versionText
    }

    /**
     * 铭牌上的小 chip（问题反馈 / PayPal）是透明底 + 刻线框，字色写死在 XML 里，
     * 主题重映射管不到（重映射只动「没有自己底色」的文字）—— 切到钛银后
     * 浅字压在浅钛面板上等于隐形。这里按面板底重新上色。
     */
    private fun paintNameplateChips() {
        val theme = com.filmcam.app.ui.BodyTheme
        val density = resources.displayMetrics.density
        listOf(R.id.tvFeedback, R.id.tvPaypal).forEach { id ->
            findViewById<TextView>(id)?.apply {
                setTextColor(theme.panelText)
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    cornerRadius = 2f * density
                    setStroke(1, theme.cardStroke)
                }
                compoundDrawableTintList =
                    android.content.res.ColorStateList.valueOf(theme.panelText)
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
        val pad = (10 * density).toInt()
        val entries = resources.getStringArray(R.array.oss_credits)

        // 卡片外框与底色由布局给（铭牌式细边框），这里只负责往里填条目

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
                setPadding(0, pad, 0, pad)
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
            cornerRadius = 2f * density
            setStroke(
                (1f * density).toInt().coerceAtLeast(1),
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

    /**
     * 工程师控制台：慢门档位、输出风格、冲洗参数，加上原来的调试项（跳过冲洗 / 模拟失焦 /
     * 慢门触发机制 / 冲洗耗时诊断）。
     *
     * 全部即时生效：这里调的是"看一眼效果"的旋钮，再让用户点一次「保存」只会让人怀疑
     * 刚才那下到底算不算数。顶部那个开关才是唯一的持久化语义 —— 关掉就重新藏起来。
     */
    private fun showEngineerConsole() {
        val prefs = getSharedPreferences("filmcam", MODE_PRIVATE)
        val view = layoutInflater.inflate(R.layout.dialog_hidden_settings, null)
        com.filmcam.app.ui.BodyTheme.applyToViewTree(view)

        val theme = com.filmcam.app.ui.BodyTheme
        val swEngineer = view.findViewById<android.widget.Switch>(R.id.swEngineerMode)
        val swSkip = view.findViewById<android.widget.Switch>(R.id.swSkipDevelop)
        val sbProb = view.findViewById<SeekBar>(R.id.sbSoftFocusProb)
        val tvProb = view.findViewById<TextView>(R.id.tvSoftFocusProb)

        val rowSlow = view.findViewById<View>(R.id.rowEngSlow)
        val rowStyle = view.findViewById<View>(R.id.rowEngStyle)
        val rowDev = view.findViewById<View>(R.id.rowEngDev)
        val rowLens = view.findViewById<View>(R.id.rowEngLens)

        /** 绑定一行「名称 + 说明」，返回右侧读数 TextView */
        fun bindRow(row: View, labelRes: Int, hintRes: Int): TextView {
            row.findViewById<TextView>(R.id.tvSetLabel).text = getString(labelRes)
            row.findViewById<TextView>(R.id.tvSetHint).text = getString(hintRes)
            row.findViewById<android.widget.ImageView>(R.id.ivSetChevron)
                .setColorFilter(theme.onBodyTextSecondary)
            return row.findViewById(R.id.tvSetValue)
        }

        val tvSlow = bindRow(rowSlow, R.string.set_slow, R.string.eng_slow_hint)
        val tvStyle = bindRow(rowStyle, R.string.set_style, R.string.eng_style_hint)
        bindRow(rowDev, R.string.set_dev, R.string.eng_dev_hint)
        val tvLens = bindRow(rowLens, R.string.eng_lens_blur, R.string.eng_lens_hint)

        fun slowMode(): com.filmcam.app.camera.FilmExposure.Mode =
            com.filmcam.app.camera.FilmExposure.Mode.fromPrefs(prefs.getInt("slowShutter", 0))

        fun saveSlow(mode: com.filmcam.app.camera.FilmExposure.Mode) {
            prefs.edit().putInt("slowShutter", mode.prefs).apply()
            tvSlow.text = when (mode) {
                com.filmcam.app.camera.FilmExposure.Mode.NATURAL -> getString(R.string.slow_natural)
                com.filmcam.app.camera.FilmExposure.Mode.SLOW -> getString(R.string.slow_frequent)
                else -> getString(R.string.slow_off)
            }
        }

        fun saveStyleName() {
            tvStyle.text = when (app.spektralab.engine.FilmStockMapper.readOutputStyle(this)) {
                app.spektralab.engine.FilmStockMapper.OutputStyle.FRONTIER -> getString(R.string.style_frontier)
                app.spektralab.engine.FilmStockMapper.OutputStyle.NORITSU -> getString(R.string.style_noritsu)
                else -> getString(R.string.style_print)
            }
        }
        saveSlow(slowMode())
        saveStyleName()
        // 镜头柔度：机身属性，全局一个值（不按胶卷存）
        tvLens.text = "${prefs.getFloat(
            "filmcam_dev_lens_blur", app.spektralab.engine.FilmStockMapper.LENS_BLUR_UM
        ).roundToInt()}μm"

        // 当前装在机身上的是哪支胶卷：冲洗参数是按胶卷分开存的，默认就该停在这支
        val rollIdx = prefs.getInt("rollIndex", 0)
        val currentRoll = com.filmcam.app.filter.FilmRollRepository.rolls
            .getOrNull(rollIdx)
            ?: com.filmcam.app.filter.FilmRollRepository.rolls.firstOrNull()
        val currentStockId = currentRoll?.let {
            runCatching { app.spektralab.engine.FilmStockMapper.filmStockIdFor(it.name) }.getOrNull()
        }

        // 慢门：档位与触发机制是一体的，收进同一个子面板
        // （触发机制就是「多大概率算慢门」，放在慢门外面等于把一件事拆成两处调）
        rowSlow.setOnClickListener {
            consoleRef?.dismiss()
            showSlowShutterDialog { showEngineerConsole() }
        }
        // 输出风格：印相 → Frontier → Noritsu，只影响下一次冲洗
        rowStyle.setOnClickListener {
            app.spektralab.engine.FilmStockMapper.cycleOutputStyle(this)
            saveStyleName()
        }
        // 冲洗参数：另开一层（颗粒/辉光/眩光/印相曝光/相纸），关掉后回到本控制台
        rowDev.setOnClickListener {
            consoleRef?.dismiss()
            com.filmcam.app.develop.DevParamsDialog.show(
                this, currentStockId, onClose = { showEngineerConsole() }
            )
        }
        // 镜头柔度：机身属性，单独一层（不随胶卷换，所以不进按卷分组的冲洗参数）
        rowLens.setOnClickListener {
            consoleRef?.dismiss()
            showLensBlurDialog { showEngineerConsole() }
        }

        // 工程师模式开关：关掉 = 这些旋钮重新藏起来，入口回到"连点 10 次"
        swEngineer.isChecked = EngineerMode.isOn(this)
        swEngineer.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                EngineerMode.setOn(this, false)
                Toast.makeText(this, R.string.eng_mode_off_toast, Toast.LENGTH_LONG).show()
                consoleRef?.dismiss()
            }
        }

        swSkip.isChecked = prefs.getBoolean("debug_instant_develop", false)
        swSkip.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("debug_instant_develop", checked).apply()
        }

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
                if (fromUser) {
                    prefs.edit()
                        .putInt("softFocus_prob", value)
                        .putBoolean("softFocus", value > 0)
                        .apply()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 冲洗耗时诊断：换机器后第一件事是看看时间花在哪一段，点一下复制走
        val tvDiag = view.findViewById<TextView>(R.id.tvDiag)
        val diagText = com.filmcam.app.develop.DevDiagnostics.summary(this)
        if (diagText.isBlank()) {
            tvDiag.text = getString(R.string.hidden_diag_none)
        } else {
            tvDiag.text = diagText
            tvDiag.setOnClickListener {
                (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("dev-diag", diagText))
                Toast.makeText(this, R.string.hidden_diag_copied, Toast.LENGTH_SHORT).show()
            }
        }

        // 崩溃日志：闪退之后唯一的现场（logcat 经常抓不到），点开可以复制或清空
        val tvCrash = view.findViewById<TextView>(R.id.tvCrash)
        val crashText = CrashLog.text(this)
        if (crashText.isBlank()) {
            tvCrash.text = getString(R.string.hidden_crash_none)
        } else {
            tvCrash.text = crashText.takeLast(600)
            tvCrash.setOnClickListener { showCrashLog(crashText) }
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, com.filmcam.app.ui.BodyTheme.dialogTheme)
            .setTitle(R.string.hidden_title)
            .setView(view)
            .setPositiveButton(R.string.set_close) { d, _ -> d.dismiss() }
            .create()
        consoleRef = dialog
        dialog.show()
    }

    /**
     * 慢门子面板：档位 + 触发机制。
     *
     * 这两件事本来是一体的 ——「档位」决定要不要慢，「触发机制」决定按多大概率算慢门。
     * 之前把触发机制平铺在控制台上，等于把同一个功能的两个旋钮放到了两处。
     * 档位行点一下就地翻档（关 → 自然 → 偏慢），与旧版控制台的上手习惯一致。
     */
    private fun showSlowShutterDialog(onClose: () -> Unit) {
        val prefs = getSharedPreferences("filmcam", MODE_PRIVATE)
        val view = layoutInflater.inflate(R.layout.dialog_slow_shutter, null)
        com.filmcam.app.ui.BodyTheme.applyToViewTree(view)
        val rowMode = view.findViewById<View>(R.id.rowSlowMode)
        val tvMode = view.findViewById<TextView>(R.id.tvSlowModeValue)
        val rg = view.findViewById<android.widget.RadioGroup>(R.id.rgSlowTrigger)

        fun currentMode(): com.filmcam.app.camera.FilmExposure.Mode =
            com.filmcam.app.camera.FilmExposure.Mode.fromPrefs(prefs.getInt("slowShutter", 0))
        fun saveMode(m: com.filmcam.app.camera.FilmExposure.Mode) {
            prefs.edit().putInt("slowShutter", m.prefs).apply()
            tvMode.setText(
                when (m) {
                    com.filmcam.app.camera.FilmExposure.Mode.NATURAL -> R.string.slow_natural
                    com.filmcam.app.camera.FilmExposure.Mode.SLOW -> R.string.slow_frequent
                    else -> R.string.slow_off
                }
            )
        }
        saveMode(currentMode())
        rowMode.setOnClickListener {
            saveMode(
                when (currentMode()) {
                    com.filmcam.app.camera.FilmExposure.Mode.OFF ->
                        com.filmcam.app.camera.FilmExposure.Mode.NATURAL
                    com.filmcam.app.camera.FilmExposure.Mode.NATURAL ->
                        com.filmcam.app.camera.FilmExposure.Mode.SLOW
                    com.filmcam.app.camera.FilmExposure.Mode.SLOW ->
                        com.filmcam.app.camera.FilmExposure.Mode.OFF
                }
            )
        }

        // 触发机制（默认纯概率派）；先 check 再挂监听，避免初始化那一下被当成用户操作
        rg.check(
            when (com.filmcam.app.camera.FilmExposure.Trigger.fromPrefs(
                prefs.getInt(
                    "slowTrigger",
                    com.filmcam.app.camera.FilmExposure.Trigger.PROBABILITY.prefs
                )
            )) {
                com.filmcam.app.camera.FilmExposure.Trigger.PROBABILITY -> R.id.rbSlowProb
                com.filmcam.app.camera.FilmExposure.Trigger.REAL -> R.id.rbSlowReal
                com.filmcam.app.camera.FilmExposure.Trigger.REAL_MIX -> R.id.rbSlowMix
            }
        )
        rg.setOnCheckedChangeListener { _, checkedId ->
            val trigger = when (checkedId) {
                R.id.rbSlowReal -> com.filmcam.app.camera.FilmExposure.Trigger.REAL
                R.id.rbSlowMix -> com.filmcam.app.camera.FilmExposure.Trigger.REAL_MIX
                else -> com.filmcam.app.camera.FilmExposure.Trigger.PROBABILITY
            }
            prefs.edit().putInt("slowTrigger", trigger.prefs).apply()
        }

        androidx.appcompat.app.AlertDialog.Builder(this, com.filmcam.app.ui.BodyTheme.dialogTheme)
            .setTitle(R.string.set_slow)
            .setView(view)
            .setPositiveButton(R.string.set_close, null)
            .create()
            .apply { setOnDismissListener { onClose() } }
            .show()
    }

    /**
     * 镜头柔度子面板（0–35μm）。
     *
     * 机身属性：全局一个值（键 filmcam_dev_lens_blur），换胶卷、恢复冲洗参数都不影响它，
     * 所以不能放在按胶卷分组的「冲洗参数」里。下一次冲洗生效。
     */
    private fun showLensBlurDialog(onClose: () -> Unit) {
        val prefs = getSharedPreferences("filmcam", MODE_PRIVATE)
        val view = layoutInflater.inflate(R.layout.dialog_lens_blur, null)
        com.filmcam.app.ui.BodyTheme.applyToViewTree(view)
        val sb = view.findViewById<SeekBar>(R.id.sbLensBlur)
        val tv = view.findViewById<TextView>(R.id.tvLensValue)

        val mapper = app.spektralab.engine.FilmStockMapper
        val current = prefs.getFloat("filmcam_dev_lens_blur", mapper.LENS_BLUR_UM)
            .coerceIn(0f, mapper.LENS_BLUR_MAX_UM)
        sb.progress = current.roundToInt()
        tv.text = "${sb.progress}μm"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    prefs.edit().putFloat("filmcam_dev_lens_blur", progress.toFloat()).apply()
                }
                tv.text = "${progress}μm"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        androidx.appcompat.app.AlertDialog.Builder(this, com.filmcam.app.ui.BodyTheme.dialogTheme)
            .setTitle(R.string.eng_lens_blur)
            .setView(view)
            .setPositiveButton(R.string.set_close, null)
            .create()
            .apply { setOnDismissListener { onClose() } }
            .show()
    }

    /**
     * 崩溃日志全文（正文 + 复制 / 清空）。
     *
     * 只放最近一段：日志本身限长 64KB，但一屏塞不下几万字，取末尾才有意义
     * （最后一次崩溃在最后面）。
     */
    private fun showCrashLog(text: String) {
        val body = text.takeLast(20_000)
        androidx.appcompat.app.AlertDialog.Builder(this, com.filmcam.app.ui.BodyTheme.dialogTheme)
            .setTitle(R.string.hidden_crash_title)
            .setMessage(body)
            .setPositiveButton(R.string.hidden_crash_copy) { _, _ ->
                (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("crash-log", body))
                Toast.makeText(this, R.string.hidden_diag_copied, Toast.LENGTH_SHORT).show()
                showEngineerConsole()
            }
            .setNegativeButton(R.string.hidden_crash_clear) { _, _ ->
                CrashLog.clear(this)
                Toast.makeText(this, R.string.hidden_crash_cleared, Toast.LENGTH_SHORT).show()
                showEngineerConsole()
            }
            .setNeutralButton(R.string.set_close, null)
            .show()
    }

    /** 当前开着的工程师控制台（"冲洗参数"要临时关掉它再回来） */
    private var consoleRef: androidx.appcompat.app.AlertDialog? = null

}
