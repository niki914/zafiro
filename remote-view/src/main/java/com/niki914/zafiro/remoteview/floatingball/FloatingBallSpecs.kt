package com.niki914.zafiro.remoteview.floatingball

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 悬浮球贴边侧向。
 */
enum class DockSide {
    Left,
    Right;

    val isLeft: Boolean get() = this == Left
    val isRight: Boolean get() = this == Right
}

/**
 * 悬浮球形态状态。
 */
enum class FloatingBallState {
    Collapsed,
    Expanded;

    val isCollapsed: Boolean get() = this == Collapsed
    val isExpanded: Boolean get() = this == Expanded
}

/**
 * 悬浮球几何与尺寸规范配置。
 */
object FloatingBallTokens {

    // --- 基准原子尺度 ---
    const val buttonDiameter = 50
    const val cardPadding = 8
    const val cardCornerRadius = 25

    // --- 排版 ---
    const val previewMaxLines = 2

    // --- 极值尺寸 (严格受原子尺度与间距约束) ---
    // 展开卡片宽度 = 3 个按钮直径 + 4 个边距 (左右各一外边距 + 两按钮间隙)
    const val expandedWidth = 3 * buttonDiameter + 4 * cardPadding
    const val expandedHeight = 118
    const val collapsedWidth = buttonDiameter
    const val collapsedHeight = buttonDiameter

    // 预览文本固定高度 = 展开总高 - 按钮高 - 上下内边距
    const val previewHeight = expandedHeight - buttonDiameter - 2 * cardPadding

    // --- 锚点槽位 (展开窗口内宿主锚点按钮的绝对坐标，确保展开收起严丝合缝) ---
    const val rightAnchorX = expandedWidth - cardPadding - buttonDiameter
    const val leftAnchorX = cardPadding
    const val anchorY = expandedHeight - cardPadding - buttonDiameter

    // --- 贴边吸附与淹没 ---
    // 距离屏幕边缘小于该阈值时触发自动吸附收起
    const val snapThreshold = 30
    // 靠边时隐藏靠边的部分宽度，屏幕上露出剩余部分作为抓手
    const val submergedOffset = 8
    // 脱离吸边区时的安全外距
    const val escapeSnapDistance = 31

    // --- Dp 转换便捷访问 ---
    val buttonDiameterDp: Dp get() = buttonDiameter.dp
    val cardPaddingDp: Dp get() = cardPadding.dp
    val buttonStepDp: Dp get() = buttonDiameterDp + cardPaddingDp
    val cardCornerRadiusDp: Dp get() = cardCornerRadius.dp
    val expandedWidthDp: Dp get() = expandedWidth.dp
    val expandedHeightDp: Dp get() = expandedHeight.dp
    val collapsedWidthDp: Dp get() = collapsedWidth.dp
    val collapsedHeightDp: Dp get() = collapsedHeight.dp
    val previewHeightDp: Dp get() = previewHeight.dp
    val rightAnchorXDp: Dp get() = rightAnchorX.dp
    val leftAnchorXDp: Dp get() = leftAnchorX.dp
    val anchorYDp: Dp get() = anchorY.dp
    val snapThresholdDp: Dp get() = snapThreshold.dp
    val submergedOffsetDp: Dp get() = submergedOffset.dp
    val escapeSnapDistanceDp: Dp get() = escapeSnapDistance.dp
}

/**
 * 悬浮球展开/收起过程中三颗卡牌按钮的叠放与铺开偏移及层叠顺序。
 */
data class CardStackOffsets(
    val jumpX: Dp,
    val stopX: Dp,
    val minimizeX: Dp,
    val jumpZIndex: Float,
    val stopZIndex: Float,
    val minimizeZIndex: Float,
)

/**
 * 悬浮球几何与状态机计算工具集。
 */
object FloatingBallGeometry {

    /**
     * 根据当前水平中心点横坐标判断更靠近左侧还是右侧。
     */
    fun resolveDockSide(currentXPx: Float, cardWidthPx: Int, screenWidthPx: Int): DockSide {
        val centerX = currentXPx + cardWidthPx / 2f
        return if (centerX < screenWidthPx / 2f) DockSide.Left else DockSide.Right
    }

    /**
     * 计算卡牌式展开/收起时三颗按钮的水平偏移量与层叠层级 (zIndex)。
     *
     * 核心规则：
     * 1. 按钮次序永远固定为：[跳转应用 (0) | 暂停 (1) | 收起 (2)]；
     * 2. 贴右侧 (Right) 时：收起按钮位于最右侧作为视觉锚点叠在最顶层 (zIndex=3)，其余按钮向左抽出铺开；
     * 3. 贴左侧 (Left) 时：跳转按钮位于最左侧作为视觉锚点叠在最顶层 (zIndex=3)，其余按钮向右抽出铺开；
     * 4. 无论何种形态，按钮本身尺寸严格固定为原子尺度，杜绝任何形变挤压。
     */
    fun computeCardStackOffsets(dockSide: DockSide, progress: Float): CardStackOffsets {
        val p = progress.coerceIn(0f, 1f)
        val step = FloatingBallTokens.buttonStepDp
        val jumpX = 0.dp
        val stopX = step * p
        val minimizeX = step * (2f * p)

        val (jumpZ, stopZ, minimizeZ) = if (dockSide.isRight) {
            Triple(1f, 2f, 3f)
        } else {
            Triple(3f, 2f, 1f)
        }

        return CardStackOffsets(
            jumpX = jumpX,
            stopX = stopX,
            minimizeX = minimizeX,
            jumpZIndex = jumpZ,
            stopZIndex = stopZ,
            minimizeZIndex = minimizeZ,
        )
    }

    /**
     * 计算指定贴边侧向下的稳定停靠坐标（位于贴边吸附阈值外，不触发自动淹没）。
     */
    fun calculateStableDockX(dockSide: DockSide, screenWidthPx: Int, ballWidthPx: Int, escapeDistancePx: Int): Int {
        return if (dockSide.isRight) {
            screenWidthPx - ballWidthPx - escapeDistancePx
        } else {
            escapeDistancePx
        }
    }
}
