package com.filmcam.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 暗房成片查看器的原图级缩放 ImageView。
 *
 * 设计要点（T3：机械、冷峻、不花哨）：
 *  1. 手势：双击 2x/还原，双指捏合 1x–4x 无级，放大后单指拖动平移；越界硬夹取。
 *  2. 清晰度：1x 显示降采样预览；scale>1 时按当前 matrix 反算可视矩形，
 *     用 [BitmapRegionDecoder.decodeRegion] 从原图分块解真像素，绝不把预览硬放大。
 *     解码在 IO 线程，主线程只负责 setBitmap/setImageMatrix；代次计数作废过期结果。
 *  3. 契约：放大时单击不回调 [onTap]（不动 EXIF 面板），回到 1x 才回调；
 *     1x 横向滑动走 [onSwipePage] 翻页，ViewPager2 自身触摸恒关，避免抢走手势。
 *
 * 坐标系：一切矩阵都在「JPEG 原始像素(raw)」空间里算。EXIF 方向旋转折进 baseMatrix，
 * 这样 region 解出来的原始朝向 bitmap 能被同一张 matrix 正确摆到屏幕上，无需为每块再旋转 bitmap。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    companion object {
        /** 双击放大目标 2f；再双击回 1f。 */
        const val DOUBLE_TAP_SCALE = 2f
        /** 捏合无级缩放上限 4f。 */
        const val MAX_SCALE = 4f
        /** 低于该倍率一律按 1x 处理：单击照旧切面板、ViewPager2 照旧可翻页。 */
        const val NEAR_ONE = 1.01f

        /** 预览图长边基准（按原图宽度 / 1080 解一张，与旧版看片观感一致）。 */
        private const val PREVIEW_LONG_EDGE = 1080
        /** 手势停下后再解码，避免捏合每一帧都触发 decodeRegion。 */
        private const val REGION_DEBOUNCE_MS = 90L

        /**
         * 捏合结束后一小段时间内，下一个 DOWN 不许被 GestureDetector 当作双击
         * （否则「捏完立刻平移」会被判成双击，把 2x 一键还原成 1x）。
         */
        private const val POST_SCALE_TAP_SUPPRESS_MS = 400L

        /**
         * 1x 横向翻页阈值：约 56dp，且 |dx| 需明显大于 |dy|，
         * 防止轻微斜向移动/纵向手势误翻页。
         */
        private const val SWIPE_MIN_DP = 56f
        private const val SWIPE_SLOPE = 1.2f
    }

    private var scaleGestureActive = false
    private var lastScaleEndAt = 0L

    /** 1x 自管翻页：DOWN 记锚点，UP 判横向位移，一次手势最多翻一页。 */
    private var swipeMinDistance = 0f
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var swipeConsumed = false
    private var scaleGestureSeen = false

    /** 1x 单击照片回调（宿主用它切 EXIF 面板）；放大状态不回调。 */
    var onTap: (() -> Unit)? = null
    /** 缩放倍率变化回调（宿主用它同步 ViewPager2 状态）。 */
    var onScaleChanged: ((Float) -> Unit)? = null
    /**
     * 1x 横向滑动翻页：direction=-1 上一张 / +1 下一张。
     * ViewPager2 的 isUserInputEnabled 恒为 false（否则 DOWN 就被它 CANCEL，
     * 双击/捏合/拖动全拿不到），翻页统一由本回调让宿主 setCurrentItem。
     */
    var onSwipePage: ((direction: Int) -> Unit)? = null

    private var lifecycleOwner: LifecycleOwner? = null
    private var pfd: ParcelFileDescriptor? = null
    private var decoder: BitmapRegionDecoder? = null

    private var rawW = 0
    private var rawH = 0
    private var rotation = 0

    /** 显示方向（应用 EXIF 旋转后）的宽高与 fit 基准缩放。 */
    private var displayW = 0
    private var displayH = 0
    private var baseFitScale = 1f

    private var previewSample = 1
    private var previewBitmap: Bitmap? = null
    private var regionBitmap: Bitmap? = null
    private var lastRegionRect: Rect? = null
    private var lastRegionSample = 1
    private var showingRegion = false

    private val baseMatrix = Matrix()
    private val finalMatrix = Matrix()

    private var viewW = 0
    private var viewH = 0

    /** 用户态：相对 fit 的倍率 + 视图像素平移量。 */
    private var scale = 1f
    private var panX = 0f
    private var panY = 0f

    /** 异步解码代次：放大/平移/换图都会 +1，旧结果回来时直接回收，禁止盖回。 */
    private var decodeGeneration = 0
    private var decodeJob: Job? = null
    private val decodeHandler = Handler(Looper.getMainLooper())
    private val decodeRunnable = Runnable { requestRegionDecode() }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var panBegun = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            /** 单击只在 1x 生效：放大时点照片不许切 EXIF 面板。 */
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (scale <= NEAR_ONE) onTap?.invoke()
                return true
            }

            /** 双击 2f / 还原，以触点为锚（焦点处内容不动）。 */
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val sinceEnd = SystemClock.uptimeMillis() - lastScaleEndAt
                val suppressed = scaleGestureActive || sinceEnd < POST_SCALE_TAP_SUPPRESS_MS
                val target = if (scale <= NEAR_ONE) DOUBLE_TAP_SCALE else 1f
                if (suppressed) return true
                zoomTo(target, e.x, e.y)
                Haptics.vibrate(context, 10, 90)   // 机械档位感：与滑杆 tick 同量级
                return true
            }
        }
    ).apply { setIsLongpressEnabled(false) }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaleGestureActive = true
                scaleGestureSeen = true
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (scale * detector.scaleFactor).coerceIn(1f, MAX_SCALE)
                // 捏合以焦点为中心：焦点下的原图像素保持不动
                applyZoom(next, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scaleGestureActive = false
                lastScaleEndAt = SystemClock.uptimeMillis()
                if (scale <= NEAR_ONE) resetZoomInternal() else scheduleRegionDecode()
            }
        }
    )

    init {
        // 必须 MATRIX：否则 ImageView 会按 FIT_CENTER 覆写我们算好的 imageMatrix
        scaleType = android.widget.ImageView.ScaleType.MATRIX
        isClickable = true
        swipeMinDistance = SWIPE_MIN_DP * resources.displayMetrics.density
    }

    /** 当前缩放倍率（1x–4x）。 */
    fun currentScale(): Float = scale

    /** 回到 1x 并居中，回收分块图，恢复预览。 */
    fun resetZoom() = resetZoomInternal()

    private fun resetZoomInternal() {
        cancelRegionDecode()
        scale = 1f
        panX = 0f
        panY = 0f
        showingRegion = false
        lastRegionRect = null
        showPreview()
        onScaleChanged?.invoke(scale)
    }

    /**
     * 载入一张成片：读 EXIF 方向，开 RegionDecoder，先解一张全图预览。
     * 换图会重置缩放（顺带把 ViewPager2 翻页权交还宿主）。
     */
    fun setImageUri(uri: Uri, owner: LifecycleOwner) {
        resetZoomInternal()
        releaseDecoder()
        lifecycleOwner = owner

        val resolver = context.contentResolver
        var opened: ParcelFileDescriptor? = null
        try {
            opened = resolver.openFileDescriptor(uri, "r")
            val fd = opened?.fileDescriptor
            val d = if (fd != null) BitmapRegionDecoder.newInstance(fd, false) else null
            if (d == null) {
                opened?.close()
                return
            }
            decoder = d
            pfd = opened
            rawW = d.width
            rawH = d.height

            // EXIF 方向（成片写的是 NORMAL；外部导入的图可能是 90/180/270）
            rotation = try {
                resolver.openInputStream(uri)?.use { ExifInterface(it) }?.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )?.let { o ->
                    when (o) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                } ?: 0
            } catch (e: Exception) {
                0
            }

            previewSample = max(1, rawW / PREVIEW_LONG_EDGE)
            val opts = BitmapFactory.Options().apply { inSampleSize = previewSample }
            previewBitmap = d.decodeRegion(Rect(0, 0, rawW, rawH), opts)
            rebuildBaseMatrix()
            showPreview()
        } catch (e: Exception) {
            opened?.close()
            pfd = null
            decoder = null
        }
    }

    /** Fragment 销毁视图时调用：彻底释放 decoder / fd / bitmap。 */
    fun release() {
        resetZoomInternal()
        releaseDecoder()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewW = w
        viewH = h
        rebuildBaseMatrix()
        clampPan()
        applyMatrix()
        if (scale > NEAR_ONE) scheduleRegionDecode()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyMatrix()
        if (scale > NEAR_ONE) scheduleRegionDecode()
    }

    override fun onDetachedFromWindow() {
        decodeHandler.removeCallbacks(decodeRunnable)
        cancelRegionDecode()
        // 不在这里 release：ViewPager2 复用 ViewHolder 只是 detach，decoder 要留着
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 关键修复：DOWN 就断掉父容器（ViewPager2/RecyclerView）的拦截权，
        // 否则手势在第一个 MOVE 就被 CANCEL，双击/捏合/拖动都拿不到完整序列。
        // 宿主侧 isUserInputEnabled 恒 false 让 ViewPager2 不拦截，这里再兜底一层。
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                lastX = downX
                lastY = downY
                gestureStartX = downX
                gestureStartY = downY
                panBegun = false
                swipeConsumed = false
                scaleGestureSeen = false
            }
            MotionEvent.ACTION_MOVE -> {
                // 每个 MOVE 都续一次，防止某些父容器在滚动中重新拿到拦截权
                parent?.requestDisallowInterceptTouchEvent(true)
                if (scale > NEAR_ONE && !scaleDetector.isInProgress) {
                    if (!panBegun && abs(event.x - downX) + abs(event.y - downY) > touchSlop) {
                        panBegun = true
                        lastX = event.x
                        lastY = event.y
                    }
                    if (panBegun) {
                        panX += event.x - lastX
                        panY += event.y - lastY
                        lastX = event.x
                        lastY = event.y
                        clampPan()
                        applyMatrix()
                    }
                } else {
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                panBegun = false
                parent?.requestDisallowInterceptTouchEvent(false)

                // 1x 且本次手势不是捏合、也不是单指拖动过的话，UP 时用总位移判横向翻页。
                // 这样双击（位移≈0）、单击（面板）、捏合（scaleGestureSeen）都不会误翻。
                if (event.actionMasked == MotionEvent.ACTION_UP &&
                    scale <= NEAR_ONE && !swipeConsumed && !scaleGestureSeen &&
                    event.pointerCount == 1
                ) {
                    val dx = event.x - gestureStartX
                    val dy = event.y - gestureStartY
                    if (abs(dx) >= swipeMinDistance && abs(dx) > abs(dy) * SWIPE_SLOPE) {
                        // 手指向左划 → 看下一张（+1），向右划 → 上一张（-1）
                        val direction = if (dx < 0) 1 else -1
                        swipeConsumed = true
                        onSwipePage?.invoke(direction)
                    }
                }
                if (scale > NEAR_ONE) scheduleRegionDecode()
            }
        }
        return true
    }

    // ---------------------------------------------------------------- 矩阵

    /** 把 raw 像素映射到 view：EXIF 旋转 + FIT_CENTER 缩放 + 居中。 */
    private fun rebuildBaseMatrix() {
        baseMatrix.reset()
        if (rawW <= 0 || rawH <= 0 || viewW <= 0 || viewH <= 0) return

        when (rotation) {
            // setRotate(k) 后再平移，得到 raw -> display 的旋转映射
            90 -> { baseMatrix.setRotate(90f); baseMatrix.postTranslate(rawH.toFloat(), 0f) }
            180 -> { baseMatrix.setRotate(180f); baseMatrix.postTranslate(rawW.toFloat(), rawH.toFloat()) }
            270 -> { baseMatrix.setRotate(270f); baseMatrix.postTranslate(0f, rawW.toFloat()) }
        }
        val rot90 = rotation == 90 || rotation == 270
        displayW = if (rot90) rawH else rawW
        displayH = if (rot90) rawW else rawH
        baseFitScale = min(viewW.toFloat() / displayW, viewH.toFloat() / displayH)
        baseMatrix.postScale(baseFitScale, baseFitScale)
        baseMatrix.postTranslate(
            (viewW - displayW * baseFitScale) / 2f,
            (viewH - displayH * baseFitScale) / 2f
        )
    }

    /** 用户变换：以 view 中心缩放 s 倍 + 平移 pan，叠加在 baseMatrix 之上。 */
    private fun applyMatrix() {
        finalMatrix.set(baseMatrix)
        finalMatrix.postScale(scale, scale, viewW / 2f, viewH / 2f)
        finalMatrix.postTranslate(panX, panY)

        // 分块图：bitmap 像素 (i,j) 对应 raw(left + i*n, top + j*n)，所以用 preConcat 补回
        val r = lastRegionRect
        if (showingRegion && r != null) {
            val m = Matrix(finalMatrix)
            m.preTranslate(r.left.toFloat(), r.top.toFloat())
            m.preScale(lastRegionSample.toFloat(), lastRegionSample.toFloat())
            setImageMatrix(m)
            return
        }
        // 预览图：bitmap 像素 (i,j) 对应 raw(i*sample, j*sample)
        val m = Matrix(finalMatrix)
        m.preScale(previewSample.toFloat(), previewSample.toFloat())
        setImageMatrix(m)
    }

    /** 越界硬夹取：图像始终盖住视口，不留黑边。 */
    private fun clampPan() {
        if (displayW <= 0 || displayH <= 0) {
            panX = 0f
            panY = 0f
            return
        }
        val imgW = displayW * baseFitScale * scale
        val imgH = displayH * baseFitScale * scale
        val maxX = max(0f, (imgW - viewW) / 2f)
        val maxY = max(0f, (imgH - viewH) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    // ---------------------------------------------------------------- 缩放/平移

    private fun zoomTo(target: Float, focusX: Float, focusY: Float) {
        applyZoom(target, focusX, focusY)
    }

    /**
     * 以 (focusX, focusY) 为锚缩放：锚点下的内容保持原位。
     * pan 的解析式由 v = c + s*(u-c) + pan 推得：pan' = (f-c)*(1-s'/s) + pan*(s'/s)
     */
    private fun applyZoom(next: Float, focusX: Float, focusY: Float) {
        if (next == scale) return
        val cx = viewW / 2f
        val cy = viewH / 2f
        val ratio = next / scale
        panX = (focusX - cx) * (1f - ratio) + panX * ratio
        panY = (focusY - cy) * (1f - ratio) + panY * ratio
        scale = next
        clampPan()
        applyMatrix()
        onScaleChanged?.invoke(scale)

        if (scale <= NEAR_ONE) {
            cancelRegionDecode()
            showPreview()
        } else {
            scheduleRegionDecode()
        }
    }

    // ---------------------------------------------------------------- 原图级分块解码

    /**
     * 由当前 finalMatrix 反算视口四个角对应的 raw 坐标，外扩 15% 预取，夹到原图边界。
     * 这就是「放大时按需加载可视区域」的核心：解的是原图像素，不是缩放后的预览。
     */
    private fun visibleRawRect(): Rect? {
        if (rawW <= 0 || rawH <= 0 || viewW <= 0 || viewH <= 0) return null
        val inv = Matrix()
        if (!finalMatrix.invert(inv)) return null
        val pts = floatArrayOf(
            0f, 0f,
            viewW.toFloat(), 0f,
            0f, viewH.toFloat(),
            viewW.toFloat(), viewH.toFloat()
        )
        inv.mapPoints(pts)
        val xs = listOf(pts[0], pts[2], pts[4], pts[6])
        val ys = listOf(pts[1], pts[3], pts[5], pts[7])
        val l = xs.minOrNull() ?: return null
        val r = xs.maxOrNull() ?: return null
        val t = ys.minOrNull() ?: return null
        val b = ys.maxOrNull() ?: return null
        val mx = (r - l) * 0.15f
        val my = (b - t) * 0.15f
        val left = max(0f, l - mx)
        val right = min(rawW.toFloat(), r + mx)
        val top = max(0f, t - my)
        val bottom = min(rawH.toFloat(), b + my)
        if (right - left < 1f || bottom - top < 1f) return null
        return Rect(
            floor(left).toInt(), floor(top).toInt(),
            ceil(right).toInt().coerceAtMost(rawW),
            ceil(bottom).toInt().coerceAtMost(rawH)
        )
    }

    /** 单次解码位图长边上限：约 1.5 倍屏幕长边，夹在 [960, 2880]，避免 OOM。 */
    private fun regionCap(): Int {
        val longest = max(viewW, viewH)
        return (longest * 1.5f).roundToInt().coerceIn(960, 2880)
    }

    private fun rectClose(a: Rect, b: Rect): Boolean {
        val tol = max(24, a.width() / 10)
        return abs(a.left - b.left) <= tol && abs(a.top - b.top) <= tol &&
            abs(a.right - b.right) <= tol && abs(a.bottom - b.bottom) <= tol
    }

    private fun scheduleRegionDecode() {
        decodeHandler.removeCallbacks(decodeRunnable)
        decodeHandler.postDelayed(decodeRunnable, REGION_DEBOUNCE_MS)
    }

    private fun requestRegionDecode() {
        val d = decoder ?: return
        val owner = lifecycleOwner ?: return
        if (scale <= NEAR_ONE || rawW <= 0 || viewW <= 0) {
            showPreview()
            return
        }
        val rect = visibleRawRect() ?: return
        // 可视区域没怎么变就不重复解码（平移一帧一帧地抖也不会狂解）
        val prev = lastRegionRect
        if (showingRegion && prev != null && rectClose(rect, prev)) return

        decodeJob?.cancel()
        val gen = ++decodeGeneration
        val cap = regionCap()
        val sample = max(1, ceil(max(rect.width(), rect.height()).toFloat() / cap).toInt())

        decodeJob = owner.lifecycleScope.launch {
            // IO 线程解码真像素：BitmapRegionDecoder.decodeRegion 按原图矩形分块，
            // 与原图级清晰度策略一致 —— 放大后看到的是原图细节，不是预览的插值放大。
            val result = withContext(Dispatchers.IO) {
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = d.decodeRegion(rect, opts)
                    if (bmp != null) RegionResult(bmp, rect, sample) else null
                } catch (e: Exception) {
                    null
                }
            }
            // 代次作废：手势/换图期间发出的旧结果直接回收，绝不盖回当前画面
            if (!isActive || gen != decodeGeneration) {
                result?.bitmap?.recycle()
                return@launch
            }
            val r = result ?: return@launch
            val old = regionBitmap
            regionBitmap = r.bitmap
            lastRegionRect = r.rect
            lastRegionSample = r.sample
            showingRegion = true
            setImageBitmap(r.bitmap)
            applyMatrix()
            if (old != null && old !== r.bitmap) old.recycle()
        }
    }

    private data class RegionResult(val bitmap: Bitmap, val rect: Rect, val sample: Int)

    private fun showPreview() {
        val bmp = previewBitmap ?: return
        showingRegion = false
        lastRegionRect = null
        setImageBitmap(bmp)
        applyMatrix()
    }

    private fun cancelRegionDecode() {
        decodeGeneration++
        decodeJob?.cancel()
        decodeJob = null
        decodeHandler.removeCallbacks(decodeRunnable)
    }

    private fun releaseDecoder() {
        cancelRegionDecode()
        val r = regionBitmap
        regionBitmap = null
        r?.recycle()
        val p = previewBitmap
        previewBitmap = null
        p?.recycle()
        decoder = null
        pfd?.close()
        pfd = null
        rawW = 0
        rawH = 0
        lastRegionRect = null
        showingRegion = false
        setImageBitmap(null)
    }
}
