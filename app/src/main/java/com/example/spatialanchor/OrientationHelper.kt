package com.example.spatialanchor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 姿态解算工具：基于系统 TYPE_ROTATION_VECTOR 旋转矢量传感器。
 *
 * 该传感器融合陀螺仪 / 加速度计 / 磁力计，输出无漂移的稳定三维姿态（四元数 / 旋转矩阵）。
 *
 * 【v1.3 核心变更 —— 刚性 2D 变换，替代原 3D 旋转】
 * 旧算法把相对旋转矩阵直接作为 3D 模型矩阵使用：手机绕屏幕法线（屏幕中心）转动时，
 * 传感器给出的相对旋转会混入倾斜分量，画面随之出现透视畸变。
 *
 * 新算法将相对旋转分解为两部分，保证画面「保持原画」：
 *  1. 绕屏幕中心的平面旋转角 θ（roll）：手机绕屏幕法线转多少度，画面就原画旋转多少度，
 *     严格平面旋转，无任何畸变；
 *  2. 倾斜平移（pitch/yaw）：手机倾斜时画面做平移（像透过窗子看固定在远处的桌面），
 *     平移量 = 焦距 F × tan(倾斜角)，F 以屏幕高度为单位（可调）；
 *  3. 不做 3D 透视、不改变纹理缩放比例；矩阵做宽高比校正，
 *     保证在【像素空间】中是严格刚性变换（旋转时画面尺寸不变）。
 *
 * 基准姿态：start() 后首个传感器事件即记录为【基准姿态 R0】——
 * 调用方在「点击启动按钮的瞬间」调用 start()，定位时机即为点击瞬间。
 *
 * 约束说明：仅处理旋转姿态变化，不响应任何位移（平移）检测。
 */
