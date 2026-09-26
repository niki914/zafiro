package com.niki914.zafiro.remoteview.floatingball

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.zafiro.remoteview.R
import kotlinx.coroutines.launch

private val ButtonSpinSpec = tween<Float>(
    durationMillis = 550,
    easing = CubicBezierEasing(0.1f, 0.1f, 0.25f, 1.0f),
)

private suspend fun Animatable<Float, *>.animateSpin(
    onProgress: ((Float) -> Unit)? = null,
) {
    snapTo(0f)
    animateTo(
        targetValue = 720f,
        animationSpec = ButtonSpinSpec,
    ) {
        onProgress?.invoke(value)
    }
    snapTo(0f)
}

/**
 * 悬浮球统一原子操作按钮组件。
 *
 * 尺寸由 [FloatingBallTokens.buttonDiameterDp] 约束。
 * 形状固定为 [CircleShape] 正圆形。
 * 当 [icon] 发生变更时，自动触发 720° 先快后慢的减速旋转动效，并在初始小幅转动后迅速切换为新图标。
 * 支持点击触发触感反馈，且在 [spinOnClick] 开启时点击亦会触发 720° 旋转动效。
 * 收起态悬浮球本体、展开态底部的 Jump、Stop、Minimize 按钮均复用本组件。
 */
@Composable
fun FloatingBallActionButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.primaryContainer,
    border: BorderStroke? = null,
    iconSize: Dp = 20.dp,
    enabled: Boolean = true,
    spinOnClick: Boolean = false,
) {
    var displayedIcon by remember { mutableStateOf(icon) }
    val rotationAnim = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(icon) {
        if (displayedIcon != icon) {
            rotationAnim.animateSpin { progress ->
                if (progress >= 40f && displayedIcon != icon) {
                    displayedIcon = icon
                }
            }
            displayedIcon = icon
        }
    }

    Box(
        modifier = modifier
            .size(FloatingBallTokens.buttonDiameterDp)
            .clip(CircleShape)
            .background(backgroundColor, CircleShape)
            .then(if (border != null) Modifier.border(border, CircleShape) else Modifier)
            .clickable(
                enabled = enabled,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    if (spinOnClick) {
                        coroutineScope.launch {
                            rotationAnim.animateSpin()
                        }
                    }
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = displayedIcon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    rotationZ = rotationAnim.value
                },
        )
    }
}

/**
 * 收起态悬浮球静态方块。
 */
@Composable
fun FloatingBallCollapsedBall(
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
) {
    FloatingBallActionButton(
        icon = ImageVector.vectorResource(R.drawable.ic_remote_view),
        onClick = onClick,
        modifier = modifier,
        backgroundColor = backgroundColor,
        contentColor = contentColor,
        iconSize = 18.dp,
    )
}

@Preview(name = "Floating Ball Collapsed Preview", showBackground = true)
@Composable
private fun FloatingBallCollapsedPreview() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            Box(modifier = Modifier.padding(24.dp)) {
                FloatingBallCollapsedBall()
            }
        }
    }
}
