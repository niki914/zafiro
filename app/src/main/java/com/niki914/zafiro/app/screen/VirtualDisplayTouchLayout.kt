package com.niki914.zafiro.app.screen

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.overlay.FloatingBallMotionPredictor
import com.niki914.zafiro.remoteview.floatingball.DockSide
import com.niki914.zafiro.remoteview.floatingball.FloatingBallGeometry
import kotlin.math.hypot

/**
 * 虚拟屏小窗（Mini Window）悬浮层触摸与吸附承载布局。
 *
 * 核心特征（零系统级 Resize 架构）：
 * - 终生尺寸固定为 100dp x 220dp，永远不在运行期间动态改变 Window 尺寸；
 * - 捕获全局外部点击（[MotionEvent.ACTION_OUTSIDE]）通知焦点收起；
 * - 以 touchSlop 解耦点击与拖动；
 * - 拖拽松手时集成 [FloatingBallMotionPredictor] 进行物理减速与平滑贴边吸附；
 * - 提供 [makeVisibleAndNotifyDrawn] 机制，在与展开大窗交接时杜绝 1 帧空白闪烁。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class VirtualDisplayTouchLayout(
    context: Context,
    private val manager: WindowManager,
    private val params: WindowManager.LayoutParams,
    private val onOutsideTouch: () -> Unit,
) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private var currentX = params.x
    private var currentY = params.y
    private var animator: ValueAnimator? = null

    val ballX: Int get() = currentX
    val ballY: Int get() = currentY

    private val density: Float get() = context.resources.displayMetrics.density
    private val screenWidth: Int get() = context.resources.displayMetrics.widthPixels
    private val screenHeight: Int get() = context.resources.displayMetrics.heightPixels
    private val widthPx: Int get() = (100 * density).toInt()

    fun getDockSide(): DockSide =
        FloatingBallGeometry.resolveDockSide(currentX.toFloat(), widthPx, screenWidth)

    fun syncPosition(newX: Int, newY: Int) {
        val minY = (32 * density).toInt()
        val maxY = (screenHeight - (220 * density).toInt() - minY).coerceAtLeast(minY)
        currentX = newX.coerceIn(0, (screenWidth - widthPx).coerceAtLeast(0))
        currentY = newY.coerceIn(minY, maxY)
        params.x = currentX
        params.y = currentY
        if (isAttachedToWindow) {
            manager.updateViewLayout(this, params)
        }
    }

    /**
     * 将小窗恢复可见，并挂载绘制监听器，直到本窗口真正完成一帧绘制后才触发 [onDrawn]，
     * 确保上层全屏大窗在此之前继续遮盖，杜绝交接期间 1 帧空白造成的闪烁。
     */
    fun makeVisibleAndNotifyDrawn(onDrawn: () -> Unit) {
        alpha = 1f
        visibility = View.VISIBLE
        invalidate()
        var handled = false
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (!handled) {
                    handled = true
                    post {
                        if (viewTreeObserver.isAlive) {
                            runCatchingCancellable { viewTreeObserver.removeOnDrawListener(this) }
                        }
                        onDrawn()
                    }
                }
            }
        }
        viewTreeObserver.addOnDrawListener(listener)
        // 50ms 超时兜底，防止极端情况下未触发 draw 导致调用方挂起
        postDelayed({
            if (!handled) {
                handled = true
                if (viewTreeObserver.isAlive) {
                    runCatchingCancellable { viewTreeObserver.removeOnDrawListener(listener) }
                }
                onDrawn()
            }
        }, 50L)
    }

    private val motionPredictor: FloatingBallMotionPredictor by lazy {
        FloatingBallMotionPredictor(
            sampleWindowMs = 200L,
            maxGlideDistancePx = 95f * density,
            snapThresholdPx = 30f * density,
            screenWidthPx = screenWidth,
            ballWidthPx = widthPx,
            submergedPx = (16 * density).toInt(),
            minBallY = (32 * density).toInt(),
            maxBallY = (screenHeight - (220 * density).toInt() - (32 * density).toInt()).coerceAtLeast((32 * density).toInt()),
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                currentX = params.x
                currentY = params.y
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false
                motionPredictor.reset()
                motionPredictor.recordPoint(event.rawX, event.rawY, event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                motionPredictor.recordPoint(event.rawX, event.rawY, event.eventTime)
                val distance = hypot(
                    (event.rawX - downRawX).toDouble(),
                    (event.rawY - downRawY).toDouble(),
                ).toFloat()
                if (distance > touchSlop) {
                    dragging = true
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                motionPredictor.reset()
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                motionPredictor.recordPoint(event.rawX, event.rawY, event.eventTime)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                motionPredictor.recordPoint(event.rawX, event.rawY, event.eventTime)
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                val minY = (32 * density).toInt()
                val maxY = (screenHeight - height - minY).coerceAtLeast(minY)
                currentX += dx.toInt()
                currentY = (currentY + dy.toInt()).coerceIn(minY, maxY)
                params.x = currentX
                params.y = currentY
                if (isAttachedToWindow) manager.updateViewLayout(this, params)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    motionPredictor.recordPoint(event.rawX, event.rawY, event.eventTime)
                    snapToEdge(event.eventTime)
                }
                motionPredictor.reset()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun snapToEdge(nowMillis: Long) {
        val margin = (16 * density).toInt()
        val prediction = motionPredictor.predict(currentX, currentY, nowMillis)
        val targetX = if (prediction.targetDock.isLeft) {
            margin
        } else {
            (screenWidth - width - margin).coerceAtLeast(margin)
        }
        val minY = (32 * density).toInt()
        val maxY = (screenHeight - height - minY).coerceAtLeast(minY)
        val targetY = prediction.targetY.coerceIn(minY, maxY)

        animator?.cancel()
        val startX = currentX
        val startY = currentY
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = prediction.durationMillis.coerceIn(180L, 320L)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val frac = it.animatedValue as Float
                currentX = (startX + (targetX - startX) * frac).toInt()
                currentY = (startY + (targetY - startY) * frac).toInt()
                params.x = currentX
                params.y = currentY
                if (isAttachedToWindow) manager.updateViewLayout(this@VirtualDisplayTouchLayout, params)
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        motionPredictor.reset()
        super.onDetachedFromWindow()
    }
}