class OrientationHelper(
    context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int
) {

    companion object {
        private const val TAG = "OrientationHelper"
        /** 采样率：SENSOR_DELAY_GAME ≈ 20ms（约 50Hz，接近 60Hz 目标），与渲染节奏同步 */
        private const val SENSOR_DELAY = SensorManager.SENSOR_DELAY_GAME
        /**
         * 倾斜平移焦距（单位：屏幕高度）。
         * 平移像素 = PAN_FOCAL_SCREEN × 屏幕高度 × tan(倾斜角)。
         * 值越大，轻微倾斜时画面移动越少（画面显得越「远」）；可依手感调整。
         */
        private const val PAN_FOCAL_SCREEN = 1.0f
        /** 法线 z 分量下限：防止倾斜接近 90° 时 tan 发散导致画面飞走 */
        private const val NORMAL_Z_MIN = 0.2f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** 传感器专用线程：避免阻塞主线程 */
    private val sensorThread = HandlerThread("orientation-sensor").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    /** 基准姿态矩阵 R0（3x3 行主序）；start() 后首个事件记录，此后不再变化 */
    @Volatile
    private var baseMatrix: FloatArray? = null

    /** 当前刚性变换矩阵（4x4 列主序，OpenGL 可直接使用），默认单位矩阵 */
    @Volatile
    private var relativeMatrix: FloatArray = identity4x4()

    @Volatile
    private var started = false

    /** 设备是否支持旋转矢量传感器 */
    val isSupported: Boolean get() = sensor != null

    /** 渲染线程每帧读取的最新刚性变换矩阵（引用读取，无锁） */
    fun latestMatrix(): FloatArray = relativeMatrix

    /** 启动监听：记录基准姿态，随后持续输出刚性变换矩阵 */
    fun start() {
        if (sensor == null) {
            Log.w(TAG, "设备不支持 TYPE_ROTATION_VECTOR 传感器")
            return
        }
        baseMatrix = null          // 下一个事件即作为基准姿态（点击启动的瞬间）
        started = true
        sensorManager.registerListener(listener, sensor, SENSOR_DELAY, sensorHandler)
        Log.i(TAG, "姿态传感器已启动")
    }

    /** 停止监听（不销毁线程，释放资源由 release() 完成） */
    fun stop() {
        started = false
        sensorManager.unregisterListener(listener)
        Log.i(TAG, "姿态传感器已停止")
    }

    /** 释放全部资源（服务销毁时调用） */
    fun release() {
        stop()
        sensorThread.quitSafely()
    }

    private val listener = object : SensorEventListener {

        override fun onSensorChanged(event: SensorEvent) {
            if (!started) return

            // 旋转矢量 → 3x3 旋转矩阵（语义：world = R * device）
            val r = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(r, event.values)

            val base = baseMatrix
            if (base == null) {
                // 模式启动瞬间：记录基准姿态
                baseMatrix = r.copyOf()
                relativeMatrix = identity4x4()
                return
            }

            // 相对旋转：rel = R1^T * R0（把「基准坐标系下的点」映射到「当前设备坐标系」）
            // 推导：p_world = R0 * p_base = R1 * p_cur  =>  p_cur = R1^T * R0 * p_base
            val rel = FloatArray(9)
            for (row in 0 until 3) {
                for (col in 0 until 3) {
                    var sum = 0f
                    for (k in 0 until 3) {
                        sum += r[k * 3 + row] * base[k * 3 + col]
                    }
                    rel[row * 3 + col] = sum
                }
            }
            relativeMatrix = buildRigidMatrix(rel)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // 精度变化无需特殊处理（旋转矢量自带融合校正）
        }
    }

    /**
     * 由相对旋转 rel（3x3 行主序）构造【刚性 2D 变换】4x4 矩阵（列主序）。
     *
     * 数学推导（rel 语义：基准坐标系 → 当前设备坐标系）：
     *  - 平面旋转角 θ = atan2(rel[1][0], rel[0][0])：
     *      rel 左上 2x2 块描述基准屏幕平面在当前屏幕上的投影方向；
     *      对纯绕屏幕法线旋转，该 2x2 块恰为旋转矩阵，θ 即手机绕屏幕中心的转动角。
     *      手机转 θ，画面保持原画旋转 θ（世界固定、窗口旋转的视觉补偿）。
     *  - 倾斜平移：当前屏幕法线在基准坐标系中的分量为 rel 的第三行 (nx, ny, nz)（= rel^T·z）。
     *      手机倾斜（法线偏离基准法线）时，画面应平移，使人感觉桌面固定在真实空间：
     *      平移像素 px = -F·nx/nz, py = -F·ny/nz（F = PAN_FOCAL_SCREEN × 屏幕高度）。
     *      符号约定：手机顶部后仰（法线指向上方 ny>0）→ 画面下移（看到上方内容），
     *      符合「透过窗子看固定桌面」的直觉。
     *  - 宽高比校正：NDC 空间 x/y 轴像素密度不同，直接旋转会轻微拉伸画面；
     *      在矩阵中乘以 (h/w) 与 (w/h) 因子，保证像素空间中为严格刚性旋转。
     */
    private fun buildRigidMatrix(rel: FloatArray): FloatArray {
        // 1. 平面旋转角
        val theta = atan2(rel[3], rel[0])   // rel[3]=rel[1][0], rel[0]=rel[0][0]
        val c = cos(theta)
        val s = sin(theta)

        // 2. 当前屏幕法线在基准坐标系的分量（rel 第三行），并防 tan 发散
        val nx = rel[6]
        val ny = rel[7]
        var nz = rel[8]
        if (nz < NORMAL_Z_MIN) nz = NORMAL_Z_MIN

        // 3. 倾斜平移：像素 → NDC 坐标
        val focalPx = PAN_FOCAL_SCREEN * screenHeight
        val panXPx = -focalPx * nx / nz
        val panYPx = -focalPx * ny / nz
        val panX = 2f * panXPx / screenWidth
        val panY = 2f * panYPx / screenHeight

        val aspectX = screenWidth.toFloat() / screenHeight
        val aspectY = screenHeight.toFloat() / screenWidth

        // 4x4 列主序：[列0]=x轴 [列1]=y轴 [列2]=z轴 [列3]=平移
        // 旋转分量含宽高比校正（A·R·A⁻¹，A=像素→NDC 对角阵），像素空间严格刚性：
        //   M(row-major) = [[c, -(h/w)s], [(w/h)s, c]]
        //   即列0=(c, (w/h)s, 0, 0)，列1=(-(h/w)s, c, 0, 0)
        return floatArrayOf(
            c, aspectX * s, 0f, 0f,
            -aspectY * s, c, 0f, 0f,
            0f, 0f, 1f, 0f,
            panX, panY, 0f, 1f
        )
    }

    /** 单位矩阵（4x4 列主序） */
    private fun identity4x4(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )
}
