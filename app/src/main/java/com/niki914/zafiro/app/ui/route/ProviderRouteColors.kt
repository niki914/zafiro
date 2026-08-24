package com.niki914.zafiro.app.ui.route

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import com.niki914.zafiro.app.ui.model.ProviderButtonTokens
import com.niki914.zafiro.app.ui.model.ProviderSpec

internal data class ProviderButtonColors(
    val darkContainerColor: Color,
    val lightContainerColor: Color,
    val darkContentColor: Color,
    val lightContentColor: Color,
)

@Composable
internal fun providerButtonColors(spec: ProviderSpec): ProviderButtonColors {
    return spec.visualTokens.button.toProviderButtonColors()
}

@Composable
internal fun ProviderButtonTokens.toProviderButtonColors(): ProviderButtonColors {
    val colorScheme = MaterialTheme.colorScheme
    return ProviderButtonColors(
        darkContainerColor = darkContainerColorRes?.let { id -> colorResource(id) }
            ?: colorScheme.primary,
        lightContainerColor = lightContainerColorRes?.let { id -> colorResource(id) }
            ?: colorScheme.primary,
        darkContentColor = darkContentColorRes?.let { id -> colorResource(id) }
            ?: colorScheme.onPrimary,
        lightContentColor = lightContentColorRes?.let { id -> colorResource(id) }
            ?: colorScheme.onPrimary,
    )
}
