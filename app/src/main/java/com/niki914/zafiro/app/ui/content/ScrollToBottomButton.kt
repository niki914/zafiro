package com.niki914.zafiro.app.ui.content

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.infra.LiquidGlassPresets
import com.niki914.uikit.infra.interaction.InteractiveHighlight
import com.niki914.uikit.infra.interaction.LiquidButtonInteractiveStyle
import com.niki914.uikit.infra.interaction.applyLiquidInteractiveTransform
import com.niki914.zafiro.app.R

@Composable
internal fun ScrollToBottomButton(
    visible: Boolean,
    bottomClearance: Dp,
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollToBottomScope = rememberCoroutineScope()
    val scrollToBottomInteractiveHighlight = remember(scrollToBottomScope) {
        InteractiveHighlight(animationScope = scrollToBottomScope)
    }
    val isDarkTheme = LocalAppDarkTheme.current
    val scrollToBottomShape = CircleShape
    val scrollToBottomSurfaceColor =
        LiquidGlassPresets.ScrollToBottom.surfaceColor(isDarkTheme, MaterialTheme.colorScheme)

    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(bottom = bottomClearance),
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(100)),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { scrollToBottomShape },
                    highlight = { LiquidGlassPresets.ScrollToBottom.highlight(isDarkTheme) },
                    shadow = { LiquidGlassPresets.ScrollToBottom.shadow(isDarkTheme) },
                    effects = {
                        LiquidGlassPresets.ScrollToBottom.applyEffects(this, isDarkTheme)
                    },
                    layerBlock = {
                        applyLiquidInteractiveTransform(
                            style = LiquidButtonInteractiveStyle,
                            pressProgress = scrollToBottomInteractiveHighlight.pressProgress,
                            offset = scrollToBottomInteractiveHighlight.offset,
                            size = size,
                        )
                    },
                    onDrawSurface = {
                        drawRect(scrollToBottomSurfaceColor)
                    },
                )
                .clip(scrollToBottomShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .then(scrollToBottomInteractiveHighlight.gestureModifier)
                .then(
                    if (LiquidButtonInteractiveStyle.highlightEnabled) {
                        scrollToBottomInteractiveHighlight.modifier
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_down),
                contentDescription = "Scroll To Bottom Icon",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
