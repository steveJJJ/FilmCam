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
            renderTo(surfaceWidth, surfaceHeight, offsetY = -0.08f)  // 画面上移一点，贴近 T3 取景视野
        }

        /** 在指定分辨率下渲染一帧。
         *  注意：shader 里不能残留未绑定纹理的 sampler 声明——部分真机驱动
         *  （Adreno/麒麟）会因 sampler3D 无有效绑定直接让整次 draw 失败（黑屏），
         *  模拟器软件渲染则不受影响。胶片调色已完全移交 Vulkan 冲洗引擎，
         *  取景器侧保持直通，不再保留任何后处理分支。 */
        fun renderTo(viewW: Int, viewH: Int, offsetY: Float = 0f) {
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
        uniform float uOffsetY;
        in vec2 vTexCoord;
        out vec4 fragColor;

        void main() {
            float s = uScreenAspect / uContentAspect;
            float contentY = (vTexCoord.y - 0.5) / s + 0.5 + uOffsetY;

            if (contentY < 0.0 || contentY > 1.0) {
                fragColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }

            vec2 uv = (uTexMatrix * vec4(vTexCoord.x, contentY, 0.0, 1.0)).xy;
            fragColor = texture(uTexture, uv);
        }
        """.trimIndent()
    }
}
