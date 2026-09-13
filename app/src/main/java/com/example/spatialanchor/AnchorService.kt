package com.example.spatialanchor

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.nio.ByteBuffer

/**
 * 前台服务：应用核心 —— 悬浮窗管理、空间锚定模式的全生命周期控制。
 *
 * 【v1.2 修复与变更】
 *  1. 真实桌面映射：点击「启动」→ 立即记录基准姿态（定位时机 = 点击瞬间）→ 录屏授权
 *     → 抓取【一帧纯净的真实桌面】（此时全屏渲染层尚未出现）→ 停止采集 → 上渲染层。
 *     全屏悬浮层无法被自身 MediaProjection 排除（FLAG_SECURE 会把整屏抠成黑色，
 *     即此前黑框根因），因此「固定画」方案是唯一干净实现：桌面成为真实空间中的一幅
 *     固定画，手机转动角度时画面反向补偿，空余区域纯黑。
 *  2. 全屏渲染层移除 FLAG_SECURE；悬浮按钮保留 FLAG_SECURE（避免按钮残影进入固定画）。
 *
 * 状态机：
 *  - 未开启：桌面只有悬浮按钮（「启动」）；点击 → 记录基准姿态 + 申请录屏授权；
 *  - 已开启：悬浮按钮变「停止」；全屏渲染层叠加所有应用之上，固定画锚定真实空间；
 *    点击 → 按序释放 渲染 → 悬浮层 → 录屏 → 传感器 全部资源。
 */
class AnchorService : Service() {

    companion object {
        private const val TAG = "AnchorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "anchor_service"
        /** 首帧采集超时（毫秒）：超时说明录屏未出帧，清理并提示 */
        private const val FIRST_FRAME_TIMEOUT_MS = 3000L

        const val ACTION_START_SERVICE = "com.example.spatialanchor.action.START_SERVICE"
        const val ACTION_START_CAPTURE = "com.example.spatialanchor.action.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.example.spatialanchor.action.STOP_CAPTURE"

        /** 当前是否处于空间锚定模式（静态，供界面/通知判断） */
        var isCapturing = false
            private set

        /** 单位矩阵（4x4 列主序），供姿态工具尚未就绪时兜底 */
        private val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
    }

    private lateinit var windowManager: WindowManager
    private lateinit var projectionManager: MediaProjectionManager

    /** 主线程 Handler：用于首帧回调后的 UI 操作与超时任务 */
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- 悬浮控制按钮 ----
    private var floatingButton: FloatingButtonView? = null

    // ---- 空间锚定模式组件 ----
    private var orientationHelper: OrientationHelper? = null
    private var screenCapturer: ScreenCapturer? = null
    private var glRenderer: GLRenderer? = null
    private var overlayTextureView: TextureView? = null
    private var mediaProjection: MediaProjection? = null

    // ---- 启动瞬间抓取的真实桌面帧（固定画） ----
    private var pendingFirstFrame: ByteBuffer? = null
    private var pendingFirstFrameW = 0
    private var pendingFirstFrameH = 0
    private var firstFrameReceived = false
    private var captureTimeoutRunnable: Runnable? = null

