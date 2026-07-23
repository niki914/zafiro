package com.niki914.nexus.agentic.animation

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared pointer animation math — quintic Hermite organic trajectory.
 *
 * Both the floating-window pointer overlay and the in-process Compose onboard
 * demo use [buildTrajectory] so the cursor moves with the same feel everywhere.
 */
object PointerCurveMath {

    /** Idle heading: pointer tip faces top-left (NW, -135 degrees in radians). */
    const val IDLE_HEADING_RAD = (-Math.PI * 3.0 / 4.0).toFloat()

    /** Pause between fly-to-start and swipe phases (ms). */
    const val SWIPE_GAP_MS = 80L

    /** Duration for the swipe phase in the onboard demo (ms). */
    const val DEMO_SWIPE_DURATION_MS = 400L

    // ---------------------------------------------------------------
    // Movement mode
    // ---------------------------------------------------------------

    enum class MovementMode {
        /** Straight line, constant [IDLE_HEADING_RAD], uniform speed. */
        TRANSLATE,
        /** Quintic Hermite organic curve, tangent-following, easeInOutSine. */
        FLY,
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    data class FrameData(val x: Float, val y: Float, val headingRad: Float)

    class Trajectory(
        val mode: MovementMode,
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val totalLen: Float,
        val totalDurationMs: Long,
        // FLY-only internals (null for TRANSLATE)
        private val coeffs: FlyCoeffs?,
        private val cumLen: FloatArray?,
        private val arcSamples: Int,
    ) {
        /**
         * Maps a distance fraction [0..1] to the corresponding position and
         * raw tangent heading at that point along the trajectory.
         *
         * For TRANSLATE the heading is always [IDLE_HEADING_RAD].
         * For FLY the heading is the raw tangent angle — callers must unwrap
         * across frames via [PointerCurveMath.unwrapAngle].
         */
        fun sampleAtDistance(distFrac: Float): FrameData {
            val f = distFrac.coerceIn(0f, 1f)
            return when (mode) {
                MovementMode.TRANSLATE -> {
                    FrameData(
                        x = startX + (endX - startX) * f,
                        y = startY + (endY - startY) * f,
                        headingRad = IDLE_HEADING_RAD,
                    )
                }
                MovementMode.FLY -> {
                    val c = coeffs
                    if (c == null) { // tiny-dist fallback
                        return FrameData(
                            x = startX + (endX - startX) * f,
                            y = startY + (endY - startY) * f,
                            headingRad = IDLE_HEADING_RAD,
                        )
                    }
                    val t = distanceToParam(f)
                    val px = positionAt(c, t)
                    val py = positionAtY(c, t)
                    val vx = derivativeAt(c, t)
                    val vy = derivativeAtY(c, t)
                    FrameData(px, py, atan2(vy, vx))
                }
            }
        }

        /** Binary search through the arc-length table to map distance → t. */
        private fun distanceToParam(distFrac: Float): Float {
            val target = distFrac * totalLen
            val cl = cumLen!!
            var lo = 0
            var hi = arcSamples
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (cl[mid] <= target) lo = mid else hi = mid - 1
            }
            if (lo >= arcSamples) return 1f
            val segLen = cl[lo + 1] - cl[lo]
            if (segLen < 1e-6f) return (lo + 1).toFloat() / arcSamples
            val frac = (target - cl[lo]) / segLen
            return (lo + frac) / arcSamples
        }
    }

