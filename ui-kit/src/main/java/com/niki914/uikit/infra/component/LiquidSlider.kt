package com.niki914.uikit.infra.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.niki914.uikit.base.LocalAppDarkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 融入 Material 3 主题色与流体玻璃质感的高级滑块组件。
 *
 * 采用双层 Backdrop 融合体系：
 * - 轨道图层注册 `trackBackdrop`
 * - 滑块 Thumb 复合外部 `backdrop` 与 `trackBackdrop`，呈现放大镜式折射与按压形变
 */
@Composable
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    visibilityThreshold: Float = (valueRange.endInclusive - valueRange.start) / 1000f,
    onValueChangeFinished: (() -> Unit)? = null,
    backdrop: Backdrop = rememberLayerBackdrop(),
) {
    val haptics = LocalHapticFeedback.current
    val isDarkTheme = LocalAppDarkTheme.current
    val colorScheme = MaterialTheme.colorScheme

    val accentColor = if (enabled) {
        colorScheme.primary
    } else {
        colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val trackColor = if (enabled) {
        colorScheme.surfaceContainerHighest
    } else {
        colorScheme.onSurface.copy(alpha = 0.08f)
    }
    val thumbSurfaceColor = if (isDarkTheme) Color(0xFFAFAFAF) else Color.White

    val trackBackdrop = rememberLayerBackdrop()
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var didDrag by remember { mutableStateOf(false) }
        var currentDragValue by remember { mutableFloatStateOf(value()) }

        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = value(),
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.35f,
                onDragStarted = {
                    didDrag = true
                    currentDragValue = targetValue
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                onDragStopped = {
                    if (didDrag) {
                        currentOnValueChange(currentDragValue)
                        currentOnValueChangeFinished?.invoke()
                    }
                    didDrag = false
                },
                onDrag = { _, dragAmount ->
                    didDrag = true
                    val delta = (valueRange.endInclusive - valueRange.start) * (dragAmount.x / trackWidth)
                    currentDragValue = if (isLtr) {
                        (currentDragValue + delta).coerceIn(valueRange)
                    } else {
                        (currentDragValue - delta).coerceIn(valueRange)
                    }
                    updateDrag(currentDragValue)
                    currentOnValueChange(currentDragValue)
                },
            )
        }

        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { value() }
                .collectLatest { externalValue ->
                    if (!didDrag && abs(dampedDragAnimation.targetValue - externalValue) > visibilityThreshold) {
                        currentDragValue = externalValue
                        dampedDragAnimation.updateValue(externalValue)
                    }
                }
        }

        // 底层轨道：包含背景槽与激活槽
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .layerBackdrop(trackBackdrop)
                .height(6.dp)
                .align(Alignment.Center)
                .then(
                    if (enabled) {
                        Modifier.pointerInput(animationScope) {
                            detectTapGestures { position ->
                                val delta = (valueRange.endInclusive - valueRange.start) * (position.x / trackWidth)
                                val targetVal = (
                                    if (isLtr) valueRange.start + delta
                                    else valueRange.endInclusive - delta
                                ).coerceIn(valueRange)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentDragValue = targetVal
                                dampedDragAnimation.animateToValue(targetVal)
                                currentOnValueChange(targetVal)
                                currentOnValueChangeFinished?.invoke()
                            }
                        }
                    } else {
                        Modifier
                    }
                ),
        ) {
            val trackHeight = size.height
            val trackCornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)

            // 1. 底层底槽 (SurfaceContainerHighest)
            drawRoundRect(
                color = trackColor,
                size = size,
                cornerRadius = trackCornerRadius,
            )

            // 2. 激活槽：填充 Material 主题色 (Primary)
            val progressFraction = dampedDragAnimation.progress.coerceIn(0f, 1f)
            val activeWidth = size.width * progressFraction
            if (activeWidth > 0f) {
                if (isLtr) {
                    drawRoundRect(
                        color = accentColor,
                        size = Size(activeWidth, trackHeight),
                        cornerRadius = trackCornerRadius,
                    )
                } else {
                    drawRoundRect(
                        color = accentColor,
                        topLeft = Offset(size.width - activeWidth, 0f),
                        size = Size(activeWidth, trackHeight),
                        cornerRadius = trackCornerRadius,
                    )
                }
            }
        }

        // 滑块 Thumb：40dp x 24dp 胶囊流体玻璃体
        val thumbShape = RoundedCornerShape(12.dp)
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    translationX = (-size.width / 2f + trackWidth * dampedDragAnimation.progress.coerceIn(0f, 1f))
                        .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) * if (isLtr) 1f else -1f
                }
                .then(if (enabled) dampedDragAnimation.modifier else Modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, trackBackdrop),
                    shape = { thumbShape },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8.dp.toPx() * (1f - progress))
                        lens(
                            refractionHeight = 10.dp.toPx() * progress,
                            refractionAmount = 14.dp.toPx() * progress,
                            chromaticAberration = true,
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress * (if (isDarkTheme) 1f else 0.4f),
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4.dp,
                            color = Color.Black.copy(alpha = if (isDarkTheme) 0.08f else 0.12f),
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4.dp * progress,
                            alpha = progress,
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(thumbSurfaceColor.copy(alpha = 1f - progress))
                    },
                )
                .size(40.dp, 24.dp),
        )
    }
}