    // ---- 屏幕指标（虚拟显示与渲染层尺寸） ----
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDpi = 0

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        queryScreenMetrics()
        // 前台服务契约：先进入前台（常驻通知栏）
        startAsForeground()
        // 【v1.1 加固】悬浮窗权限自检：缺失时引导重新授权并退出，
        // 避免后续 addView 抛 BadTokenException 导致服务/应用崩溃
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "悬浮窗权限缺失，引导重新授权")
            try {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
            stopSelf()
            return
        }
        showFloatingButton()
        Log.i(TAG, "服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> handleStartCaptureClick()
            ACTION_STOP_CAPTURE -> stopCapture()
        }
        // 服务被杀后自动重建（START_STICKY），悬浮按钮随之恢复
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 兜底释放：无论处于何种状态，全部资源按序清理
        stopCapture()
        removeFloatingButton()
        super.onDestroy()
        Log.i(TAG, "服务已销毁")
    }

    // ==================== 前台服务与通知 ====================

    /** 启动为前台服务（mediaProjection 类型，常驻通知栏防回收） */
    private fun startAsForeground() {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)

            // 通知上的「停止」按钮
            val stopIntent = Intent(this, AnchorService::class.java).setAction(ACTION_STOP_CAPTURE)
            val stopPi = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .setContentIntent(stopPi)
                .addAction(0, getString(R.string.action_stop), stopPi)
                .build()

            // API 29+ 必须声明 foregroundServiceType=mediaProjection（清单中已声明）
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } catch (e: Exception) {
            // 前台服务启动失败不应导致进程崩溃（记录日志，系统会按超时规则处理）
            Log.e(TAG, "前台服务启动失败: ${e.message}")
        }
    }

    // ==================== 悬浮控制按钮 ====================

    /** 添加悬浮按钮（48dp 半透明白色圆形，可拖拽、边缘吸附） */
    private fun showFloatingButton() {
        if (floatingButton != null) return
        try {
            val btn = FloatingButtonView(this)
            val size = (48 * resources.displayMetrics.density).toInt()

            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SECURE, // 按钮不进入固定画画面，避免残影
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = 24
            lp.y = (resources.displayMetrics.heightPixels * 0.35f).toInt()
            btn.syncPosition(lp.x, lp.y)

            // 拖拽/吸附：更新窗口坐标
            btn.onPositionUpdate = { x, y ->
                lp.x = x
                lp.y = y
                try {
                    windowManager.updateViewLayout(btn, lp)
                } catch (_: Exception) {
                }
            }
            // 点击：模式切换（未开启→开启；已开启→停止）
            btn.onClick = {
                if (isCapturing) stopCapture() else handleStartCaptureClick()
            }

            windowManager.addView(btn, lp)
            floatingButton = btn
        } catch (e: Exception) {
            Log.e(TAG, "添加悬浮按钮失败: ${e.message}")
            floatingButton = null
        }
    }

    private fun removeFloatingButton() {
        floatingButton?.let { btn ->
            try {
                windowManager.removeView(btn)
            } catch (_: Exception) {
            }
        }
        floatingButton = null
    }

    // ==================== 模式切换：开启 ====================

    /** 点击「启动」：记录基准姿态 → 申请录屏授权 */
    private fun handleStartCaptureClick() {
        if (isCapturing) return
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "悬浮窗权限已失效，请重新打开应用", Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
            return
        }
        // 【v1.2】定位时机：点击「启动」的瞬间记录基准姿态（而非打开应用/授权完成时）
        if (!startOrientationForCapture()) return
        requestProjection()
    }

    /** 点击「启动」时立即启动姿态监听，首个传感器事件即基准姿态 */
    private fun startOrientationForCapture(): Boolean {
        val orientation = OrientationHelper(this)
        if (!orientation.isSupported) {
            Toast.makeText(this, "设备不支持旋转矢量传感器", Toast.LENGTH_SHORT).show()
            return false
        }
        orientationHelper = orientation
        orientation.start()
        Log.i(TAG, "基准姿态已记录（点击启动瞬间）")
        return true
    }

    /** 启动透明授权 Activity，弹出 MediaProjection 录屏授权对话框 */
    private fun requestProjection() {
        MediaProjectionRequestActivity.callback = { resultCode, data ->
            if (resultCode == Activity.RESULT_OK && data != null) {
                startCapture(resultCode, data)
            } else {
                // 授权被取消：释放已启动的姿态监听，回到未开启状态
                orientationHelper?.release()
                orientationHelper = null
                Toast.makeText(this, "未获得屏幕录制授权", Toast.LENGTH_SHORT).show()
            }
        }
        try {
            val intent = Intent(this, MediaProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "启动授权页失败: ${e.message}")
            orientationHelper?.release()
            orientationHelper = null
            Toast.makeText(this, "无法启动录屏授权，请从桌面重新打开应用", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 授权通过：抓取一帧真实桌面作为「固定画」，随后上全屏渲染层。
     * 流程：MediaProjection → 虚拟显示首帧（此刻无渲染层，画面纯净）→ 停止采集
     *       → 添加渲染层 → 渲染线程消费固定画 + 实时姿态矩阵。
     */
    private fun startCapture(resultCode: Int, data: Intent) {
        if (isCapturing) return

        // 1. 创建 MediaProjection（Android 14 要求：此前前台服务须已以 mediaProjection 类型运行）
        val projection = projectionManager.getMediaProjection(resultCode, data)
            ?: run {
                Toast.makeText(this, "创建录屏会话失败", Toast.LENGTH_SHORT).show()
                orientationHelper?.release()
                orientationHelper = null
                return
            }
        mediaProjection = projection
        // 用户从系统界面撤销授权时自动退出模式
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection 被系统停止")
                stopCapture()
            }
        }, null)

        // 2. 抓取首帧：此时全屏渲染层尚未添加，捕获到的是真实桌面（不含悬浮层）
        firstFrameReceived = false
        screenCapturer = ScreenCapturer(
            projection, screenWidth, screenHeight, screenDpi
        ) { buffer, w, h, stride ->
            if (!firstFrameReceived) {
                firstFrameReceived = true
                pendingFirstFrame = buffer
                pendingFirstFrameW = w
                pendingFirstFrameH = h
                captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                mainHandler.post {
                    // 首帧已就绪：停止采集，上渲染层
                    screenCapturer?.stop()
                    screenCapturer = null
                    showRenderingLayer()
                }
            }
        }.also { it.start() }

        // 3. 首帧超时保护：录屏未出帧则清理退出
        val timeout = Runnable {
            if (!firstFrameReceived) {
                Log.w(TAG, "首帧采集超时")
                Toast.makeText(this, "屏幕采集超时，请重试", Toast.LENGTH_SHORT).show()
                stopCapture()
            }
        }
        captureTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, FIRST_FRAME_TIMEOUT_MS)
    }

    /** 首帧就绪后：添加全屏渲染层并进入锚定状态 */
    private fun showRenderingLayer() {
        addOverlayTextureView()
        isCapturing = true
        floatingButton?.text = "停止"
        Toast.makeText(this, "空间锚定模式已开启", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "空间锚定模式已开启")
    }

    /**
     * 添加全屏悬浮渲染层。
     * 窗口参数说明：
     *  - TYPE_APPLICATION_OVERLAY：覆盖所有应用之上；
     *  - FLAG_LAYOUT_IN_SCREEN / FLAG_LAYOUT_NO_LIMITS：无状态栏/导航栏遮挡；
     *  - FLAG_NOT_TOUCHABLE：触摸事件透传给下层应用；
     *  - 【v1.2】不再使用 FLAG_SECURE：它会令本窗口在 MediaProjection 采集中被
     *    整块抠成黑色（此前黑框根因）；固定画在采集首帧后已定格，无反馈回路风险。
     */
    private fun addOverlayTextureView() {
        try {
            val tv = TextureView(this)
            tv.setOpaque(false) // 画面完全由 GL 输出（黑色背景由 glClear 填充）

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = 0
            lp.y = 0

            // SurfaceTexture 就绪后启动 GL 渲染器
            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    surface.setDefaultBufferSize(screenWidth, screenHeight)
                    startRenderer(surface)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }

            windowManager.addView(tv, lp)
            overlayTextureView = tv
        } catch (e: Exception) {
            Log.e(TAG, "添加渲染悬浮窗失败: ${e.message}")
            stopCapture()
        }
    }

    /** 创建 GL 渲染器：矩阵来源为姿态工具，画面来源为启动瞬间抓取的固定画 */
    private fun startRenderer(surface: SurfaceTexture) {
        val renderer = GLRenderer(surface, screenWidth, screenHeight)
        renderer.matrixProvider = { orientationHelper?.latestMatrix() ?: IDENTITY }
        // 喂入固定画（渲染线程启动后自动上传纹理）
        pendingFirstFrame?.let {
            renderer.pushFrame(it, pendingFirstFrameW, pendingFirstFrameH, pendingFirstFrameW * 4)
        }
        renderer.start()
        glRenderer = renderer
    }

    // ==================== 模式切换：停止 ====================

    /**
     * 退出空间锚定模式：按创建逆序释放全部资源，避免内存泄漏。
     * 渲染(EGL/线程) → 悬浮渲染层 → 屏幕采集(虚拟显示/ImageReader) → 姿态传感器 → MediaProjection
     */
    private fun stopCapture() {
        if (!isCapturing && glRenderer == null && screenCapturer == null && pendingFirstFrame == null) {
            return
        }
        isCapturing = false
        captureTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        captureTimeoutRunnable = null

        // 1. 停止渲染线程并释放 GL 资源（必须先于移除窗口，避免使用已销毁的 SurfaceTexture）
        glRenderer?.stop()
        glRenderer = null

        // 2. 移除全屏渲染层
        overlayTextureView?.let { tv ->
            try {
                windowManager.removeView(tv)
            } catch (_: Exception) {
            }
        }
        overlayTextureView = null

        // 3. 停止屏幕采集（释放虚拟显示与 ImageReader）
        screenCapturer?.stop()
        screenCapturer = null

        // 4. 停止姿态传感器
        orientationHelper?.release()
        orientationHelper = null

        // 5. 停止 MediaProjection（触发 onStop 回调，因 isCapturing 已复位而不会重复清理）
        mediaProjection?.stop()
        mediaProjection = null

        // 6. 清空固定画缓冲
        pendingFirstFrame = null
        firstFrameReceived = false

        floatingButton?.text = "启动"
        Toast.makeText(this, "空间锚定模式已停止", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "空间锚定模式已停止，资源已全部释放")
    }

    // ==================== 工具方法 ====================

    /** 获取全屏尺寸与 DPI（虚拟显示需要与物理屏幕一致） */
    private fun queryScreenMetrics() {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        screenDpi = resources.displayMetrics.densityDpi
        Log.i(TAG, "屏幕尺寸: ${screenWidth}x${screenHeight}@${screenDpi}dpi")
    }
}
