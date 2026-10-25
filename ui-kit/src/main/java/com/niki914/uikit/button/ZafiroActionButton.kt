package com.niki914.uikit.button

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.ActionBarButton
import com.niki914.uikit.motion.springSquish

@Composable
fun ZafiroActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = null,
    onButtonBoundsChanged: ((Rect) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val themeStyle = LocalThemeStyle.current

    if (themeStyle == ThemeStyle.LiquidGlass) {
        ActionBarButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            backdrop = backdrop ?: emptyBackdrop(),
            onButtonBoundsChanged = onButtonBoundsChanged,
            content = content,
        )
    } else {
        val interactionSource = remember { MutableInteractionSource() }
        val buttonSize = 48.dp
        val buttonBgColor = MaterialTheme.colorScheme.surfaceContainerHighest

        val motionModifier = if (themeStyle == ThemeStyle.Material3Expressive && enabled) {
            Modifier.springSquish(interactionSource, targetScale = 0.92f)
        } else {
            Modifier
        }

        Box(
            modifier = modifier
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .then(
                    if (onButtonBoundsChanged != null) {
                        Modifier.onGloballyPositioned { coordinates ->
                            onButtonBoundsChanged(coordinates.boundsInRoot())
                        }
                    } else {
                        Modifier
                    }
                )
                .then(motionModifier)
                .requiredSize(buttonSize)
                .clip(CircleShape)
                .background(buttonBgColor, CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}
