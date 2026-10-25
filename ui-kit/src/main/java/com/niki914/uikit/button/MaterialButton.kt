package com.niki914.uikit.button

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.shape.G2FieldShape
import com.niki914.uikit.motion.springSquish

/**
 * Material 3 / Material 3 Expressive 规范实心主按钮：
 * - 尺寸规范：默认 56dp 大操作槽位，16dp 水平内边距（符合 M3 Expressive Recommended 标准）；
 * - 状态分级：高对比度主操作底座（Filled Button，primary 容器色 + onPrimary 文字色）；
 * - 表现力支持：当为 [ThemeStyle.Material3Expressive] 时，采用 Squircle（G2 表现力圆角）并叠加 [springSquish] 弹簧按压物理反馈，加载态使用花瓣 LoadingIndicator。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MaterialButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    buttonHeight: Dp = 56.dp,
    isLoading: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val themeStyle = LocalThemeStyle.current
    val isExpressive = themeStyle == ThemeStyle.Material3Expressive
    val interactionSource = remember { MutableInteractionSource() }

    // 形状 Token：经典 M3 为标准胶囊，M3E 为表现力 Squircle 圆角
    val buttonShape: Shape = if (isExpressive) G2FieldShape(20.dp) else CircleShape

    val motionModifier = if (isExpressive && enabled && !isLoading) {
        Modifier.springSquish(interactionSource, targetScale = 0.95f)
    } else {
        Modifier
    }

    val disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(buttonHeight)
            .then(motionModifier),
        enabled = enabled && !isLoading,
        shape = buttonShape,
        interactionSource = interactionSource,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = disabledContainerColor,
            disabledContentColor = disabledContentColor,
        ),
    ) {
        if (isLoading) {
            val indicatorColor = if (enabled) contentColor else disabledContentColor
            if (isExpressive) {
                LoadingIndicator(
                    modifier = Modifier.size(28.dp),
                    color = indicatorColor,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = indicatorColor,
                    strokeWidth = 2.5.dp,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingIcon?.invoke()
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                trailingIcon?.invoke()
            }
        }
    }
}

/**
 * Material 3 / Material 3 Expressive 规范中对比色调按钮（次操作 / Tonal Button）：
 * - 色彩规范：中对比度底板（FilledTonalButton，默认 primaryContainer 或 surfaceContainerHighest）；
 * - 动效与形状：MD3 为胶囊 + Ripple，MD3E 为 Squircle + 弹簧弹性微缩放。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MaterialTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    lightContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    darkContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    buttonHeight: Dp = 56.dp,
    isLoading: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val themeStyle = LocalThemeStyle.current
    val isExpressive = themeStyle == ThemeStyle.Material3Expressive
    val interactionSource = remember { MutableInteractionSource() }

    val isDarkTheme = LocalAppDarkTheme.current
    val resolvedContainerColor = when {
        darkContainerColor.isSpecified && lightContainerColor.isSpecified -> {
            if (isDarkTheme) darkContainerColor else lightContainerColor
        }
        darkContainerColor.isSpecified -> darkContainerColor
        lightContainerColor.isSpecified -> lightContainerColor
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val buttonShape: Shape = if (isExpressive) G2FieldShape(20.dp) else CircleShape

    val motionModifier = if (isExpressive && enabled && !isLoading) {
        Modifier.springSquish(interactionSource, targetScale = 0.95f)
    } else {
        Modifier
    }

    val disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(buttonHeight)
            .then(motionModifier),
        enabled = enabled && !isLoading,
        shape = buttonShape,
        interactionSource = interactionSource,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = resolvedContainerColor,
            contentColor = contentColor,
            disabledContainerColor = disabledContainerColor,
            disabledContentColor = disabledContentColor,
        ),
    ) {
        if (isLoading) {
            val indicatorColor = if (enabled) contentColor else disabledContentColor
            if (isExpressive) {
                LoadingIndicator(
                    modifier = Modifier.size(28.dp),
                    color = indicatorColor,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = indicatorColor,
                    strokeWidth = 2.5.dp,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingIcon?.invoke()
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                trailingIcon?.invoke()
            }
        }
    }
}

/**
 * Material 3 / Material 3 Expressive 规范滚动到底部悬浮按钮（ScrollToBottom FAB）：
 * - 纯原生 Canvas 渲染，不依赖离屏 Backdrop，零额外 GPU 开销；
 * - 40dp 紧凑圆形底座 + 表面高反差阴影 + 浅灰边框；
 * - MD3 采用水波纹反馈，MD3E 叠加弹性按压缩放。
 */
@Composable
fun MaterialScrollToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bottomClearance: Dp = 0.dp,
    icon: (@Composable () -> Unit)? = null,
) {
    val themeStyle = LocalThemeStyle.current
    val isExpressive = themeStyle == ThemeStyle.Material3Expressive
    val interactionSource = remember { MutableInteractionSource() }

    val motionModifier = if (isExpressive) {
        Modifier.springSquish(interactionSource, targetScale = 0.88f)
    } else {
        Modifier
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(bottom = bottomClearance),
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(100)),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(motionModifier)
                .shadow(elevation = 4.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .border(
                    BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    ),
                    CircleShape,
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                icon()
            } else {
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = "Scroll to bottom",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
