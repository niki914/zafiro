package com.niki914.uikit.infra.scrollbar

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 带有 iOS 风格刚体超滚平移的统一滚动容器（不含滚动条）。
 *
 * 内容层采用纯平移形变（PRD 1.2），拦截未消费的滑动量并由弹簧阻尼控制位移。
 * Action Bar 位于外层 LiquidScreen 中，天然不受此容器的平移影响。
 */
@Composable
fun LiquidOverscrollContainer(
    overscrollController: LiquidOverscrollController,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                overscrollController.viewportHeightPx = it.height.toFloat()
            },
    ) {
        // 刚体平移内容层（纯平移，杜绝拉伸放大）
        CompositionLocalProvider(
            LocalOverscrollFactory provides null,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(overscrollController.nestedScrollConnection)
                    .graphicsLayer {
                        translationY = overscrollController.overscrollOffset.value
                    },
                content = content,
            )
        }
    }
}

/**
 * 带有 iOS 风格刚体超滚平移与自适应微型滚动条的统一滚动容器。
 *
 * 内容层采用纯平移形变（PRD 1.2），滚动条处于上层并在有效视口轨道内随滚动与超滚状态变形与收敛。
 * Action Bar 位于外层 LiquidScreen 中，天然不受此容器的平移影响。
 */
@Composable
fun LiquidScrollableContainer(
    state: LiquidScrollbarState,
    overscrollController: LiquidOverscrollController,
    modifier: Modifier = Modifier,
    topClearance: Dp = 0.dp,
    bottomClearance: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        // Layer 1: 刚体平移内容层（纯平移，杜绝拉伸放大）
        LiquidOverscrollContainer(
            overscrollController = overscrollController,
            modifier = Modifier.fillMaxSize(),
            content = content,
        )

        // Layer 2: 独立微型滚动条覆盖层（避让顶栏 Action Bar 与底部输入条）
        LiquidAdaptiveScrollbar(
            state = state,
            topClearance = topClearance,
            bottomClearance = bottomClearance,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}
