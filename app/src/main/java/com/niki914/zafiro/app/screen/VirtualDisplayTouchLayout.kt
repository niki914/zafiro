package com.niki914.zafiro.app.screen

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.niki914.zafiro.app.overlay.FloatingBallMotionPredictor
import kotlin.math.hypot

/**
 * 虚拟屏小窗悬浮层触摸与吸附承载布局。
 *
 * 职责：
 * - 捕获全局外部点击（[MotionEvent.ACTION_OUTSIDE]）通知焦点收起；
 * - 以 touchSlop 解耦点击与拖动；
 * - 拖拽松手时集成 [FloatingBallMotionPredictor] 进行物理减速与平滑贴边吸附。
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

    private val density: Float get() = context.resources.displayMetrics.density
    private val screenWidth: Int get() = context.resources.displayMetrics.widthPixels
    private val screenHeight: Int get() = context.resources.displayMetrics.heightPixels
    private val widthPx: Int get() = (100 * density).toInt()

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
