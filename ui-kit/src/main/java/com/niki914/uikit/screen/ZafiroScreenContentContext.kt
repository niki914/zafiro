package com.niki914.uikit.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.LiquidScreenContentContext
import com.niki914.uikit.infra.LocalLiquidScreenContentContext
import com.niki914.uikit.infra.ProvideLiquidScreenContentForPreview
import com.niki914.uikit.infra.ReportTitleBarCollapsed as InfraReportTitleBarCollapsed
import com.niki914.uikit.infra.liquidScreenActionButtonBottomPadding
import com.niki914.uikit.infra.liquidScreenBottomPadding
import com.niki914.uikit.infra.liquidScreenTopPadding

typealias ZafiroScreenContentContext = LiquidScreenContentContext

val LocalZafiroScreenContentContext: ProvidableCompositionLocal<ZafiroScreenContentContext> =
    LocalLiquidScreenContentContext

@Composable
fun zafiroScreenTopPadding(extra: Dp = 0.dp): Dp =
    liquidScreenTopPadding(extra)

@Composable
fun zafiroScreenBottomPadding(extra: Dp = 0.dp): Dp =
    liquidScreenBottomPadding(extra)

@Composable
fun zafiroActionButtonBottomPadding(extra: Dp = 0.dp): Dp =
    liquidScreenActionButtonBottomPadding(extra)

@Composable
fun ProvideZafiroScreenContentForPreview(
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
    actionButtonBottomPadding: Dp = 24.dp,
    isTitleCollapsible: Boolean = true,
    content: @Composable () -> Unit,
) = ProvideLiquidScreenContentForPreview(
    topPadding = topPadding,
    bottomPadding = bottomPadding,
    actionButtonBottomPadding = actionButtonBottomPadding,
    isTitleCollapsible = isTitleCollapsible,
    content = content,
)

@Composable
fun ReportTitleBarCollapsed(
    isCollapsed: () -> Boolean,
) = InfraReportTitleBarCollapsed(isCollapsed)
