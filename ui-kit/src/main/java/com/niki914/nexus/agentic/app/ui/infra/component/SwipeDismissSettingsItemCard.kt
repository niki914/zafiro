package com.niki914.nexus.agentic.app.ui.infra.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.niki914.nexus.agentic.app.ui.infra.shape.G2CardShape
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.exp

@Composable
fun SwipeDismissSettingsItemCard(
    title: String,
    onClick: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    showChevron: Boolean = false,
    highlightPulseKey: Any? = null,
    highlightPulseDurationMillis: Int = 500,
    enabled: Boolean = true,
    threshold: Dp = SwipeDismissSettingsItemDefaults.Threshold,
    iconAnchor: Dp = SwipeDismissSettingsItemDefaults.IconAnchor,
    dampingRange: Dp = SwipeDismissSettingsItemDefaults.DampingRange,
    dismissIcon: ImageVector = Icons.Default.Delete,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { threshold.toPx() }
    val dampingRangePx = with(density) { dampingRange.toPx() }
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)

    var rawDistancePx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val progress = (rawDistancePx.absoluteValue / thresholdPx).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = if (isDragging) 0 else 120),
        label = "swipeDismissProgress",
    )
    val animatedDistancePx by animateFloatAsState(
        targetValue = visualSignedDistancePx(
            signedRawDistancePx = rawDistancePx,
            thresholdPx = thresholdPx,
            dampingRangePx = dampingRangePx,
        ),
        animationSpec = tween(durationMillis = if (isDragging) 0 else 120),
        label = "swipeDismissDistance",
    )

    val shape = G2CardShape(28.dp)
    val backgroundColor = lerp(
        start = MaterialTheme.colorScheme.surfaceContainer,
        stop = MaterialTheme.colorScheme.error,
        fraction = animatedProgress,
    )
    val contentColor = lerp(
        start = MaterialTheme.colorScheme.onSurface,
        stop = MaterialTheme.colorScheme.onError,
        fraction = animatedProgress,
    )
    val summaryColor = lerp(
        start = MaterialTheme.colorScheme.onSurfaceVariant,
        stop = MaterialTheme.colorScheme.onError,
        fraction = animatedProgress,
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundColor, shape)
            .then(
                if (enabled) {
                    Modifier.pointerInput(thresholdPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var pointerId = down.id
                            var dragAxis = DragAxis.Undecided

                            fun finishHorizontalDrag() {
                                isDragging = false
                                val endProgress =
                                    (rawDistancePx.absoluteValue / thresholdPx).coerceIn(0f, 1f)
                                rawDistancePx = 0f
                                if (endProgress >= SwipeDismissSettingsItemDefaults.DeleteProgressThreshold) {
                                    currentOnDismissRequest()
                                }
                            }

                            fun cancelHorizontalDrag() {
                                isDragging = false
                                rawDistancePx = 0f
                            }

                            val slopChange =
                                awaitTouchSlopOrCancellation(pointerId) { change, overSlop ->
                                    pointerId = change.id
                                    dragAxis = resolveDragAxis(overSlop.x, overSlop.y)
                                    if (dragAxis == DragAxis.Horizontal) {
                                        isDragging = true
                                        rawDistancePx -= overSlop.x
                                        change.consume()
                                    }
                                }

                            when {
                                slopChange == null -> {
                                    cancelHorizontalDrag()
                                }

                                dragAxis == DragAxis.Vertical || dragAxis == DragAxis.Undecided -> {
                                    cancelHorizontalDrag()
                                }

                                else -> {
                                    val dragCompleted = drag(pointerId) { change ->
                                        val deltaX = change.positionChange().x
                                        if (deltaX != 0f) {
                                            change.consume()
                                        }
                                        rawDistancePx -= deltaX
                                    }
                                    if (dragCompleted) {
                                        finishHorizontalDrag()
                                    } else {
                                        cancelHorizontalDrag()
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }
            ),
    ) {
        Icon(
            imageVector = dismissIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onError,
            modifier = Modifier
                .align(if (rawDistancePx < 0f) Alignment.CenterStart else Alignment.CenterEnd)
                .then(
                    if (rawDistancePx < 0f) {
                        Modifier.padding(start = iconAnchor)
                    } else {
                        Modifier.padding(end = iconAnchor)
                    },
                )
                .alpha(animatedProgress)
                .size(22.dp),
        )
        SettingsItemSurface(
            enabled = enabled,
            highlightPulseKey = highlightPulseKey,
            highlightPulseDurationMillis = highlightPulseDurationMillis,
            onClick = onClick,
            modifier = Modifier
                .offset { IntOffset(x = -animatedDistancePx.toInt(), y = 0) },
        ) {
            SwipeDismissSettingsItemContent(
                title = title,
                summary = summary,
                showChevron = showChevron,
                contentColor = contentColor,
                summaryColor = summaryColor,
            )
        }
    }
}

@Composable
private fun SwipeDismissSettingsItemContent(
    title: String,
    summary: String?,
    showChevron: Boolean,
    contentColor: Color,
    summaryColor: Color,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val titleAreaMaxWidth = maxWidth * 0.66f

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.widthIn(max = titleAreaMaxWidth),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!summary.isNullOrBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = summaryColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (showChevron) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = summaryColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

object SwipeDismissSettingsItemDefaults {
    val Threshold: Dp = 40.dp
    val IconAnchor: Dp = 20.dp
    val DampingRange: Dp = 72.dp
    const val HorizontalBiasRatio: Float = 1.2f
    const val DeleteProgressThreshold: Float = 1f
}

private enum class DragAxis {
    Undecided,
    Horizontal,
    Vertical,
}

private fun resolveDragAxis(deltaX: Float, deltaY: Float): DragAxis {
    val absX = abs(deltaX)
    val absY = abs(deltaY)
    return when {
        absX > absY * SwipeDismissSettingsItemDefaults.HorizontalBiasRatio -> DragAxis.Horizontal
        absY > absX * SwipeDismissSettingsItemDefaults.HorizontalBiasRatio -> DragAxis.Vertical
        else -> DragAxis.Undecided
    }
}

private fun visualSignedDistancePx(
    signedRawDistancePx: Float,
    thresholdPx: Float,
    dampingRangePx: Float,
): Float {
    val direction = if (signedRawDistancePx >= 0f) 1f else -1f
    val rawDistancePx = signedRawDistancePx.absoluteValue
    val dampedDistancePx = if (rawDistancePx <= thresholdPx) {
        rawDistancePx
    } else {
        thresholdPx + dampingRangePx * (1f - exp(-(rawDistancePx - thresholdPx) / dampingRangePx))
    }
    return direction * dampedDistancePx
}
