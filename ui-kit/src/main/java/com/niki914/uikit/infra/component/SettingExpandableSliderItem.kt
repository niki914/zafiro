package com.niki914.uikit.infra.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 通用可折叠设置滑块项（支持有极与无极）：
 * - 折叠态显示：标题、副标题、当前值（Primary 加粗）以及向右的旋转箭头；
 * - 点击行头部向下平滑展开滑块，箭头顺时针旋转 90°；
 * - 两端文本与滑块之间保留 20dp+ 安全内边距，确保 Thumb 放大或拖至端点时绝不黏连；
 * - [steps] == 0 时为无极平滑滑动；[steps] > 0 时为有极滑动，轨道正下方自动对齐渲染刻度圆点，滑动逐级微震吸附。
 */
@Composable
fun SettingExpandableSliderItem(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    summary: String? = null,
    valueLabel: String? = null,
    startLabel: String? = null,
    endLabel: String? = null,
    steps: Int = 0,
    enabled: Boolean = true,
    initiallyExpanded: Boolean = false,
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onValueCommit: ((Float) -> Unit)? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    var internalExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val isExpanded = expanded ?: internalExpanded

    fun updateExpanded(newVal: Boolean) {
        if (newVal) {
            focusManager.clearFocus()
        }
        if (expanded == null) {
            internalExpanded = newVal
        }
        onExpandedChange?.invoke(newVal)
    }

    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "settingExpandableSliderArrowRotation",
    )

    val haptics = LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(value) }
    var lastSnappedValue by remember { mutableFloatStateOf(value) }

    LaunchedEffect(value) {
        if (!isDragging) {
            dragProgress = value
            lastSnappedValue = value
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // 头部可点击行：覆盖整行，仅头部绑定点击，展开区操作不误触折叠
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val titleAreaMaxWidth = maxWidth * 0.66f
            val stateAreaMaxWidth = maxWidth * 0.22f

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (enabled) {
                            Modifier.pointerInput(isExpanded) {
                                detectTapGestures(onTap = { updateExpanded(!isExpanded) })
                            }
                        } else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.widthIn(max = titleAreaMaxWidth),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!summary.isNullOrBlank()) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!valueLabel.isNullOrBlank()) {
                        Text(
                            text = valueLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = stateAreaMaxWidth),
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer { rotationZ = arrowRotation },
                    )
                }
            }
        }

        // 展开滑块区域
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            ) + fadeIn(
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            ),
            exit = shrinkVertically(
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            ) + fadeOut(
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左侧极值文字：右侧 20dp 防粘连 Padding
                if (!startLabel.isNullOrBlank()) {
                    Text(
                        text = startLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 20.dp),
                    )
                }

                // 中间区域：滑块轨道 + 自动对齐的刻度圆点行（若 steps > 0）
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                ) {
                    LiquidSlider(
                        value = { dragProgress },
                        onValueChange = { raw ->
                            isDragging = true
                            dragProgress = raw
                            if (steps > 0) {
                                val stepSpan = (valueRange.endInclusive - valueRange.start) / steps
                                val stepIndex = ((raw - valueRange.start) / stepSpan).roundToInt().coerceIn(0, steps)
                                val snapped = valueRange.start + stepIndex * stepSpan
                                if (abs(snapped - lastSnappedValue) > 0.001f) {
                                    lastSnappedValue = snapped
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onValueChange(snapped)
                                }
                            } else {
                                onValueChange(raw)
                            }
                        },
                        onValueChangeFinished = {
                            isDragging = false
                            val finalVal = if (steps > 0) {
                                val stepSpan = (valueRange.endInclusive - valueRange.start) / steps
                                val stepIndex = ((dragProgress - valueRange.start) / stepSpan).roundToInt().coerceIn(0, steps)
                                val snapped = valueRange.start + stepIndex * stepSpan
                                dragProgress = snapped
                                lastSnappedValue = snapped
                                onValueChange(snapped)
                                snapped
                            } else {
                                dragProgress
                            }
                            onValueCommit?.invoke(finalVal)
                            onValueChangeFinished?.invoke()
                        },
                        valueRange = valueRange,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // 有极滑动（steps > 0）时渲染小圆点刻度标尺，自动与上方滑轨两端贴合对齐
                    if (steps > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val stepCount = steps + 1
                            val stepSpan = (valueRange.endInclusive - valueRange.start) / steps
                            for (i in 0 until stepCount) {
                                val targetVal = valueRange.start + i * stepSpan
                                val isCurrent = abs(value - targetVal) < (stepSpan / 2f)
                                Box(
                                    modifier = Modifier
                                        .size(if (isCurrent) 6.dp else 4.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isCurrent) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                        ),
                                )
                            }
                        }
                    }
                }

                // 右侧极值文字：左侧 20dp 防粘连 Padding
                if (!endLabel.isNullOrBlank()) {
                    Text(
                        text = endLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp),
                    )
                }
            }
        }
    }
}
}


