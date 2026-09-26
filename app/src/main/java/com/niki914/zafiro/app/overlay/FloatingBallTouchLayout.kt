package com.niki914.zafiro.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import com.niki914.zafiro.remoteview.floatingball.DockSide
import com.niki914.zafiro.remoteview.floatingball.FloatingBallGeometry
import com.niki914.zafiro.remoteview.floatingball.FloatingBallTokens
import kotlin.math.hypot

/**
 * 小球窗口触摸承载（专职小球拖动、贴边吸附与淹没）。
 */
@SuppressLint("ViewConstructor")
internal class FloatingBallTouchLayout(
    context: Context,
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams,
    initialBallX: Int,
    initialBallY: Int,
    private val dockSideProvider: () -> DockSide,
    private val isSubmergedProvider: () -> Boolean,
    private val onRequestExpand: () -> Unit,
    private val onDockSideChanged: (DockSide) -> Unit,
    private val onSnapFinished: (DockSide, Boolean) -> Unit,
    private val onPositionUpdated: (Float) -> Unit,
) : FrameLayout(context) {

    private val dockSide: DockSide get() = dockSideProvider()
    private val isSubmerged: Boolean get() = isSubmergedProvider()

    var ballX: Int = initialBallX
        private set
    var ballY: Int = initialBallY
        private set

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var isDragging = false
    private var positionAnimator: ValueAnimator? = null

    private val density: Float get() = context.resources.displayMetrics.density
    private val ballWidthPx: Int get() = (FloatingBallTokens.buttonDiameter * density).toInt()
    private val ballHeightPx: Int get() = (FloatingBallTokens.buttonDiameter * density).toInt()
    private val submergedPx: Int get() = (FloatingBallTokens.submergedOffset * density).toInt()
    private val snapThresholdPx: Float get() = FloatingBallTokens.snapThreshold * density
    private val escapeDistancePx: Int get() = (FloatingBallTokens.escapeSnapDistance * density).toInt()

    private val minBallY: Int get() = ((32 + FloatingBallTokens.anchorY) * density).toInt()
    private val maxBallY: Int get() = (context.resources.displayMetrics.heightPixels - (48 + FloatingBallTokens.expandedHeight - FloatingBallTokens.anchorY) * density).toInt()

    fun syncPosition(newX: Int, newY: Int) {
        ballX = newX
        ballY = newY.coerceIn(minBallY, maxBallY)
        lp.x = ballX
        lp.y = ballY
        val screenWidth = context.resources.displayMetrics.widthPixels
        val newDock = FloatingBallGeometry.resolveDockSide(ballX.toFloat(), ballWidthPx, screenWidth)
        if (newDock != dockSide) {
            onDockSideChanged(newDock)
        }
        if (isAttachedToWindow) {
            wm.updateViewLayout(this, lp)
        }
    }

    fun snapToEdge(dock: DockSide) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val targetX = if (dock.isLeft) -submergedPx else screenWidth - (ballWidthPx - submergedPx)
        onDockSideChanged(dock)
        animateBallTo(targetX, ballY) {
            onSnapFinished(dock, true)
        }
    }

    fun applySubmerged(dock: DockSide) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        ballX = if (dock.isLeft) -submergedPx else screenWidth - (ballWidthPx - submergedPx)
        lp.x = ballX
        if (isAttachedToWindow) {
            wm.updateViewLayout(this, lp)
        }
    }

    private val motionPredictor: FloatingBallMotionPredictor by lazy {
        val screenWidth = context.resources.displayMetrics.widthPixels
        FloatingBallMotionPredictor(
            sampleWindowMs = 200L,
            maxGlideDistancePx = 95f * density,
            snapThresholdPx = snapThresholdPx,
            screenWidthPx = screenWidth,
            ballWidthPx = ballWidthPx,
            submergedPx = submergedPx,
            minBallY = minBallY,
            maxBallY = maxBallY,
        )
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                positionAnimator?.cancel()
                motionPredictor.reset()
                motionPredictor.recordPoint(ev.rawX, ev.rawY, ev.eventTime)
                downRawX = ev.rawX
                downRawY = ev.rawY
                lastRawX = ev.rawX
                lastRawY = ev.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                motionPredictor.recordPoint(ev.rawX, ev.rawY, ev.eventTime)
                val dist = hypot((ev.rawX - downRawX).toDouble(), (ev.rawY - downRawY).toDouble()).toFloat()
                if (dist > touchSlop) {
                    isDragging = true
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                motionPredictor.reset()
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                motionPredictor.recordPoint(ev.rawX, ev.rawY, ev.eventTime)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                motionPredictor.recordPoint(ev.rawX, ev.rawY, ev.eventTime)
                val dx = ev.rawX - lastRawX
                val dy = ev.rawY - lastRawY
                lastRawX = ev.rawX
                lastRawY = ev.rawY

                val screenWidth = context.resources.displayMetrics.widthPixels

                ballX += dx.toInt()
                ballY = (ballY + dy.toInt()).coerceIn(minBallY, maxBallY)
                lp.x = ballX
                lp.y = ballY

                val newDock = FloatingBallGeometry.resolveDockSide(ballX.toFloat(), ballWidthPx, screenWidth)
                if (newDock != dockSide) {
                    onDockSideChanged(newDock)
                }

                if (isAttachedToWindow) {
                    wm.updateViewLayout(this, lp)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    isDragging = false
                    motionPredictor.recordPoint(ev.rawX, ev.rawY, ev.eventTime)
                    val prediction = motionPredictor.predict(ballX, ballY, ev.eventTime)
                    applyPrediction(prediction)
                }
                motionPredictor.reset()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    val prediction = motionPredictor.predict(ballX, ballY, ev.eventTime)
                    applyPrediction(prediction)
                }
                motionPredictor.reset()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun applyPrediction(prediction: PredictionResult) {
        onDockSideChanged(prediction.targetDock)
        animateBallTo(
            targetX = prediction.targetX,
            targetY = prediction.targetY,
            duration = prediction.durationMillis,
        ) {
            val screenHeight = context.resources.displayMetrics.heightPixels
            val currentYRatio = ballY.toFloat() / screenHeight.coerceAtLeast(1)
            onPositionUpdated(currentYRatio)
            onSnapFinished(prediction.targetDock, prediction.willSubmerge)
        }
    }

    fun requestExpand() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val distanceToLeft = ballX
        val distanceToRight = screenWidth - (ballX + ballWidthPx)

        if (isSubmerged || distanceToLeft < escapeDistancePx || distanceToRight < escapeDistancePx) {
            val escapeX = FloatingBallGeometry.calculateStableDockX(
                dockSide = dockSide,
                screenWidthPx = screenWidth,
                ballWidthPx = ballWidthPx,
                escapeDistancePx = escapeDistancePx,
            )

            animateBallTo(escapeX, ballY) {
                onSnapFinished(dockSide, false)
                onRequestExpand()
            }
        } else {
            onRequestExpand()
        }
    }

    private fun animateBallTo(
        targetX: Int,
        targetY: Int,
        duration: Long = 200L,
        onEnd: () -> Unit = {},
    ) {
        positionAnimator?.cancel()
        val startX = ballX
        val startY = ballY

        if (startX == targetX && startY == targetY) {
            onEnd()
            return
        }

        positionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val frac = it.animatedValue as Float
                ballX = (startX + (targetX - startX) * frac).toInt()
                ballY = (startY + (targetY - startY) * frac).toInt()
                lp.x = ballX
                lp.y = ballY
                if (isAttachedToWindow) {
                    wm.updateViewLayout(this@FloatingBallTouchLayout, lp)
                }
            }
            doOnEnd {
                onEnd()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        positionAnimator?.cancel()
        motionPredictor.reset()
    }
}
