package com.niki914.nexus.agentic.app.ui.infra.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun SettingsToggleListItemCard(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    SettingsGroupCard(
        title = null,
        modifier = modifier,
    ) {
        SettingsToggleListItemRow(
            title = title,
            summary = summary,
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            onClick = onClick,
        )
    }
}

@Composable
private fun SettingsToggleListItemRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    onClick: (() -> Unit)?,
) {
    val currentChecked by rememberUpdatedState(checked)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsItemSurface(
            modifier = Modifier.weight(1f),
            enabled = enabled,
            contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 0.dp, bottom = 14.dp),
            onClick = onClick,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!summary.isNullOrBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        LiquidToggle(
            modifier = Modifier.padding(end = 16.dp),
            checked = checked,
            enabled = enabled,
            onCheckedChange = { newChecked ->
                if (enabled && newChecked != currentChecked) {
                    onCheckedChange(newChecked)
                }
            },
        )
    }
}
