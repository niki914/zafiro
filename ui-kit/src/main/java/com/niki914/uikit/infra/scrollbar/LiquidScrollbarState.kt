package com.niki914.uikit.infra.scrollbar

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/**
 * 滚动条状态统一接口，统一抹平 [ScrollState]、[LazyListState] 与超滚位移。
 */
@Stable
interface LiquidScrollbarState {
    /** 视口高度（px） */
    val viewportHeightPx: Float

    /** 内容总高度（px） */
    val contentHeightPx: Float

    /** 当前视口内滚动偏移（px，>= 0） */
    val scrollOffsetPx: Float

    /** 最大正常滚动偏移（px） */
    val maxScrollPx: Float

    /** 当前是否正在手势滑动、惯性滑动或回弹收敛中 */
    val isScrollInProgress: Boolean

    /** 刚体平移超滚量（px）。正数 = 触顶下拉越界；负数 = 触底上拉越界 */
    val overscrollOffsetPx: Float

    /** 通过拖拽滑块直接驱动底层容器滚动 */
    fun scrollBy(deltaPx: Float)
}

@Composable
fun rememberScrollStateAdapter(
    scrollState: ScrollState,
    overscrollController: LiquidOverscrollController,
): LiquidScrollbarState {
    return remember(scrollState, overscrollController) {
        object : LiquidScrollbarState {
            override val viewportHeightPx: Float
                get() = overscrollController.viewportHeightPx

            override val contentHeightPx: Float
                get() = overscrollController.viewportHeightPx + scrollState.maxValue.toFloat()

            override val scrollOffsetPx: Float
                get() = scrollState.value.toFloat()

            override val maxScrollPx: Float
                get() = scrollState.maxValue.toFloat()

            override val isScrollInProgress: Boolean
                get() = scrollState.isScrollInProgress ||
                        overscrollController.isRebounding

            override val overscrollOffsetPx: Float
                get() = overscrollController.overscrollOffset.value

            override fun scrollBy(deltaPx: Float) {
                scrollState.dispatchRawDelta(deltaPx)
            }
        }
    }
}

@Composable
fun rememberLazyListStateAdapter(
    lazyListState: LazyListState,
    overscrollController: LiquidOverscrollController,
): LiquidScrollbarState {
    return remember(lazyListState, overscrollController) {
        object : LiquidScrollbarState {
            private var smoothedItemSize = 0f

            override val viewportHeightPx: Float
                get() = overscrollController.viewportHeightPx

            override val contentHeightPx: Float
                get() {
                    val layoutInfo = lazyListState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    val totalItems = layoutInfo.totalItemsCount
                    if (visibleItems.isEmpty() || totalItems == 0) {
                        return overscrollController.viewportHeightPx
                    }
                    val currentAvg = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
                    // 指数滑动平均（EMA），避免单行短气泡离屏造成总高剧烈跳变
                    val effectiveSize = if (smoothedItemSize <= 0f) {
                        smoothedItemSize = currentAvg
                        currentAvg
                    } else {
                        val updated = smoothedItemSize * 0.90f + currentAvg * 0.10f
                        smoothedItemSize = updated
                        updated
                    }
                    return (effectiveSize * totalItems).coerceAtLeast(overscrollController.viewportHeightPx)
                }

            override val scrollOffsetPx: Float
                get() {
                    val layoutInfo = lazyListState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    if (visibleItems.isEmpty()) return 0f
                    val first = visibleItems.first()
                    val effectiveSize = if (smoothedItemSize > 0f) {
                        smoothedItemSize
                    } else {
                        visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
                    }
                    return (first.index * effectiveSize - first.offset).coerceAtLeast(0f)
                }

            override val maxScrollPx: Float
                get() = (contentHeightPx - viewportHeightPx).coerceAtLeast(0f)

            override val isScrollInProgress: Boolean
                get() = lazyListState.isScrollInProgress ||
                        overscrollController.isRebounding

            override val overscrollOffsetPx: Float
                get() = overscrollController.overscrollOffset.value

            override fun scrollBy(deltaPx: Float) {
                lazyListState.dispatchRawDelta(deltaPx)
            }
        }
    }
}
