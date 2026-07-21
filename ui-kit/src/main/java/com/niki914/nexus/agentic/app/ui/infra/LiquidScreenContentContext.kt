package com.niki914.nexus.agentic.app.ui.infra

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

@Stable
class LiquidScreenContentContext internal constructor(
    val topPadding: Dp,
    val hazeState: HazeState,
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

@Composable
fun liquidScreenTopPadding(extra: Dp = 0.dp): Dp {
    return LocalLiquidScreenContentContext.current.topPadding + extra
}

@Composable
fun Modifier.liquidScreenHazeSource(): Modifier {
    return hazeSource(LocalLiquidScreenContentContext.current.hazeState)
}

@Composable
fun ProvideLiquidScreenContentForPreview(
    topPadding: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val hazeState = rememberHazeState(blurEnabled = true)
    CompositionLocalProvider(
        LocalLiquidScreenContentContext provides LiquidScreenContentContext(
            topPadding = topPadding,
            hazeState = hazeState,
        ),
        content = content,
    )
}
