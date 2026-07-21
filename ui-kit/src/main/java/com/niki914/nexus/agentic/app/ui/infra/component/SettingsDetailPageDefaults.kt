package com.niki914.nexus.agentic.app.ui.infra.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object SettingsDetailPageDefaults {
    val HorizontalPadding: Dp = 20.dp
    val VerticalPadding: Dp = 24.dp
    val RootVerticalSpacing: Dp = 20.dp
    val ContentVerticalSpacing: Dp = 20.dp
    val InlineErrorHorizontalPadding: Dp = 16.dp
    val ActionButtonReservedHeight: Dp = 56.dp
    val DividerHorizontalPadding: Dp = 12.dp
    val DividerThickness: Dp = 1.dp
}

@Composable
fun SettingsItemDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        thickness = SettingsDetailPageDefaults.DividerThickness,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = modifier.padding(
            horizontal = SettingsDetailPageDefaults.DividerHorizontalPadding,
        ),
    )
}
