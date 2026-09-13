package com.example.spatialanchor

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * OpenGL ES 2.0 渲染器 —— 空间锚定显示的核心。
 *
 * 渲染目标：全屏悬浮窗内的 TextureView 的 SurfaceTexture（EGL window surface），
 * 窗口层级覆盖所有应用，无状态栏/导航栏遮挡（详见 AnchorService 的窗口参数）。
 *
 * 画面变换（核心数学）：
 *  - 采集到的屏幕画面作为纹理，贴在一个位于 z=0 的全屏四边形上；
 *  - 模型矩阵 = 相对旋转矩阵 R_rel（由 [matrixProvider] 从姿态工具实时获取）；
 *  - 顶点乘以模型矩阵后在三维空间旋转，等价于「画面锚定在真实空间的初始方向」——
 *    转动手机时画面反向补偿，手机如同一个观察窗口；
 *  - 使用正交投影（无 3D 透视变形），纹理缩放比例不变；
 *  - 旋转产生的屏幕空余区域用 glClearColor(0,0,0,1) 填充纯黑。
 *
 * 线程模型：
 *  - 渲染线程独占 EGL 上下文，以 vsync 节奏（约 60Hz）循环绘制；
 *  - 采集线程通过 [pushFrame] 投递最新帧，渲染线程每帧消费一次并上传纹理；
 *  - 姿态矩阵由传感器线程写入 volatile 引用，渲染线程每帧读取，端到端延迟 < 50ms。
 */
