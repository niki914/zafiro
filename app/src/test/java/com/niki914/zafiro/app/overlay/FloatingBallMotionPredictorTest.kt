package com.niki914.zafiro.app.overlay

import com.niki914.zafiro.remoteview.floatingball.DockSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FloatingBallMotionPredictorTest {

    private lateinit var predictor: FloatingBallMotionPredictor

    private val screenWidth = 1080
    private val ballWidth = 150
    private val submerged = 60
    private val snapThreshold = 300f
    private val minBallY = 100
    private val maxBallY = 2000

    @Before
    fun setup() {
        predictor = FloatingBallMotionPredictor(
            sampleWindowMs = 200L,
            maxGlideDistancePx = 250f,
            snapThresholdPx = snapThreshold,
            screenWidthPx = screenWidth,
            ballWidthPx = ballWidth,
            submergedPx = submerged,
            minBallY = minBallY,
            maxBallY = maxBallY,
            minVelocityThreshold = 0.45f,
            maxVelocityThreshold = 2.2f,
        )
    }

    @Test
    fun `when stationary with long pause, predicts no glide and evaluates static snap`() {
        predictor.recordPoint(500f, 500f, 1000L)
        predictor.recordPoint(500f, 500f, 1050L)

        // 停顿 150ms 后松手
        val result = predictor.predict(currentBallX = 500, currentBallY = 500, nowMillis = 1200L)

        assertEquals(500, result.targetX)
        assertEquals(500, result.targetY)
        assertFalse(result.willSubmerge)
    }

    @Test
    fun `when slow drag below velocity threshold, stops at current position if far from edges`() {
        predictor.recordPoint(500f, 500f, 1000L)
        predictor.recordPoint(510f, 500f, 1080L) // 10px / 80ms = 0.125 px/ms < 0.35

        val result = predictor.predict(currentBallX = 510, currentBallY = 500, nowMillis = 1080L)

        assertEquals(510, result.targetX)
        assertEquals(500, result.targetY)
        assertFalse(result.willSubmerge)
    }

    @Test
    fun `when fling left penetrates snap threshold, docks and submerges on left`() {
        // 从 400 向左快速划到 320（当前还在安全区 300 之外）
        predictor.recordPoint(400f, 500f, 1000L)
        predictor.recordPoint(320f, 500f, 1040L) // 80px / 40ms = 2.0 px/ms (满额速度)

        val result = predictor.predict(currentBallX = 320, currentBallY = 500, nowMillis = 1040L)

        // 惯性滑行将超过 400px，必然突破 300px 阈值
        assertEquals(-submerged, result.targetX)
        assertEquals(500, result.targetY)
        assertEquals(DockSide.Left, result.targetDock)
        assertTrue(result.willSubmerge)
    }

    @Test
    fun `when fling right penetrates right snap threshold, docks and submerges on right`() {
        // 从 650 向右快速划到 720
        predictor.recordPoint(650f, 500f, 1000L)
        predictor.recordPoint(720f, 500f, 1040L)

        val result = predictor.predict(currentBallX = 720, currentBallY = 500, nowMillis = 1040L)

        val expectedRightSubmergedX = screenWidth - (ballWidth - submerged)
        assertEquals(expectedRightSubmergedX, result.targetX)
        assertEquals(500, result.targetY)
        assertEquals(DockSide.Right, result.targetDock)
        assertTrue(result.willSubmerge)
    }

    @Test
    fun `when gentle fling stays inside safe zone, glides honestly and does not submerge`() {
        // 在屏幕正中央（500）向右微划，位移小，预测落点未达到右边缘阈值
        predictor.recordPoint(480f, 500f, 1000L)
        predictor.recordPoint(500f, 500f, 1040L) // 20px / 40ms = 0.5 px/ms (轻度初速度)

        val result = predictor.predict(currentBallX = 500, currentBallY = 500, nowMillis = 1040L)

        // 落点在屏幕中央偏右，但远未触及右侧 snapThreshold (1080 - 150 - 300 = 630)
        assertTrue("targetX should be greater than 500", result.targetX > 500)
        assertTrue("targetX should stay inside safe area", result.targetX < 630)
        assertFalse(result.willSubmerge)
    }

    @Test
    fun `vertical glide is clamped within minBallY and maxBallY`() {
        // 极速向下划出屏幕边界
        predictor.recordPoint(500f, 1900f, 1000L)
        predictor.recordPoint(500f, 1980f, 1030L)

        val result = predictor.predict(currentBallX = 500, currentBallY = 1980, nowMillis = 1030L)

        assertEquals(maxBallY, result.targetY)
    }
}
