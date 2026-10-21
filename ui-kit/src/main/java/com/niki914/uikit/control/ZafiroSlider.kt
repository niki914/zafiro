package com.niki914.uikit.control

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kyant.backdrop.Backdrop
import com.niki914.uikit.infra.LocalScreenBackdrop
import com.niki914.uikit.infra.component.LiquidSlider

@Composable
fun ZafiroSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    visibilityThreshold: Float = (valueRange.endInclusive - valueRange.start) / 1000f,
    onValueChangeFinished: (() -> Unit)? = null,
    backdrop: Backdrop? = LocalScreenBackdrop.current,
) {
    if (backdrop != null) {
        LiquidSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            visibilityThreshold = visibilityThreshold,
            onValueChangeFinished = onValueChangeFinished,
            backdrop = backdrop,
        )
    } else {
        LiquidSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            visibilityThreshold = visibilityThreshold,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

@Composable
fun ZafiroSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    backdrop: Backdrop? = LocalScreenBackdrop.current,
) {
    if (backdrop != null) {
        LiquidSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished,
            backdrop = backdrop,
        )
    } else {
        LiquidSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}
