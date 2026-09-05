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
    val darkIconColor: Color? = null,
    val lightIconColor: Color? = null,
)

@Composable
internal fun providerButtonColors(spec: ProviderSpec): ProviderButtonColors {
    return spec.visualTokens.button.toProviderButtonColors()
}

/** 无显式品牌色（如 Gemini）时返回 null，调用方回退到中性圆底。 */
@Composable
internal fun providerButtonColorsOrNull(spec: ProviderSpec): ProviderButtonColors? {
    val button = spec.visualTokens.button
    val hasExplicitColors = button.darkContainerColorRes != null ||
            button.lightContainerColorRes != null
    return if (hasExplicitColors) button.toProviderButtonColors() else null
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
        darkIconColor = darkIconColorRes?.let { colorResource(it) },
        lightIconColor = lightIconColorRes?.let { colorResource(it) },
    )
}
