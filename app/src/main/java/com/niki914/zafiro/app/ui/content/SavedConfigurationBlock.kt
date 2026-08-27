package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.R
import com.niki914.uikit.infra.component.SwipeDismissSettingsItemCard
import com.niki914.zafiro.app.ui.model.SavedConfigSummary

/**
 * Saved Configuration 列表块：
 * ◉ = 当前生效配置（纯指示器，不可点击）；
 * 行点击 = 切换为生效配置；尾随「编辑」= 加载进上方表单编辑；整行左滑 = 删除。
 */
@Composable
internal fun SavedConfigurationBlock(
    configs: List<SavedConfigSummary>,
    onEditClick: (String) -> Unit,
    onActivateClick: (String) -> Unit,
    onDeleteRequest: (String) -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.ui_settings_saved_configuration_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Column(
            modifier = Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            configs.forEach { config ->
                key(config.id) {
                    SwipeDismissSettingsItemCard(
                        title = config.name,
                        summary = config.modelId,
                        leadingContent = {
                            ActiveConfigDot(isActive = config.isActive)
                        },
                        onClick = { onActivateClick(config.id) },
                        trailingActionText = stringResource(R.string.ui_settings_saved_configuration_edit),
                        onTrailingActionClick = { onEditClick(config.id) },
                        onDismissRequest = { onDeleteRequest(config.id) },
                        showChevron = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveConfigDot(
    isActive: Boolean,
) {
    Icon(
        imageVector = if (isActive) {
            Icons.Default.RadioButtonChecked
        } else {
            Icons.Default.RadioButtonUnchecked
        },
        contentDescription = null,
        tint = if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        },
    )
}
