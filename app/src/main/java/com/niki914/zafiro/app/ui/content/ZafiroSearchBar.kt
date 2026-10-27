package com.niki914.zafiro.app.ui.content

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.LiquidGlassPresets
import com.niki914.uikit.infra.LocalScreenBackdrop
import com.niki914.uikit.infra.interaction.InteractiveHighlight
import com.niki914.uikit.infra.interaction.LiquidInteractiveStyle
import com.niki914.uikit.infra.interaction.applyLiquidInteractiveTransform
import com.niki914.uikit.infra.shape.G2FieldShape
import com.niki914.uikit.motion.springSquish
import com.niki914.zafiro.app.R

private val SearchBarLiquidInteractiveStyle =
    LiquidInteractiveStyle(
        pressScalePx = 3.dp,
        maxDragScalePx = 3.dp,
    )

/**
 * 顶栏搜索栏（ZafiroSearchBar）：
 * - 默认常态：居中胶囊，带搜索图标与占位文本，点击切换激活态；
 * - 搜索激活态：左侧叉号图标（清空并退出搜索模式），中间输入框（自动聚焦），
 *   右侧退格图标（仅在有输入内容时显示，点击清空当前文本）。
 * - 同构多主题架构：
 *   - [ThemeStyle.LiquidGlass]：基于 [drawBackdrop] 与 [LiquidGlassPresets.TextField] 渲染离屏毛玻璃与动态高光；
 *   - [ThemeStyle.Material3] / [ThemeStyle.Material3Expressive]：渲染标准容器纯色与弹性微动效。
 */
@Composable
internal fun ZafiroSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    isActive: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onClearQuery: () -> Unit,
    onExitSearch: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.ui_conversation_search_placeholder),
) {
    val themeStyle = LocalThemeStyle.current
    val colorScheme = MaterialTheme.colorScheme
    val isDarkTheme = LocalAppDarkTheme.current
    val shape = remember { G2FieldShape(24.dp) }
    val interactionSource = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isActive) {
        if (isActive) {
            focusRequester.requestFocus()
        }
    }

    val interactiveEffectsEnabled = !isActive || query.isEmpty()

    @Composable
    fun SearchBarContent() {
        if (!isActive) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 12.dp),
            ) {
                // 左叉按钮：点击退出搜索模式并清空
                IconButton(
                    onClick = {
                        keyboardController?.hide()
                        onExitSearch()
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // 中间输入框
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 6.dp, end = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = colorScheme.onSurface,
                            fontSize = 14.sp,
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = { keyboardController?.hide() },
                        ),
                    )
                }

                // 右⌫退格删除按钮：仅在有文字时显示，点击清空文字
                AnimatedVisibility(
                    visible = query.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    IconButton(
                        onClick = onClearQuery,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Backspace,
                            contentDescription = "Clear",
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }

    when (themeStyle) {
        ThemeStyle.LiquidGlass -> {
            val glassStyle = LiquidGlassPresets.TextField
            val resolvedBackdrop = LocalScreenBackdrop.current ?: rememberLayerBackdrop()
            val animationScope = rememberCoroutineScope()
            val interactiveHighlight = remember(animationScope) {
                InteractiveHighlight(animationScope = animationScope)
            }
            val surfaceColor = glassStyle.surfaceColor(isDarkTheme, colorScheme)
            val tintColor = colorScheme.primaryContainer.copy(alpha = 0.28f)

            Box(
                modifier = modifier
                    .height(48.dp)
                    .fillMaxWidth()
                    .drawBackdrop(
                        backdrop = resolvedBackdrop,
                        shape = { shape },
                        highlight = { glassStyle.highlight(isDarkTheme) },
                        shadow = { glassStyle.shadow(isDarkTheme) },
                        effects = { glassStyle.applyEffects(this, isDarkTheme) },
                        layerBlock = if (interactiveEffectsEnabled) {
                            {
                                applyLiquidInteractiveTransform(
                                    style = SearchBarLiquidInteractiveStyle,
                                    pressProgress = interactiveHighlight.pressProgress,
                                    offset = interactiveHighlight.offset,
                                    size = size,
                                )
                            }
                        } else {
                            null
                        },
                        onDrawSurface = {
                            drawRect(tintColor, blendMode = BlendMode.Hue)
                            drawRect(surfaceColor)
                        },
                    )
                    .clip(shape)
                    .then(
                        if (!isActive) {
                            Modifier.clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { onActiveChange(true) },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (interactiveEffectsEnabled && SearchBarLiquidInteractiveStyle.highlightEnabled) {
                            interactiveHighlight.modifier
                        } else {
                            Modifier
                        },
                    )
                    .then(if (interactiveEffectsEnabled) interactiveHighlight.gestureModifier else Modifier),
                contentAlignment = Alignment.CenterStart,
            ) {
                SearchBarContent()
            }
        }

        ThemeStyle.Material3,
        ThemeStyle.Material3Expressive -> {
            val motionModifier = if (themeStyle == ThemeStyle.Material3Expressive && interactiveEffectsEnabled) {
                Modifier.springSquish(interactionSource, targetScale = 0.98f)
            } else {
                Modifier
            }

            Surface(
                modifier = modifier
                    .height(48.dp)
                    .fillMaxWidth()
                    .then(motionModifier)
                    .then(
                        if (!isActive) {
                            Modifier.clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { onActiveChange(true) },
                            )
                        } else {
                            Modifier
                        },
                    ),
                shape = shape,
                color = colorScheme.surfaceContainerHigh,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    SearchBarContent()
                }
            }
        }
    }
}
