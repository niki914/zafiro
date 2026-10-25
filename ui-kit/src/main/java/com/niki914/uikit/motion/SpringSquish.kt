package com.niki914.uikit.motion

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Material 3 Expressive 风格的核心按压动效：
 * 当处于按下状态时进行微缩放（例如按钮 0.92f，输入框容器 0.985f），抬手时利用弹簧物理回弹。
 */
@Composable
fun Modifier.springSquish(
    interactionSource: InteractionSource,
    targetScale: Float = 0.92f,
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) targetScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "SpringSquish",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
