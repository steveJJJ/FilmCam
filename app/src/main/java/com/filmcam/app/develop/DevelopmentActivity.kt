package com.filmcam.app.develop

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.spektralab.engine.FilmStockMapper
import com.filmcam.app.GalleryActivity
import com.filmcam.app.R
import com.filmcam.app.film.RollInfo
import com.filmcam.app.film.UndevelopedFilmStore
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 暗房显影活动（规格 §6.2）。
 *
 *  - 红光安全灯氛围（布局背景）
 *  - 照片模糊→清晰（预缩小小图放大模拟模糊 + ValueAnimator 线性变清晰）
 *  - 液体波动（LiquidWaveView）
 *  - 胶卷条 36 格，当前帧高亮 + 自动滚动
 *  - 摇手机 = 搅动显影液：水纹变高变快，显影进度同步加速（见 tickDevelop）
 *  - 退出按钮"后台冲洗"→ finish()，服务继续
 *  - 完成态显示"冲洗完成"+"查看相册"
 */
class DevelopmentActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "DevelopmentActivity"
        private const val REQ_POST_NOTIF = 1001
        private const val SHAKE_MIN = 12f            // 起振阈值：加速度模值偏离重力的量
        private const val SHAKE_FULL = 26f           // 到这个强度算「用力晃」
        private const val DEVELOP_MS = 4200L         // 单帧从乳剂色到完全显影的基准时长
        private const val DWELL_MS = 700L            // 显影完成后停留，给人看清的间隙
        private const val SHARP_FADE_MS = 800L       // 引擎成片淡入时长
        private const val TICK_MS = 66L              // 推进节奏 ≈15fps，足够顺滑
        private const val EMULSION_CLEAR_END = 0.42f // 乳剂层完全退去时的显影进度
        private const val REVEAL_CAP = 0.86f         // 引擎未冲出本帧时进度上限
        /** 未显影乳剂的底色（偏暖琥珀褐，红光安全灯下会再被整体压暗） */
        private const val EMULSION_COLOR = 0xFF7A5A38.toInt()
        private const val STRIP_CELLS = 36
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvStockName: TextView
    private lateinit var tvCount: TextView
    private lateinit var ivFrame: ImageView
    /** 潜影层：底图去饱和 + 提亮的褪色影像，显影推进中短暂可见 */
    private lateinit var ivGhost: ImageView
    /** 成片层：引擎冲洗完成的 JPEG，落盘后淡入 */
    private lateinit var ivSharp: ImageView
    /** 乳剂层：整片胶片底色，显影前完全埋住影像 */
    private lateinit var ivEmulsion: ImageView
    /** 液面波动层：叠在最上面，随搅动加强 */
    private lateinit var liquidWave: LiquidWaveView
    private lateinit var tvProgress: TextView
    private lateinit var layoutDone: View
    private lateinit var tvDoneTitle: TextView
    private lateinit var tvDoneSub: TextView
    /** 双重用途：完成态 → "查看相册"；错误态 → "重试冲洗"。ID 保留兼容 layout XML。 */
    private lateinit var btnViewGallery: Button
    private lateinit var rvFilmStrip: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvShakeHint: TextView
    private lateinit var btnBackground: Button

    private var rollId: String? = null
    private var rollInfo: RollInfo? = null

    /** 仪式进度：当前正在"显影"的帧 index（由 state.done + 当前 currentIndex 推导）。 */
    private var displayedIndex: Int = -1
    private var sensorManager: SensorManager? = null
    private var lastShakeAtMs: Long = 0L

    /** 单帧显影进度 0..1：0 = 一片乳剂色，1 = 完全显影 */
    private var reveal = 0f
    /** 晃动强度 0..1：由摇动速度累加、随时间衰减 */
    private var agitation = 0f
    /** 显影完成后的停留截止时刻；0 = 未开始计时 */
    private var dwellUntilMs = 0L
    /** 上一次推进的时刻，用于按真实间隔推进进度 */
    private var lastTickMs = 0L
    /** 本帧的成片是否已开始淡入（服务把这一帧冲出来了） */
    private var developedLoaded = false
    private var developRunnable: Runnable? = null
    private var sharpAnimator: ValueAnimator? = null
    private val developHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** 显影液水声（循环播放，音量与音调随搅动变化） */
    private var waterPool: android.media.SoundPool? = null
    private var waterSoundId = 0
    private var waterStreamId = 0

    private lateinit var stripAdapter: FilmStripAdapter

    private val stateListener: () -> Unit = { renderState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_development)

        tvTitle = findViewById(R.id.tvTitle)
        tvStockName = findViewById(R.id.tvStockName)
        tvCount = findViewById(R.id.tvCount)
        ivFrame = findViewById(R.id.ivFrame)
        tvProgress = findViewById(R.id.tvProgress)
        layoutDone = findViewById(R.id.layoutDone)
        tvDoneTitle = findViewById(R.id.tvDoneTitle)
        tvDoneSub = findViewById(R.id.tvDoneSub)
        btnViewGallery = findViewById(R.id.btnViewGallery)
        rvFilmStrip = findViewById(R.id.rvFilmStrip)
        progressBar = findViewById(R.id.progressBar)
        tvShakeHint = findViewById(R.id.tvShakeHint)
        btnBackground = findViewById(R.id.btnBackground)
        ivGhost = findViewById(R.id.ivGhost)
        ivSharp = findViewById(R.id.ivSharp)
        ivEmulsion = findViewById(R.id.ivEmulsion)
        liquidWave = findViewById(R.id.liquidWaveView)
        initWaterSound()

        // 1) 读 extra
        rollId = intent?.getStringExtra(DevelopController.EXTRA_ROLL_ID)
        if (rollId == null) {
            finish()
            return
        }

        // 2) 定位卷信息（用于标题/张数）
        rollInfo = UndevelopedFilmStore.undevelopedRolls().firstOrNull { it.rollId == rollId }

        // 3) 若 state 不是这一卷（新冲洗），先起服务；一致则直接挂接
        if (DevelopmentState.rollId != rollId) {
            DevelopController.developRollInBackground(this, rollId!!)
        }

        // 4) 胶卷条
        stripAdapter = FilmStripAdapter(STRIP_CELLS)
        rvFilmStrip.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvFilmStrip.adapter = stripAdapter

        // 5) 按钮
        btnBackground.setOnClickListener { finish() }
        // 按当前状态分发：ERROR → 重试冲洗同一卷；否则 → 进相册
        btnViewGallery.setOnClickListener {
            val currentRollId = rollId ?: return@setOnClickListener
            if (DevelopmentState.phase == DevelopmentState.PHASE_ERROR) {
                // 重试：服务会 reset 状态重新跑整卷
                DevelopController.developRollInBackground(this, currentRollId)
                renderState()
            } else {
                startActivity(Intent(this, GalleryActivity::class.java))
                finish()
            }
        }

        // 6) API 33+ 请求通知权限（拒绝不致命）
        maybeRequestPostNotif()

        // 7) 传感器（摇手机）
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    override fun onStart() {
        super.onStart()
        DevelopmentState.addListener(stateListener)
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { s ->
            // 要反映「摇动速度」就得采样够密：GAME ≈20ms。显影是前台仪式，回调开销可忽略。
            sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        }
        renderState()
        startDevelopLoop()
    }

    override fun onStop() {
        sensorManager?.unregisterListener(this)
        DevelopmentState.removeListener(stateListener)
        stopDevelopLoop()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { waterPool?.release() }
        waterPool = null
        waterSoundId = 0
    }

    // ---------- 状态渲染（主线程） ----------

    private fun renderState() {
        val st = DevelopmentState
        val info = rollInfo ?: UndevelopedFilmStore.undevelopedRolls().firstOrNull { it.rollId == st.rollId }
        rollInfo = info

        // 标题区
        val name = info?.let { FilmStockMapper.stockDisplayName(it.filmStockId) } ?: ""
        tvStockName.text = name
        tvCount.text = if (info != null) "${info.shotCount}/36" else ""

        // 进度
        val total = st.total.coerceAtLeast(0)
        val done = st.done.coerceAtLeast(0)
        val cur = st.currentIndex.coerceAtLeast(0)
        progressBar.max = 100
        progressBar.progress = if (total > 0) (done * 100 / total) else 0
        // 准备阶段（首次要拷 30MB 引擎资源 + 建引擎，约 10s）：给出明确文案，
        // 否则 UI 会停在「显影中 0/36」像卡死。
        tvProgress.text = when {
            st.phase == DevelopmentState.PHASE_PREPARING && st.preparingFirstTime ->
                "首次冲洗，正在准备显影液…"
            st.phase == DevelopmentState.PHASE_PREPARING -> "正在准备…"
            else -> "显影中 $done/$total"
        }

        // 胶卷条
        stripAdapter.shotCount = info?.shotCount ?: total
        stripAdapter.currentCell = if (st.phase == DevelopmentState.PHASE_DONE) total else cur
        stripAdapter.notifyDataSetChanged()
        // 自动滚动到当前帧
        val target = stripAdapter.currentCell - 1
        if (target >= 0) {
            rvFilmStrip.scrollToPosition(target)
        }

        // 完成态
        if (st.phase == DevelopmentState.PHASE_DONE) {
            layoutDone.visibility = View.VISIBLE
            tvDoneTitle.text = "冲洗完成"
            tvDoneSub.text = "共 $done 张"
            btnViewGallery.text = "查看相册"
            tvProgress.text = "显影完成 $done/$total"
            return
        }
        if (st.phase == DevelopmentState.PHASE_ERROR) {
            layoutDone.visibility = View.VISIBLE
            tvDoneTitle.text = "冲洗失败"
            tvDoneSub.text = buildString {
                if (st.errorCount > 0) append("${st.errorCount} 帧未完成：")
                append((DevelopmentState.lastError ?: "未知错误").take(160))
            }
            btnViewGallery.text = "重试冲洗"
            tvProgress.text = "显影中断"
            return
        }

        // 进行中：隐藏完成/错误覆盖层（重试后再次进入 developing 时旧覆盖层不残留）
        layoutDone.visibility = View.GONE

        // 进行中：把正在「显影」的那一帧摆进液盘（进度推进交给 tickDevelop）
        val frameToShow = if (cur > 0) cur else 1
        if (frameToShow != displayedIndex && frameToShow <= (info?.shotCount ?: 0)) {
            beginFrame(frameToShow, info)
        }
    }

    // ---------- 显影仪式：乳剂 → 液体波动 → 影像逐渐浮出 ----------

    /**
     * 开始显影第 index 帧。
     *
     * 层序（自下而上）：
     *   ① ivFrame    底图：本帧原始像素（未冲洗的样子）
     *   ② ivSharp    成片：引擎冲洗完成的 JPEG —— 服务落盘后淡入，盖住 ①
     *   ③ ivGhost    潜影：去饱和 + 提亮的「褪色底片」，显影推进中短暂可见
     *   ④ ivEmulsion 乳剂：一整片胶片底色，显影前把影像完全埋住
     *
     * 随进度推进 ④ 先被冲开、③ 再褪去，影像从一片乳剂里浮出来 —— 和拍立得
     * 在显影液里的次序一致：先暗部、后亮部。
     */
    private fun beginFrame(index: Int, info: RollInfo?) {
        displayedIndex = index
        reveal = 0f
        dwellUntilMs = 0L
        developedLoaded = false
        lastTickMs = System.currentTimeMillis()
        if (info == null) return

        val sharp = decodeSmallFrame(info.rollId, index, 720)
        if (sharp != null) {
            ivFrame.setImageBitmap(sharp)
            ivGhost.setImageBitmap(makeLatentGhost(sharp))
            // 乳剂层与底图同尺寸 → fitCenter 后逐像素对齐，只盖住画面不留黑边
            ivEmulsion.setImageBitmap(emulsionBitmap(sharp.width, sharp.height))
        }
        ivSharp.setImageDrawable(null)
        ivSharp.alpha = 0f
        ivFrame.alpha = 1f
        ivGhost.alpha = 1f
        ivEmulsion.alpha = 1f
        ivGhost.visibility = View.VISIBLE
        ivEmulsion.visibility = View.VISIBLE
        ivSharp.visibility = View.GONE
    }

    /** 把当前显影进度映射到各层 alpha。 */
    private fun applyReveal() {
        // 乳剂层先退（看到轮廓）→ 潜影层后退（由虚变实）
        ivEmulsion.alpha = 1f - smoothstep(0f, EMULSION_CLEAR_END, reveal)
        ivGhost.alpha = 1f - smoothstep(0.30f, 1f, reveal)
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        if (b <= a) return if (x < a) 0f else 1f
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun startDevelopLoop() {
        if (developRunnable != null) return
        lastTickMs = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                tickDevelop()
                if (developRunnable === this) developHandler.postDelayed(this, TICK_MS)
            }
        }
        developRunnable = r
        developHandler.postDelayed(r, TICK_MS)
    }

    private fun stopDevelopLoop() {
        developRunnable?.let { developHandler.removeCallbacks(it) }
        developRunnable = null
        sharpAnimator?.cancel()
        sharpAnimator = null
        stopWaterSound()
    }

    private fun tickDevelop() {
        val st = DevelopmentState
        if (st.phase == DevelopmentState.PHASE_DONE || st.phase == DevelopmentState.PHASE_ERROR) {
            // 完成/失败：把画面停在最终态，液体声收掉
            reveal = 1f
            applyReveal()
            stopWaterSound()
            tvShakeHint.visibility = View.GONE
            developRunnable?.let { developHandler.removeCallbacks(it) }
            developRunnable = null
            return
        }
        // PREPARING（首次要拷引擎资源，约 10s）：液盘还没开始反应，
        // 乳剂层保持盖住，水声不起，等服务进入 DEVELOPING 再推进。

        // 正在显影：循环播放显影液水声（音量按搅动强度在下面推）
        if (st.phase == DevelopmentState.PHASE_DEVELOPING) {
            startWaterSound()
            tvShakeHint.visibility = View.VISIBLE
        } else {
            stopWaterSound()
        }

        val now = System.currentTimeMillis()
        val dt = (now - lastTickMs).coerceIn(0L, 250L).toFloat()
        lastTickMs = now

        // 搅动强度随时间衰减（约半秒落到很低），持续摇会不断补上
        val decay = java.lang.Math.pow(0.92, (dt / TICK_MS.toFloat()).toDouble()).toFloat()
        agitation = (agitation * decay).coerceIn(0f, 1f)

        // 服务比显示领先多少帧：领先多就快放，别让仪式拖太久
        val backlog = st.done - displayedIndex
        val speed = when {
            backlog >= 3 -> 3.4f
            backlog >= 1 -> 1.9f
            else -> 1.0f
        }
        // 搅动加速显影：真实暗房里晃药液就是要加快显影，这里直接体现在进度上
        val gain = 1f + agitation * 2.4f
        // 引擎还没冲出这一帧时进度封顶，避免「还没洗就显影完了」
        val cap = if (st.done >= displayedIndex) 1f else REVEAL_CAP
        reveal = (reveal + dt / DEVELOP_MS * speed * gain).coerceAtMost(cap)
        applyReveal()
        liquidWave.setAgitation(agitation)

        // 水声：搅得越猛越响、音调越高
        if (waterStreamId != 0) {
            val v = (0.16f + agitation * 0.62f).coerceIn(0f, 1f)
            waterPool?.setVolume(waterStreamId, v, v)
            waterPool?.setRate(waterStreamId, (0.9f + agitation * 0.42f).coerceIn(0.5f, 2f))
        }

        // 成片已落盘 → 淡入；停留够了 → 推进下一帧
        if (reveal >= 1f) {
            if (dwellUntilMs == 0L) {
                dwellUntilMs = now + DWELL_MS
                fadeInDevelopedFrame()
            } else if (now >= dwellUntilMs) {
                dwellUntilMs = 0L
                val next = displayedIndex + 1
                if (next <= (rollInfo?.shotCount ?: st.total)) {
                    beginFrame(next, rollInfo)
                }
            }
        }
    }

    /** 引擎把本帧冲出来了：解码成片并淡入盖住未冲洗底图。 */
    private fun fadeInDevelopedFrame() {
        val info = rollInfo ?: return
        val idx = displayedIndex
        developHandler.post {
            val developed = decodeSmallFrame(info.rollId, idx, 720)
            if (developed != null && displayedIndex == idx) {
                ivSharp.setImageBitmap(developed)
                ivSharp.visibility = View.VISIBLE
                sharpAnimator?.cancel()
                sharpAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = SHARP_FADE_MS
                    interpolator = LinearInterpolator()
                    addUpdateListener { va -> ivSharp.alpha = va.animatedValue as Float }
                    start()
                }
            }
        }
    }

    /** 胶片底色：未显影乳剂那种偏暖的琥珀褐。按底图尺寸生成并缓存，尺寸变了才重建。 */
    private var emulsionCache: Bitmap? = null
    private fun emulsionBitmap(w: Int, h: Int): Bitmap {
        emulsionCache?.let { if (it.width == w && it.height == h) return it }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(EMULSION_COLOR)
        emulsionCache = b
        return b
    }

    /**
     * 潜影：把底图做成「褪色底片」——大幅去饱和 + 压对比 + 提亮 + 暖调。
     * 这一层在乳剂层退去后短暂可见，像显影液里刚浮出轮廓的状态。
     */
    private fun makeLatentGhost(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val m = ColorMatrix().apply { setSaturation(0.18f) }
        // 压对比（0.45 斜率 + 抬到中间灰）+ 暖色偏移
        m.postConcat(
            ColorMatrix(
                floatArrayOf(
                    0.45f, 0f, 0f, 0f, 78f,
                    0f, 0.45f, 0f, 0f, 68f,
                    0f, 0f, 0.45f, 0f, 52f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(m) }
        canvas.drawBitmap(src, 0f, 0f, p)
        return out
    }

    /** 缓存的帧尺寸（同卷同相机尺寸一致，只探一次）。null = 未探测或失败。 */
    private var cachedFrameWidth: Int = 0
    private var cachedFrameHeight: Int = 0

    private fun decodeSmallFrame(rollId: String, index: Int, maxEdge: Int): Bitmap? {
        return try {
            // 同卷尺寸一致：有缓存直接用，没有才 inJustDecodeBounds 探一次
            val w: Int
            val h: Int
            if (cachedFrameWidth > 0 && cachedFrameHeight > 0) {
                w = cachedFrameWidth
                h = cachedFrameHeight
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                UndevelopedFilmStore.openFrameStream(rollId, index)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
                w = opts.outWidth
                h = opts.outHeight
                if (w <= 0 || h <= 0) return null
                cachedFrameWidth = w
                cachedFrameHeight = h
            }
            var sample = 1
            while (w / sample > maxEdge || h / sample > maxEdge) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val small = UndevelopedFilmStore.openFrameStream(rollId, index)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
            // 未冲洗帧像素保持传感器方向（拍摄侧直传不旋转），动画里按记录角度摆正
            val rot = UndevelopedFilmStore.frameOrientation(rollId, index)
            if (small != null && rot != 0) {
                val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                android.graphics.Bitmap
                    .createBitmap(small, 0, 0, small.width, small.height, m, false)
                    .also { small.recycle() }
            } else {
                small
            }
        } catch (e: Throwable) {
            Log.w(TAG, "decodeSmallFrame 失败: ${e.message}")
            null
        }
    }

    // ---------- 摇手机 = 搅动显影液 ----------

    /**
     * 摇手机不再是「跳过仪式」，而是真实暗房里的搅动动作：
     * 摇得越猛 → agitation 越高 → 水纹越高越快、显影进度也越快。
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        if (DevelopmentState.phase != DevelopmentState.PHASE_DEVELOPING) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val mag = sqrt(x * x + y * y + z * z)
        // 减去重力模值 ~9.8，取瞬时冲击
        val delta = abs(mag - 9.8f)
        if (delta <= SHAKE_MIN) return
        val impulse = ((delta - SHAKE_MIN) / (SHAKE_FULL - SHAKE_MIN)).coerceIn(0f, 1f)
        agitation = (agitation + impulse * 0.9f).coerceIn(0f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------- 权限 ----------

    private fun maybeRequestPostNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_POST_NOTIF
                )
            }
        }
    }

    // ---------- 显影液水声 ----------

    private fun initWaterSound() {
        runCatching {
            waterPool = android.media.SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            waterSoundId = waterPool?.load(this, R.raw.develop_water, 1) ?: 0
        }
    }

    /** 起播（循环）。音量从 0 起，实际响度由 tickDevelop 按搅动强度推上去。 */
    private fun startWaterSound() {
        if (waterSoundId == 0 || waterStreamId != 0) return
        waterStreamId = waterPool?.play(waterSoundId, 0f, 0f, 1, -1, 1f) ?: 0
    }

    private fun stopWaterSound() {
        if (waterStreamId != 0) {
            runCatching { waterPool?.stop(waterStreamId) }
            waterStreamId = 0
        }
    }

    // ---------- 胶卷条 Adapter ----------

    inner class FilmStripAdapter(private val capacity: Int) :
        RecyclerView.Adapter<FilmStripAdapter.CellVH>() {

        var shotCount: Int = 0
        var currentCell: Int = 0

        inner class CellVH(val cell: View) : RecyclerView.ViewHolder(cell)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CellVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_film_strip, parent, false)
            return CellVH(v)
        }

        override fun onBindViewHolder(holder: CellVH, position: Int) {
            val cellIndex = position + 1
            val isShot = cellIndex <= shotCount
            val isCurrent = cellIndex == currentCell && shotCount > 0
            holder.cell.setBackgroundColor(
                when {
                    isCurrent -> 0xFFFFC100.toInt()
                    isShot -> 0xFF6B1414.toInt()
                    else -> 0xFF2A0808.toInt()
                }
            )
            holder.cell.alpha = if (isShot) 1f else 0.4f
        }

        override fun getItemCount(): Int = capacity
    }
}
