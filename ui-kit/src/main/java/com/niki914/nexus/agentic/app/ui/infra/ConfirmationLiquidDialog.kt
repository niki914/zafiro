package com.niki914.nexus.agentic.app.ui.infra

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.nexus.agentic.app.ui.infra.component.MaterialTintLiquidButton

@Composable
fun ConfirmationLiquidDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    negativeButtonText: String,
    positiveButtonText: String,
    onNegativeClick: () -> Unit,
    onPositiveClick: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnBackgroundTap: Boolean = true,
) {
    LiquidDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        dismissOnBackgroundTap = dismissOnBackgroundTap,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Box(modifier = Modifier.padding(horizontal = 2.dp)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        actions = {
            MaterialTintLiquidButton(
                text = positiveButtonText,
                onClick = onPositiveClick,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
            MaterialTintLiquidButton(
                text = negativeButtonText,
                onClick = onNegativeClick,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
    )
}
