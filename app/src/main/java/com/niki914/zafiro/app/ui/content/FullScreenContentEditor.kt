package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.niki914.uikit.infra.component.PageDescriptionText
import com.niki914.uikit.infra.component.SettingsDetailPageDefaults
import com.niki914.uikit.infra.component.TintLiquidButton
import com.niki914.uikit.infra.liquidScreenTopPadding

/**
 * 整页内容编辑器骨架（infra）：描述文案 + 全页 TextField + 底部保存按钮 + 内联错误。
 * 从 SkillDetailContent 提取；Prompt 编辑页与 Skill 详情页共用。
 * 顶层 chrome（未保存确认、删除确认）仍由调用方经 [EditableSettingsDetailChrome] 提供。
 */
@Composable
internal fun FullScreenContentEditor(
    description: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    actionText: String,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
    actionLoading: Boolean = false,
    inlineErrorText: String? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = SettingsDetailPageDefaults.HorizontalPadding)
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
            PageDescriptionText(text = description)
            TextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            inlineErrorText?.let { errorText ->
                Text(
                    text = errorText,
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
            enabled = enabled,
            isLoading = actionLoading,
            onClick = onActionClick,
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
