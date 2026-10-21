package com.niki914.uikit.scroll

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

@Composable
fun ZafiroScrollableContainer(
    state: ZafiroScrollbarState,
    overscrollController: ZafiroOverscrollController,
    modifier: Modifier = Modifier,
    topClearance: Dp = 0.dp,
    bottomClearance: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    LiquidScrollableContainer(
        state = state,
        overscrollController = overscrollController,
        modifier = modifier,
        topClearance = topClearance,
        bottomClearance = bottomClearance,
        content = content,
    )
}
