package com.niki914.nexus.agentic.app.ui.infra

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.niki914.nexus.agentic.app.ui.infra.interaction.ActionBarButtonInteractiveStyle
import com.niki914.nexus.agentic.app.ui.infra.interaction.InteractiveHighlight
import com.niki914.nexus.agentic.app.ui.infra.interaction.applyLiquidInteractiveTransform
import kotlinx.coroutines.launch

@Composable
internal fun ActionBarButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true,
    backdrop: LayerBackdrop = rememberLayerBackdrop(),
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val animationScope = rememberCoroutineScope()
    val interactiveStyle = ActionBarButtonInteractiveStyle
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val innerShadowLayer = rememberGraphicsLayer().apply {
        compositingStrategy = CompositingStrategy.Offscreen
    }
    val density = LocalDensity.current
    val isDarkTheme = isSystemInDarkTheme()
    val interactionSource = remember { MutableInteractionSource() }
    val buttonShape = RoundedCornerShape(56.dp)

    Box(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .requiredSize(with(density) { 48.sp.toDp() })
            .drawBackdrop(
                backdrop = backdrop,
                shape = { buttonShape },
                highlight = { Highlight.Ambient.copy(alpha = if (isDarkTheme) 1f else 0f) },
                shadow = {
                    Shadow(
                        radius = 12f.dp,
                        color = Color.Black.copy(if (isDarkTheme) 0.08f else 0.2f)
                    )
                },
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

                    drawRect(
                        (if (isDarkTheme) Color(0xFFAFAFAF) else Color.White).copy(
                            progress.coerceIn(0.15f, 0.35f)
                        )
                    )
                },
                effects = {
                    lens(
                        refractionHeight = 6f.dp.toPx(),
                        refractionAmount = size.height / 2f,
                        depthEffect = true,
                        chromaticAberration = true
                    )
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
