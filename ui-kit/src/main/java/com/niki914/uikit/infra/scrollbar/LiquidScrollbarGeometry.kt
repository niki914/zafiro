package com.niki914.uikit.infra.scrollbar

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

/**
 * 滚动条与超滚度量规范常数（PRD 第 2 节与动量硬阻断规范）。
 */
object LiquidScrollbarDefaults {
    /** 常规滑动与浏览时的固定粗细 */
    val NormalWidth: Dp = 3.5.dp

    /** 拖拽/激活时的膨胀粗细 */
    val ActiveWidth: Dp = 5.0.dp

    /** 紧贴屏幕右侧边缘的外边距 */
    val EndMargin: Dp = 3.0.dp

    /** 触顶/触底用力拉扯时的物理硬下限 */
    val MinThumbLength: Dp = 18.0.dp

    /** 基准滑条长度下限 */
    val MinBaseThumbLength: Dp = 36.0.dp

    /** 基准滑条长度上限比率（H_track * 0.65） */
    const val MaxBaseThumbRatio: Float = 0.65f

    /** 超滚拉扯时的滑块压缩系数 */
    const val OverscrollCompressionRatio: Float = 0.38f

    /** 触顶/触底橡皮筋阻尼系数 */
    const val RubberBandDamping: Float = 0.52f
}

/**
 * 计算得出的滚动条滑块当前几何状态。
 */
data class ThumbGeometry(
    /** 滑块顶部相对于滑轨起点的偏移（px） */
    val topPx: Float,
    /** 滑块当前物理长度（px） */
    val lengthPx: Float,
    /** 是否应该展示（当内容无滚动且无超滚时为 false） */
    val isVisible: Boolean,
)

/**
 * 根据 PRD 第 2 节与第 4 节数学模型计算滑块位置与长度。
 *
 * @param viewportHeightPx 视口高度
 * @param contentHeightPx 内容总高度
 * @param scrollOffsetPx 正常视口内滚动偏移 (>= 0)
 * @param maxScrollPx 最大正常滚动偏移
 * @param overscrollOffsetPx 超滚位移（正数 = 触顶下拉越界，负数 = 触底上拉越界）
 * @param trackHeightPx 有效轨道高度（视口高度 - topClearance - bottomClearance）
 * @param density 屏幕密度
 */
fun calculateThumbGeometry(
    viewportHeightPx: Float,
    contentHeightPx: Float,
    scrollOffsetPx: Float,
    maxScrollPx: Float,
    overscrollOffsetPx: Float,
    trackHeightPx: Float,
    density: Density,
): ThumbGeometry {
    if (trackHeightPx <= 0f) {
        return ThumbGeometry(topPx = 0f, lengthPx = 0f, isVisible = false)
    }

    val minLengthPx = with(density) { LiquidScrollbarDefaults.MinThumbLength.toPx() }
    val minBaseLengthPx = with(density) { LiquidScrollbarDefaults.MinBaseThumbLength.toPx() }
    val maxBaseLengthPx = trackHeightPx * LiquidScrollbarDefaults.MaxBaseThumbRatio

    // 当内容高度 <= 视口高度（无需滚动）时：无超滚不展示滑条
    val needsScroll = contentHeightPx > viewportHeightPx + 1f
    if (!needsScroll && abs(overscrollOffsetPx) < 0.5f) {
        return ThumbGeometry(topPx = 0f, lengthPx = 0f, isVisible = false)
    }

    // 基准长度 L0 计算（PRD 2.1）
    val ratio = if (contentHeightPx > 0f) (viewportHeightPx / contentHeightPx).coerceIn(0f, 1f) else 1f
    val baseLengthPx = (trackHeightPx * ratio).coerceIn(minBaseLengthPx, maxBaseLengthPx)

    return when {
        // 状态 A：触顶下拉越界 (overscrollOffsetPx > 0)
        overscrollOffsetPx > 0.5f -> {
            val compression = overscrollOffsetPx * LiquidScrollbarDefaults.OverscrollCompressionRatio
            val length = max(minLengthPx, baseLengthPx - compression)
            // 固定端：滑块顶端死锁 (Top = 0)，底端朝上收缩
            ThumbGeometry(topPx = 0f, lengthPx = length, isVisible = true)
        }
        // 状态 C：触底上拉越界 (overscrollOffsetPx < 0)
        overscrollOffsetPx < -0.5f -> {
            val compression = abs(overscrollOffsetPx) * LiquidScrollbarDefaults.OverscrollCompressionRatio
            val length = max(minLengthPx, baseLengthPx - compression)
            // 固定端：滑块底端死锁 (Bottom = trackHeightPx)，顶端朝下收缩
            val top = trackHeightPx - length
            ThumbGeometry(topPx = top, lengthPx = length, isVisible = true)
        }
        // 状态 B：视口内常规滚动
        else -> {
            val progress = if (maxScrollPx > 0f) (scrollOffsetPx / maxScrollPx).coerceIn(0f, 1f) else 0f
            val top = progress * (trackHeightPx - baseLengthPx)
            ThumbGeometry(topPx = top, lengthPx = baseLengthPx, isVisible = true)
        }
    }
}
