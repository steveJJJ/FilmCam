package com.filmcam.app.develop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.filmcam.app.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 显影液盘：把照片画在「晃动的水面之下」。
 *
 * 和上一版 LiquidWaveView 的本质区别：
 *  - 旧版是在照片上面**画几条正弦线**，所以一眼假；
 *  - 这一版用一张真实水面法线贴图（res/drawable-nodpi/water_normal.jpg，
 *    1024×1024 无缝切线空间法线）驱动**照片本身的折射扭曲** ——
 *    看到的是「透过晃动液面看到的照片」，这是水体渲染的标准做法。
 *
 * 每帧三件事：
 *  ① 合成当前显影态（底图 → 成片 → 潜影 → 乳剂）到一张小尺寸离屏位图
 *  ② 用两层不同速度/尺度滚动的法线场算出每个网格顶点的位移，drawBitmapMesh 扭曲照片
 *  ③ 由同一张法线烘焙的高光（锐反射）+ 焦散（柔光团）以 ADD 叠加，随搅动变亮变密
 *
 * 液面四边被「钉住」（位移向边缘衰减到 0），配合 5% overscan，不会拉出透明边。
 */
class WaterTrayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        /** 离屏合成尺寸上限：折射会抹掉细节，做太大没意义，还白耗性能 */
        private const val SCENE_MAX_EDGE = 560
        /** 轻微放大，避免边缘位移把底下的透明像素拉进来 */
        private const val OVER = 1.05f
        /** 网格列数（行数按照片比例算），22×N 足够表现折射的连续形变 */
        private const val MESH_COLS = 22
        /** 法线采样数组边长（素材 1024 → inSampleSize=2） */
        private const val N_SIZE = 512
        /** 两层法线的尺度：texel = 场景像素 × S，512/S = 一个水面贴图在屏幕上跨越的像素 */
        private const val SA = 0.90f
        private const val SB = 1.70f
        private const val MIN_FRAME_MS = 30L
        private const val EMULSION_COLOR = 0xFF7A5A38.toInt()
    }

    // ---- 水面法线数据 ----
    private var nPixels: IntArray? = null
    private var specSharp: Bitmap? = null
    private var specSoft: Bitmap? = null
    private var waterReady = false

    // ---- 图层素材 ----
    private var base: Bitmap? = null
    private var ghost: Bitmap? = null
    private var developed: Bitmap? = null

    /** 引擎成片的淡入进度 0..1 */
    var developedAlpha: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f) }

    /** 显影进度 0..1：0 = 一片乳剂，1 = 完全显影（内部换算潜影/乳剂层 alpha） */
    var reveal: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f) }

    /** 搅动强度 0..1，由摇手机推动 */
    private var agitation: Float = 0f

    // ---- 离屏合成 ----
    private var scene: Bitmap? = null
    private var sceneCanvas: Canvas? = null
    private var sceneW = 0
    private var sceneH = 0
    private var rows = 0
    private var verts: FloatArray? = null

    // ---- 照片在 View 里的实际矩形（fitCenter）----
    private var cx = 0f
    private var cy = 0f
    private var cw = 0f
    private var ch = 0f

    // ---- 水面滚动偏移 / 时间 ----
    private var offAx = 0f
    private var offAy = 0f
    private var offBx = 0f
    private var offBy = 0f
    private var tSec = 0f
    private var lastMs = 0L
    private var lastDrawMs = 0L
    private var running = false

    private val tmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val emulPaint = Paint().apply { color = EMULSION_COLOR }
    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val specAPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val specBPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val vignettePaint = Paint()
    private val mat = Matrix()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val nrm = FloatArray(2)

    init {
        specAPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        specBPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    // ---------- 对外接口 ----------

    /** 换帧：传入本帧底图与潜影图，清空成片层。 */
    fun setFrame(baseBmp: Bitmap?, ghostBmp: Bitmap?) {
        base = baseBmp
        ghost = ghostBmp
        developed = null
        developedAlpha = 0f
        reveal = 0f
        if (baseBmp == null) {
            scene = null
            sceneCanvas = null
            return
        }
        val s = min(1f, SCENE_MAX_EDGE.toFloat() / max(baseBmp.width, baseBmp.height).toFloat())
        val w = max(1, (baseBmp.width * s).roundToInt())
        val h = max(1, (baseBmp.height * s).roundToInt())
        if (w != sceneW || h != sceneH) {
            sceneW = w
            sceneH = h
            scene = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            sceneCanvas = Canvas(scene!!)
            rows = max(4, (MESH_COLS.toFloat() * h / w.toFloat()).roundToInt())
            verts = FloatArray((MESH_COLS + 1) * (rows + 1) * 2)
        }
        computeContentRect()
        invalidate()
    }

    /** 引擎冲出成片：淡入盖住未冲洗底图。 */
    fun setDeveloped(bmp: Bitmap?) {
        developed = bmp
        developedAlpha = 0f
    }

    fun setAgitation(value: Float) {
        agitation = value.coerceIn(0f, 1f)
    }

    // ---------- 尺寸 ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeContentRect()
        if (w > 0 && h > 0) {
            vignettePaint.shader = RadialGradient(
                w * 0.5f, h * 0.45f, max(w, h) * 0.62f,
                Color.TRANSPARENT, 0x66000000.toInt(), Shader.TileMode.CLAMP
            )
        }
    }

    private fun computeContentRect() {
        val bw = sceneW.toFloat()
        val bh = sceneH.toFloat()
        if (bw <= 0f || bh <= 0f || width <= 0 || height <= 0) return
        val s = min(width / bw, height / bh)
        cw = bw * s
        ch = bh * s
        cx = (width - cw) * 0.5f
        cy = (height - ch) * 0.5f
    }

    // ---------- 水面数据（首次绘制时惰性载入）----------

    private fun ensureWater() {
        if (waterReady) return
        waterReady = true
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = runCatching {
            BitmapFactory.decodeResource(resources, R.drawable.water_normal, opts)
        }.getOrNull() ?: return
        val n = bmp.width
        val px = IntArray(n * n)
        bmp.getPixels(px, 0, n, 0, 0, n, n)
        bmp.recycle()
        nPixels = px
        bakeSpecular(px, n)
        specAPaint.shader = specSharp?.let { BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        specBPaint.shader = specSoft?.let { BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }

    /**
     * 由法线烘焙两层水面光：
     *  - 锐反射（exp 26）：细碎的白色反光点，水面「波光粼粼」就靠它
     *  - 焦散柔光（exp 5）：大块的暖色亮团，药水在红光下的透光感
     * 一次烘焙、之后只是平移，和折射用的是同一套法线，所以光和形变严格对得上。
     */
    private fun bakeSpecular(px: IntArray, n: Int) {
        val size = 256
        val stride = n / size
        val sharp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val soft = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val ps = IntArray(size * size)
        val pf = IntArray(size * size)
        // 安全灯光源方向（左上偏前），与红光氛围一致
        var lx = -0.42f; var ly = -0.60f; var lz = 0.68f
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        lx /= ll; ly /= ll; lz /= ll
        var i = 0
        for (y in 0 until size) {
            val sy = y * stride
            for (x in 0 until size) {
                val p = px[sy * n + x * stride]
                val nx = ((p shr 16 and 0xFF) / 255f) * 2f - 1f
                val ny = ((p shr 8 and 0xFF) / 255f) * 2f - 1f
                var nz = 1f - nx * nx - ny * ny
                if (nz < 0f) nz = 0f
                nz = sqrt(nz)
                var d = nx * lx + ny * ly + nz * lz
                if (d < 0f) d = 0f
                val sA = (d.pow(26) * 255f).toInt().coerceIn(0, 255)
                val sB = (d.pow(5) * 130f).toInt().coerceIn(0, 255)
                // 锐反射偏暖白，焦散偏橙（红光安全灯下的药水色）
                ps[i] = (sA shl 24) or 0x00FFE4C8
                pf[i] = (sB shl 24) or 0x00FF9A55
                i++
            }
        }
        sharp.setPixels(ps, 0, size, 0, 0, size, size)
        soft.setPixels(pf, 0, size, 0, 0, size, size)
        specSharp = sharp
        specSoft = soft
    }

    // ---------- 动画驱动 ----------

    private val frameCallback: Choreographer.FrameCallback = Choreographer.FrameCallback { _ ->
        if (!running) return@FrameCallback
        val nowMs = SystemClock.uptimeMillis()
        val dt = ((nowMs - lastMs).coerceIn(0L, 120L)) / 1000f
        lastMs = nowMs
        // 30fps 足够：水面是低频运动，再快只是白烧 CPU/GPU
        if (nowMs - lastDrawMs >= MIN_FRAME_MS) {
            lastDrawMs = nowMs
            invalidate()
        }
        if (dt > 0f) {
            tSec += dt
            val spdA = 13f + 78f * agitation
            val spdB = 19f + 104f * agitation
            offAx += dt * spdA
            offAy += dt * spdA * 0.55f
            offBx -= dt * spdB
            offBy += dt * spdB * 0.42f
            val m = N_SIZE.toFloat()
            offAx %= m; offAy %= m; offBx %= m; offBy %= m
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastMs = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = scene
        val vs = verts
        if (bmp == null || vs == null || cw <= 0f || ch <= 0f) return
        ensureWater()

        composeScene()
        buildMesh(vs)

        // ① 被水面折射的照片
        val sx = cw * OVER / sceneW
        val sy = ch * OVER / sceneH
        mat.setScale(sx, sy)
        mat.postTranslate(cx - cw * (OVER - 1f) * 0.5f, cy - ch * (OVER - 1f) * 0.5f)
        canvas.save()
        canvas.concat(mat)
        canvas.drawBitmapMesh(bmp, MESH_COLS, rows, vs, 0, null, 0, meshPaint)
        canvas.restore()

        // ② 水面高光 + 焦散（与折射同源，ADD 叠加；搅动越猛越亮）
        if (specAPaint.shader != null) {
            drawSpecLayer(canvas, specAPaint, SA, offAx, offAy, (46f + 150f * agitation).toInt().coerceIn(0, 255))
        }
        if (specBPaint.shader != null) {
            drawSpecLayer(canvas, specBPaint, SB, offBx, offBy, (34f + 108f * agitation).toInt().coerceIn(0, 255))
        }

        // ③ 液盘边缘压暗，把画面收进盘里
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)
    }

    /** 合成当前显影态：底图 → 成片 → 潜影 → 乳剂（后者盖住前者）。 */
    private fun composeScene() {
        val c = sceneCanvas ?: return
        val b = base ?: return
        drawScaled(c, b, 1f)
        val d = developed
        if (d != null && developedAlpha > 0.004f) drawScaled(c, d, developedAlpha)
        val ga = 1f - smoothstep(0.30f, 1f, reveal)
        val g = ghost
        if (g != null && ga > 0.004f) drawScaled(c, g, ga)
        val ea = 1f - smoothstep(0f, 0.42f, reveal)
        if (ea > 0.004f) {
            emulPaint.alpha = (ea * 255f).toInt().coerceIn(0, 255)
            c.drawRect(0f, 0f, sceneW.toFloat(), sceneH.toFloat(), emulPaint)
        }
    }

    private fun drawScaled(c: Canvas, bmp: Bitmap, alpha: Float) {
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(0f, 0f, sceneW.toFloat(), sceneH.toFloat())
        val p: Paint? = if (alpha >= 0.999f) null else tmpPaint.apply {
            this.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        }
        c.drawBitmap(bmp, srcRect, dstRect, p)
    }

    /** 按两层滚动的法线场，算出每个网格顶点的折射位移。 */
    private fun buildMesh(vs: FloatArray) {
        val px = nPixels
        val amp = sceneH * (0.005f + 0.030f * agitation)
        val slosh = sceneH * (0.004f + 0.020f * agitation)
        val sloshX = sin(tSec * 1.15f) * slosh
        val sloshY = cos(tSec * 0.87f) * slosh * 0.7f
        var i = 0
        for (r in 0..rows) {
            val v = r.toFloat() / rows
            val py = v * sceneH
            for (cc in 0..MESH_COLS) {
                val u = cc.toFloat() / MESH_COLS
                val pxx = u * sceneW
                var dx: Float
                var dy: Float
                if (px == null) {
                    // 素材没解出来时的兜底：极轻的呼吸式晃动，不至于完全静止
                    dx = sin(pxx * 0.02f + tSec) * 0.4f
                    dy = cos(py * 0.02f + tSec * 0.8f) * 0.4f
                } else {
                    sampleN(px, pxx * SA + offAx, py * SA + offAy, nrm)
                    dx = nrm[0] * 0.62f
                    dy = nrm[1] * 0.62f
                    sampleN(px, pxx * SB + offBx, py * SB + offBy, nrm)
                    dx += nrm[0] * 0.38f
                    dy += nrm[1] * 0.38f
                }
                // 液面四边被盘壁「钉住」：位移向边缘衰减到 0，避免拉出透明边
                val edge = edgeMask(u, v)
                val k = amp * edge
                vs[i++] = pxx + dx * k + sloshX * edge
                vs[i++] = py + dy * k + sloshY * edge
            }
        }
    }

    private fun edgeMask(u: Float, v: Float): Float {
        val d = min(min(u, 1f - u), min(v, 1f - v))
        return smoothstep(0f, 0.16f, d)
    }

    private fun sampleN(px: IntArray, tx: Float, ty: Float, out: FloatArray) {
        var x = tx % N_SIZE
        var y = ty % N_SIZE
        if (x < 0f) x += N_SIZE
        if (y < 0f) y += N_SIZE
        val p = px[y.toInt() * N_SIZE + x.toInt()]
        out[0] = ((p shr 16 and 0xFF) / 255f) * 2f - 1f
        out[1] = ((p shr 8 and 0xFF) / 255f) * 2f - 1f
    }

    /**
     * 把水面光以 REPEAT 平铺画满整屏，平移量与折射层严格同源：
     * 屏幕 x → 场景 x → 法线 texel → 高光位图像素。
     */
    private fun drawSpecLayer(
        canvas: Canvas,
        paint: Paint,
        s: Float,
        offX: Float,
        offY: Float,
        alpha: Int
    ) {
        if (cw <= 0f || ch <= 0f) return
        // 高光位图 256px 对应 512 texel → ×0.5
        val kx = 0.5f * s * sceneW / cw
        val ky = 0.5f * s * sceneH / ch
        val tx = 0.5f * (offX - s * sceneW * cx / cw)
        val ty = 0.5f * (offY - s * sceneH * cy / ch)
        mat.setScale(kx, ky)
        mat.postTranslate(tx, ty)
        (paint.shader as? BitmapShader)?.setLocalMatrix(mat)
        paint.alpha = alpha
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        if (b <= a) return if (x < a) 0f else 1f
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
