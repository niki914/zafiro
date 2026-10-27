package com.niki914.uikit.infra

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.infra.interaction.ActionBarButtonInteractiveStyle
import com.niki914.uikit.infra.interaction.InteractiveHighlight
import com.niki914.uikit.infra.interaction.applyLiquidInteractiveTransform
import kotlinx.coroutines.launch

@Composable
fun ActionBarButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true,
    backdrop: Backdrop = emptyBackdrop(),
    onButtonBoundsChanged: ((Rect) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val resolvedBackdrop = backdrop
    val haptics = LocalHapticFeedback.current
    val animationScope = rememberCoroutineScope()
    val interactiveStyle =
        ActionBarButtonInteractiveStyle
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val innerShadowLayer = rememberGraphicsLayer().apply {
        compositingStrategy = CompositingStrategy.Offscreen
    }
    val density = LocalDensity.current
    val isDarkTheme = LocalAppDarkTheme.current
    val colorScheme = MaterialTheme.colorScheme
    val baseSurfaceColor = LiquidGlassPresets.Button.surfaceColor(isDarkTheme, colorScheme)
    val interactionSource = remember { MutableInteractionSource() }
    val buttonShape = RoundedCornerShape(56.dp)

    Box(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .requiredSize(with(density) { 48.sp.toDp() })
            .then(
                if (onButtonBoundsChanged != null) {
                    Modifier.onGloballyPositioned { coordinates ->
                        onButtonBoundsChanged(coordinates.boundsInRoot())
                    }
                } else {
                    Modifier
                }
            )
            .drawBackdrop(
                backdrop = resolvedBackdrop,
                shape = { buttonShape },
                highlight = { LiquidGlassPresets.Button.highlight(isDarkTheme) },
                shadow = { LiquidGlassPresets.Button.shadow(isDarkTheme) },
                layerBlock = {
                    applyLiquidInteractiveTransform(
                        style = interactiveStyle,
                        pressProgress = interactiveHighlight.pressProgress,
                        offset = interactiveHighlight.offset,
                        size = size,
                    )
                },
                onDrawSurface = {
                    val progress = interactiveHighlight.pressProgress.coerceIn(0f, 1f)

                    if (progress > 0f) {
                        val outline = buttonShape.createOutline(size, layoutDirection, this)
                        val innerShadowOffset = 4f.dp.toPx()
                        val innerShadowBlurRadius = 4f.dp.toPx()

                        innerShadowLayer.alpha = progress
                        innerShadowLayer.renderEffect =
                            BlurEffect(innerShadowBlurRadius, innerShadowBlurRadius, TileMode.Decal)
                        innerShadowLayer.record {
                            drawOutline(outline, Color.Black.copy(0.2f))
                            translate(0f, innerShadowOffset) {
                                drawOutline(outline, Color.Transparent, blendMode = BlendMode.Clear)
                            }
                        }
                        drawLayer(innerShadowLayer)
                    }

                    val tintColor = colorScheme.primaryContainer.copy(alpha = 0.28f)
                    drawRect(tintColor, blendMode = BlendMode.Hue)
                    drawRect(baseSurfaceColor)
                },
                effects = {
                    LiquidGlassPresets.Button.applyEffects(this, isDarkTheme)
                },
            )
            .clickable(
                enabled = true,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
            ) {
                if (enabled) {
                    animationScope.launch { haptics.performHapticFeedback(HapticFeedbackType.ContextClick) }
                    onClick()
                }
            }
            .then(interactiveHighlight.gestureModifier)
            .then(
                if (interactiveStyle.highlightEnabled) {
                    interactiveHighlight.modifier
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
