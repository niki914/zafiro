package com.niki914.nexus.agentic.app.ui.infra.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 正式的 infra toggle 入口。
 * 具体视觉和交互继续复用 StyledSwitch 的稳定实现，避免 settings 与主线分叉。
 */
@Composable
fun LiquidToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    val colorScheme = MaterialTheme.colorScheme
    val currentChecked by rememberUpdatedState(checked)
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)

    val onColor = if (enabled) colorScheme.primary
    else colorScheme.onSurface.copy(alpha = 0.12f)
    val offColor = if (enabled) colorScheme.surfaceVariant
    else colorScheme.onSurface.copy(alpha = 0.12f)
    val thumbBackdropFallback = colorScheme.surface

    val trackWidth = 64.dp
    val trackHeight = 28.dp
    val thumbHeight = 24.dp
    val thumbWidth = 39.dp

    val backdrop = rememberLayerBackdrop()
    val switchBackdrop = rememberLayerBackdrop()
    val animatedFraction = remember { Animatable(if (checked) 1f else 0f) }
    val trackWidthPx = remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val progressAnimationSpec = spring(0.5f, 300f, 0.001f)
    val progressAnimation = remember { Animatable(0f) }
    val stateMachine = remember { LiquidToggleStateMachine(initialChecked = checked) }
    var interactionState by remember {
        mutableStateOf<LiquidToggleInteractionState>(
            LiquidToggleInteractionState.Settled(checked = checked)
        )
    }
    val innerShadowLayer = rememberGraphicsLayer().apply {
        compositingStrategy = CompositingStrategy.Offscreen
    }
    val visualChecked = liquidToggleVisualChecked(checked = checked, state = interactionState)
    val targetColor = if (visualChecked) onColor else offColor
    val animatedTrackColor by animateColorAsState(targetColor, label = "trackColor")
    fun applyTransition(transition: LiquidToggleTransition) {
        interactionState = transition.state
        if (transition.emitThresholdTick) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
        transition.commitChecked?.let { nextChecked ->
            if (nextChecked != currentChecked) {
                haptics.performHapticFeedback(
                    if (nextChecked) HapticFeedbackType.ToggleOn
                    else HapticFeedbackType.ToggleOff
                )
                currentOnCheckedChange(nextChecked)
            }
        }
    }
    LaunchedEffect(checked) {
        interactionState = stateMachine.onExternalCheckedChange(checked).state
    }
    LaunchedEffect(interactionState) {
        when (val current = interactionState) {
            is LiquidToggleInteractionState.Settled -> {
                coroutineScope {
                    launch {
                        animatedFraction.animateTo(
                            if (current.checked) 1f else 0f,
                            progressAnimationSpec
                        )
                    }
                    launch {
                        progressAnimation.animateTo(0f, progressAnimationSpec)
                    }
                }
            }

            is LiquidToggleInteractionState.Pressed -> {
                coroutineScope {
                    launch {
                        animatedFraction.animateTo(current.fraction, progressAnimationSpec)
                    }
                    launch {
                        progressAnimation.animateTo(1f, progressAnimationSpec)
                    }
                }
            }

            is LiquidToggleInteractionState.Dragging -> {
                coroutineScope {
                    launch {
                        animatedFraction.snapTo(current.fraction)
                    }
                    launch {
                        progressAnimation.animateTo(1f, progressAnimationSpec)
                    }
                }
            }

            is LiquidToggleInteractionState.Releasing -> {
                coroutineScope {
                    launch {
                        animatedFraction.animateTo(current.targetFraction, progressAnimationSpec)
                    }
                    launch {
                        progressAnimation.animateTo(0f, progressAnimationSpec)
                    }
                }
                if (interactionState == current) {
                    applyTransition(stateMachine.onReleaseAnimationFinished())
                }
            }
        }
    }

    Box(
        modifier = modifier
            .width(trackWidth)
            .height(trackHeight),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .layerBackdrop(switchBackdrop)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(animatedTrackColor)
                .width(trackWidth)
                .height(trackHeight)
                .onSizeChanged { trackWidthPx.floatValue = it.width.toFloat() }
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .graphicsLayer {
                    translationX =
                        animatedFraction.value * (trackWidthPx.floatValue - with(density) { thumbWidth.toPx() + 4.dp.toPx() })
                }
                .then(
                    if (enabled) {
                        Modifier
                            .pointerInput(enabled) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val draggableWidth =
                                        trackWidthPx.floatValue -
                                                with(density) { thumbWidth.toPx() + 4.dp.toPx() }
                                    if (draggableWidth <= 0f) {
                                        waitForUpOrCancellation()
                                        return@awaitEachGesture
                                    }

                                    var pointerId = down.id
                                    var dragStarted = false

                                    fun startDragIfNeeded() {
                                        if (!dragStarted) {
                                            dragStarted = true
                                            applyTransition(
                                                stateMachine.onDragStart(
                                                    fraction = animatedFraction.value.fastCoerceIn(
                                                        0f,
                                                        1f
                                                    )
                                                )
                                            )
                                        }
                                    }

                                    fun dragBy(delta: Float) {
                                        val newFraction =
                                            (animatedFraction.value + delta / draggableWidth).fastCoerceIn(
                                                -0.3f,
                                                1.3f
                                            )
                                        applyTransition(stateMachine.onDragProgress(newFraction))
                                    }

                                    val slopChange =
                                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                            awaitTouchSlopOrCancellation(pointerId) { change, overSlop ->
                                                pointerId = change.id
                                                startDragIfNeeded()
                                                dragBy(overSlop.x)
                                                change.consume()
                                            }
                                        }

                                    if (slopChange != null) {
                                        drag(pointerId) { change ->
                                            dragBy(change.positionChange().x)
                                            change.consume()
                                        }
                                        applyTransition(stateMachine.onDragStop())
                                        return@awaitEachGesture
                                    }

                                    val isStillPressed =
                                        currentEvent.changes.any { it.id == pointerId && it.pressed }
                                    if (!isStillPressed) {
                                        applyTransition(stateMachine.onThumbTap())
                                        return@awaitEachGesture
                                    }

                                    applyTransition(
                                        stateMachine.onPress(
                                            fraction = animatedFraction.value.fastCoerceIn(0f, 1f)
                                        )
                                    )

                                    val pressedSlopChange =
                                        awaitTouchSlopOrCancellation(pointerId) { change, overSlop ->
                                            pointerId = change.id
                                            startDragIfNeeded()
                                            dragBy(overSlop.x)
                                            change.consume()
                                        }

                                    if (pressedSlopChange != null) {
                                        drag(pointerId) { change ->
                                            dragBy(change.positionChange().x)
                                            change.consume()
                                        }
                                        applyTransition(stateMachine.onDragStop())
                                    } else {
                                        applyTransition(stateMachine.onPressEnd())
                                    }
                                }
                            }
                    } else {
                        Modifier
                    }
                )
                .drawBackdrop(
                    rememberCombinedBackdrop(backdrop, switchBackdrop),
                    { RoundedCornerShape(thumbHeight / 2) },
                    highlight = {
                        val progress = progressAnimation.value
                        Highlight.Ambient.copy(
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(0.05f)
                        )
                    },
                    layerBlock = {
                        val progress = progressAnimation.value
                        val scale = lerp(1f, 1.5f, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawBackdrop = { drawScope ->
                        drawIntoCanvas { canvas ->
                            canvas.save()
                            canvas.drawRect(
                                left = 0f,
                                top = 0f,
                                right = size.width,
                                bottom = size.height,
                                paint = Paint().apply {
                                    color = thumbBackdropFallback
                                }
                            )
                            scale(0.7f) {
                                drawScope()
                            }
                        }
                    },
                    onDrawSurface = {
                        val progress = progressAnimation.value.fastCoerceIn(0f, 1f)

                        val shape = RoundedCornerShape(thumbHeight / 2)
                        val outline = shape.createOutline(size, layoutDirection, this)
                        val innerShadowOffset = 4f.dp.toPx()
                        val innerShadowBlurRadius = 4f.dp.toPx()

                        innerShadowLayer.alpha = progress
                        innerShadowLayer.renderEffect =
                            BlurEffect(
                                innerShadowBlurRadius,
                                innerShadowBlurRadius,
                                TileMode.Decal
                            )
                        innerShadowLayer.record {
                            drawOutline(outline, Color.Black.copy(0.2f))
                            translate(0f, innerShadowOffset) {
                                drawOutline(
                                    outline,
                                    Color.Transparent,
                                    blendMode = BlendMode.Clear
                                )
                            }
                        }
                        drawLayer(innerShadowLayer)

                        drawRect(Color.White.copy(1f - progress))
                    },
                    effects = {
                        lens(
                            refractionHeight = 6f.dp.toPx(),
                            refractionAmount = size.height / 2f,
                            depthEffect = true,
                            chromaticAberration = true
                        )
                    }
                )
                .width(thumbWidth)
                .height(thumbHeight)
        )
    }
}
