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

@Stable
class LiquidScreenContentContext internal constructor(
    val topPadding: Dp,
    /** 底部安全距离 = 系统导航栏 inset + 设计间距。
     * 由 `LiquidScreen` 计算并下发，业务经 `liquidScreenBottomPadding` 读取，
     * 不自行读取 WindowInsets，避免多来源漂移。 */
    val bottomPadding: Dp,
)

/**
 * LiquidScreen 内容树的壳层上下文。
 *
 * 业务页面应由 `LiquidScreen` 承载；Preview 或独立样例请使用
 * `ProvideLiquidScreenContentForPreview` 包裹。
 */
/**
 * 把页面自身的「内容是否已滚离顶部」写入当前导航条目（`LocalNavigationEntry`）。
 *
 * 折叠状态归属条目（`NavigationEntry.titleCollapsed`）：action bar 只拉取
 * 当前条目的状态，因此过渡期退场页的写入不会干扰新页；条目在栈内存活期间
 * 状态保留，返回本页时 bar 首帧即恢复离开前的状态（配合 alpha 动画平滑过渡）。
 *
 * 不可滚动的页面无需调用（条目默认 false = 背景板透明）。
 */
@Composable
fun ReportTitleBarCollapsed(isCollapsed: () -> Boolean) {
    val entry = LocalNavigationEntry.current
    LaunchedEffect(entry) {
        snapshotFlow(isCollapsed).collect { entry.titleCollapsed = it }
    }
}

// ponytail: 折叠信号唯一来源是条目上的 titleCollapsed（bar 拉、页面推自己槽）；
// 若再出现第二个信号通道，应先删掉旧的再考虑新的。

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
