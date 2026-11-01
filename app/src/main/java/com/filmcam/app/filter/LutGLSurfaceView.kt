package com.filmcam.app.filter

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import com.filmcam.app.ui.ViewfinderFrameView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.0 取景器渲染表面
 *
 * 设计取向：**模拟康泰时 T3 的光学取景器**——取景器里看到的就是镜头真实捕捉到的
 * 画面，不加任何胶片色调、颗粒、暗角或构图网格。胶片的色彩只发生在"冲洗"环节
 * （Vulkan 引擎侧），与取景无关，这与真机上"取景器看实景、出片才见胶片味"一致。
 *
 * 架构：
 *   Camera2 帧 → SurfaceTexture(OES 外部纹理) → 片元着色器（直通 + center-crop）→ 屏幕
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

    /** 调试开关：-1 = 按设备旋转自动选映射；0..3 = 强制 0原始/1旋转180/2顺时针90/3逆时针90 */
    var debugRotateOverride: Int = -1
        set(value) { field = value; android.util.Log.i("RotateTest", "override set $value") }

    /**
     * 机身配色：true = 钛银。取景画面之外的留白（letterbox）在真机上是**机身外壳**，
     * 不是黑边——黑钛档画黑，钛银档画拉丝钛面。改这个会立刻触发一帧重绘。
     */
    var bodyTitan: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestRender()
        }

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

    inner class LutRenderer : GLSurfaceView.Renderer {

        private var program = 0
        private var oesTexId = 0
        private var surfaceTexture: SurfaceTexture? = null
        var surfaceWidth = 0
            private set
        var surfaceHeight = 0
            private set

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
            diagAppend("surfaceCreated view=${width}x${height} measuredW=${measuredWidth} measuredH=${measuredHeight}")
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
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = width
            surfaceHeight = height
            GLES30.glViewport(0, 0, width, height)
            Log.i("LutRenderer", "surfaceChanged: ${width}x${height}")
            diagAppend("surfaceChanged: ${width}x${height}")
        }

        private var loggedFrames = 0

        /** 华为用户版屏蔽三方应用 logcat，诊断信息写文件 */
        private fun diagAppend(line: String) {
            try {
                val ctx = context.applicationContext ?: return
                java.io.File(ctx.filesDir, "filmcam_diag.txt")
                    .appendText("${System.currentTimeMillis() % 100000} $line\n")
            } catch (_: Throwable) {}
        }

        override fun onDrawFrame(gl: GL10?) {
            val st = surfaceTexture ?: return
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            // 诊断：前 2 帧输出纹理矩阵，用于排查不同设备上的几何问题
            if (loggedFrames < 2) {
                loggedFrames++
                Log.i("LutRenderer", "frame#$loggedFrames texMatrix=${texMatrix.joinToString(",") { "%.3f".format(it) }}")
                diagAppend("frame#$loggedFrames texMatrix=${texMatrix.joinToString(",") { "%.3f".format(it) }}")
            }
            // 竖屏走 SurfaceTexture 变换矩阵（模式 0，一直正常）；
            // 横屏这台机器拿到的是单位阵（缺 V 翻转），自己补上：
            //   ROTATION_90 → 模式 1；ROTATION_270 → 模式 2（同一画面转 180°）。
            val mode = if (debugRotateOverride >= 0) (1 + (debugRotateOverride % 2)).toFloat()
            else when (this@LutGLSurfaceView.display?.rotation) {
                android.view.Surface.ROTATION_90 -> 1f
                android.view.Surface.ROTATION_270 -> 2f
                else -> 0f
            }
            renderTo(surfaceWidth, surfaceHeight, offsetY = ViewfinderFrameView.PREVIEW_OFFSET_Y,
                mode = mode)
        }

        /** 在指定分辨率下渲染一帧。
         *  注意：shader 里不能残留未绑定纹理的 sampler 声明——部分真机驱动
         *  （Adreno/麒麟）会因 sampler3D 无有效绑定直接让整次 draw 失败（黑屏），
         *  模拟器软件渲染则不受影响。胶片调色已完全移交 Vulkan 冲洗引擎，
         *  取景器侧保持直通，不再保留任何后处理分支。 */
        fun renderTo(viewW: Int, viewH: Int, offsetY: Float = 0f, mode: Float = 0f) {
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

            // 内容比例随屏幕朝向切换：横屏 4:3、竖屏 3:4（与 ViewfinderFrameView 同源）
            val contentAspect = ViewfinderFrameView.contentAspectFor(viewW.toFloat(), viewH.toFloat())
            val screenAspect = viewW.toFloat() / viewH.toFloat()
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uScreenAspect"), screenAspect)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uContentAspect"),
                contentAspect)
            // 屏幕比内容更宽 → 内容满高、左右留黑边（横屏）；否则满宽、上下留黑边（竖屏）
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uFitHeight"),
                if (screenAspect > contentAspect) 1f else 0f)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uOffsetY"), offsetY)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uMode"), mode)

            // 机身外壳：留白区域按机身配色画（黑=黑漆钛，钛银=拉丝钛面）
            val titan = bodyTitan
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uBodyTitan"),
                if (titan) 1f else 0f)
            if (titan) {
                val theme = com.filmcam.app.ui.BodyTheme
                GLES30.glUniform3fv(GLES30.glGetUniformLocation(program, "uBodyC"), 5, theme.bodyShadeRgb, 0)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uBodyGrain"),
                    com.filmcam.app.ui.BodyTheme.BODY_GRAIN)
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

        // 片元着色器：纯直通。
        // 取景器刻意不做任何胶片化处理（无 LUT / 无 halation / 无颗粒 / 无暗角），
        // 复刻康泰时 T3 光学取景器"所见即实景"的观感；胶片色彩只在冲洗环节产生。
        private val FRAGMENT_SHADER = """#version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;
        // GLSL ES 3.0 规范：samplerExternalOES 无默认精度，
        // 必须显式声明，否则严格驱动（含模拟器 translator）编译失败 → 黑屏
        uniform mediump samplerExternalOES uTexture;
        uniform mat4 uTexMatrix;
        uniform float uScreenAspect;
        uniform float uContentAspect;
        uniform float uFitHeight;
        uniform float uOffsetY;
        uniform float uMode;
        // 机身外壳（取景画面之外的留白）：uBodyTitan=1 时按钛面画，否则纯黑
        uniform float uBodyTitan;
        uniform vec3 uBodyC[5];    // 受光 5 档（顶/肩/中/下/底），见 BodyTheme
        uniform float uBodyGrain;  // 缎面砂感幅度
        in vec2 vTexCoord;
        out vec4 fragColor;

        // 光滑钛面：只有大面积平缓明暗，没有拉丝纹（真机是 smooth titanium）
        vec3 bodyShade(float t) {
            if (t < 0.08) return mix(uBodyC[0], uBodyC[1], t / 0.08);
            if (t < 0.42) return mix(uBodyC[1], uBodyC[2], (t - 0.08) / 0.34);
            if (t < 0.78) return mix(uBodyC[2], uBodyC[3], (t - 0.42) / 0.36);
            return mix(uBodyC[3], uBodyC[4], (t - 0.78) / 0.22);
        }

        float hash(vec2 p) {
            return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
        }

        void main() {
            float s = uScreenAspect / uContentAspect;
            float texX, texY;
            if (uFitHeight > 0.5) {
                // 横屏：内容满高 —— 纵向铺满，横向只取中间一条，左右留黑边
                float sx = uContentAspect / uScreenAspect;
                texX = (vTexCoord.x - 0.5) / sx + 0.5;
                texY = vTexCoord.y;
            } else {
                // 竖屏：内容满宽 —— 横向铺满，纵向只取中间一段，上下留黑边
                texX = vTexCoord.x;
                texY = (vTexCoord.y - 0.5) / s + 0.5 + uOffsetY;
            }

            if (texX < 0.0 || texX > 1.0 || texY < 0.0 || texY > 1.0) {
                // 画面之外是机身外壳，不是"黑边"：钛银档画裸钛面（暖香槟灰 + 细横拉丝），
                // 与 TitanBodyDrawable 同一套受光/起纹逻辑，黑钛档维持纯黑。
                if (uBodyTitan < 0.5) {
                    fragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                float t = clamp(1.0 - vTexCoord.y, 0.0, 1.0);   // 0=顶 1=底
                vec3 body = bodyShade(t);
                // 缎面砂感：按物理像素起极细颗粒，去掉"塑料感"，肉眼看不出颗粒本身
                float n = hash(floor(gl_FragCoord.xy));
                body *= 1.0 + (n - 0.5) * uBodyGrain;
                // 边缘转折：面板四周是圆角过渡到侧面的，压一点点暗，面才不死平
                float ex = min(vTexCoord.x, 1.0 - vTexCoord.x);
                float ey = min(vTexCoord.y, 1.0 - vTexCoord.y);
                float e = smoothstep(0.0, 0.025, min(ex, ey));
                body *= mix(0.94, 1.0, e);
                fragColor = vec4(body, 1.0);
                return;
            }

            // 采样映射 uMode：
            //   0 = 竖屏：走 SurfaceTexture 变换矩阵（竖屏一直正常）
            //   1 = 横屏（ROTATION_90）：只做标准 V 翻转，不旋转
            //   2 = 横屏（ROTATION_270）：V 翻转 + 180
            // 该机型（三星 HAL）在横屏会话里给出的变换矩阵是单位阵，缺了标准的
            // V 翻转 —— 只补旋转会得到「方向对但左右反」的画面，必须自己补翻转。
            vec2 uv;
            if (uMode < 0.5) {
                uv = (uTexMatrix * vec4(texX, texY, 0.0, 1.0)).xy;
            } else if (uMode < 1.5) {
                uv = vec2(texX, 1.0 - texY);
            } else {
                uv = vec2(1.0 - texX, texY);
            }
            fragColor = texture(uTexture, uv);
        }
        """.trimIndent()
    }
}
