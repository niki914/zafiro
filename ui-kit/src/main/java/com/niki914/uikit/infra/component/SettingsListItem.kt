package com.niki914.uikit.infra.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsListItem(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    currentState: String? = null,
    enabled: Boolean = true,
    showChevron: Boolean = false,
    highlightPulseKey: Any? = null,
    highlightPulseDurationMillis: Int = 500,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    // 无图标行的文字起点与有图标行的圆底左缘齐平（20dp），竖向视觉对齐。
    val contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 16.dp, bottom = 20.dp)

    SettingsItemSurface(
        modifier = modifier,
        enabled = enabled,
        contentPadding = contentPadding,
        highlightPulseKey = highlightPulseKey,
        highlightPulseDurationMillis = highlightPulseDurationMillis,
        onClick = onClick,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val titleAreaMaxWidth = maxWidth * 0.66f
            val stateAreaMaxWidth = maxWidth * 0.22f

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (leadingContent != null) {
                    leadingContent()
                }

                Column(
                    modifier = Modifier.widthIn(max = titleAreaMaxWidth),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
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

                Spacer(modifier = Modifier.weight(1f))

                if (trailingContent != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        content = trailingContent,
                    )
                } else {
                    if (currentState != null) {
                        Text(
                            text = currentState,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = stateAreaMaxWidth),
                        )
                    }

                    if (showChevron) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}
