package com.filmcam.app.develop

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.filmcam.app.R
import com.filmcam.app.film.MotherNegativeStore
import com.filmcam.app.film.ShotMeta
import com.filmcam.app.film.UndevelopedFilmStore
import com.filmcam.app.ui.Haptics

/**
 * v2 暗房编辑页（仿豆包 UI 稿）：选相框（无/片边/白边）、日期开关、应用到范围，
 * 实时预览，保存到相册才渲染入册。参数记在 EditsStore，不落盘到像素。
 *
 * 预览图是母片的纯色彩缩略图（A 方案）；日期开关就地叠加一层示意，
 * 真正的引擎渲染（片边）/白边外扩/EXIF 都在 EditExportService 保存时做。
 */
class EditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROLL_ID = "edit_roll_id"
        const val EXTRA_INDICES = "edit_indices"
        private const val ACTION_EXPORT_DONE = "com.filmcam.app.edit.export_done"
    }

    private lateinit var rollId: String
    private var indices: List<Int> = emptyList()
    private var currentIdx: Int = 1
    private var dirty = false
    private var saving = false
    /** 加载某帧编辑状态时抑制控件监听器，避免 check() 把别的帧误标脏/写回。 */
    private var loadingState = false

    private lateinit var ivPreview: ImageView
    private lateinit var tvCount: TextView
    private lateinit var tvHint: TextView
    private lateinit var rgFrame: RadioGroup
    private lateinit var rgDate: RadioGroup
    /** 日期 pill 当前是否刻印（替代原 Switch.isChecked）。 */
    private var dateOn: Boolean
        get() = ::rgDate.isInitialized && rgDate.checkedRadioButtonId == R.id.rbDateOn
        set(value) { if (::rgDate.isInitialized) rgDate.check(if (value) R.id.rbDateOn else R.id.rbDateOff) }
    private lateinit var btnSave: TextView

    private val exportDoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            saving = false
            btnSave.isEnabled = true
            btnSave.text = getString(R.string.edit_save)
            // 无条件解除进度锁：哪怕导出帧失败，也绝不能让用户卡在"正在导出中"
            val failed = intent?.getIntExtra(EditExportService.EXTRA_FAILED, 0) ?: 0
            if (failed > 0) {
                com.filmcam.app.ui.RelicToast.show(
                    this@EditActivity, getString(R.string.edit_export_failed, failed), long = true
                )
                // 有失败帧：留在编辑页，让用户看到哪张失败并重试/删除
            } else {
                com.filmcam.app.ui.RelicToast.show(
                    this@EditActivity, getString(R.string.edit_saved, indices.size)
                )
                dirty = false
                // 全部保存成功： closing 编辑页回画廊（用户明确反馈"弹窗说已保存但页面还在"）
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        // 与暗房/画廊/关于页同一套边到边：顶栏注入状态栏 padding、底栏注入导航栏 padding。
        // 本页是此前唯一漏掉的 Activity，文案曾被状态栏压住。
        com.filmcam.app.ui.EdgeToEdge.setup(
            this,
            topView = findViewById(R.id.editTopBar),
            bottomView = findViewById(R.id.editBottomBar),
        )
        rollId = intent.getStringExtra(EXTRA_ROLL_ID).orEmpty()
        indices = (intent.getIntArrayExtra(EXTRA_INDICES) ?: intArrayOf()).toList()
        require(rollId.isNotEmpty() && indices.isNotEmpty()) { "编辑页需要 rollId + 帧列表" }
        currentIdx = indices.first()

        ivPreview = findViewById(R.id.ivEditPreview)
        tvCount = findViewById(R.id.tvEditCount)
        tvHint = findViewById(R.id.tvEditHint)
        rgFrame = findViewById(R.id.rgEditFrame)
        rgDate = findViewById(R.id.rgEditDate)
        btnSave = findViewById(R.id.btnEditSave)

        bindControls()
        loadEditState(currentIdx)
        renderPreview()

        // 左右滑动切换卷内各帧：自己算位移，避开 GestureDetector 的 API 签名兼容问题
        var touchX = 0f
        var touchY = 0f
        ivPreview.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchX = event.x; touchY = event.y; true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = event.x - touchX
                    val dy = event.y - touchY
                    if (!saving && kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(dx) > 60f) {
                        switchFrame(if (dx < 0) 1 else -1) // 左滑→下一张，右滑→上一张
                        v.performClick()
                    }
                    true
                }
                else -> true
            }
        }

        findViewById<View>(R.id.btnEditBack).setOnClickListener { onBackPressed() }
        btnSave.setOnClickListener { onSave() }

        val filter = IntentFilter(ACTION_EXPORT_DONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(exportDoneReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(exportDoneReceiver, filter)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(exportDoneReceiver) }
        super.onDestroy()
    }

    /** 左右滑切换帧：切前先把当前状态确保落盘（监听器已存，这里兜底），再加载目标帧。 */
    private fun switchFrame(delta: Int) {
        val pos = indices.indexOf(currentIdx)
        val next = pos + delta
        if (next < 0 || next >= indices.size) {
            Haptics.vibrate(this, 10, 90) // 到头轻震
            return
        }
        val edit = EditsCodec.FrameEdit(currentFrame(), dateOn)
        EditsStore.setFrame(rollId, currentIdx, edit)
        currentIdx = indices[next]
        Haptics.vibrate(this, 12)
        loadEditState(currentIdx)
        renderPreview()
    }

    private fun bindControls() {
        rgFrame.setOnCheckedChangeListener { _, checkedId ->
            if (loadingState) return@setOnCheckedChangeListener
            val frame = when (checkedId) {
                R.id.rbFrameFilm -> EditsCodec.FRAME_FILM
                R.id.rbFrameNone -> EditsCodec.FRAME_NONE
                else -> EditsCodec.FRAME_WHITE
            }
            tvHint.text = getString(when (frame) {
                EditsCodec.FRAME_FILM -> R.string.edit_hint_film
                EditsCodec.FRAME_NONE -> R.string.edit_hint_none
                else -> R.string.edit_hint_white
            })
            markDirtyAndPersist(frame)
            renderPreview()
        }
        rgDate.setOnCheckedChangeListener { _, _ ->
            if (loadingState) return@setOnCheckedChangeListener
            val frame = currentFrame()
            markDirtyAndPersist(frame)
            renderPreview()
        }
    }

    private fun loadEditState(index: Int) {
        loadingState = true
        try {
            val edit = EditsStore.load(rollId).frames[index]
            rgFrame.check(when (edit?.frame ?: EditsCodec.FRAME_WHITE) {
                EditsCodec.FRAME_FILM -> R.id.rbFrameFilm
                EditsCodec.FRAME_NONE -> R.id.rbFrameNone
                else -> R.id.rbFrameWhite
            })
            dateOn = edit?.dateOn ?: false
        } finally {
            loadingState = false
        }
        tvCount.text = "${indices.indexOf(index) + 1} / ${indices.size}"
        findViewById<View>(R.id.tvEditSwipe).visibility =
            if (indices.size > 1) View.VISIBLE else View.GONE
        tvHint.text = getString(when (currentFrame()) {
            EditsCodec.FRAME_FILM -> R.string.edit_hint_film
            EditsCodec.FRAME_NONE -> R.string.edit_hint_none
            else -> R.string.edit_hint_white
        })
    }

    private fun currentFrame(): Int = when (rgFrame.checkedRadioButtonId) {
        R.id.rbFrameFilm -> EditsCodec.FRAME_FILM
        R.id.rbFrameNone -> EditsCodec.FRAME_NONE
        else -> EditsCodec.FRAME_WHITE
    }

    private fun markDirtyAndPersist(frame: Int) {
        dirty = true
        Haptics.vibrate(this, 12)
        val edit = EditsCodec.FrameEdit(frame, dateOn)
        // v2 定稿：编辑整卷全量生效（去掉"仅此张"），多帧同款，导出必全量 → 状态一致
        EditsStore.applyTo(rollId, indices, edit)
    }

    /**
     * 预览：母片纯色彩 → 先在**纯画面**上画日期（与 EditExportService 同 contentRect 基准，
     * 不再画在加 padding 的整图上）→ 再按档位外扩。
     *  - 无：直接纯画面
     *  - 白边：复用 WhiteBorderRenderer.apply（与成片像素级一致）
     *  - 片边：本地 Canvas 画一圈齿孔示意（引擎片框要重渲，这里只做可感知的档位反馈；
     *    保存时仍走引擎 overscan_*，以真片框为准）
     */
    private fun renderPreview() {
        // 🔴先把在途的引擎渲染作废，再决定这次怎么出图。
        // 之前只在「日期开启」分支里 ++gen，于是**关掉日期**时这条路径不递增，
        // 之前那次带日期的渲染晚到后 gen 仍然相等 → 把带日期的图盖回界面，
        // 用户看到的就是「点日期开关没预览 / 开关像失灵」。
        val gen = ++previewGen
        val frame = currentFrame()
        val dateOn = dateOn

        // 日期由**引擎**在渲染时印（`date_imprint_*`）——不能在 Bitmap 上手绘：
        // 手绘融不进镜头柔度/颗粒/印相曲线，而且预览会和成片对不上。
        // 代价是开日期（或带日期切档）要重渲一次（RAW 约 2~4s），
        // 期间先显示不带日期的原图，避免白屏。
        if (dateOn) {
            renderPreviewWithEngineDate(frame, gen)
            return
        }

        // 片边档：优先显示冲洗时预渲染的真实带框成片（引擎齿孔/片基，所见即所得）。
        // framed.jpg 是全分辨率，用 inSampleSize 缩到预览尺寸附近再加载，避免 OOM。
        val framedFile = MotherNegativeStore.framedFile(rollId, currentIdx)
        if (frame == EditsCodec.FRAME_FILM && framedFile.exists()) {
            val gate = runCatching {
                ShotMeta.fromJson(MotherNegativeStore.mother(rollId, currentIdx)?.shotJson)?.gate
            }.getOrNull()
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(framedFile.absolutePath, opts)
            var sample = 1
            val target = 1400
            while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= target) sample *= 2
            val decode = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = true; inSampleSize = sample
            }
            val framed = BitmapFactory.decodeFile(framedFile.absolutePath, decode)
            if (framed != null) {
                // 日期不在这里画：已改由引擎印（`date_imprint_*`），
                // 日期开启时 renderPreview() 早就转到 renderPreviewWithEngineDate() 了。
                ivPreview.setImageBitmap(framed)
                return
            }
        }

        // 其余档位（白边/无框）或老卷无预渲染图：走母片预览 + 本地叠加
        val f = MotherNegativeStore.previewFile(rollId, currentIdx)
        if (!f.exists()) {
            ivPreview.setImageResource(R.drawable.ic_develop)
            return
        }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val src = BitmapFactory.decodeFile(f.absolutePath, opts)
            ?: run { ivPreview.setImageResource(R.drawable.ic_develop); return }

        // 日期不在这里画：已改由引擎印（`date_imprint_*`），见 renderPreviewWithEngineDate()。

        // 按档位外扩
        val out = when (frame) {
            EditsCodec.FRAME_WHITE -> WhiteBorderRenderer.apply(src)
            EditsCodec.FRAME_FILM -> drawFilmEdgePreview(src)  // 老卷无 framed.jpg 的回落
            else -> src
        }
        if (out !== src) src.recycle()
        ivPreview.setImageBitmap(out)
    }

    /** 预览渲染代次：切帧/切档会 +1，回来晚的旧结果直接丢掉 */
    @Volatile private var previewGen = 0

    /**
     * 日期开启时的预览：用引擎重渲一张（与导出同源），白边档再本地补白边。
     * 先立刻显示一张不带日期的底图，避免用户以为卡住。
     */
    private fun renderPreviewWithEngineDate(frame: Int, gen: Int) {
        val idx = currentIdx
        // 先给一张"无日期"的底图垫着（framed/preview 都是现成的）
        showPlainPreview(frame)
        // 引擎重渲一帧要 1~4s，中间必须有明确反馈 —— 否则用户只看到「点了没反应」。
        tvHint.text = getString(R.string.edit_date_rendering)

        val takenAt = runCatching { UndevelopedFilmStore.frameTakenAt(rollId, idx) }
            .getOrDefault(System.currentTimeMillis())
        val imprint = runCatching {
            app.spektralab.engine.DateImprint.t3Style(
                app.spektralab.engine.DateImprint.textForMillis(takenAt),
                portrait = EditRenderCore.isPortrait(rollId, idx),
            )
        }.getOrNull() ?: run { refreshHintText(); return }

        Thread {
            var bmp: android.graphics.Bitmap? = null
            try {
                bmp = EditRenderCore.renderPreview(this, rollId, idx, frame, imprint)
                if (bmp != null && frame == EditsCodec.FRAME_WHITE) {
                    val bordered = WhiteBorderRenderer.apply(bmp)
                    if (bordered !== bmp) bmp.recycle()
                    bmp = bordered
                }
            } catch (e: Throwable) {
                android.util.Log.w("EditActivity", "引擎预览失败: ${e.message}")
            }
            val result = bmp
            runOnUiThread {
                if (gen != previewGen) {          // 已经切走了，丢掉
                    result?.recycle()
                    return@runOnUiThread
                }
                refreshHintText()                 // 渲染结束，把「正在冲洗」换回档位说明
                if (result != null && !isFinishing) ivPreview.setImageBitmap(result)
            }
        }.apply { isDaemon = true }.start()
    }

    /** 底部说明行按当前档位重置（渲染中会临时改成「正在冲洗」，结束后必须还原）。 */
    private fun refreshHintText() {
        tvHint.text = getString(when (currentFrame()) {
            EditsCodec.FRAME_FILM -> R.string.edit_hint_film
            EditsCodec.FRAME_NONE -> R.string.edit_hint_none
            else -> R.string.edit_hint_white
        })
    }

    /** 不带日期的快速预览（framed.jpg 优先，其次 preview.jpg + 本地白边），仅用于垫图。 */
    private fun showPlainPreview(frame: Int) {
        runCatching {
            val framedFile = MotherNegativeStore.framedFile(rollId, currentIdx)
            if (frame == EditsCodec.FRAME_FILM && framedFile.exists()) {
                val o = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeFile(framedFile.absolutePath, o)
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1400) s *= 2
                val b = android.graphics.BitmapFactory.decodeFile(
                    framedFile.absolutePath,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = s },
                )
                if (b != null) { ivPreview.setImageBitmap(b); return@runCatching }
            }
            val f = MotherNegativeStore.previewFile(rollId, currentIdx)
            if (!f.exists()) return@runCatching
            val src = android.graphics.BitmapFactory.decodeFile(
                f.absolutePath,
                android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                },
            ) ?: return@runCatching
            val out = if (frame == EditsCodec.FRAME_WHITE) {
                WhiteBorderRenderer.apply(src).also { if (it !== src) src.recycle() }
            } else src
            ivPreview.setImageBitmap(out)
        }
    }

    /** 片边档预览：深片基底 + 上下齿孔条 + 画幅内的母片（示意，非引擎真片框）。 */
    private fun drawFilmEdgePreview(src: Bitmap): Bitmap {
        val band = (src.width * 0.055f).toInt().coerceAtLeast(6)
        val out = Bitmap.createBitmap(
            src.width + band * 2, src.height + band * 2, Bitmap.Config.ARGB_8888
        )
        val c = android.graphics.Canvas(out)
        c.drawColor(0xFF12100E.toInt())
        // 上下齿孔条：米白小方块等距排列（与豆包稿 repeating-linear-gradient 同观感）
        val holePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE4DFD4.toInt()
        }
        val holeW = (src.width * 0.032f).toInt().coerceAtLeast(4)
        val holeH = (band * 0.5f).toInt().coerceAtLeast(2)
        var x = band.toFloat()
        while (x < out.width - holeW) {
            c.drawRect(x, (band - holeH) / 2f, x + holeW, (band + holeH) / 2f, holePaint)
            c.drawRect(x, out.height - (band + holeH) / 2f, x + holeW, out.height - (band - holeH) / 2f, holePaint)
            x += holeW * 2.4f
        }
        c.drawBitmap(src, band.toFloat(), band.toFloat(), null)
        return out
    }

    private fun onSave() {
        if (saving) return
        Haptics.vibrate(this, 24)
        saving = true
        btnSave.isEnabled = false
        btnSave.text = getString(R.string.edit_saving, 0, indices.size)
        val edit = EditsCodec.FrameEdit(currentFrame(), dateOn)
        // 整卷全量：所有帧已由 markDirtyAndPersist 同步为同款，这里再确保一遍
        indices.forEach { EditsStore.setFrame(rollId, it, edit) }
        EditExportService.start(this, rollId, indices.toIntArray())
        // 服务完成后发广播回来清 saving（见 exportDoneReceiver）
    }

    override fun onBackPressed() {
        if (saving) {
            com.filmcam.app.ui.RelicToast.show(this, getString(R.string.edit_export_wait))
            return
        }
        if (dirty) {
            showDiscardConfirm()
        } else {
            finish()
        }
    }

    /**
     * v15：放弃修改确认弹窗，从系统 AlertDialog 换成与设置/反馈面板同款机身铭牌语言。
     * 宽度口径照抄 AboutActivity.showFeedbackDialog —— create() 后先取 decorView
     * 触发 installDecor，再 setLayout(0.78×屏宽≤400dp)，否则 show() 会重置成 wrap_content。
     */
    private fun showDiscardConfirm() {
        val theme = com.filmcam.app.ui.BodyTheme
        theme.load(this)
        val view = layoutInflater.inflate(R.layout.dialog_edit_confirm, null)
        theme.applyToViewTree(view)
        val logo = view.findViewById<android.widget.ImageView>(R.id.ivEditConfirmLogo)
        logo.setImageResource(if (theme.isTitan) R.drawable.ic_t3_logo_ink else R.drawable.ic_t3_logo)

        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.78f).toInt().coerceAtMost((400 * dm.density).toInt())
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, theme.dialogTheme)
            .setView(view)
            .create()
        val win = dialog.window
        win?.decorView
        win?.setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        win?.setBackgroundDrawable(theme.panelDrawable())
        view.findViewById<android.view.View>(R.id.tvEditKeep).setOnClickListener {
            vibrate(18); dialog.dismiss()
        }
        view.findViewById<android.view.View>(R.id.tvEditDiscard).setOnClickListener {
            vibrate(20); dialog.dismiss(); finish()
        }
        dialog.show()
        dialog.window?.setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun vibrate(ms: Long) {
        try {
            val v = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE)
                    as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                v?.vibrate(android.os.VibrationEffect.createOneShot(ms, 175))
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(ms)
            }
        } catch (_: Exception) {}
    }
}
