package com.niki914.uikit.control

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.component.LiquidSlider
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 通用滑块控件门面组件：
 * - 统一收敛离散步长吸附、触觉震动反馈与高频 UI 更新 / 松手提交落盘契约；
 * - 业务层直接面向本基件编程，通过 [LocalThemeStyle] 自动分流；
 * - [ThemeStyle.LiquidGlass]: 渲染为带毛玻璃折射与阻尼物理形变的 [LiquidSlider]，并在有极模式下内聚底部刻度分点；
 * - [ThemeStyle.Material3] / [ThemeStyle.Material3Expressive]: 渲染为原生 Material [Slider]，轨道内置 Stop 点，无下方额外分点，零 GPU 离屏损耗。
 */
@Composable
fun ZafiroSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    visibilityThreshold: Float = (valueRange.endInclusive - valueRange.start) / 1000f,
    onValueCommit: ((Float) -> Unit)? = null,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val haptics = LocalHapticFeedback.current
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueCommit by rememberUpdatedState(onValueCommit)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(value()) }
    var lastSnappedValue by remember { mutableFloatStateOf(value()) }

    LaunchedEffect(value()) {
        if (!isDragging) {
            val currentExternal = value()
            dragProgress = currentExternal
            lastSnappedValue = currentExternal
        }
    }

    fun snap(raw: Float): Float {
        if (steps <= 0) return raw
        val intervals = steps + 1
        val stepSpan = (valueRange.endInclusive - valueRange.start) / intervals
        val stepIndex = ((raw - valueRange.start) / stepSpan).roundToInt().coerceIn(0, intervals)
        return (valueRange.start + stepIndex * stepSpan).coerceIn(valueRange)
    }

    val handleValueChange: (Float) -> Unit = { raw ->
        isDragging = true
        val snapped = snap(raw)
        dragProgress = snapped
        if (steps > 0) {
            if (abs(snapped - lastSnappedValue) > 0.0001f) {
                lastSnappedValue = snapped
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                currentOnValueChange(snapped)
            }
        } else {
            lastSnappedValue = snapped
            currentOnValueChange(snapped)
        }
    }

    val handleValueChangeFinished: () -> Unit = {
        isDragging = false
        val finalVal = snap(dragProgress)
        dragProgress = finalVal
        lastSnappedValue = finalVal
        currentOnValueChange(finalVal)
        currentOnValueCommit?.invoke(finalVal)
        currentOnValueChangeFinished?.invoke()
    }

    when (LocalThemeStyle.current) {
        ThemeStyle.LiquidGlass -> {
            Column(modifier = modifier) {
                LiquidSlider(
                    value = { dragProgress },
                    onValueChange = handleValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    valueRange = valueRange,
                    visibilityThreshold = visibilityThreshold,
                    onValueChangeFinished = handleValueChangeFinished,
                )

                if (steps > 0) {
                    val primaryColor = MaterialTheme.colorScheme.primary
                    val outlineVariantColor = MaterialTheme.colorScheme.outlineVariant
                    val activeValue = if (isDragging) dragProgress else value()
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .padding(vertical = 2.dp),
                    ) {
                        val intervals = steps + 1
                        val stepSpan = (valueRange.endInclusive - valueRange.start) / intervals
                        val totalPoints = intervals + 1
                        for (i in 0 until totalPoints) {
                            val targetVal = valueRange.start + i * stepSpan
                            val isCurrent = abs(activeValue - targetVal) < (stepSpan / 2f)
                            val inset = 10.dp.toPx()
                            val x = when (i) {
                                0 -> inset
                                intervals -> size.width - inset
                                else -> inset + (size.width - inset * 2f) * (i.toFloat() / intervals)
                            }
                            val radius = if (isCurrent) 3.dp.toPx() else 2.dp.toPx()
                            val color = if (isCurrent) primaryColor else outlineVariantColor.copy(alpha = 0.6f)
                            drawCircle(
                                color = color,
                                radius = radius,
                                center = Offset(x, size.height / 2f),
                            )
                        }
                    }
                }
            }
        }

        ThemeStyle.Material3,
        ThemeStyle.Material3Expressive -> {
            Slider(
                value = if (isDragging) dragProgress else value(),
                onValueChange = handleValueChange,
                modifier = modifier,
                enabled = enabled,
                valueRange = valueRange,
                steps = steps,
                onValueChangeFinished = handleValueChangeFinished,
                colors = colors,
                interactionSource = interactionSource,
            )
        }
    }
}

@Composable
fun ZafiroSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    visibilityThreshold: Float = (valueRange.endInclusive - valueRange.start) / 1000f,
    onValueCommit: ((Float) -> Unit)? = null,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val currentVal by rememberUpdatedState(value)
    ZafiroSlider(
        value = { currentVal },
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        visibilityThreshold = visibilityThreshold,
        onValueCommit = onValueCommit,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interactionSource,
    )
}
