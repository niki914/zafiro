package com.niki914.nexus.agentic.app.overlay

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import com.niki914.nexus.agentic.animation.PointerCurveMath
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.chat.agentic.accessibility.IPointerOverlay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class PointerOverlay : IPointerOverlay {

    companion object {
        private const val FADE_DURATION_MS = 300L
        private const val SWIPE_GAP_MS = 80L
        private const val POINTER_SIZE_DP = 80
    }

    // Android overlay infrastructure
    private var wm: WindowManager? = null
    private var view: ImageView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var attached = false
    private var density = 2f

    // Mutable state
    private var curX = 0f
    private var curY = 0f
    private var curHeading = PointerCurveMath.IDLE_HEADING_RAD
    private var screenW = 0
    private var screenH = 0
    private var runningAnim: ValueAnimator? = null

    private val handler = Handler(Looper.getMainLooper())

    // Pre-computed speed lookup: time fraction (0..1) -> distance fraction (0..1)
    private val speedLut: FloatArray by lazy { PointerCurveMath.buildSpeedLut() }

    // ============================================================
    // Initialization
    // ============================================================

    fun init(ctx: Context) {
        val dm = ctx.resources.displayMetrics
        density = dm.density
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        val sizePx = (POINTER_SIZE_DP * density).toInt()

        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        view = ImageView(ctx).apply {
            setImageResource(R.drawable.cursor)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        lp = WindowManager.LayoutParams(
            sizePx, sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        view?.alpha = 0f  // start invisible, animate view alpha for fade

        grantOverlayPermission(ctx.packageName)
    }

    private fun grantOverlayPermission(pkg: String) {
        Thread {
            try {
                val proc = Runtime.getRuntime().exec(
                    arrayOf("su", "-c", "appops set $pkg SYSTEM_ALERT_WINDOW allow")
                )
                proc.waitFor()
            } catch (_: Exception) {
            }
        }.start()
    }

    // ============================================================
    // IPointerOverlay
    // ============================================================

    override fun show(x: Float, y: Float) {
        handler.post {
            attachIfNeeded()
            curX = x
            curY = y
            curHeading = PointerCurveMath.IDLE_HEADING_RAD
            applyTransform(x, y, PointerCurveMath.IDLE_HEADING_RAD)
            view?.animate()?.alpha(1f)?.setDuration(FADE_DURATION_MS)?.start()
        }
    }

    override fun hide() {
        handler.post {
            view?.animate()?.alpha(0f)?.setDuration(FADE_DURATION_MS)?.withEndAction {
                detach()
            }?.start()
        }
    }

    override suspend fun animateTo(x: Float, y: Float) {
        if (!attached) return
        cancelAnim()
        val path = PointerCurveMath.buildCurve(curX, curY, curHeading, x, y)
        animatePath(path, x, y)
    }

    override suspend fun showSwipe(
        sx: Float, sy: Float, ex: Float, ey: Float, duration: Long,
    ) {
        if (!attached) return
        cancelAnim()

        // Phase 1: fly to swipe start
        val path1 = PointerCurveMath.buildCurve(curX, curY, curHeading, sx, sy)
        animatePath(path1, sx, sy)

        // Brief pause so the pointer "lands" before the stroke
        delayOnMain(SWIPE_GAP_MS)

        // Phase 2: straight-line swipe trajectory
        val path2 = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        animatePathRaw(path2, ex, ey, fixedHeading = true, durationMs = duration)
    }

    // ============================================================
    // Animation
    // ============================================================

    private suspend fun animatePath(path: Path, toX: Float, toY: Float) {
        animatePathRaw(path, toX, toY, fixedHeading = false)
    }

    /**
     * Drive a [ValueAnimator] along [path], ending at (toX,toY).
     * When [fixedHeading] is true the pointer keeps its current angle (mouse-like);
     * when false it rotates to follow the path tangent (fish-like).
     */
    private suspend fun animatePathRaw(
        path: Path, toX: Float, toY: Float, fixedHeading: Boolean,
        durationMs: Long? = null,
    ) {
        val pm = PathMeasure(path, false)
        val arcLen = pm.length
        if (arcLen <= 0f) return

        val duration = durationMs ?: PointerCurveMath.curveDurationMs(arcLen)
        val startHeading = curHeading

        suspendCancellableCoroutine<Unit> { cont ->
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                interpolator = LinearInterpolator()
                this.duration = duration
                addUpdateListener {
                    val timeFrac = it.animatedValue as Float
                    val distFrac = PointerCurveMath.timeToDistance(timeFrac, speedLut)
                    val sample = PointerCurveMath.sampleCurve(path, distFrac)
                    val h = if (fixedHeading) startHeading else sample.headingRad
                    handler.post {
                        curX = sample.x
                        curY = sample.y
                        curHeading = h
                        applyTransform(sample.x, sample.y, h)
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        runningAnim = null
                        val h = if (fixedHeading) startHeading else PointerCurveMath.IDLE_HEADING_RAD
                        curX = toX
                        curY = toY
                        curHeading = h
                        handler.post { applyTransform(toX, toY, h) }
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onAnimationCancel(a: android.animation.Animator) {
                        runningAnim = null
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
            }
            cont.invokeOnCancellation {
                handler.post {
                    if (runningAnim === anim) {
                        anim.cancel()
                        runningAnim = null
                    }
                }
            }
            handler.post {
                if (cont.isActive) {
                    runningAnim = anim
                    anim.start()
                }
            }
        }
    }

    private fun cancelAnim() {
        val anim = runningAnim
        runningAnim = null
        if (anim != null) {
            handler.post { anim.cancel() }
        }
    }

    // ============================================================
    // Window management
    // ============================================================

    private fun attachIfNeeded() {
        if (attached || wm == null || view == null || lp == null) return
        try {
            wm!!.addView(view, lp)
            attached = true
        } catch (_: Exception) {
        }
    }

    private fun detach() {
        if (!attached) return
        try {
            wm?.removeView(view)
        } catch (_: Exception) {
        }
        attached = false
    }

    private fun applyTransform(x: Float, y: Float, headingRad: Float) {
        val p = lp ?: return
        val half = (POINTER_SIZE_DP * density / 2f).toInt()
        p.x = x.toInt() - half
        p.y = y.toInt() - half
        // Drawable tip naturally points top-left; subtract IDLE_HEADING_RAD to
        // convert absolute screen heading to a rotation relative to default.
        view?.rotation = Math.toDegrees((headingRad - PointerCurveMath.IDLE_HEADING_RAD).toDouble()).toFloat()
        try {
            if (attached) wm?.updateViewLayout(view, p)
        } catch (_: Exception) {
        }
    }

    private suspend fun delayOnMain(ms: Long) {
        suspendCancellableCoroutine<Unit> { cont ->
            handler.postDelayed({ if (cont.isActive) cont.resume(Unit) }, ms)
        }
    }
}
