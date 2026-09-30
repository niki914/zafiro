package com.niki914.uikit.infra.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.uikit.infra.TitleBarCollapseThreshold
import com.niki914.uikit.infra.liquidScreenBottomPadding
import com.niki914.uikit.infra.liquidScreenTopPadding
import com.niki914.uikit.infra.nav.LocalPageTitle

/**
 * 设置列表页容器，必须运行在 `LiquidScreen` 内容树内。
 *
 * 内容顶部渲染页面大标题（`LocalPageTitle`，由页面宿主按 entry 提供），
 * 随滚动淡出；顶栏背景渐显与小标题浮现由 `LiquidScreen` 经 nestedScroll 自动感知，
 * 页面无需上报。
 *
 * Preview 或独立样例请用 `ProvideLiquidScreenContentForPreview` 提供壳层上下文。
 *
 * @param contentBottomPadding 内容区底部安全距离。null（默认）走壳层下发的
 * `liquidScreenBottomPadding()`；非空则严格使用传入值，供业务主动覆盖重写。
 */
@Composable
fun SettingsListPageContent(
    description: String? = null,
    modifier: Modifier = Modifier,
    contentBottomPadding: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState =
        rememberSaveable(saver = ScrollState.Saver, init = { ScrollState(initial = 0) })
    val collapseRangePx = with(LocalDensity.current) { TitleBarCollapseThreshold.toPx() }

    val resolvedBottomPadding = contentBottomPadding ?: liquidScreenBottomPadding()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(
                start = 16.dp,
                end = 16.dp,
                // 顶部原来为 topPadding 与 24dp 两层叠加，此处保持等价
                top = liquidScreenTopPadding(24.dp),
                bottom = resolvedBottomPadding,
            ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val pageTitle = LocalPageTitle.current
        if (pageTitle.isNotBlank()) {
            Text(
                text = pageTitle,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp),
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