@Composable
fun LiquidSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    visibilityThreshold: Float = (valueRange.endInclusive - valueRange.start) / 1000f,
    onValueChangeFinished: (() -> Unit)? = null,
    backdrop: Backdrop = rememberLayerBackdrop(),
) = LiquidSlider(
    value = { value },
    onValueChange = onValueChange,
    modifier = modifier,
    enabled = enabled,
    valueRange = valueRange,
    visibilityThreshold = visibilityThreshold,
    onValueChangeFinished = onValueChangeFinished,
    backdrop = backdrop,
)

/**
 * 物理阻尼与弹性形变手势控制器
 */
internal class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {
    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()
    private var updateJob: Job? = null
    private var velocityJob: Job? = null

    var isDragging by mutableStateOf(false)
        private set
    var dragValue by mutableFloatStateOf(initialValue)
        private set

    val value: Float get() = if (isDragging) dragValue else valueAnimation.value
    val progress: Float
        get() {
            val span = valueRange.endInclusive - valueRange.start
            return if (span != 0f) (value - valueRange.start) / span else 0f
        }
    val targetValue: Float get() = if (isDragging) dragValue else valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                startDrag()
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                stopDrag()
                release()
            },
            onDragCancel = {
                onDragStopped()
                stopDrag()
                release()
            },
        ) { _, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun startDrag() {
        updateJob?.cancel()
        dragValue = valueAnimation.value
        isDragging = true
    }

    fun updateDrag(target: Float) {
        val clamped = target.coerceIn(valueRange)
        dragValue = clamped
        updateVelocity()
    }

    fun stopDrag() {
        if (isDragging) {
            val finalVal = dragValue
            updateJob?.cancel()
            updateJob = animationScope.launch {
                valueAnimation.snapTo(finalVal)
                isDragging = false
            }
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            withFrameNanos { }
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            if (velocity != 0f) {
                launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(target: Float) {
        val clamped = target.coerceIn(valueRange)
        updateJob?.cancel()
        updateJob = animationScope.launch {
            valueAnimation.animateTo(clamped, valueAnimationSpec) { updateVelocity() }
        }
    }

    fun animateToValue(target: Float) {
        updateJob?.cancel()
        updateJob = animationScope.launch {
            mutatorMutex.mutate {
                press()
                val clamped = target.coerceIn(valueRange)
                launch { valueAnimation.animateTo(clamped, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(System.currentTimeMillis(), Offset(value, 0f))
        val span = valueRange.endInclusive - valueRange.start
        val targetVelocity = if (span != 0f) velocityTracker.calculateVelocity().x / span else 0f
        velocityJob?.cancel()
        velocityJob = animationScope.launch {
            velocityAnimation.snapTo(targetVelocity)
        }
    }
}

/**
 * 触摸拖拽手势检测器
 */
private suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)
        val down = awaitFirstDown(false)
        val drag = initialDown

        onDragStart(down)
        onDrag(drag, Offset.Zero)
        val upEvent = drag(
            pointerId = drag.id,
            onDrag = { change ->
                val delta = change.positionChange()
                change.consume()
                onDrag(change, delta)
            },
        )
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) return null
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else {
            val hasDragged = dragEvent.previousPosition != dragEvent.position
            if (hasDragged) {
                return dragEvent
            }
        }
    }
}