class GLRenderer(
    private val surfaceTexture: SurfaceTexture,
    private val viewWidth: Int,
    private val viewHeight: Int
) {

    companion object {
        private const val TAG = "GLRenderer"
        /** 60Hz 帧周期（纳秒），vsync 未阻塞时用于兜底节流 */
        private const val FRAME_PERIOD_NS = 16_666_667L

        /** 正交投影矩阵（-1..1 视景体，列主序）：仅负向 z，无透视 */
        private val ORTHO = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 0f, 0f, 1f
        )

        private val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
    }

    /** 每帧渲染前读取最新相对旋转矩阵（4x4 列主序） */
    @Volatile
    var matrixProvider: (() -> FloatArray)? = null

    /** 待上传的最新屏幕帧（采集线程写入，渲染线程 getAndSet 消费） */
    private val pendingFrame = AtomicReference<FrameData?>(null)

    data class FrameData(
        val buffer: ByteBuffer,
        val width: Int,
        val height: Int,
        val rowStride: Int
    )

    // ---- GL 状态（仅渲染线程访问） ----
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uMvpLoc = 0
    private var uTextureLoc = 0
    private var screenTexture = 0
    private var quadVbo = 0
    private var quadIbo = 0
    private var textureAllocated = false

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** 采集线程投递最新帧（低延迟：覆盖旧帧，只保留最新） */
    fun pushFrame(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int) {
        pendingFrame.set(FrameData(buffer, width, height, rowStride))
    }

    /** 启动渲染线程 */
    fun start() {
        if (running) return
        running = true
        thread = thread(name = "gl-render") { runLoop() }
    }

    /** 停止渲染线程并释放 GL 资源 */
    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
        Log.i(TAG, "渲染器已停止")
    }

    // ==================== 渲染主循环 ====================

    private fun runLoop() {
        if (!initEGL()) {
            Log.e(TAG, "EGL 初始化失败，渲染线程退出")
            return
        }
        initGL()
        // 启用 vsync：eglSwapBuffers 会阻塞到垂直同步，渲染节奏与屏幕刷新率同步
        EGL14.eglSwapInterval(eglDisplay, 1)
        var lastFrameNs = System.nanoTime()

        while (running) {
            // 1. 消费最新采集帧并上传纹理
            pendingFrame.getAndSet(null)?.let { uploadFrame(it) }

            // 2. 读取最新相对旋转矩阵并绘制（无矩阵提供者时保持单位矩阵）
            draw(matrixProvider?.invoke() ?: IDENTITY)

            // 3. 交换缓冲（vsync 阻塞；未阻塞时按 60Hz 兜底节流）
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            val now = System.nanoTime()
            val elapsed = now - lastFrameNs
            lastFrameNs = now
            if (elapsed < FRAME_PERIOD_NS) {
                try {
                    Thread.sleep((FRAME_PERIOD_NS - elapsed) / 1_000_000)
                } catch (_: InterruptedException) {
                }
            }
        }
        destroyGL()
    }

    // ==================== EGL 初始化 ====================

    private fun initEGL(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

        // 配置：RGBA8888 + OpenGL ES 2.0 + Window Surface
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
            || numConfigs[0] == 0
        ) {
            Log.e(TAG, "eglChooseConfig 失败")
            return false
        }

        // ES 2.0 上下文
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return false

        // 以 TextureView 的 SurfaceTexture 为渲染目标创建 EGL window surface
        val surface = Surface(surfaceTexture)
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0], surface,
            intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) return false

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false
        // 以 EGL 表面实际尺寸设置视口（个别机型上窗口表面尺寸可能与传入参数不同，
        // 防止出现「小框」或边缘黑边）
        val sw = IntArray(1)
        val sh = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, sw, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, sh, 0)
        val surfaceW = if (sw[0] > 0) sw[0] else viewWidth
        val surfaceH = if (sh[0] > 0) sh[0] else viewHeight
        GLES20.glViewport(0, 0, surfaceW, surfaceH)
        Log.i(TAG, "EGL 表面尺寸: ${surfaceW}x${surfaceH}")
        return true
    }

    // ==================== GL 资源初始化 ====================

    private fun initGL() {
        // 顶点着色器：正交投影 × 模型矩阵（相对旋转）变换顶点
        val vertexSrc = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMVP;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVP * aPosition;
                vTexCoord = aTexCoord;
            }
        """

        // 片段着色器：直接采样屏幕纹理，并【强制 alpha = 1.0】
        // （v1.2 回归版的唯一修复：ImageReader 的 RGBA 帧 alpha 通道为 0，
        //   若不强制为 1，渲染层整体半透明，底层实时桌面会透出造成两层重合显示）
        val fragmentSrc = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = vec4(texture2D(uTexture, vTexCoord).rgb, 1.0);
            }
        """

        program = createProgram(vertexSrc, fragmentSrc)
        if (program == 0) {
            Log.e(TAG, "着色器程序创建失败")
            return
        }
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uMvpLoc = GLES20.glGetUniformLocation(program, "uMVP")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")

        // 全屏四边形（z=0）：位置(x,y,z) + 纹理坐标(u,v)
        // v 已做翻转对齐：ImageReader 首行为屏幕顶部，上传后位于纹理 v=0
        val vertices = floatArrayOf(
            //   x     y    z    u    v
            -1f,  1f, 0f, 0f, 0f,   // 左上（屏幕顶部）
             1f,  1f, 0f, 1f, 0f,   // 右上
            -1f, -1f, 0f, 0f, 1f,   // 左下（屏幕底部）
             1f, -1f, 0f, 1f, 1f    // 右下
        )
        val indices = shortArrayOf(0, 1, 2, 2, 1, 3)

        val vbo = IntArray(1)
        GLES20.glGenBuffers(1, vbo, 0)
        quadVbo = vbo[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        val vb = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(vertices).position(0)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.size * 4, vb, GLES20.GL_STATIC_DRAW)

        val ibo = IntArray(1)
        GLES20.glGenBuffers(1, ibo, 0)
        quadIbo = ibo[0]
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, quadIbo)
        val ib = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
        ib.put(indices).position(0)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, ib, GLES20.GL_STATIC_DRAW)

        // 屏幕纹理：尺寸以首帧为准（init 时仅分配占位）
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        screenTexture = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, screenTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    // ==================== 每帧绘制 ====================

    private fun draw(model: FloatArray) {
        // 旋转产生的空余区域：纯黑填充
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        // MVP = 正交投影 × 模型（相对旋转），列主序
        val mvp = multiply4x4(ORTHO, model)
        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvp, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, screenTexture)
        GLES20.glUniform1i(uTextureLoc, 0)

        // 顶点：位置(3) + 纹理(2)，stride = 20 字节
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 20, 0)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 20, 12)

        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, quadIbo)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, 0)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    /** 上传最新采集帧到 GL 纹理（采集器已保证行紧致打包 rowStride == width*4） */
    private fun uploadFrame(frame: FrameData) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, screenTexture)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        val format = GLES20.GL_RGBA
        if (!textureAllocated) {
            // 首帧分配纹理存储
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, format,
                frame.width, frame.height, 0,
                format, GLES20.GL_UNSIGNED_BYTE, frame.buffer
            )
            textureAllocated = true
        } else {
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0,
                frame.width, frame.height,
                format, GLES20.GL_UNSIGNED_BYTE, frame.buffer
            )
        }
    }

    // ==================== 资源释放 ====================

    private fun destroyGL() {
        try {
            if (screenTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(screenTexture), 0)
            if (quadVbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
            if (quadIbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(quadIbo), 0)
            if (program != 0) GLES20.glDeleteProgram(program)

            if (eglDisplay != null && eglSurface != null && eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (e: Exception) {
            Log.w(TAG, "GL 资源释放异常: ${e.message}")
        }
        eglDisplay = null
        eglContext = null
        eglSurface = null
        textureAllocated = false
    }

    // ==================== 工具方法 ====================

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vs == 0 || fs == 0) return 0

        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "着色器链接失败: " + GLES20.glGetProgramInfoLog(prog))
            return 0
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "着色器编译失败: " + GLES20.glGetShaderInfoLog(shader))
            return 0
        }
        return shader
    }

    /** 4x4 矩阵乘法（列主序）：result = a * b */
    private fun multiply4x4(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) {
                    s += a[k * 4 + row] * b[col * 4 + k]
                }
                r[col * 4 + row] = s
            }
        }
        return r
    }
}
