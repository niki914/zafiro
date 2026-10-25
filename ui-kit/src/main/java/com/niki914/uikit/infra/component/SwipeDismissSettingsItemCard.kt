package com.niki914.uikit.infra.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.component.settings.item.SettingHeaderRowLayout
import com.niki914.uikit.infra.component.settings.item.SettingLeadingSlot
import com.niki914.uikit.infra.component.settings.item.SettingTrailingSlot
import com.niki914.uikit.infra.component.settings.item.SettingsTokens
import com.niki914.uikit.infra.shape.G2CardShape
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
    leadingSlot: SettingLeadingSlot = SettingLeadingSlot.None,
    trailingSlot: SettingTrailingSlot = SettingTrailingSlot.None,
    leadingContent: (@Composable () -> Unit)? = null,
    showChevron: Boolean = false,
    /** 尾随动作文字（如「编辑」），点击命中由 SettingsItemSurface 统一分发。 */
    trailingActionText: String? = null,
    onTrailingActionClick: (() -> Unit)? = null,
    highlightPulseKey: Any? = null,
    highlightPulseDurationMillis: Int = 500,
    enabled: Boolean = true,
    /** 仅控制左滑删除手势；false 时保留点击，用于「生效配置不可删」这类行。 */
    swipeEnabled: Boolean = true,
    threshold: Dp = SwipeDismissSettingsItemDefaults.Threshold,
    iconAnchor: Dp = SwipeDismissSettingsItemDefaults.IconAnchor,
    dampingRange: Dp = SwipeDismissSettingsItemDefaults.DampingRange,
    dismissIcon: ImageVector = Icons.Default.Delete,
) {
    val effectiveLeadingSlot = remember(leadingSlot, leadingContent) {
        when {
            leadingSlot !is SettingLeadingSlot.None -> leadingSlot
            leadingContent != null -> SettingLeadingSlot.Custom(leadingContent)
            else -> SettingLeadingSlot.None
        }
    }

    val effectiveTrailingSlot = remember(trailingSlot, trailingActionText, onTrailingActionClick, showChevron) {
        when {
            trailingSlot !is SettingTrailingSlot.None -> trailingSlot
            !trailingActionText.isNullOrBlank() -> SettingTrailingSlot.ActionText(
                text = trailingActionText,
                showChevron = showChevron,
                onClick = onTrailingActionClick,
            )
            showChevron -> SettingTrailingSlot.Chevron()
            else -> SettingTrailingSlot.None
        }
    }

    val trailingAction: (() -> Unit)? = when {
        !enabled -> null
        effectiveTrailingSlot is SettingTrailingSlot.ActionText && effectiveTrailingSlot.onClick != null -> {
            effectiveTrailingSlot.onClick
        }
        else -> null
    }

    var trailingActionBounds by remember { mutableStateOf<Rect?>(null) }

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

    val shape = G2CardShape(SettingsTokens.CardCornerRadius)
    val backgroundColor = lerp(
        start = MaterialTheme.colorScheme.surfaceContainerLow,
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
                if (enabled && swipeEnabled) {
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
            contentPadding = SettingsTokens.ContentPadding,
            minHeight = SettingsTokens.ItemMinHeight,
            shape = shape,
            highlightPulseKey = highlightPulseKey,
            highlightPulseDurationMillis = highlightPulseDurationMillis,
            onClick = onClick,
            onTrailingActionClick = trailingAction,
            trailingActionBoundsInWindow = if (trailingAction != null) trailingActionBounds else null,
            modifier = Modifier
                .offset { IntOffset(x = -animatedDistancePx.toInt(), y = 0) },
        ) {
            SettingHeaderRowLayout(
                leadingSlot = effectiveLeadingSlot,
                title = title,
                summary = summary,
                trailingSlot = effectiveTrailingSlot,
                enabled = enabled,
                titleColor = contentColor,
                summaryColor = summaryColor,
                onCardClick = onClick,
                onTrailingBoundsChanged = { trailingActionBounds = it },
            )
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
