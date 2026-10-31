package com.filmcam.app.develop

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 *  - 摇手机跳过仪式（SensorManager 加速度计）
 *  - 退出按钮"后台冲洗"→ finish()，服务继续
 *  - 完成态显示"冲洗完成"+"查看相册"
 */
class DevelopmentActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val TAG = "DevelopmentActivity"
        private const val REQ_POST_NOTIF = 1001
        private const val STAY_MS_PER_FRAME = 2000L   // 默认每帧仪式停留 ≥2s
        private const val SHAKE_THRESHOLD = 18f         // m/s^2 模值超过即判定 shake
        private const val STRIP_CELLS = 36
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvStockName: TextView
    private lateinit var tvCount: TextView
    private lateinit var ivFrame: ImageView
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
    private var blurAnimator: ValueAnimator? = null

    private var shakeMode: Boolean = false

    private var sensorManager: SensorManager? = null
    private var lastShakeAtMs: Long = 0L

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
            // SENSOR_DELAY_NORMAL（~200ms）足够检测摇手机，冲洗约 5 分钟可减少 ~12× 主线程回调
            sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
        }
        renderState()
    }

    override fun onStop() {
        sensorManager?.unregisterListener(this)
        DevelopmentState.removeListener(stateListener)
        blurAnimator?.cancel()
        blurAnimator = null
        super.onStop()
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

        // 进行中：推进照片显示
        val frameToShow = if (cur > 0) cur else 1
        if (frameToShow != displayedIndex && frameToShow <= (info?.shotCount ?: 0)) {
            displayedIndex = frameToShow
            showFrameTransition(frameToShow, info)
        }
    }

    /**
     * 每帧开始：原帧先以低分辨率小图放大显示（模糊感），
     * 随后 ValueAnimator 在 STAY_MS 内把 alpha 从 0.2 渐变到 1.0。
     * shakeMode 下停留压缩为 0，立即切到清晰态跟随真实进度。
     */
    private fun showFrameTransition(index: Int, info: RollInfo?) {
        blurAnimator?.cancel()
        blurAnimator = null
        if (info == null) return

        // 解码小图当模糊底
        val blurBmp = decodeSmallFrame(info.rollId, index, 80)
        if (blurBmp != null) {
            ivFrame.setImageBitmap(blurBmp)
            ivFrame.scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val duration = if (shakeMode) 0L else STAY_MS_PER_FRAME
        if (duration <= 0L) {
            ivFrame.alpha = 1f
            return
        }

        val anim = ValueAnimator.ofFloat(0.2f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                ivFrame.alpha = va.animatedValue as Float
            }
        }
        blurAnimator = anim
        anim.start()
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

    // ---------- 摇手机 ----------

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val mag = sqrt(x * x + y * y + z * z)
        // 减去重力模值 ~9.8，取瞬时冲击
        val delta = abs(mag - 9.8f)
        val now = System.currentTimeMillis()
        if (delta > SHAKE_THRESHOLD && now - lastShakeAtMs > 600L) {
            lastShakeAtMs = now
            if (!shakeMode) {
                shakeMode = true
                tvShakeHint.visibility = View.VISIBLE
                // 立即把当前帧拉到清晰态
                ivFrame.alpha = 1f
                blurAnimator?.cancel()
                blurAnimator = null
                Log.d(TAG, "shakeMode 开启，跳过仪式停留")
            }
        }
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
