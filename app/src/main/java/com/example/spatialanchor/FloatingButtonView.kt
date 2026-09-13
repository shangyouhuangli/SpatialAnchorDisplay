package com.example.spatialanchor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.abs

/**
 * 悬浮控制按钮。
 *
 * 样式：半透明白色圆形（直径 48dp，透明度 50%），默认文字「启动」。
 * 交互：
 *  - 自由拖拽：ACTION_MOVE 跟随手指更新窗口位置；
 *  - 边缘吸附：ACTION_UP 后自动吸附到屏幕左/右边缘（带 180ms 动画）；
 *  - 点击切换模式：位移小于阈值视为点击（触发 [onClick]），否则视为拖拽。
 *
 * 位置状态由本 View 维护，实际窗口移动通过 [onPositionUpdate] 回调交给宿主 Service
 * （内部调用 WindowManager.updateViewLayout）。
 */
class FloatingButtonView(context: Context) : TextView(context) {

    companion object {
        private const val TAG = "FloatingButton"
        /** 按钮直径 48dp */
        private const val SIZE_DP = 48f
        /** 白色底色透明度 50% */
        private const val BG_ALPHA = 0.5f
        /** 点击与拖拽的判定阈值（位移小于该值视为点击） */
        private const val CLICK_SLOP_DP = 8f
        /** 边缘吸附动画时长 */
        private const val EDGE_ANIM_MS = 180L
    }

    /** 拖拽/吸附过程中更新窗口坐标（由宿主实现 updateViewLayout） */
    var onPositionUpdate: ((x: Int, y: Int) -> Unit)? = null

    /** 点击回调（未开启→请求开启；已开启→停止） */
    var onClick: (() -> Unit)? = null

    /** 当前窗口坐标（本 View 维护，宿主 addView 后同步一次） */
    private var currentX = 0
    private var currentY = 0

    // 手势状态
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var initX = 0
    private var initY = 0
    private var dragging = false
    private var edgeAnimator: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val buttonSize = (SIZE_DP * density).toInt()

    init {
        // 半透明白色圆形背景（透明度 50%）
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.argb((255 * BG_ALPHA).toInt(), 255, 255, 255))
        background = bg

        // 默认文字「启动」
        text = "启动"
        setTextColor(Color.BLACK)
        textSize = 12f
        gravity = Gravity.CENTER
        isClickable = true
    }

    /** 宿主 addView 后同步初始位置 */
    fun syncPosition(x: Int, y: Int) {
        currentX = x
        currentY = y
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeAnimator?.cancel()
                downX = event.x
                downY = event.y
                downRawX = event.rawX
                downRawY = event.rawY
                initX = currentX
                initY = currentY
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                // 超过阈值才判定为拖拽，避免误触
                if (!dragging && (abs(dx) > CLICK_SLOP_DP * density || abs(dy) > CLICK_SLOP_DP * density)) {
                    dragging = true
                }
                if (dragging) {
                    currentX = (initX + dx).toInt()
                    currentY = (initY + dy).toInt()
                    clampToScreen()
                    onPositionUpdate?.invoke(currentX, currentY)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    snapToEdge()
                } else {
                    onClick?.invoke()
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 松手后吸附到左右边缘 */
    private fun snapToEdge() {
        val screenW = resources.displayMetrics.widthPixels
        val targetX = if (currentX + buttonSize / 2 < screenW / 2) 0 else screenW - buttonSize
        edgeAnimator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = EDGE_ANIM_MS
            addUpdateListener { anim ->
                currentX = anim.animatedValue as Int
                onPositionUpdate?.invoke(currentX, currentY)
            }
            start()
        }
    }

    /** 限制按钮不出屏（Y 方向也做保护） */
    private fun clampToScreen() {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        if (currentX < 0) currentX = 0
        if (currentX > screenW - buttonSize) currentX = screenW - buttonSize
        if (currentY < 0) currentY = 0
        if (currentY > screenH - buttonSize) currentY = screenH - buttonSize
    }
}
