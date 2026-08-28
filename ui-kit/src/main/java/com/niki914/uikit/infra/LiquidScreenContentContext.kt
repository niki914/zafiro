package com.niki914.uikit.infra

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Stable
class LiquidScreenContentContext internal constructor(
    val topPadding: Dp,
)

/**
 * LiquidScreen 内容树的壳层上下文。
 *
 * 业务页面应由 `LiquidScreen` 承载；Preview 或独立样例请使用
 * `ProvideLiquidScreenContentForPreview` 包裹。
 */
val LocalLiquidScreenContentContext: ProvidableCompositionLocal<LiquidScreenContentContext> =
    compositionLocalOf {
        error(
            "LocalLiquidScreenContentContext is not provided. " +
                    "Wrap content in LiquidScreen, or use ProvideLiquidScreenContentForPreview for previews."
        )
    }

/**
 * Collapsible 页（`ZafiroPage.titleMode == Collapsible`）向 `LiquidScreen` 回报
 * 「内容是否已滚离顶部」的通道：true = 大标题已滚走（小标题浮现、背景实色）。
 * 默认 false = 假定在顶部（大标题展示中）。Pinned 页不写此状态。
 */
@Stable
class TitleBarCollapseState {
    /** true = 内容已滚过大标题（小标题应浮现）；非滚动页恒为 false（顶栏全透明）。 */
    var isCollapsed: Boolean by mutableStateOf(false)
}

val LocalTitleBarCollapseState: ProvidableCompositionLocal<TitleBarCollapseState> =
    staticCompositionLocalOf { TitleBarCollapseState() }

@Composable
fun liquidScreenTopPadding(extra: Dp = 0.dp): Dp {
    return LocalLiquidScreenContentContext.current.topPadding + extra
}

@Composable
fun ProvideLiquidScreenContentForPreview(
    topPadding: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalLiquidScreenContentContext provides LiquidScreenContentContext(
            topPadding = topPadding,
        ),
        LocalTitleBarCollapseState provides TitleBarCollapseState(),
        content = content,
    )
}
