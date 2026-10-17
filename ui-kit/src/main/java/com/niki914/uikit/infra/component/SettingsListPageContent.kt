package com.niki914.uikit.infra.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.uikit.infra.LocalLiquidScreenContentContext
import com.niki914.uikit.infra.ReportTitleBarCollapsed
import com.niki914.uikit.infra.TitleBarCollapseThreshold
import com.niki914.uikit.infra.liquidScreenBottomPadding
import com.niki914.uikit.infra.liquidScreenTopPadding
import com.niki914.uikit.infra.nav.LocalPageTitle

import com.niki914.uikit.infra.scrollbar.LiquidScrollableContainer
import com.niki914.uikit.infra.scrollbar.LiquidScrollbarDefaults
import com.niki914.uikit.infra.scrollbar.rememberLiquidOverscrollController
import com.niki914.uikit.infra.scrollbar.rememberScrollStateAdapter

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
 * @param onBackgroundTap 点击列表背景空白处时的回调（如用于收起已展开的滑块或输入框）。
 */
@Composable
fun SettingsListPageContent(
    description: String? = null,
    modifier: Modifier = Modifier,
    contentBottomPadding: Dp? = null,
    onBackgroundTap: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState =
        rememberSaveable(saver = ScrollState.Saver, init = { ScrollState(initial = 0) })
    val collapseRangePx = with(LocalDensity.current) { TitleBarCollapseThreshold.toPx() }
    val isTitleCollapsible = LocalLiquidScreenContentContext.current.isTitleCollapsible

    val resolvedBottomPadding = contentBottomPadding ?: liquidScreenBottomPadding()
    val overscrollController = rememberLiquidOverscrollController()
    val scrollbarState = rememberScrollStateAdapter(scrollState, overscrollController)

    // 精确接管顶栏折叠：
    // - Collapsible 页：内容滚出超过大标题高度或向上拉扯超滚时折叠；回弹归位或滚回顶部绝对变回透明；
    // - Pinned 页：首屏即常驻小标题，产生任何滚动或向上拉扯时显示顶栏背景，归零时背景恢复透明。
    ReportTitleBarCollapsed {
        if (isTitleCollapsible) {
            val upwardOverscrollPx = (-overscrollController.overscrollOffset.value).coerceAtLeast(0f)
            val effectiveScrollPx = scrollState.value + upwardOverscrollPx / LiquidScrollbarDefaults.RubberBandDamping
            effectiveScrollPx > collapseRangePx
        } else {
            scrollState.value > 0 || overscrollController.overscrollOffset.value < 0f
        }
    }

    val contentModifier = if (onBackgroundTap != null) {
        Modifier.pointerInput(onBackgroundTap) {
            detectTapGestures(onTap = { onBackgroundTap() })
        }
    } else {
        Modifier
    }

    LiquidScrollableContainer(
        state = scrollbarState,
        overscrollController = overscrollController,
        topClearance = liquidScreenTopPadding(24.dp),
        bottomClearance = resolvedBottomPadding,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(contentModifier)
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
        if (isTitleCollapsible && pageTitle.isNotBlank()) {
            Text(
                text = pageTitle,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 30.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        // 大标题随滚动与向上拉扯超滚连续淡出（跟随内容移动，与顶栏布尔动画互补）。
                        val upwardOverscrollPx = (-overscrollController.overscrollOffset.value).coerceAtLeast(0f)
                        val effectiveScrollPx = scrollState.value + upwardOverscrollPx / LiquidScrollbarDefaults.RubberBandDamping
                        alpha = 1f - (effectiveScrollPx / collapseRangePx).coerceIn(0f, 1f)
                    },
            )
        }
        if (!description.isNullOrBlank()) {
            PageDescriptionText(text = description)
        }
        content()
    }
}
}
