package com.niki914.uikit.button

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.LiquidGlassPresets
import com.niki914.uikit.infra.LocalScreenBackdrop
import com.niki914.uikit.infra.interaction.InteractiveHighlight
import com.niki914.uikit.infra.interaction.LiquidButtonInteractiveStyle
import com.niki914.uikit.infra.interaction.applyLiquidInteractiveTransform

/**
 * 滚动到底部快捷悬浮按钮门面（统一三模分流）：
 * - [ThemeStyle.LiquidGlass]: 毛玻璃悬浮球与液态按压弹性形变；
 * - [ThemeStyle.Material3] / [ThemeStyle.Material3Expressive]: M3 实体高辨识度悬浮圆钮（[MaterialScrollToBottomButton]，彻底切断 Backdrop FBO 抓取，MD3E 叠加弹性触感反馈）。
 */
@Composable
fun ZafiroScrollToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bottomClearance: Dp = 0.dp,
    backdrop: Backdrop? = LocalScreenBackdrop.current,
    icon: (@Composable () -> Unit)? = null,
) {
    when (LocalThemeStyle.current) {
        ThemeStyle.LiquidGlass -> {
            LiquidScrollToBottomButtonCore(
                visible = visible,
                onClick = onClick,
                modifier = modifier,
                bottomClearance = bottomClearance,
                backdrop = backdrop,
                icon = icon,
            )
        }

        ThemeStyle.Material3,
        ThemeStyle.Material3Expressive -> {
            MaterialScrollToBottomButton(
                visible = visible,
                onClick = onClick,
                modifier = modifier,
                bottomClearance = bottomClearance,
                icon = icon,
            )
        }
    }
}

@Composable
private fun LiquidScrollToBottomButtonCore(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bottomClearance: Dp = 0.dp,
    backdrop: Backdrop? = LocalScreenBackdrop.current,
    icon: (@Composable () -> Unit)? = null,
) {
    val fallbackBackdrop = remember { emptyBackdrop() }
    val resolvedBackdrop = backdrop ?: LocalScreenBackdrop.current ?: fallbackBackdrop
    val scrollToBottomScope = rememberCoroutineScope()
    val scrollToBottomInteractiveHighlight = remember(scrollToBottomScope) {
        InteractiveHighlight(animationScope = scrollToBottomScope)
    }
    val isDarkTheme = LocalAppDarkTheme.current
    val scrollToBottomShape = CircleShape
    val scrollToBottomSurfaceColor =
        LiquidGlassPresets.ScrollToBottom.surfaceColor(isDarkTheme, MaterialTheme.colorScheme)

    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(bottom = bottomClearance),
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(100)),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .drawBackdrop(
                    backdrop = resolvedBackdrop,
                    shape = { scrollToBottomShape },
                    highlight = { LiquidGlassPresets.ScrollToBottom.highlight(isDarkTheme) },
                    shadow = { LiquidGlassPresets.ScrollToBottom.shadow(isDarkTheme) },
                    effects = {
                        LiquidGlassPresets.ScrollToBottom.applyEffects(this, isDarkTheme)
                    },
                    layerBlock = {
                        applyLiquidInteractiveTransform(
                            style = LiquidButtonInteractiveStyle,
                            pressProgress = scrollToBottomInteractiveHighlight.pressProgress,
                            offset = scrollToBottomInteractiveHighlight.offset,
                            size = size,
                        )
                    },
                    onDrawSurface = {
                        drawRect(scrollToBottomSurfaceColor)
                    },
                )
                .clip(scrollToBottomShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .then(scrollToBottomInteractiveHighlight.gestureModifier)
                .then(
                    if (LiquidButtonInteractiveStyle.highlightEnabled) {
                        scrollToBottomInteractiveHighlight.modifier
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                icon()
            } else {
                Icon(
                    imageVector = Icons.Rounded.ArrowDownward,
                    contentDescription = "Scroll To Bottom Icon",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
