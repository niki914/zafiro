package com.niki914.nexus.agentic.app.overlay

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
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.chat.agentic.accessibility.IPointerOverlay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.coroutines.resume

class PointerOverlay : IPointerOverlay {

    // ============================================================
    // Tunable constants — tweak these to adjust feel
    // ============================================================
    companion object {
        // --- Curve shape ---
        /** Min control-point distance as fraction of path length (with-tip shots) */
        private const val CTRL_DIST_MIN_FRAC = 0.15f
        /** Max control-point distance as fraction of path length (against-tip arcs) */
        private const val CTRL_DIST_MAX_FRAC = 0.55f
        /** Max perpendicular offset fraction for figure-8 side-sweep */
        private const val CTRL_PERP_FRAC = 0.25f

        // --- Speed profile (slow -> fast -> slow) ---
        private const val MAX_SPEED_PX_PER_S = 1500f
        private const val MIN_SPEED_PX_PER_S = 200f
        private const val ACCEL_FRAC = 0.20f
        private const val DECEL_FRAC = 0.30f
        private const val SPEED_LUT_SIZE = 100

        // --- Timing ---
        private const val FADE_DURATION_MS = 300L
        private const val SWIPE_GAP_MS = 80L

        // --- Visual ---
        private const val POINTER_SIZE_DP = 80
    }

    /** Idle heading: pointer tip faces top-left (-135°, rad) */
    private val IDLE_HEADING = (-Math.PI * 3.0 / 4.0).toFloat()

    // Android overlay infrastructure
    private var wm: WindowManager? = null
    private var view: ImageView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var attached = false
    private var density = 2f

    // Mutable state
    private var curX = 0f
    private var curY = 0f
    private var curHeading = IDLE_HEADING
    private var screenW = 0
    private var screenH = 0
    private var runningAnim: ValueAnimator? = null

    private val handler = Handler(Looper.getMainLooper())