    /**
     * Build a trajectory from (startX,startY) to (endX,endY).
     *
     * [startHeadingRad] is the pointer's current heading (used as V0 direction
     * in FLY mode so consecutive animations don't snap).
     *
     * [mode] selects the movement strategy:
     * - [MovementMode.TRANSLATE]: straight line, constant [IDLE_HEADING_RAD],
     *   uniform speed. [startHeadingRad] is ignored.
     * - [MovementMode.FLY]: quintic Hermite curve. V0 direction follows
     *   [startHeadingRad] for smooth departure; V1 direction is always
     *   [IDLE_HEADING_RAD] (NW) so the pointer arrives naturally idle.
     *
     * [canvasW] and [canvasH] are used by FLY for candidate scoring
     * (avoiding out-of-bounds curves).
     */
    fun buildTrajectory(
        startX: Float,
        startY: Float,
        startHeadingRad: Float,
        endX: Float,
        endY: Float,
        mode: MovementMode,
        canvasW: Int,
        canvasH: Int,
    ): Trajectory = when (mode) {
        MovementMode.TRANSLATE -> buildTranslate(startX, startY, endX, endY)
        MovementMode.FLY -> buildFly(
            startX, startY, startHeadingRad, endX, endY, canvasW, canvasH,
        )
    }

    // ---------------------------------------------------------------
    // Angle unwrapping
    // ---------------------------------------------------------------

    /**
     * Unwraps [rawDeg] (in degrees) relative to [prevDeg] so the angle
     * changes continuously without ±180° jumps.
     */
    fun unwrapAngle(rawDeg: Float, prevDeg: Float): Float {
        var r = rawDeg
        while (r - prevDeg > 180f) r -= 360f
        while (r - prevDeg < -180f) r += 360f
        return r
    }

    // ---------------------------------------------------------------
    // Easing
    // ---------------------------------------------------------------

    /** Cosine ease-in-out: starts at 0, accelerates, then decelerates to 1. */
    fun easeInOutSine(t: Float): Float =
        0.5f - 0.5f * cos(Math.PI.toFloat() * t.coerceIn(0f, 1f))

    // ================================================================
    // Internals — TRANSLATE
    // ================================================================

    private const val TRANSLATE_SPEED_PX_S = 2000f
    private const val MIN_DURATION_MS = 80L
    private const val MAX_DURATION_MS = 3000L

    private fun buildTranslate(
        sx: Float, sy: Float, ex: Float, ey: Float,
    ): Trajectory {
        val dx = ex - sx
        val dy = ey - sy
        val dist = sqrt(dx * dx + dy * dy)
        val dur = (dist / TRANSLATE_SPEED_PX_S * 1000f).toLong()
            .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
        return Trajectory(
            mode = MovementMode.TRANSLATE,
            startX = sx, startY = sy,
            endX = ex, endY = ey,
            totalLen = dist, totalDurationMs = dur,
            coeffs = null, cumLen = null, arcSamples = 0,
        )
    }

    // ================================================================
    // Internals — FLY (quintic Hermite + bump perturbation)
    // ================================================================

    private const val EPSILON_DIST = 2f
    private const val ARC_SAMPLES = 512
    private const val NOMINAL_SPEED_PX_S = 3000f
    private const val SKEW = 0.42f
    private const val BEND_FRAC = 0.14f
    private const val MIN_SPEED_FRAC = 0.04f       // minSpeed > 0.04 * D
    private const val MAX_ANGLE_STEP_DEG = 6f      // max consecutive angle step
    private const val CANDIDATE_SAMPLES = 256       // for candidate scoring

    private val IDLE_DIR_X = (-1.0 / sqrt(2.0)).toFloat()
    private val IDLE_DIR_Y = (-1.0 / sqrt(2.0)).toFloat()

    class FlyCoeffs(
        val sx: Float, val sy: Float,
        val v0x: Float, val v0y: Float,
        val ax: Float, val ay: Float,
        val bx: Float, val by: Float,
        val cx: Float, val cy: Float,
        val nx: Float, val ny: Float,
        val bend: Float,
    )

    data class CandidateScore(
        val minSpeed: Float,
        val overflow: Float,
        val maxAngleStep: Float,
    )

