package com.niki914.nexus.agentic.app.ui.infra.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@Composable
fun MaterialTintLiquidButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val backdrop = rememberLayerBackdrop()
    val disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
    val disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        LiquidButton(
            onClick = if (enabled) onClick else { -> },
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            isInteractive = enabled,
            tint = if (enabled) containerColor else disabledContainerColor,
            surfaceColor = if (enabled) liquidButtonSurfaceColor(containerColor) else Color.Transparent,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) contentColor else disabledContentColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
