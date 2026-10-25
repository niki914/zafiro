package com.niki914.uikit.menu

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.LiquidMenuItem
import com.niki914.uikit.infra.shape.G2FieldShape

@Composable
fun ZafiroMenuItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checked: Boolean? = null,
    leadingIcon: ImageVector? = null,
    trailingText: String? = null,
) {
    when (LocalThemeStyle.current) {
        ThemeStyle.LiquidGlass -> {
            LiquidMenuItem(
                title = title,
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                checked = checked,
                leadingIcon = leadingIcon,
                trailingText = trailingText,
            )
        }
        ThemeStyle.Material3,
        ThemeStyle.Material3Expressive -> {
            MaterialMenuItem(
                title = title,
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                checked = checked,
                leadingIcon = leadingIcon,
                trailingText = trailingText,
            )
        }
    }
}

@Composable
internal fun MaterialMenuItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checked: Boolean? = null,
    leadingIcon: ImageVector? = null,
    trailingText: String? = null,
) {
    val themeStyle = LocalThemeStyle.current
    val isExpressive = themeStyle == ThemeStyle.Material3Expressive
    val isChecked = checked == true
    val isDarkTheme = LocalAppDarkTheme.current
    val colorScheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val itemShape = if (isExpressive && isChecked) G2FieldShape(12.dp) else G2FieldShape(16.dp)

    val targetBgColor = when {
        isChecked -> {
            if (isPressed) {
                colorScheme.secondaryContainer.copy(alpha = 0.82f)
            } else {
                colorScheme.secondaryContainer
            }
        }
        isPressed -> (if (isDarkTheme) Color.White else Color.Black).copy(alpha = 0.08f)
        else -> Color.Transparent
    }

    val animatedBgColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = tween(durationMillis = 240, easing = LinearOutSlowInEasing),
        label = "materialMenuItemBgColor",
    )

    val contentColor = when {
        !enabled -> colorScheme.onSurface.copy(alpha = 0.38f)
        isChecked -> colorScheme.onSecondaryContainer
        else -> colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(itemShape)
            .background(animatedBgColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (enabled) {
                    if (isChecked) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant
                } else {
                    colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
            )
        }

        if (trailingText != null) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = trailingText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isChecked) {
                        colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    } else {
                        colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        if (isChecked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                modifier = Modifier.size(18.dp),
                tint = colorScheme.onSecondaryContainer,
            )
        }
    }
}