    // Pre-computed speed lookup: time fraction (0..1) -> distance fraction (0..1)
    private val speedLut: FloatArray by lazy { buildSpeedLut() }

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
            } catch (_: Exception) {}
        }.start()
    }

    // ============================================================
    // IPointerOverlay
    // ============================================================

    override fun show(x: Float, y: Float) {
        handler.post {
            attachIfNeeded()
            curX = x; curY = y; curHeading = IDLE_HEADING
            applyTransform(x, y, IDLE_HEADING)
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
        val path = buildCurve(curX, curY, curHeading, x, y)
        animatePath(path, x, y)
    }

    override suspend fun showSwipe(
        sx: Float, sy: Float, ex: Float, ey: Float, duration: Long,
    ) {
        if (!attached) return
        cancelAnim()

        // Phase 1: fly to swipe start
        val path1 = buildCurve(curX, curY, curHeading, sx, sy)
        animatePath(path1, sx, sy)

        // Brief pause so the pointer "lands" before the stroke
        delayOnMain(SWIPE_GAP_MS)

        // Phase 2: straight-line swipe trajectory
        val path2 = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        animatePathRaw(path2, ex, ey, fixedHeading = true)
    }

    // ============================================================
    // Curve construction
    // ============================================================

    /**
     * Builds a cubic bezier from (x1,y1) to (x2,y2) with direction-aware
     * control points.
     *
     * - When the pointer flies with its tip (heading aligns with target
     *   direction), the curve is nearly straight.
     * - When flying against the tip, control points push far out and a
     *   perpendicular offset adds a figure-8 side-sweep, creating a big
     *   banking arc.
     *
     * The bezier is constructed so both boundary tangents point in the
     * [heading] direction, which is the pointer's idle tip direction
     * (top-left). This means the pointer arrives and departs pointing
     * top-left.
     */
    private fun buildCurve(
        x1: Float, y1: Float, heading: Float,
        x2: Float, y2: Float,
    ): Path {
        val dx = x2 - x1
        val dy = y2 - y1
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < 2f) return Path().apply { moveTo(x1, y1); lineTo(x2, y2) }

        val hx = cos(heading)
        val hy = sin(heading)

        // Unit vector toward target
        val tx = dx / dist
        val ty = dy / dist

        // dot = 1 when flying with the tip, -1 when against
        val dot = hx * tx + hy * ty
        val arcFactor = ((1f - dot) / 2f).coerceIn(0f, 1f)

        // Perpendicular to heading (clockwise 90°), for figure-8 swoop
        val px = -hy
        val py = hx

        val ctrlDist = (CTRL_DIST_MIN_FRAC + arcFactor * (CTRL_DIST_MAX_FRAC - CTRL_DIST_MIN_FRAC)) * dist
        val perpDist = arcFactor * CTRL_PERP_FRAC * dist

        return Path().apply {
            moveTo(x1, y1)
            cubicTo(
                x1 + hx * ctrlDist + px * perpDist, y1 + hy * ctrlDist + py * perpDist,
                x2 - hx * ctrlDist - px * perpDist, y2 - hy * ctrlDist - py * perpDist,
                x2, y2,
            )
        }
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
    ) {
        val pm = PathMeasure(path, false)
        val arcLen = pm.length
        if (arcLen <= 0f) return

        val pos = FloatArray(2); val tan = FloatArray(2)

        // Duration from speed profile: average ~55% of max
        val durationMs =
            (arcLen / (MAX_SPEED_PX_PER_S * 0.55f) * 1000f).toLong().coerceAtLeast(50L)

        val startHeading = curHeading

        suspendCancellableCoroutine<Unit> { cont ->
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                interpolator = LinearInterpolator() // speed handled in update
                this.duration = durationMs
                addUpdateListener {
                    val timeFrac = it.animatedValue as Float
                    val distFrac = timeToDistance(timeFrac)
                    pm.getPosTan(distFrac * arcLen, pos, tan)
                    val h = if (fixedHeading) startHeading else atan2(tan[1], tan[0])
                    handler.post {
                        curX = pos[0]; curY = pos[1]; curHeading = h
                        applyTransform(pos[0], pos[1], h)
                    }
                }
                addListener(object : android.animation.Animator.AnimatorListener {
                    override fun onAnimationStart(a: android.animation.Animator) {}
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        runningAnim = null
                        val h = if (fixedHeading) startHeading else IDLE_HEADING
                        curX = toX; curY = toY; curHeading = h
                        handler.post { applyTransform(toX, toY, h) }
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onAnimationCancel(a: android.animation.Animator) {
                        runningAnim = null
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onAnimationRepeat(a: android.animation.Animator) {}
                })
            }
            runningAnim = anim
            handler.post { anim.start() }
        }
    }

    private fun cancelAnim() {
        runningAnim?.cancel()
        runningAnim = null
    }

    // ============================================================
    // Speed profile
    // ============================================================

    /** Pre-compute the time→distance LUT by integrating the speed profile. */
    private fun buildSpeedLut(): FloatArray {
        val dt = 1f / SPEED_LUT_SIZE
        val speeds = FloatArray(SPEED_LUT_SIZE + 1) { speedAtT(it.toFloat() / SPEED_LUT_SIZE) }
        var total = 0f
        for (i in 1..SPEED_LUT_SIZE) total += (speeds[i - 1] + speeds[i]) / 2f * dt
        val lut = FloatArray(SPEED_LUT_SIZE + 1)
        var cum = 0f
        for (i in 1..SPEED_LUT_SIZE) {
            cum += (speeds[i - 1] + speeds[i]) / 2f * dt
            lut[i] = cum / total
        }
        return lut
    }

    private fun speedAtT(t: Float): Float = when {
        t < ACCEL_FRAC -> {
            val p = t / ACCEL_FRAC
            MIN_SPEED_PX_PER_S + (MAX_SPEED_PX_PER_S - MIN_SPEED_PX_PER_S) * p * p
        }
        t < 1f - DECEL_FRAC -> MAX_SPEED_PX_PER_S
        else -> {
            val p = (t - (1f - DECEL_FRAC)) / DECEL_FRAC
            MIN_SPEED_PX_PER_S + (MAX_SPEED_PX_PER_S - MIN_SPEED_PX_PER_S) * (1f - p) * (1f - p)
        }
    }

    /** Map linear time fraction → distance fraction via pre-computed LUT. */
    private fun timeToDistance(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val idx = t * SPEED_LUT_SIZE
        val i = idx.toInt()
        val frac = idx - i
        return speedLut[i] + (speedLut[i + 1] - speedLut[i]) * frac
    }

    // ============================================================
    // Window management
    // ============================================================

    private fun attachIfNeeded() {
        if (attached || wm == null || view == null || lp == null) return
        try { wm!!.addView(view, lp); attached = true } catch (_: Exception) {}
    }

    private fun detach() {
        if (!attached) return
        try { wm?.removeView(view) } catch (_: Exception) {}
        attached = false
    }

    private fun applyTransform(x: Float, y: Float, headingRad: Float) {
        val p = lp ?: return
        val half = (POINTER_SIZE_DP * density / 2f).toInt()
        p.x = x.toInt() - half
        p.y = y.toInt() - half
        // Drawable tip naturally points top-left; subtract IDLE_HEADING to
        // convert absolute screen heading to a rotation relative to default.
        view?.rotation = Math.toDegrees((headingRad - IDLE_HEADING).toDouble()).toFloat()
        try { if (attached) wm?.updateViewLayout(view, p) } catch (_: Exception) {}
    }

    private suspend fun delayOnMain(ms: Long) {
        suspendCancellableCoroutine<Unit> { cont ->
            handler.postDelayed({ if (cont.isActive) cont.resume(Unit) }, ms)
        }
    }
}
