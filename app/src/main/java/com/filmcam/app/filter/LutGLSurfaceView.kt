package com.filmcam.app.filter

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
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

    /** 设置临时色调（后续替换真实 LUT 后删掉） */
    fun setTint(r: Float, g: Float, b: Float) {
        queueEvent { renderer?.tintR = r; renderer?.tintG = g; renderer?.tintB = b }
    }

    /** 设置胶片颗粒强度 0.0~1.0 */
    fun setGrain(strength: Float) {
        queueEvent { renderer?.grainStrength = strength }
    }

    inner class LutRenderer : GLSurfaceView.Renderer {

        private var program = 0
        private var oesTexId = 0
        private var surfaceTexture: SurfaceTexture? = null
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
            renderTo(surfaceWidth, surfaceHeight, applyPostFX = false, offsetY = -0.08f)  // 预览原相机，画面上移一点
        }

        /** 在指定分辨率下渲染一帧。
         *  注意：shader 里不能残留未绑定纹理的 sampler 声明——部分真机驱动
         *  （Adreno/麒麟）会因 sampler3D 无有效绑定直接让整次 draw 失败（黑屏），
         *  模拟器软件渲染则不受影响。LUT 调色已移交 Vulkan 冲洗引擎。 */
        fun renderTo(viewW: Int, viewH: Int, applyPostFX: Boolean = true, offsetY: Float = 0f) {
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

            // 后处理效果开关（halation/grain/vignette）
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uPostFX"),
                if (applyPostFX) 1f else 0f)

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

        // 片元着色器：halation + 胶片颗粒 + 暗角（LUT 调色在冲洗引擎侧做）
        private val FRAGMENT_SHADER = """#version 300 es
        #extension GL_OES_EGL_image_external_essl3 : require
        precision mediump float;
        // GLSL ES 3.0 规范：samplerExternalOES 无默认精度，
        // 必须显式声明，否则严格驱动（含模拟器 translator）编译失败 → 黑屏
        uniform mediump samplerExternalOES uTexture;
        uniform mat4 uTexMatrix;
        uniform float uScreenAspect;
        uniform float uContentAspect;
        uniform float uOffsetY;
        uniform vec3 uTint;
        uniform float uGrain;
        uniform float uPostFX;
        in vec2 vTexCoord;
        out vec4 fragColor;

        // 高质量 hash 噪声
        float hash(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
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
