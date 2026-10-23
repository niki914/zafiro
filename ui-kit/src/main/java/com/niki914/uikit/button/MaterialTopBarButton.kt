package com.niki914.uikit.button

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.motion.springSquish

/**
 * Material Design (MD3 / MD3E) 专属顶栏动作按钮。
 *
 * 核心规范：
 * - 纯透明无底色（无 Container 背景），48dp 触摸靶心
 * - 亮色 State Layer 水波纹（以 onSurface 颜色为基底，在深色/浅色底上均清脆明亮，绝不发黑发暗）
 * - MD3E 专属弹簧按压微缩动效 [springSquish]
 */
@Composable
fun MaterialTopBarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onButtonBoundsChanged: ((Rect) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val themeStyle = LocalThemeStyle.current
    val interactionSource = remember { MutableInteractionSource() }
    val buttonSize = 48.dp

    val motionModifier = if (themeStyle == ThemeStyle.Material3Expressive && enabled) {
        Modifier.springSquish(interactionSource, targetScale = 0.92f)
    } else {
        Modifier
    }

    val rippleColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .then(
                if (onButtonBoundsChanged != null) {
                    Modifier.onGloballyPositioned { coordinates ->
                        onButtonBoundsChanged(coordinates.boundsInRoot())
                    }
                } else {
                    Modifier
                }
            )
            .then(motionModifier)
            .requiredSize(buttonSize)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = rippleColor),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides rippleColor,
        ) {
            content()
        }
    }
}
