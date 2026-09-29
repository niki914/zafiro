package com.niki914.uikit.infra.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.liquidScreenBottomPadding
import com.niki914.uikit.infra.liquidScreenTopPadding

/**
 * 设置详情表单脚手架，必须运行在 `LiquidScreen` 内容树内。
 *
 * Preview 或独立样例请用 `ProvideLiquidScreenContentForPreview` 提供壳层上下文。
 *
 * @param contentBottomPadding 滚动内容区底部安全距离。null（默认）走壳层下发的
 * `liquidScreenBottomPadding()`；非空则覆盖重写。
 * @param actionButtonBottomPadding 吸底按钮的底边距。null（默认）走壳层下发的
 * `liquidScreenBottomPadding()`；非空则覆盖重写。与 `contentBottomPadding`
 * 分开暴露：内容区预留与按钮位置是两个独立诉求。
 */
@Composable
fun SettingsDetailFormScaffold(
    actionText: String,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    inlineErrorText: String? = null,
    actionEnabled: Boolean = true,
    onBackgroundTap: (() -> Unit)? = null,
    contentBottomPadding: Dp? = null,
    actionButtonBottomPadding: Dp? = null,
    actionButtonDarkContainerColor: Color = Color.Unspecified,
    actionButtonLightContainerColor: Color = Color.Unspecified,
    actionButtonDarkContentColor: Color = Color.Unspecified,
    actionButtonLightContentColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState =
        rememberSaveable(saver = ScrollState.Saver, init = { ScrollState(initial = 0) })
    // 折叠状态由 LiquidScreen 经 nestedScroll 自动感知，页面不再上报。
    val resolvedContentBottomPadding = contentBottomPadding ?: liquidScreenBottomPadding()
    val resolvedActionButtonBottomPadding =
        actionButtonBottomPadding ?: liquidScreenBottomPadding()
    val contentModifier = if (onBackgroundTap != null) {
        Modifier.pointerInput(onBackgroundTap) {
            detectTapGestures(onTap = { onBackgroundTap() })
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize(),
    ) {
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    horizontal = SettingsDetailPageDefaults.HorizontalPadding,
                )
                .padding(
                    top = liquidScreenTopPadding(
                        SettingsDetailPageDefaults.VerticalPadding
                    ),
                    // 底部预留必须 ≥ 按钮位高（底边距 + 按钮高），否则最后一张卡片会被吸底按钮盖住；
                    // 与下方按钮的 bottom 同源解析，两处必须一起改。
                    bottom = resolvedContentBottomPadding +
                            SettingsDetailPageDefaults.RootVerticalSpacing +
                            SettingsDetailPageDefaults.ActionButtonReservedHeight,
                ),
            verticalArrangement = Arrangement.spacedBy(
                SettingsDetailPageDefaults.ContentVerticalSpacing,
            ),
        ) {
            if (!description.isNullOrBlank()) {
                PageDescriptionText(text = description)
            }
            content()
            if (!inlineErrorText.isNullOrBlank()) {
                Text(
                    text = inlineErrorText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(
                        horizontal = SettingsDetailPageDefaults.InlineErrorHorizontalPadding,
                    ),
                )
            }
        }

        TintLiquidButton(
            text = actionText,
            enabled = actionEnabled,
            onClick = onActionClick,
            buttonHeight = 56.dp,
            darkContainerColor = actionButtonDarkContainerColor,
            lightContainerColor = actionButtonLightContainerColor,
            darkContentColor = actionButtonDarkContentColor,
            lightContentColor = actionButtonLightContentColor,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = SettingsDetailPageDefaults.HorizontalPadding,
                    end = SettingsDetailPageDefaults.HorizontalPadding,
                    bottom = resolvedActionButtonBottomPadding,
                ),
        )
    }
}
