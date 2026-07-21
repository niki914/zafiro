package com.niki914.nexus.agentic.app.ui.infra.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@Composable
fun TintLiquidButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes leadingIconRes: Int? = null,
    @DrawableRes trailingIconRes: Int? = null,
    trailingIcon: ImageVector? = null,
    leadingIconContentDescription: String? = null,
    trailingIconContentDescription: String? = null,
    tintLeadingIcon: Boolean = true,
    tintTrailingIcon: Boolean = true,
    darkContainerColor: Color = Color.Unspecified,
    lightContainerColor: Color = Color.Unspecified,
    darkContentColor: Color = Color.Unspecified,
    lightContentColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    buttonHeight: Dp = 48.dp,
    isLoading: Boolean = false,
) {
    val isDarkTheme = isSystemInDarkTheme()
    val backdrop = rememberLayerBackdrop()
    val disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
    val disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    val hasExplicitContainerColor = darkContainerColor.isSpecified ||
            lightContainerColor.isSpecified
    val resolvedContainerColor = when {
        darkContainerColor.isSpecified && lightContainerColor.isSpecified -> {
            if (isDarkTheme) darkContainerColor else lightContainerColor
        }

        darkContainerColor.isSpecified -> darkContainerColor
        lightContainerColor.isSpecified -> lightContainerColor
        else -> MaterialTheme.colorScheme.primary
    }
    val resolvedSurfaceColor = liquidButtonSurfaceColor(resolvedContainerColor)
    val resolvedContentColor = when {
        contentColor.isSpecified -> contentColor
        darkContentColor.isSpecified && lightContentColor.isSpecified -> {
            if (isDarkTheme) darkContentColor else lightContentColor
        }

        darkContentColor.isSpecified -> darkContentColor
        lightContentColor.isSpecified -> lightContentColor
        hasExplicitContainerColor -> {
            if (resolvedContainerColor.luminance() > 0.42f) Color(0xFF0F172A) else Color.White
        }

        else -> MaterialTheme.colorScheme.onPrimary
    }

    val isButtonInteractive = enabled && !isLoading

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = buttonHeight + 8.dp)
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        LiquidButton(
            onClick = if (isButtonInteractive) onClick else { -> },
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            isInteractive = isButtonInteractive,
            tint = if (enabled) resolvedContainerColor else disabledContainerColor,
            surfaceColor = if (enabled) resolvedSurfaceColor else Color.Transparent,
            height = buttonHeight,
        ) {
            val currentContentColor = if (enabled) {
                resolvedContentColor
            } else {
                disabledContentColor
            }
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = currentContentColor,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else if (leadingIconRes == null && trailingIconRes == null && trailingIcon == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        8.dp,
                        Alignment.CenterHorizontally
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleMedium,
                        color = currentContentColor,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leadingIconRes?.let { iconRes ->
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = leadingIconContentDescription,
                            tint = if (tintLeadingIcon) currentContentColor else Color.Unspecified,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleMedium,
                        color = currentContentColor,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1f),
                    )
                    if (trailingIconRes != null || trailingIcon != null) {
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    trailingIconRes?.let { iconRes ->
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = trailingIconContentDescription,
                            tint = if (tintTrailingIcon) currentContentColor else Color.Unspecified,
                            modifier = Modifier.size(20.dp),
                        )
                    } ?: trailingIcon?.let { icon ->
                        Icon(
                            imageVector = icon,
                            contentDescription = trailingIconContentDescription,
                            tint = if (tintTrailingIcon) currentContentColor else Color.Unspecified,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
