package com.niki914.nexus.agentic.app.ui.infra.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 组内开关子项：保持整行切换和透明 item 语义，由外层容器提供卡片边界。
 */
@Composable
fun SettingToggleItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    val currentChecked by rememberUpdatedState(checked)
    SettingsItemSurface(
        modifier = modifier,
        enabled = enabled,
        hapticFeedbackType = if (!currentChecked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
        onClick = { onCheckedChange(!currentChecked) },
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))

        LiquidToggle(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { newChecked ->
                if (enabled) onCheckedChange(newChecked)
            },
        )
    }
}
