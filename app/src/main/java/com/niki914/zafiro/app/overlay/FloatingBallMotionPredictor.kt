package com.niki914.zafiro.app.overlay

import com.niki914.zafiro.remoteview.floatingball.DockSide
import kotlin.math.hypot
import kotlin.math.pow

data class TimedPoint(
    val x: Float,
    val y: Float,
    val timeMillis: Long,
)

data class PredictionResult(
    val targetX: Int,
    val targetY: Int,
    val targetDock: DockSide,
    val willSubmerge: Boolean,
    val durationMillis: Long,
)

/**
 * 悬浮球手势抛掷与运动轨迹预测器（高阻尼物理模型与初速度单调递减）。
 *
 * 核心设计：
 * 1. **200ms 后半程滑动采样**：提取松手前 200ms 内的运动序列，采样真实的离开瞬时速度；
 * 2. **加大阻尼约束**：提高起步阈值与衰减指数，单次滑行路程严谨受控，防止打滑漂移；
 * 3. **初速度物理递减**：动画初始速度严格对齐松手瞬时速度（绝不产生快于手速的窜动），并单调减速制动；
 * 4. **诚实落点判定**：仅当预测落点突破边缘安全区时吸附淹没，未突破则平滑停在屏幕内部。
 */
class FloatingBallMotionPredictor(
    private val sampleWindowMs: Long = 200L,
    private val maxGlideDistancePx: Float,
    private val snapThresholdPx: Float,
    private val screenWidthPx: Int,
    private val ballWidthPx: Int,
    private val submergedPx: Int,
    private val minBallY: Int,
    private val maxBallY: Int,
    private val minVelocityThreshold: Float = 0.45f, // 0.45 px/ms = 450 px/s (提高起步阈值加大阻尼)
    private val maxVelocityThreshold: Float = 2.2f,  // 2.2 px/ms = 2200 px/s
) {
    private val samplePoints = ArrayDeque<TimedPoint>()

    fun recordPoint(x: Float, y: Float, timeMillis: Long = System.currentTimeMillis()) {
        samplePoints.addLast(TimedPoint(x, y, timeMillis))
        pruneOldSamples(timeMillis)
    }

    fun reset() {
        samplePoints.clear()
    }

    private fun pruneOldSamples(nowMillis: Long) {
        val cutoff = nowMillis - sampleWindowMs
        while (samplePoints.size > 2 && samplePoints.first().timeMillis < cutoff) {
            samplePoints.removeFirst()
        }
    }

    fun predict(
        currentBallX: Int,
        currentBallY: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): PredictionResult {
        pruneOldSamples(nowMillis)

        if (samplePoints.size < 2) {
            return resolveStaticSnap(currentBallX, currentBallY)
        }

        val start = samplePoints.first()
        val end = samplePoints.last()
        val dt = (end.timeMillis - start.timeMillis).coerceAtLeast(1)

        // 若最近采样距今停顿超过 100ms，视作停顿静止，无惯性
        if (nowMillis - end.timeMillis > 100L) {
            return resolveStaticSnap(currentBallX, currentBallY)
        }

        val dx = end.x - start.x
        val dy = end.y - start.y
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (distance <= 0.001f) {
            return resolveStaticSnap(currentBallX, currentBallY)
        }

        // 离开瞬时速率 (px/ms)
        val velocity = distance / dt

        if (velocity < minVelocityThreshold) {
            return resolveStaticSnap(currentBallX, currentBallY)
        }

        // 强阻尼非线性映射（指数 1.25，低速阻尼更大，滑行极克制）
        val speedRatio = ((velocity - minVelocityThreshold) / (maxVelocityThreshold - minVelocityThreshold)).coerceIn(0f, 1f)
        val glideScale = speedRatio.pow(1.25f)
        val glideDistance = maxGlideDistancePx * glideScale

        val dirX = dx / distance
        val dirY = dy / distance

        val projectedX = currentBallX + (glideDistance * dirX).toInt()
        val projectedY = (currentBallY + (glideDistance * dirY).toInt()).coerceIn(minBallY, maxBallY)

        val landing = evaluateLanding(projectedX, projectedY)

        // 基于真实运动位移反算动画时长，确保初速度严丝合缝匹配松手离开速度 v0，且单调递减
        val actualDistance = hypot(
            (landing.targetX - currentBallX).toDouble(),
            (landing.targetY - currentBallY).toDouble(),
        ).toFloat()

        // 抛物线减速模型中，v(0) = 1.6 * D / T => T = 1.6 * D / v0
        val computedDuration = if (velocity > 0.05f) {
            (1.6f * actualDistance / velocity).toLong()
        } else {
            200L
        }
        val clampedDuration = computedDuration.coerceIn(180L, 320L)

        return landing.copy(durationMillis = clampedDuration)
    }

    private fun resolveStaticSnap(currentBallX: Int, currentBallY: Int): PredictionResult {
        return evaluateLanding(currentBallX, currentBallY).copy(durationMillis = 200L)
    }

    private fun evaluateLanding(
        projectedX: Int,
        projectedY: Int,
    ): PredictionResult {
        val leftSubmergedX = -submergedPx
        val rightSubmergedX = screenWidthPx - (ballWidthPx - submergedPx)

        val distanceToLeft = projectedX.toFloat()
        val distanceToRight = (screenWidthPx - (projectedX + ballWidthPx)).toFloat()

        return when {
            distanceToLeft < snapThresholdPx -> {
                PredictionResult(
                    targetX = leftSubmergedX,
                    targetY = projectedY,
                    targetDock = DockSide.Left,
                    willSubmerge = true,
                    durationMillis = 200L,
                )
            }
            distanceToRight < snapThresholdPx -> {
                PredictionResult(
                    targetX = rightSubmergedX,
                    targetY = projectedY,
                    targetDock = DockSide.Right,
                    willSubmerge = true,
                    durationMillis = 200L,
                )
            }
            else -> {
                val dock = if (distanceToLeft < distanceToRight) DockSide.Left else DockSide.Right
                PredictionResult(
                    targetX = projectedX,
                    targetY = projectedY,
                    targetDock = dock,
                    willSubmerge = false,
                    durationMillis = 200L,
                )
            }
        }
    }
}
