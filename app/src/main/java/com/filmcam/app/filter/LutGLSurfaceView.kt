package com.filmcam.app.filter

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import com.filmcam.app.filter.LutParser.LutResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.0 滤镜渲染表面
 *
 * 架构：
 *   CameraX 帧 → SurfaceTexture(OES 外部纹理) → 片元着色器（3D LUT 查表 + center-crop）→ 屏幕
 *
 * 比例处理：
 *   相机传感器输出 4:3 横屏，通过 SurfaceTexture transform matrix 旋转到竖屏，
 *   然后 center-crop 适配屏幕宽高比。
 */
class LutGLSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    /** CameraX 就绪后回调，把 SurfaceTexture 交给 CameraX 输出帧 */
    var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null

    private var lutResult: LutResult? = null
    private var renderer: LutRenderer? = null

    init {
        setEGLContextClientVersion(3)
        // 开启 4x MSAA 抗锯齿
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        renderer = LutRenderer().also {
            setRenderer(it)
        }
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** 从 res/raw 加载 .cube LUT */
    fun loadLutFromRaw(resId: Int) {
        context.resources.openRawResource(resId).use { stream ->
            lutResult = LutParser().parse(stream.bufferedReader())
        }
        queueEvent { renderer?.uploadLut(lutResult) }
    }

    /** 设置临时色调（后续替换真实 LUT 后删掉） */
    fun setTint(r: Float, g: Float, b: Float) {
        queueEvent { renderer?.tintR = r; renderer?.tintG = g; renderer?.tintB = b }
    }

    /** 设置胶片颗粒强度 0.0~1.0 */
    fun setGrain(strength: Float) {
        queueEvent { renderer?.grainStrength = strength }
    }

    // 胶片瑕疵效果开关（默认关）
    var enableLightLeak = false
    var enableLensDust = false
    var enableShakeBlur = false
    var enableHalation = false
    // 拍照成片 halation：高斯辉光（降采样 bloom），独立于预览，默认开启
    var photoHalationEnabled = true
    var enableSoftFocus = false  // 模拟对不上焦：柔焦失焦效果

    // 更逼真胶片颗粒：分形相关噪声（预生成纹理，仅用于拍照输出，不影响预览取景器）
    private var photoGrainStrength = 0.06f   // 拍照颗粒强度，可调
    // 拍照颗粒总开关（独立于预览 shader）
    var photoGrainEnabled = true
    private var grainTex: IntArray? = null
    private val grainTexSize = 512

    /** 懒生成分形颗粒纹理：3 octave 值噪声 fbm，RGB 独立，含低频"颗粒簇"+高频细化 */
    private fun ensureGrainTexture() {
        if (grainTex != null) return
        val seed = 1337
        val freqBase = 64f
        val arr = IntArray(grainTexSize * grainTexSize)
        for (y in 0 until grainTexSize) {
            for (x in 0 until grainTexSize) {
                val fx = x.toFloat() / grainTexSize * freqBase
                val fy = y.toFloat() / grainTexSize * freqBase
                val r = grainFbm(fx, fy, seed)
                val g = grainFbm(fx, fy, seed + 101)
                val b = grainFbm(fx, fy, seed + 1009)
                val r255 = (((r - 0.44f) * 510f).toInt() + 128).coerceIn(0, 255)
                val g255 = (((g - 0.44f) * 510f).toInt() + 128).coerceIn(0, 255)
                val b255 = (((b - 0.44f) * 510f).toInt() + 128).coerceIn(0, 255)
                arr[y * grainTexSize + x] = (r255 shl 16) or (g255 shl 8) or b255
            }
        }
        grainTex = arr
    }

    private fun grainNoise(ix: Int, iy: Int, seed: Int): Float {
        var n = ix * 374761393 + iy * 668265263 + seed * 69069
        n = (n xor (n shr 13)) * 1274126177
        n = n xor (n shr 16)
        return (n and 0xFFFFFF).toFloat() / 0xFFFFFF.toFloat()
    }

    private fun grainFbm(x: Float, y: Float, seed: Int): Float {
        var v = 0f
        var amp = 0.5f
        var freq = 1f
        for (o in 0 until 3) {
            val ix = (x * freq).toInt()
            val iy = (y * freq).toInt()
            val fx = x * freq - ix
            val fy = y * freq - iy
            val a = grainNoise(ix, iy, seed)
            val b = grainNoise(ix + 1, iy, seed)
            val c = grainNoise(ix, iy + 1, seed)
            val d = grainNoise(ix + 1, iy + 1, seed)
            val ux = fx * fx * (3f - 2f * fx)
            val uy = fy * fy * (3f - 2f * fy)
            v += (a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy) * amp
            freq *= 2.37f
            amp *= 0.5f
        }
        return v
    }

    /** 软件方式对 Bitmap 应用 3D LUT + halation + 暗角（优化版） */
    fun applyLutToBitmap(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val lut = lutResult ?: return src
        val size = lut.size
        val data = lut.data
        ensureGrainTexture()

        // 全分辨率处理：胶片冲洗等待动画覆盖处理耗时，无需降质（对齐主流主摄 12MP）
        val maxDim = 4000
        val scale = if (src.width > src.height) maxDim.toFloat() / src.width else maxDim.toFloat() / src.height
        val newW = (src.width * scale).toInt()
        val newH = (src.height * scale).toInt()
        val scaled = android.graphics.Bitmap.createScaledBitmap(src, newW, newH, true)

        val w = newW
        val h = newH
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        // 胶片效果开关
        var lightLeak = false
        var lensDust = false
        var shakeBlur = false
        // 由外部设置注入
        lightLeak = enableLightLeak
        lensDust = enableLensDust
        shakeBlur = enableShakeBlur

        // 漏光参数：更明显的边缘色光
        val random = java.util.Random()
        val hasLightLeak = lightLeak && random.nextFloat() < 0.7f
        val leakSide = random.nextInt(4)
        val leakR = floatArrayOf(1f, 1f, 1f, 1f)[random.nextInt(4)]
        val leakG = floatArrayOf(0.2f, 0.5f, 0.7f, 0.3f)[random.nextInt(4)]
        val leakB = floatArrayOf(0.1f, 0.1f, 0.2f, 0.5f)[random.nextInt(4)]
        val leakStrength = 0.3f + random.nextFloat() * 0.4f

        // 预计算 LUT 步长
        val s = size - 1
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        for (i in pixels.indices) {
            val p = pixels[i]
            var r = (p shr 16 and 0xFF) / 255f
            var g = (p shr 8 and 0xFF) / 255f
            var b = (p and 0xFF) / 255f

            // 3D LUT 三线性插值（平滑，无 banding）
            val fx = (r * s).coerceIn(0f, s.toFloat())
            val fy = (g * s).coerceIn(0f, s.toFloat())
            val fz = (b * s).coerceIn(0f, s.toFloat())
            val x0 = fx.toInt(); val y0 = fy.toInt(); val z0 = fz.toInt()
            val x1 = (x0 + 1).coerceAtMost(s)
            val y1 = (y0 + 1).coerceAtMost(s)
            val z1 = (z0 + 1).coerceAtMost(s)
            val dx = fx - x0; val dy = fy - y0; val dz = fz - z0

            fun sample(x: Int, y: Int, z: Int, c: Int) = data[(z * size * size + y * size + x) * 3 + c]

            var rr = lerp(
                lerp(lerp(sample(x0,y0,z0,0), sample(x1,y0,z0,0), dx),
                     lerp(sample(x0,y1,z0,0), sample(x1,y1,z0,0), dx), dy),
                lerp(lerp(sample(x0,y0,z1,0), sample(x1,y0,z1,0), dx),
                     lerp(sample(x0,y1,z1,0), sample(x1,y1,z1,0), dx), dy), dz)
            var gg = lerp(
                lerp(lerp(sample(x0,y0,z0,1), sample(x1,y0,z0,1), dx),
                     lerp(sample(x0,y1,z0,1), sample(x1,y1,z0,1), dx), dy),
                lerp(lerp(sample(x0,y0,z1,1), sample(x1,y0,z1,1), dx),
                     lerp(sample(x0,y1,z1,1), sample(x1,y1,z1,1), dx), dy), dz)
            var bb = lerp(
                lerp(lerp(sample(x0,y0,z0,2), sample(x1,y0,z0,2), dx),
                     lerp(sample(x0,y1,z0,2), sample(x1,y1,z0,2), dx), dy),
                lerp(lerp(sample(x0,y0,z1,2), sample(x1,y0,z1,2), dx),
                     lerp(sample(x0,y1,z1,2), sample(x1,y1,z1,2), dx), dy), dz)

            // 漏光：边缘色光渐变（更明显）
            if (hasLightLeak) {
                val px = i % w
                val py = i / w
                var leak = 0f
                when (leakSide) {
                    0 -> leak = (1f - px / w.toFloat())
                    1 -> leak = (px / w.toFloat())
                    2 -> leak = (1f - py / h.toFloat())
                    3 -> leak = (py / h.toFloat())
                }
                leak = leak * leak * leakStrength  // 平方渐变，边缘更浓
                rr += leak * leakR
                gg += leak * leakG
                bb += leak * leakB
            }

            // 暗角
            val px2 = (i % w) / w.toFloat() - 0.5f
            val py2 = (i / w) / h.toFloat() - 0.5f
            val dist = Math.sqrt((px2 * px2 + py2 * py2).toDouble()).toFloat()
            val vig = 1f - (dist - 0.3f).coerceIn(0f, 1f) * 0.3f
            rr *= vig; gg *= vig; bb *= vig

            // 更逼真胶片颗粒：分形相关噪声（块状/簇状），中间调偏浓，RGB 独立彩色
            if (photoGrainEnabled && photoGrainStrength > 0f) {
                val lumG = 0.299f * rr + 0.587f * gg + 0.114f * bb
                val midG = 1f - kotlin.math.abs(lumG - 0.5f) * 2f   // 0=纯黑/纯白，1=中间调
                val gAmt = photoGrainStrength * (0.4f + 0.6f * midG)
                val gxi = i % w
                val gyi = i / w
                val gi = (gxi % grainTexSize) + (gyi % grainTexSize) * grainTexSize
                val g = grainTex!![gi]
                rr += (((g shr 16) and 0xFF) / 255f - 0.5f) * gAmt
                gg += (((g shr 8) and 0xFF) / 255f - 0.5f) * gAmt
                bb += ((g and 0xFF) / 255f - 0.5f) * gAmt
            }

            pixels[i] = ((rr.coerceIn(0f,1f) * 255).toInt() shl 16) or
                        ((gg.coerceIn(0f,1f) * 255).toInt() shl 8) or
                        ((bb.coerceIn(0f,1f) * 255).toInt()) or -0x1000000
        }
        var result = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)

        // 手抖模糊：闪光冻结主体 + 背景运动模糊
        if (shakeBlur) {
            val rnd = java.util.Random()
            val angle = rnd.nextFloat() * Math.PI.toFloat()
            val offsetX = (Math.cos(angle.toDouble()) * 5).toInt()
            val offsetY = (Math.sin(angle.toDouble()) * 5).toInt()
            val blurPixels = IntArray(w * h)
            result.getPixels(blurPixels, 0, w, 0, 0, w, h)
            val outPixels = pixels.copyOf()
            for (i in 0 until w * h) {
                val px = i % w
                val py = i / w
                // 当前像素亮度（闪光灯照到的=亮=主体）
                val p = blurPixels[i]
                val lum = 0.299f * (p shr 16 and 0xFF) + 0.587f * (p shr 8 and 0xFF) + 0.114f * (p and 0xFF)
                // 亮的地方保留清晰，暗的地方模糊
                val sharpness = (lum / 255f).coerceIn(0f, 1f)

                var br = 0f; var bg = 0f; var bb = 0f
                var count = 0
                for (t in -5..5) {
                    val sx = px + offsetX * t / 5
                    val sy = py + offsetY * t / 5
                    if (sx in 0 until w && sy in 0 until h) {
                        val bp = blurPixels[sy * w + sx]
                        br += (bp shr 16 and 0xFF)
                        bg += (bp shr 8 and 0xFF)
                        bb += (bp and 0xFF)
                        count++
                    }
                }
                if (count > 0) {
                    br /= count; bg /= count; bb /= count
                    // 主体=原始清晰，背景=模糊
                    val orig = blurPixels[i]
                    val origR = (orig shr 16 and 0xFF).toFloat()
                    val origG = (orig shr 8 and 0xFF).toFloat()
                    val origB = (orig and 0xFF).toFloat()
                    val finalR = origR * sharpness + br * (1f - sharpness)
                    val finalG = origG * sharpness + bg * (1f - sharpness)
                    val finalB = origB * sharpness + bb * (1f - sharpness)
                    outPixels[i] = (finalR.toInt() shl 16) or (finalG.toInt() shl 8) or finalB.toInt() or -0x1000000
                }
            }
            result.setPixels(outPixels, 0, w, 0, 0, w, h)
        }

        // 镜头灰尘：用 Canvas 在结果上叠加随机白点（Screen 混合）
        if (lensDust) {
            val canvas = android.graphics.Canvas(result)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                alpha = 60
                isAntiAlias = true
            }
            val rnd = java.util.Random()
            repeat(rnd.nextInt(20) + 10) {
                val cx = rnd.nextFloat() * w
                val cy = rnd.nextFloat() * h
                val radius = rnd.nextFloat() * 2f + 0.5f
                canvas.drawCircle(cx, cy, radius, paint)
            }
            // 随机划痕
            repeat(rnd.nextInt(3) + 1) {
                val sy = rnd.nextFloat() * h
                paint.alpha = 30
                canvas.drawLine(0f, sy, w.toFloat(), sy + (rnd.nextFloat() - 0.5f) * 20, paint)
            }
        }

        // 模拟对不上焦：整体柔焦失焦（保留少量清晰度，营造轻微失焦感）
        if (enableSoftFocus) {
            result = applySoftFocus(result)
        }

        // Halation 高斯辉光：降采样 bloom，亮部向周围晕开（红光倾向），拍照成片
        if (photoHalationEnabled) {
            result = applyHalation(result)
        }

        return result
    }

    /** Halation 高斯辉光：提取高光 → 降采样 → 多次盒式模糊 → 放大叠加回原图（红光倾向，负片亮部晕开） */
    private fun applyHalation(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val sw = src.width
        val sh = src.height
        val target = 512
        val ds = target.toFloat() / kotlin.math.max(sw, sh)
        val dw = (sw * ds).toInt().coerceAtLeast(2)
        val dh = (sh * ds).toInt().coerceAtLeast(2)
        val srcPx = IntArray(sw * sh)
        src.getPixels(srcPx, 0, sw, 0, 0, sw, sh)
        // 降采样 + 高光提取（>0.75 线性蒙版）
        val glow = FloatArray(dw * dh)
        for (y in 0 until dh) {
            for (x in 0 until dw) {
                val sx0 = (x * sw) / dw
                val sx1 = kotlin.math.max(((x + 1) * sw) / dw, sx0 + 1)
                val sy0 = (y * sh) / dh
                val sy1 = kotlin.math.max(((y + 1) * sh) / dh, sy0 + 1)
                var sum = 0f
                var cnt = 0
                for (sy in sy0 until sy1) {
                    val row = sy * sw
                    for (sx in sx0 until sx1) {
                        val p = srcPx[row + sx]
                        val r = (p shr 16 and 0xFF) / 255f
                        val g = (p shr 8 and 0xFF) / 255f
                        val b = (p and 0xFF) / 255f
                        sum += 0.299f * r + 0.587f * g + 0.114f * b
                        cnt++
                    }
                }
                val lum = sum / cnt
                glow[y * dw + x] = ((lum - 0.75f) / 0.25f).coerceIn(0f, 1f)
            }
        }
        // 多次盒式模糊，扩展辉光范围
        var blurred = glow
        repeat(3) { blurred = boxBlurF(blurred, dw, dh) }
        // 放大回全尺寸，叠加（红光倾向：R 加最多）
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val gx = ((x * dw) / sw).coerceAtMost(dw - 1)
                val gy = ((y * dh) / sh).coerceAtMost(dh - 1)
                val h = blurred[gy * dw + gx]
                if (h > 0.02f) {
                    val i = y * sw + x
                    val p = srcPx[i]
                    val r = (p shr 16 and 0xFF)
                    val g = (p shr 8 and 0xFF)
                    val b = (p and 0xFF)
                    val add = (h * 10).toInt()           // 辉光亮度
                    val rAdd = add + (h * 14).toInt()    // 红光倾向
                    val nr = (r + rAdd).coerceAtMost(255)
                    val ng = (g + add).coerceAtMost(255)
                    val nb = (b + (h * 6).toInt()).coerceAtMost(255)
                    srcPx[i] = (nr shl 16) or (ng shl 8) or nb or -0x1000000
                }
            }
        }
        val out = android.graphics.Bitmap.createBitmap(sw, sh, android.graphics.Bitmap.Config.ARGB_8888)
        out.setPixels(srcPx, 0, sw, 0, 0, sw, sh)
        return out
    }

    private fun boxBlurF(arr: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var cnt = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val ny = (y + dy).coerceIn(0, h - 1)
                        sum += arr[ny * w + nx]
                        cnt++
                    }
                }
                out[y * w + x] = sum / cnt
            }
        }
        return out
    }

    /** 柔焦失焦：盒式模糊近似高斯 + 与原图混合保留部分清晰度 */
    private fun applySoftFocus(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = src.width
        val h = src.height
        val srcPx = IntArray(w * h)
        src.getPixels(srcPx, 0, w, 0, 0, w, h)

        // 3 次盒式模糊近似高斯（半径随图片尺寸缩放）
        val radius = ((w * h / 2_000_000f).coerceIn(0.8f, 1.6f)).toInt().coerceAtLeast(1)
        var blurred = srcPx
        repeat(3) { blurred = boxBlur(blurred, w, h, radius) }

        // 与原图混合：失焦但保留一定轮廓（70% 模糊 + 30% 原图）
        val outPx = IntArray(w * h)
        for (i in srcPx.indices) {
            val b = blurred[i]
            val o = srcPx[i]
            val br = b shr 16 and 0xFF
            val bg = b shr 8 and 0xFF
            val bb = b and 0xFF
            val or = o shr 16 and 0xFF
            val og = o shr 8 and 0xFF
            val ob = o and 0xFF
            outPx[i] = -0x1000000 or
                ((br * 0.7f + or * 0.3f).toInt() shl 16) or
                ((bg * 0.7f + og * 0.3f).toInt() shl 8) or
                (bb * 0.7f + ob * 0.3f).toInt()
        }
        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        return out
    }

    /** 盒式模糊（水平 + 垂直各一次） */
    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int): IntArray {
        val hPass = IntArray(px.size)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                var sumR = 0; var sumG = 0; var sumB = 0; var cnt = 0
                for (dx in -r..r) {
                    val nx = (x + dx).coerceIn(0, w - 1)
                    val p = px[base + nx]
                    sumR += p shr 16 and 0xFF
                    sumG += p shr 8 and 0xFF
                    sumB += p and 0xFF
                    cnt++
                }
                hPass[base + x] = -0x1000000 or ((sumR / cnt) shl 16) or ((sumG / cnt) shl 8) or (sumB / cnt)
            }
        }
        val out = IntArray(px.size)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var sumR = 0; var sumG = 0; var sumB = 0; var cnt = 0
                for (dy in -r..r) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    val p = hPass[ny * w + x]
                    sumR += p shr 16 and 0xFF
                    sumG += p shr 8 and 0xFF
                    sumB += p and 0xFF
                    cnt++
                }
                out[y * w + x] = -0x1000000 or ((sumR / cnt) shl 16) or ((sumG / cnt) shl 8) or (sumB / cnt)
            }
        }
        return out
    }

    private var pendingCapture: ((android.graphics.Bitmap?) -> Unit)? = null

    /** 读取当前 GL 帧并回调 Bitmap */
    fun captureFrame(onResult: (android.graphics.Bitmap?) -> Unit) {
        queueEvent {
            pendingCapture = onResult
            requestRender()  // 触发一次渲染，渲染完成后会在 onDrawFrame 里读像素
        }
    }

    /** 在 onDrawFrame 末尾调用，执行待捕获（FBO 高分辨率离屏渲染） */
    internal fun executePendingCapture() {
        val callback = pendingCapture ?: return
        pendingCapture = null
        renderer?.let { r ->
            val targetW = 3000  // 高分辨率出图 3:4
            val targetH = 4000

            // 创建 FBO
            val fboIds = IntArray(1)
            val texIds = IntArray(1)
            GLES30.glGenFramebuffers(1, fboIds, 0)
            GLES30.glGenTextures(1, texIds, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texIds[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA,
                targetW, targetH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboIds[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, texIds[0], 0)

            // 高分辨率渲染（颗粒暂未启用）
            r.renderTo(targetW, targetH)

            // 读像素
            val buf = java.nio.ByteBuffer.allocateDirect(targetW * targetH * 4)
            GLES30.glReadPixels(0, 0, targetW, targetH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)

            // 清理 FBO
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteTextures(1, texIds, 0)
            GLES30.glDeleteFramebuffers(1, fboIds, 0)

            val bmp = android.graphics.Bitmap.createBitmap(targetW, targetH, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            val matrix = android.graphics.Matrix().apply { postScale(1f, -1f) }
            val flipped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, targetW, targetH, matrix, false)
            post { callback(flipped) }
        }
    }

    inner class LutRenderer : GLSurfaceView.Renderer {

        private var program = 0
        private var oesTexId = 0
        private var lutTexId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var lutSize = 0
        var surfaceWidth = 0
            private set
        var surfaceHeight = 0
            private set
        var tintR: Float = 1.0f
        var tintG: Float = 1.0f
        var tintB: Float = 1.0f
        var grainStrength: Float = 0.03f  // 轻微颗粒

        // transform matrix (from SurfaceTexture, handles rotation)
        private val texMatrix = FloatArray(16)

        private val vertexBuffer: FloatBuffer
        private val TEX_VERTICES = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
        )

        init {
            vertexBuffer = ByteBuffer.allocateDirect(TEX_VERTICES.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(TEX_VERTICES)
            vertexBuffer.position(0)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            oesTexId = ids[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            surfaceTexture = SurfaceTexture(oesTexId).apply {
                setDefaultBufferSize(3840, 2880)  // 4K 4:3 高分辨率
                setOnFrameAvailableListener {
                    requestRender()
                }
            }
            onSurfaceTextureReady?.invoke(surfaceTexture!!)

            program = createProgram()
            lutResult?.let { uploadLut(it) }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            GLES30.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            val st = surfaceTexture ?: return
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            renderTo(surfaceWidth, surfaceHeight, applyLut = false, applyPostFX = false, offsetY = -0.08f)  // 预览原相机，画面上移一点
            executePendingCapture()
        }

        /** 在指定分辨率下渲染一帧 */
        fun renderTo(viewW: Int, viewH: Int, applyLut: Boolean = true, applyPostFX: Boolean = true, offsetY: Float = 0f) {
            val st = surfaceTexture ?: return
            GLES30.glViewport(0, 0, viewW, viewH)
            GLES30.glUseProgram(program)

            // OES 相机纹理
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTexture"), 0)

            GLES30.glUniformMatrix4fv(
                GLES30.glGetUniformLocation(program, "uTexMatrix"),
                1, false, texMatrix, 0
            )

            val contentAspect = 1440f / 1920f
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uScreenAspect"),
                viewW.toFloat() / viewH.toFloat())
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uContentAspect"),
                contentAspect)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uOffsetY"), offsetY)
            GLES30.glUniform3f(GLES30.glGetUniformLocation(program, "uTint"),
                tintR, tintG, tintB)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uGrain"),
                grainStrength)

            // LUT 开关（预览关闭，拍照开启）
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uUseLut"),
                if (applyLut && lutTexId != 0) 1f else 0f)
            // 后处理效果开关（halation/grain/vignette）
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uPostFX"),
                if (applyPostFX) 1f else 0f)

            if (lutTexId != 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1)
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLutSize"), lutSize)
            }

            vertexBuffer.position(0)
            val posLoc = GLES30.glGetAttribLocation(program, "aPosition")
            GLES30.glEnableVertexAttribArray(posLoc)
            GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)

            vertexBuffer.position(2)
            val texLoc = GLES30.glGetAttribLocation(program, "aTexCoord")
            GLES30.glEnableVertexAttribArray(texLoc)
            GLES30.glVertexAttribPointer(texLoc, 2, GLES30.GL_FLOAT, false, 16, vertexBuffer)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

            GLES30.glDisableVertexAttribArray(posLoc)
            GLES30.glDisableVertexAttribArray(texLoc)
        }

        fun uploadLut(result: LutResult?) {
            result ?: return
            lutSize = result.size

            if (lutTexId == 0) {
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                lutTexId = ids[0]
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

            val lutBuffer = ByteBuffer.allocateDirect(result.data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(result.data)
            lutBuffer.position(0)

            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB,
                lutSize, lutSize, lutSize, 0,
                GLES30.GL_RGB, GLES30.GL_FLOAT, lutBuffer
            )
            Log.i("LutRenderer", "LUT uploaded: ${lutSize}x${lutSize}x${lutSize}")
        }

        private fun createProgram(): Int {
            val vs = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, vs)
            GLES30.glAttachShader(p, fs)
            GLES30.glLinkProgram(p)
            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Log.e("LutRenderer", "Link error: ${GLES30.glGetProgramInfoLog(p)}")
            }
            return p
        }

        private fun compileShader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val status = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e("LutRenderer", "Compile error: ${GLES30.glGetShaderInfoLog(s)}")
            }
            return s
        }
    }

    companion object {

        private val VERTEX_SHADER = """#version 300 es
        layout(location = 0) in vec4 aPosition;
        layout(location = 1) in vec2 aTexCoord;
        out vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPosition.xy, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
        """.trimIndent()

        // 片元着色器：LUT + halation + 胶片颗粒 + 暗角
        private val FRAGMENT_SHADER = """#version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;
        uniform samplerExternalOES uTexture;
        uniform sampler3D uLut;
        uniform mat4 uTexMatrix;
        uniform float uScreenAspect;
        uniform float uContentAspect;
        uniform float uOffsetY;
        uniform vec3 uTint;
        uniform float uGrain;
        uniform float uUseLut;
        uniform float uPostFX;
        uniform int uLutSize;
        in vec2 vTexCoord;
        out vec4 fragColor;

        // 高质量 hash 噪声
        float hash(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        vec3 applyLut(vec3 color) {
            vec3 c = clamp(color, 0.0, 1.0);
            return texture(uLut, c).rgb;
        }

        void main() {
            float s = uScreenAspect / uContentAspect;
            float contentY = (vTexCoord.y - 0.5) / s + 0.5 + uOffsetY;

            if (contentY < 0.0 || contentY > 1.0) {
                fragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }

            vec2 uv = (uTexMatrix * vec4(vTexCoord.x, contentY, 0.0, 1.0)).xy;
            vec4 color = texture(uTexture, uv);

            if (uPostFX > 0.5) {
                // Halation：高光红色溢出
                float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                float highlight = smoothstep(0.7, 1.0, lum);
                color.r += highlight * highlight * 0.15;
                color.g += highlight * highlight * 0.05;
            }

            // LUT
            if (uUseLut > 0.5) {
                color.rgb = applyLut(color.rgb);
            }
            color.rgb *= uTint;

            if (uPostFX > 0.5) {
                // 胶片颗粒
                float grainNoise = hash(gl_FragCoord.xy * 0.5) - 0.5;
                float lum2 = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                float grainAmount = uGrain * (0.3 + lum2 * 0.7);
                color.rgb += grainNoise * grainAmount;

                // 暗角
                vec2 centered = vTexCoord - 0.5;
                centered.x *= uScreenAspect / uContentAspect;
                float dist = length(centered);
                float vignette = 1.0 - smoothstep(0.3, 0.8, dist) * 0.3;
                color.rgb *= vignette;
            }

            fragColor = color;
        }
        """.trimIndent()
    }
}
