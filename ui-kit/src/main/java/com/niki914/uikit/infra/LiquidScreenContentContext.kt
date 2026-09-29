package com.niki914.uikit.infra

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.nav.LocalNavigationEntry
import com.niki914.uikit.infra.nav.NavigationEntry
import com.niki914.uikit.infra.nav.Page

/**
 * LiquidScreen 内容树的壳层上下文。
 *
 * 业务页面应由 `LiquidScreen` 承载；Preview 或独立样例请使用
 * `ProvideLiquidScreenContentForPreview` 包裹。
 *
 * 顶栏折叠信号不在此下发：它归属导航条目（`NavigationEntry.titleBarScroll`），
 * 默认由 `LiquidScreen` 经 nestedScroll 自动写入，页面无需参与；仅需定制的
 * 页面用 `ReportTitleBarCollapsed` 接管。
 */
@Stable
class LiquidScreenContentContext internal constructor(
    val topPadding: Dp,
    /** 底部安全距离 = 系统导航栏 inset + 设计间距。
     * 由 `LiquidScreen` 计算并下发，业务经 `liquidScreenBottomPadding` 读取，
     * 不自行读取 WindowInsets，避免多来源漂移。 */
    val bottomPadding: Dp,
)

/** Collapsible 页的顶栏折叠阈值：与大标题滚走的距离一致（页面大标题淡出同用此值）。 */
val TitleBarCollapseThreshold = 55.dp

/**
 * 用页面自身的滚动状态提供精确的顶栏折叠信号，接管壳层的自动感知。
 *
 * 默认路径是壳层经 nestedScroll 累积滚离量，页面不需参与。但累积量只能反映
 * **手势**产生的位移，存在两类它看不见的情况：
 *
 * - 程序化定位：自动贴底、恢复滚动位置等非手势位移完全不计入，导致列表已在
 *   中部而累积量仍为 0，顶栏保持透明（Home Chat 的自动贴底即属此类）。
 * - 页内嵌套滚动子树：如工具结果框自带滚动，其位移会被误计入页面滚离量。
 *
 * 因此**仅当页面命中上述任一条**时才调用本函数，用自身的滚动状态接管；
 * 这属于显式声明的特例，其余页面应保持自动感知。
 *
 * 不可滚动的页面无需调用（条目默认 false = 背景板透明）。
 */
@Composable
fun ReportTitleBarCollapsed(isCollapsed: () -> Boolean) {
    val titleBarScroll = LocalNavigationEntry.current.titleBarScroll
    LaunchedEffect(titleBarScroll) {
        snapshotFlow(isCollapsed).collect { titleBarScroll.setFromPage(it) }
    }
}

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

/**
 * 内容区底部安全距离（已含系统导航栏 inset）。
 *
 * 与 [liquidScreenTopPadding] 对称：默认值由壳层下发，业务可传 `extra` 追加，
 * 也可在容器参数（如 `contentBottomPadding`）上显式覆盖重写。
 */
@Composable
fun liquidScreenBottomPadding(extra: Dp = 0.dp): Dp {
    return LocalLiquidScreenContentContext.current.bottomPadding + extra
}

private object PreviewPage : Page {
    override val routeKey: String = "preview"
}

@Composable
fun ProvideLiquidScreenContentForPreview(
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalLiquidScreenContentContext provides LiquidScreenContentContext(
            topPadding = topPadding,
            bottomPadding = bottomPadding,
        ),
        LocalNavigationEntry provides NavigationEntry(id = "preview", page = PreviewPage),
        content = content,
    )
}
