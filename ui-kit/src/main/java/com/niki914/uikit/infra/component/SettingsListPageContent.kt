package com.niki914.uikit.infra.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ScrollState
import com.niki914.uikit.infra.nav.LocalPageTitle
import com.niki914.uikit.infra.LocalTitleBarCollapseState
import com.niki914.uikit.infra.liquidScreenTopPadding

/**
 * 设置列表页容器，必须运行在 `LiquidScreen` 内容树内。
 *
 * 内容顶部渲染页面大标题（`LocalPageTitle`，由页面宿主按 entry 提供），
 * 随滚动淡出并把折叠比例写回 `LocalTitleBarCollapseState`，
 * 由 `LiquidScreen` 驱动顶栏小标题浮现与背景色渐显。
 *
 * Preview 或独立样例请用 `ProvideLiquidScreenContentForPreview` 提供壳层上下文。
 */
@Composable
fun SettingsListPageContent(
    description: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberSaveable(saver = ScrollState.Saver, init = { ScrollState(initial = 0) })
    val collapseRangePx = with(LocalDensity.current) { 96.dp.toPx() }
    // 大标题完全滚离的布尔判定；derivedStateOf 避免逐帧重组。
    val isCollapsed by remember { derivedStateOf { scrollState.value > collapseRangePx } }

    val titleCollapseState = LocalTitleBarCollapseState.current
    // 用 snapshotFlow 订阅 derivedState：composition 不读它，不能用 SideEffect（不会重组重跑）。
    // snapshotFlow 启动时先发射当前值：返回本页时立即纠正共享状态，
    // 因此不需要 onDispose 重置（转场期间退场页写入后不会再次发射，不会覆盖新页的正确值）。
    LaunchedEffect(titleCollapseState) {
        snapshotFlow { isCollapsed }.collect { titleCollapseState.isCollapsed = it }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(top = liquidScreenTopPadding())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val pageTitle = LocalPageTitle.current
        if (pageTitle.isNotBlank()) {
            Text(
                text = pageTitle,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        // 大标题随滚动连续淡出（跟随内容移动，与顶栏布尔动画互补）。
                        alpha = 1f - (scrollState.value / collapseRangePx).coerceIn(0f, 1f)
                    },
            )
        }
        if (!description.isNullOrBlank()) {
            PageDescriptionText(text = description)
        }
        content()
    }
}
