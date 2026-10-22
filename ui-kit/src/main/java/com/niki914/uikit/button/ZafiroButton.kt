package com.niki914.uikit.button

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.component.MaterialTintLiquidButton
import com.niki914.uikit.infra.component.TintLiquidButton

@Composable
fun ZafiroButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    MaterialTintLiquidButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        containerColor = containerColor,
        contentColor = contentColor,
    )
}

@Composable
fun ZafiroTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    lightContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    darkContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    buttonHeight: Dp = 56.dp,
    isLoading: Boolean = false,
) {
    TintLiquidButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        lightContainerColor = lightContainerColor,
        darkContainerColor = darkContainerColor,
        contentColor = contentColor,
        buttonHeight = buttonHeight,
        isLoading = isLoading,
    )
}
