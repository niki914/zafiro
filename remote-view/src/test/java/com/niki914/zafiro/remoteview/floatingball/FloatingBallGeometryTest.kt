package com.niki914.zafiro.remoteview.floatingball

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingBallGeometryTest {

    @Test
    fun resolveDockSide_picksCloserEdge() {
        val screenWidth = 1080
        val cardWidth = 150

        // 左半边中心点位于左侧
        assertEquals(
            DockSide.Left,
            FloatingBallGeometry.resolveDockSide(currentXPx = 100f, cardWidthPx = cardWidth, screenWidthPx = screenWidth),
        )
        // 右半边中心点位于右侧
        assertEquals(
            DockSide.Right,
            FloatingBallGeometry.resolveDockSide(currentXPx = 800f, cardWidthPx = cardWidth, screenWidthPx = screenWidth),
        )
    }

    @Test
    fun tokens_geometryInvariantsHold() {
        // 卡片总宽 = 3 * 按钮直径 + 4 * 内边距 (左右边距 + 两间隙)
        val expectedWidth = 3 * FloatingBallTokens.buttonDiameter + 4 * FloatingBallTokens.cardPadding
        assertEquals(expectedWidth, FloatingBallTokens.expandedWidth)

        // 内部排版宽度 (去除左右两端 cardPadding)
        val innerWidth = FloatingBallTokens.expandedWidth - 2 * FloatingBallTokens.cardPadding
        val buttonStep = FloatingBallTokens.buttonDiameter + FloatingBallTokens.cardPadding

        // 3 颗按钮所占总跨度必须完全严丝合缝匹配 innerWidth
        val threeButtonsSpan = 2 * buttonStep + FloatingBallTokens.buttonDiameter
        assertEquals(innerWidth, threeButtonsSpan)

        // 锚点 X 坐标必须与最右侧按钮完全对齐
        val rightAnchor = FloatingBallTokens.expandedWidth - FloatingBallTokens.cardPadding - FloatingBallTokens.buttonDiameter
        assertEquals(rightAnchor, FloatingBallTokens.rightAnchorX)
    }

    @Test
    fun computeCardStackOffsets_collapsed_stacksAtOriginWithCorrectZIndex() {
        // 右侧贴边收起态：全部收拢在 0 处，最右侧的 Minimize (收起) 按钮在最顶层
        val rightStack = FloatingBallGeometry.computeCardStackOffsets(DockSide.Right, progress = 0f)
        assertEquals(0f, rightStack.jumpX.value, 0.001f)
        assertEquals(0f, rightStack.stopX.value, 0.001f)
        assertEquals(0f, rightStack.minimizeX.value, 0.001f)
        assertEquals(3f, rightStack.minimizeZIndex, 0.001f)
        assertEquals(2f, rightStack.stopZIndex, 0.001f)
        assertEquals(1f, rightStack.jumpZIndex, 0.001f)

        // 左侧贴边收起态：全部收拢在 0 处，最左侧的 Jump (跳转) 按钮在最顶层
        val leftStack = FloatingBallGeometry.computeCardStackOffsets(DockSide.Left, progress = 0f)
        assertEquals(0f, leftStack.jumpX.value, 0.001f)
        assertEquals(0f, leftStack.stopX.value, 0.001f)
        assertEquals(0f, leftStack.minimizeX.value, 0.001f)
        assertEquals(3f, leftStack.jumpZIndex, 0.001f)
        assertEquals(2f, leftStack.stopZIndex, 0.001f)
        assertEquals(1f, leftStack.minimizeZIndex, 0.001f)
    }

    @Test
    fun computeCardStackOffsets_expanded_spreadsAcrossContentWidth() {
        val expanded = FloatingBallGeometry.computeCardStackOffsets(DockSide.Right, progress = 1f)
        assertEquals(0f, expanded.jumpX.value, 0.001f)
        assertEquals(FloatingBallTokens.buttonStepDp.value, expanded.stopX.value, 0.001f)
        assertEquals(FloatingBallTokens.buttonStepDp.value * 2f, expanded.minimizeX.value, 0.001f)

        // Minimize 按钮右边界必须严丝合缝匹配卡片内部内容宽度
        val innerContentWidth = FloatingBallTokens.expandedWidthDp.value - 2 * FloatingBallTokens.cardPaddingDp.value
        val rightEdge = expanded.minimizeX.value + FloatingBallTokens.buttonDiameterDp.value
        assertEquals(innerContentWidth, rightEdge, 0.001f)
    }

    @Test
    fun computeCardStackOffsets_halfProgress_cardsSpreadUniformly() {
        val half = FloatingBallGeometry.computeCardStackOffsets(DockSide.Right, progress = 0.5f)
        assertEquals(0f, half.jumpX.value, 0.001f)
        assertEquals(FloatingBallTokens.buttonStepDp.value * 0.5f, half.stopX.value, 0.001f)
        assertEquals(FloatingBallTokens.buttonStepDp.value, half.minimizeX.value, 0.001f)

        // 卡牌之间距离均匀递增
        val gap1 = half.stopX.value - half.jumpX.value
        val gap2 = half.minimizeX.value - half.stopX.value
        assertEquals(gap1, gap2, 0.001f)
    }

    @Test
    fun computeCardStackOffsets_clampsProgressOutOfBounds() {
        val clampedLower = FloatingBallGeometry.computeCardStackOffsets(DockSide.Right, progress = -0.5f)
        assertEquals(0f, clampedLower.stopX.value, 0.001f)

        val clampedUpper = FloatingBallGeometry.computeCardStackOffsets(DockSide.Right, progress = 1.5f)
        assertEquals(FloatingBallTokens.buttonStepDp.value, clampedUpper.stopX.value, 0.001f)
    }

    @Test
    fun calculateStableDockX_placesBallAtSafeDistanceOutsideSnapThreshold() {
        val screenWidth = 1080
        val ballWidth = 150
        val escapeDistance = 93

        val rightDockX = FloatingBallGeometry.calculateStableDockX(
            dockSide = DockSide.Right,
            screenWidthPx = screenWidth,
            ballWidthPx = ballWidth,
            escapeDistancePx = escapeDistance,
        )
        // 右停靠稳定横坐标：离屏幕右边缘刚好 escapeDistance
        assertEquals(screenWidth - ballWidth - escapeDistance, rightDockX)

        val leftDockX = FloatingBallGeometry.calculateStableDockX(
            dockSide = DockSide.Left,
            screenWidthPx = screenWidth,
            ballWidthPx = ballWidth,
            escapeDistancePx = escapeDistance,
        )
        // 左停靠稳定横坐标：离屏幕左边缘刚好 escapeDistance
        assertEquals(escapeDistance, leftDockX)
    }
}
