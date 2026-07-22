package com.niki914.nexus.agentic.animation

import android.graphics.Path
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared pointer animation math extracted from [PointerOverlay].
 *
 * Both the floating-window overlay and the in-process Compose onboard demo
 * use these functions so the cursor moves with the same feel everywhere.
 */
object PointerCurveMath {

    // --- Curve shape ---
    const val CTRL_DIST_MIN_FRAC = 0.15f
    const val CTRL_DIST_MAX_FRAC = 0.55f
    const val CTRL_PERP_FRAC = 0.25f

    // --- Speed profile ---
    const val MAX_SPEED_PX_PER_S = 4500f
    const val MIN_SPEED_PX_PER_S = 200f
    const val ACCEL_FRAC = 0.20f
    const val DECEL_FRAC = 0.30f
    const val SPEED_LUT_SIZE = 100

    /** Idle heading: pointer tip faces top-left (-135°, rad) */
    const val IDLE_HEADING_RAD = (-Math.PI * 3.0 / 4.0).toFloat()

    // ============================================================
    // Curve construction
    // ============================================================

    /**
     * Builds a cubic bezier from (x1,y1) to (x2,y2) with direction-aware
     * control points.
     *
     * When the pointer flies with its tip (heading aligns with target
     * direction), the curve is nearly straight.
     * When flying against the tip, control points push far out and a
     * perpendicular offset adds a figure-8 side-sweep.
     */
    fun buildCurve(
        x1: Float, y1: Float, headingRad: Float,
        x2: Float, y2: Float,
    ): Path {
        val dx = x2 - x1
        val dy = y2 - y1
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < 2f) return Path().apply { moveTo(x1, y1); lineTo(x2, y2) }

        val hx = cos(headingRad)
        val hy = sin(headingRad)

        val tx = dx / dist
        val ty = dy / dist

        val dot = hx * tx + hy * ty
        val arcFactor = ((1f - dot) / 2f).coerceIn(0f, 1f)

        val px = -hy
        val py = hx

        val ctrlDist =
            (CTRL_DIST_MIN_FRAC + arcFactor * (CTRL_DIST_MAX_FRAC - CTRL_DIST_MIN_FRAC)) * dist
        val perpDist = arcFactor * CTRL_PERP_FRAC * dist

        return Path().apply {
            moveTo(x1, y1)
            cubicTo(
                x1 + hx * ctrlDist + px * perpDist,
                y1 + hy * ctrlDist + py * perpDist,
                x2 - hx * ctrlDist - px * perpDist,
                y2 - hy * ctrlDist - py * perpDist,
                x2, y2,
            )
        }
    }

    // ============================================================
    // Speed profile
    // ============================================================

    fun buildSpeedLut(): FloatArray {
        val dt = 1f / SPEED_LUT_SIZE
        val speeds = FloatArray(SPEED_LUT_SIZE + 1) {
            speedAtT(it.toFloat() / SPEED_LUT_SIZE)
        }
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

    fun speedAtT(t: Float): Float = when {
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

    /** Map linear time fraction -> distance fraction via pre-computed LUT. */
    fun timeToDistance(t: Float, lut: FloatArray): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val idx = t * SPEED_LUT_SIZE
        val i = idx.toInt()
        val frac = idx - i
        return lut[i] + (lut[i + 1] - lut[i]) * frac
    }
}