    private fun buildFly(
        sx: Float, sy: Float,
        startHeadingRad: Float,
        ex: Float, ey: Float,
        canvasW: Int, canvasH: Int,
    ): Trajectory {
        val dx = ex - sx
        val dy = ey - sy
        val dist = sqrt(dx * dx + dy * dy)

        // Extremely short travel — skip curve, treat as tiny translate
        if (dist < EPSILON_DIST) {
            val dur = MIN_DURATION_MS
            return Trajectory(
                mode = MovementMode.FLY,
                startX = sx, startY = sy,
                endX = ex, endY = ey,
                totalLen = dist, totalDurationMs = dur,
                coeffs = null, cumLen = null, arcSamples = 0,
            )
        }

        val d = minOf(canvasW.toFloat(), canvasH.toFloat())

        // V0 follows current heading for smooth departure
        val v0DirX = cos(startHeadingRad)
        val v0DirY = sin(startHeadingRad)

        val startMag = minOf(0.55f * dist, 0.60f * d).coerceAtLeast(0.12f * d)
        val endMag = minOf(0.50f * dist, 0.55f * d).coerceAtLeast(0.11f * d)

        val v0x = v0DirX * startMag
        val v0y = v0DirY * startMag
        val v1x = IDLE_DIR_X * endMag   // always arrive facing NW
        val v1y = IDLE_DIR_Y * endMag

        // Quintic Hermite coefficients
        val ax = 10f * dx - 6f * v0x - 4f * v1x
        val ay = 10f * dy - 6f * v0y - 4f * v1y
        val bx = -15f * dx + 8f * v0x + 7f * v1x
        val by = -15f * dy + 8f * v0y + 7f * v1y
        val cx = 6f * dx - 3f * v0x - 3f * v1x
        val cy = 6f * dy - 3f * v0y - 3f * v1y

        // Chord normal
        val chordLen = sqrt(dx * dx + dy * dy)
        val chordNx = if (chordLen > 0f) -dy / chordLen else 1f
        val chordNy = if (chordLen > 0f) dx / chordLen else 0f
        val baseBend = minOf(BEND_FRAC * dist, 0.19f * d).coerceAtLeast(0.04f * d)

        var best: Pair<FlyCoeffs, CandidateScore>? = null

        for (dirSign in intArrayOf(1, -1)) {
            val nx = chordNx * dirSign
            val ny = chordNy * dirSign
            val coeffs = FlyCoeffs(sx, sy, v0x, v0y, ax, ay, bx, by, cx, cy, nx, ny, baseBend)
            val score = scoreCandidate(coeffs, canvasW, canvasH, d)
            if (score.minSpeed <= MIN_SPEED_FRAC * d) continue // cusp risk
            if (best == null ||
                score.overflow < best.second.overflow - 1f ||
                (abs(score.overflow - best.second.overflow) < 1f &&
                        score.maxAngleStep < best.second.maxAngleStep)
            ) {
                best = coeffs to score
            }
        }

        val chosen = best?.first ?: FlyCoeffs(
            sx, sy, v0x, v0y, ax, ay, bx, by, cx, cy,
            chordNx, chordNy, baseBend * 0.5f,
        )

        val cumLen = buildArcLengthTable(chosen)
        val totalLen = cumLen[ARC_SAMPLES]
        val dur = (totalLen / NOMINAL_SPEED_PX_S * 1000f).toLong()
            .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

        return Trajectory(
            mode = MovementMode.FLY,
            startX = sx, startY = sy,
            endX = ex, endY = ey,
            totalLen = totalLen, totalDurationMs = dur,
            coeffs = chosen, cumLen = cumLen, arcSamples = ARC_SAMPLES,
        )
    }

    /** Evaluate a candidate curve by sampling [CANDIDATE_SAMPLES] points. */
    private fun scoreCandidate(
        c: FlyCoeffs, cw: Int, ch: Int, d: Float,
    ): CandidateScore {
        var minSpeed = Float.MAX_VALUE
        var overflow = 0f
        var maxAngleStep = 0f
        var prevAngle = 0f
        var first = true

        val steps = CANDIDATE_SAMPLES
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val vx = derivativeAt(c, t)
            val vy = derivativeAtY(c, t)
            val speed = sqrt(vx * vx + vy * vy)
            if (speed < minSpeed) minSpeed = speed

            // Out of bounds
            val px = positionAt(c, t)
            val py = positionAtY(c, t)
            if (px < 0f) overflow -= px
            else if (px > cw) overflow += px - cw
            if (py < 0f) overflow -= py
            else if (py > ch) overflow += py - ch

            // Angle step
            val angle = Math.toDegrees(atan2(vy.toDouble(), vx.toDouble())).toFloat()
            if (!first) {
                val step = angleUnwrappedDiff(angle, prevAngle)
                if (step > maxAngleStep) maxAngleStep = step
            }
            prevAngle = angle
            first = false
        }

