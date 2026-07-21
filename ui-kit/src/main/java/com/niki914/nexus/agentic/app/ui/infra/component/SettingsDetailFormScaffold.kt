package com.niki914.nexus.agentic.app.ui.infra.component

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.niki914.nexus.agentic.app.ui.infra.liquidScreenHazeSource
import com.niki914.nexus.agentic.app.ui.infra.liquidScreenTopPadding

/**
 * 设置详情表单脚手架，必须运行在 `LiquidScreen` 内容树内。
 *
 * Preview 或独立样例请用 `ProvideLiquidScreenContentForPreview` 提供壳层上下文。
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
    actionButtonDarkContainerColor: Color = Color.Unspecified,
    actionButtonLightContainerColor: Color = Color.Unspecified,
    actionButtonDarkContentColor: Color = Color.Unspecified,
    actionButtonLightContentColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val contentModifier = if (onBackgroundTap != null) {
        Modifier.pointerInput(onBackgroundTap) {
            detectTapGestures(onTap = { onBackgroundTap() })
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .liquidScreenHazeSource(),
    ) {
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    horizontal = SettingsDetailPageDefaults.HorizontalPadding,
                )
                .padding(
                    top = liquidScreenTopPadding(SettingsDetailPageDefaults.VerticalPadding),
                    bottom = SettingsDetailPageDefaults.VerticalPadding +
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
            darkContainerColor = actionButtonDarkContainerColor,
            lightContainerColor = actionButtonLightContainerColor,
            darkContentColor = actionButtonDarkContentColor,
            lightContentColor = actionButtonLightContentColor,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = SettingsDetailPageDefaults.HorizontalPadding,
                    end = SettingsDetailPageDefaults.HorizontalPadding,
                    bottom = SettingsDetailPageDefaults.VerticalPadding,
                ),
        )
    }
}
