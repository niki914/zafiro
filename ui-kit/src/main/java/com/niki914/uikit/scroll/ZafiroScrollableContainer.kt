package com.niki914.uikit.scroll

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.scrollbar.LiquidScrollableContainer
import com.niki914.uikit.infra.scrollbar.LiquidScrollbarState
import com.niki914.uikit.infra.scrollbar.rememberLazyListStateAdapter
import com.niki914.uikit.infra.scrollbar.rememberScrollStateAdapter

typealias ZafiroScrollbarState = LiquidScrollbarState

@Composable
fun rememberZafiroScrollStateAdapter(
    scrollState: ScrollState,
    overscrollController: ZafiroOverscrollController,
): ZafiroScrollbarState = rememberScrollStateAdapter(
    scrollState = scrollState,
    overscrollController = overscrollController,
)

@Composable
fun rememberZafiroLazyListStateAdapter(
    lazyListState: LazyListState,
    overscrollController: ZafiroOverscrollController,
): ZafiroScrollbarState = rememberLazyListStateAdapter(
    lazyListState = lazyListState,
    overscrollController = overscrollController,
)

/**
 * 统一滚动容器门面（含超滚与滚动条分流）：
 * - [ThemeStyle.LiquidGlass]: 渲染为 iOS 刚体超滚平移与自适应微型滚动条（[LiquidScrollableContainer]）；
 * - [ThemeStyle.Material3] / [ThemeStyle.Material3Expressive]: 渲染为纯容器，内容保留原生拉伸弹性张力（Stretch Overscroll），
 *   且不悬浮微型滚动条，符合 Material 规范。
 */
@Composable
fun ZafiroScrollableContainer(
    state: ZafiroScrollbarState,
    overscrollController: ZafiroOverscrollController,
    modifier: Modifier = Modifier,
    topClearance: Dp = 0.dp,
    bottomClearance: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    when (LocalThemeStyle.current) {
        ThemeStyle.LiquidGlass -> {
            LiquidScrollableContainer(
                state = state,
                overscrollController = overscrollController,
                modifier = modifier,
                topClearance = topClearance,
                bottomClearance = bottomClearance,
                content = content,
            )
        }

        ThemeStyle.Material3,
        ThemeStyle.Material3Expressive -> {
            Box(
                modifier = modifier.fillMaxSize(),
                content = content,
            )
        }
    }
}