        return CandidateScore(minSpeed, overflow, maxAngleStep)
    }

    /** Shortest angular distance between two degree values. */
    private fun angleUnwrappedDiff(a: Float, b: Float): Float {
        val diff = abs(a - b)
        return if (diff > 180f) 360f - diff else diff
    }

    /** Build the cumulative arc-length table (ARC_SAMPLES + 1 entries). */
    private fun buildArcLengthTable(c: FlyCoeffs): FloatArray {
        val cl = FloatArray(ARC_SAMPLES + 1)
        var px = c.sx
        var py = c.sy
        for (i in 1..ARC_SAMPLES) {
            val t = i.toFloat() / ARC_SAMPLES
            val x = positionAt(c, t)
            val y = positionAtY(c, t)
            val seg = sqrt((x - px) * (x - px) + (y - py) * (y - py))
            cl[i] = cl[i - 1] + seg
            px = x
            py = y
        }
        return cl
    }

    // --- Analytic position & derivative ---
    //    P(t)   = S + V0·t + A·t³ + B·t⁴ + C·t⁵ + normal·bend·bump(t)
    //    P'(t)  = V0 + 3A·t² + 4B·t³ + 5C·t⁴ + normal·bend·bump'(t)

    private fun positionAt(c: FlyCoeffs, t: Float): Float {
        val t2 = t * t; val t3 = t2 * t; val t4 = t3 * t; val t5 = t4 * t
        return c.sx + c.v0x * t + c.ax * t3 + c.bx * t4 + c.cx * t5 +
                c.nx * c.bend * bump(t)
    }

    private fun positionAtY(c: FlyCoeffs, t: Float): Float {
        val t2 = t * t; val t3 = t2 * t; val t4 = t3 * t; val t5 = t4 * t
        return c.sy + c.v0y * t + c.ay * t3 + c.by * t4 + c.cy * t5 +
                c.ny * c.bend * bump(t)
    }

    private fun derivativeAt(c: FlyCoeffs, t: Float): Float {
        val t2 = t * t; val t3 = t2 * t; val t4 = t3 * t
        return c.v0x + 3f * c.ax * t2 + 4f * c.bx * t3 + 5f * c.cx * t4 +
                c.nx * c.bend * bumpDerivative(t)
    }

    private fun derivativeAtY(c: FlyCoeffs, t: Float): Float {
        val t2 = t * t; val t3 = t2 * t; val t4 = t3 * t
        return c.v0y + 3f * c.ay * t2 + 4f * c.by * t3 + 5f * c.cy * t4 +
                c.ny * c.bend * bumpDerivative(t)
    }

    // --- Bump (侧向扰动) ---

    /**
     * Smooth perturbation that vanishes at both ends:
     *   bump(t) = 16·t²·(1-t)² × (1 + skew·(2t-1))
     */
    private fun bump(t: Float): Float {
        val g = 16f * t * t * (1f - t) * (1f - t)
        val q = 1f + SKEW * (2f * t - 1f)
        return g * q
    }

    /**
     * Derivative of [bump]:
     *   bump'(t) = g'(t)·q(t) + 2·skew·g(t)
     *   where g'(t) = 32·t·(1-t)·(1-2t)
     */
    private fun bumpDerivative(t: Float): Float {
        val g = 16f * t * t * (1f - t) * (1f - t)
        val dg = 32f * t * (1f - t) * (1f - 2f * t)
        val q = 1f + SKEW * (2f * t - 1f)
        return dg * q + g * 2f * SKEW
    }
}
