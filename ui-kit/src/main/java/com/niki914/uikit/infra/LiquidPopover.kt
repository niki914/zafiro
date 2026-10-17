package com.niki914.uikit.infra

import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.infra.interaction.InteractiveHighlight
import com.niki914.uikit.infra.interaction.LiquidInteractiveStyle
import com.niki914.uikit.infra.interaction.applyLiquidInteractiveTransform
import com.niki914.uikit.infra.shape.G2FieldShape

enum class PopoverAlignment {
    /** 锚定在目标右侧，与按钮同体向左下方放大扩展（适合右上角按钮） */
    BottomEnd,

    /** 锚定在目标中轴，与标题同体向下对称扩展（适合顶栏标题） */
    BottomCenter,

    /** 锚定在目标左侧，与按钮同体向右下方放大扩展（适合左上角按钮） */
    BottomStart,
}

fun Modifier.popoverAnchor(onBoundsChanged: (Rect) -> Unit): Modifier =
    onGloballyPositioned { coordinates ->
        onBoundsChanged(coordinates.boundsInRoot())
    }

@Composable
fun LiquidPopover(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorBounds: Rect?,
    modifier: Modifier = Modifier,
    alignment: PopoverAlignment = PopoverAlignment.BottomEnd,
    containerColor: Color? = null,
    scrollable: Boolean = false,
    onProgressChanged: ((Float) -> Unit)? = null,
    backdrop: Backdrop? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hostState = LocalLiquidDialogHostState.current
    val popoverId = remember { nextLiquidDialogHostEntryId() }

    if (hostState != null) {
        SideEffect {
            hostState.upsert(popoverId, expanded) {
                LiquidPopoverSurface(
                    expanded = expanded,
                    onDismissRequest = onDismissRequest,
                    anchorBounds = anchorBounds,
                    alignment = alignment,
                    modifier = modifier,
                    containerColor = containerColor,
                    scrollable = scrollable,
                    onProgressChanged = onProgressChanged,
                    backdrop = backdrop,
                    content = content,
                )
            }
        }

        DisposableEffect(hostState, popoverId) {
            onDispose {
                hostState.remove(popoverId)
            }
        }
    } else {
        LiquidPopoverSurface(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            anchorBounds = anchorBounds,
            alignment = alignment,
            modifier = modifier,
            containerColor = containerColor,
            scrollable = scrollable,
            onProgressChanged = onProgressChanged,
            backdrop = backdrop,
            content = content,
        )
    }
}

private val PopoverInteractiveStyle =
    LiquidInteractiveStyle(
        pressScalePx = 6.dp,
        maxDragScalePx = 6.dp,
        translationDamping = 0.08f,
        highlightEnabled = false,
    )

@Composable
internal fun LiquidPopoverSurface(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorBounds: Rect?,
    alignment: PopoverAlignment,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    scrollable: Boolean = false,
    onProgressChanged: ((Float) -> Unit)? = null,
    backdrop: Backdrop? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) {
        InteractiveHighlight(animationScope = animationScope)
    }
    val fallbackBackdrop = rememberLayerBackdrop()
    val resolvedBackdrop = backdrop ?: LocalScreenBackdrop.current ?: fallbackBackdrop
    val isDarkTheme = LocalAppDarkTheme.current
    val density = LocalDensity.current
    val statusBarTopPx = WindowInsets.statusBars.getTop(density).toFloat()

    // 0f = 完全收起于锚点按钮形态，1f = 完全展开为菜单形态
    val animProgress = remember { Animatable(if (expanded) 1f else 0f) }

    LaunchedEffect(expanded) {
        if (expanded) {
            animProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
            ) {
                onProgressChanged?.invoke(value)
            }
        } else {
            animProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            ) {
                onProgressChanged?.invoke(value)
            }
        }
    }

    val progress = animProgress.value
    if (progress <= 0f && !expanded) return

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(expanded) {
        if (expanded) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    BackHandler(enabled = expanded) {
        onDismissRequest()
    }

    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    var cardBounds by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(expanded) {
        if (!expanded) {
            cardBounds = null
        }
    }

    DisposableEffect(expanded) {
        if (!expanded) return@DisposableEffect onDispose {}

        val unregister = GlobalTouchDispatcher.register { event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val bounds = cardBounds ?: anchorBounds
                if (bounds != null) {
                    val isInside = bounds.contains(Offset(event.x, event.y))
                    if (!isInside) {
                        currentOnDismissRequest()
                    }
                }
            }
        }

        onDispose {
            unregister()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SubcomposeLayout(modifier = Modifier.fillMaxSize()) { constraints ->
            val anchor = anchorBounds ?: run {
                val defaultButtonSizePx = with(density) { 48.dp.toPx() }
                val defaultTitleWidthPx = with(density) { 120.dp.toPx() }
                val screenWidthPx = constraints.maxWidth.toFloat()
                when (alignment) {
                    PopoverAlignment.BottomCenter -> {
                        val left = (screenWidthPx - defaultTitleWidthPx) / 2f
                        Rect(
                            left,
                            statusBarTopPx + 4f,
                            left + defaultTitleWidthPx,
                            statusBarTopPx + 4f + defaultButtonSizePx
                        )
                    }

                    PopoverAlignment.BottomEnd -> {
                        val right = screenWidthPx - with(density) { 12.dp.toPx() }
                        Rect(
                            right - defaultButtonSizePx,
                            statusBarTopPx + 4f,
                            right,
                            statusBarTopPx + 4f + defaultButtonSizePx
                        )
                    }

                    PopoverAlignment.BottomStart -> {
                        val left = with(density) { 12.dp.toPx() }
                        Rect(
                            left,
                            statusBarTopPx + 4f,
                            left + defaultButtonSizePx,
                            statusBarTopPx + 4f + defaultButtonSizePx
                        )
                    }
                }
            }

            // 参考长标题避让边距（4dp 顶栏外边距 + ActionBarButton 占宽：12dp 内边距 × 2 + 48sp 按钮），
            // 确保右侧或左侧 Popover 不会侵占相对侧按钮区域
            val titleButtonClearancePx = with(density) { (4.dp + 12.dp * 2 + 48.sp.toDp()).toPx() }
            val minPopoverWidthPx = with(density) { 180.dp.toPx() }

            val maxAllowedWidthPx = when (alignment) {
                PopoverAlignment.BottomEnd -> {
                    (anchor.right - titleButtonClearancePx).coerceAtLeast(minPopoverWidthPx)
                }

                PopoverAlignment.BottomStart -> {
                    (constraints.maxWidth - titleButtonClearancePx - anchor.left).coerceAtLeast(
                        minPopoverWidthPx
                    )
                }

                PopoverAlignment.BottomCenter -> {
                    val availableHalf = minOf(
                        anchor.center.x - titleButtonClearancePx,
                        constraints.maxWidth - titleButtonClearancePx - anchor.center.x,
                    ).coerceAtLeast(0f)
                    (availableHalf * 2f).coerceAtLeast(minPopoverWidthPx)
                }
            }

            val dynamicMaxWidthDp = with(density) { minOf(300.dp.toPx(), maxAllowedWidthPx).toDp() }

            // 先测量菜单内容自然尺寸
            val contentMeasurables = subcompose("menu_content_measurement") {
                Column(
                    modifier = Modifier
                        .widthIn(min = 180.dp, max = dynamicMaxWidthDp)
                        .then(modifier)
                        .then(
                            if (scrollable) {
                                Modifier.verticalScroll(rememberScrollState())
                            } else {
                                Modifier
                            }
                        )
                        .padding(vertical = 4.dp),
                    content = content,
                )
            }

            val screenAvailableHeightPx =
                (constraints.maxHeight - anchor.top.toInt() - with(density) { 16.dp.roundToPx() })
                    .coerceAtLeast(with(density) { 100.dp.roundToPx() })
            // 超过 5 项滚动时，将展开卡片高度约束在约 5.5 项高度（256.dp），使底部露半截提示可滚动，并激活内部纵向滚动
            val maxAvailableHeightPx = if (scrollable) {
                minOf(screenAvailableHeightPx, with(density) { 256.dp.roundToPx() })
            } else {
                screenAvailableHeightPx
            }

            val contentPlaceable = contentMeasurables.first().measure(
                constraints.copy(
                    minWidth = 0,
                    minHeight = 0,
                    maxHeight = maxAvailableHeightPx,
                ),
            )

            val targetWidthPx = contentPlaceable.width.toFloat()
            val targetHeightPx = contentPlaceable.height.toFloat()

            val anchorWidthPx =
                if (anchor.width > 0f) anchor.width else with(density) { 48.dp.toPx() }
            val anchorHeightPx =
                if (anchor.height > 0f) anchor.height else with(density) { 48.dp.toPx() }

            // 尺寸与圆角在锚点与菜单之间平滑演变（悬浮球同款 Morph 算法）
            val currentWidthPx = lerp(anchorWidthPx, targetWidthPx, progress)
            val currentHeightPx = lerp(anchorHeightPx, targetHeightPx, progress)
            val currentRadiusPx =
                lerp(anchorHeightPx / 2f, with(density) { 20.dp.toPx() }, progress)

            // 绝对锚点贴齐：BottomEnd 严格锁定在 (anchor.right, anchor.top)，不加额外位移 margin
            val currentLeft = when (alignment) {
                PopoverAlignment.BottomEnd -> anchor.right - currentWidthPx
                PopoverAlignment.BottomCenter -> anchor.center.x - currentWidthPx / 2f
                PopoverAlignment.BottomStart -> anchor.left
            }

            val currentTop = anchor.top

            // 构造展开态形态卡片
            val cardMeasurables = subcompose("menu_surface_card") {
                val panelShape = G2FieldShape(with(density) { currentRadiusPx.toDp() })
                val contentAlpha =
                    if (progress > 0.25f) ((progress - 0.25f) / 0.75f).coerceIn(0f, 1f) else 0f

                // 默认颜色与 ActionBarButton 保持 100% 一致（透明白/灰色），支持自定义传入
                val resolvedSurfaceColor = containerColor
                    ?: LiquidGlassPresets.Popover.surfaceColor(isDarkTheme, MaterialTheme.colorScheme)

                Box(
                    modifier = Modifier
                        .size(
                            width = with(density) { currentWidthPx.toDp() },
                            height = with(density) { currentHeightPx.toDp() },
                        )
                        .drawBackdrop(
                            backdrop = resolvedBackdrop,
                            shape = { panelShape },
                            highlight = { LiquidGlassPresets.Popover.highlight(isDarkTheme) },
                            shadow = { LiquidGlassPresets.Popover.shadow(isDarkTheme) },
                            layerBlock = {
                                if (!scrollable) {
                                    applyLiquidInteractiveTransform(
                                        style = PopoverInteractiveStyle,
                                        pressProgress = interactiveHighlight.pressProgress,
                                        offset = interactiveHighlight.offset,
                                        size = size,
                                    )
                                }
                            },
                            effects = {
                                LiquidGlassPresets.Popover.applyEffects(this, isDarkTheme)
                            },
                            onDrawSurface = {
                                drawRect(resolvedSurfaceColor)
                            },
                        )
                        .clip(panelShape)
                        .then(if (scrollable) Modifier else interactiveHighlight.gestureModifier)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .onGloballyPositioned { coordinates ->
                            cardBounds = coordinates.boundsInWindow()
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = contentAlpha },
                    ) {
                        Column(
                            modifier = Modifier
                                .width(with(density) { targetWidthPx.toDp() })
                                .then(
                                    if (scrollable) {
                                        Modifier.verticalScroll(rememberScrollState())
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(vertical = 4.dp),
                            content = content,
                        )
                    }
                }
            }

            val cardPlaceable = cardMeasurables.first().measure(
                constraints.copy(minWidth = 0, minHeight = 0),
            )

            layout(constraints.maxWidth, constraints.maxHeight) {
                cardPlaceable.place(currentLeft.toInt(), currentTop.toInt())
            }
        }
    }
}

@Composable
fun LiquidMenuItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checked: Boolean? = null,
    leadingIcon: ImageVector? = null,
    trailingText: String? = null,
) {
    val itemShape = G2FieldShape(16.dp)
    val colorScheme = MaterialTheme.colorScheme
    val isDarkTheme = LocalAppDarkTheme.current
    val isChecked = checked == true
    val haptics = LocalHapticFeedback.current

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val targetBgColor = when {
        isPressed -> (if (isDarkTheme) Color.White else Color.Black).copy(alpha = 0.08f)
        isChecked -> (if (isDarkTheme) Color.White else Color.Black).copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val animatedBgColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = tween(durationMillis = 240, easing = LinearOutSlowInEasing),
        label = "menuItemBgColor",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(itemShape)
            .background(animatedBgColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null, // 绝无 Material 点击波纹
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (enabled) {
                    colorScheme.onSurfaceVariant
                } else {
                    colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                colorScheme.onSurface
            } else {
                colorScheme.onSurface.copy(alpha = 0.38f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )

        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.70f),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        if (isChecked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colorScheme.primary,
            )
        }
    }
}

@Composable
fun LiquidMenuDivider(
    modifier: Modifier = Modifier,
) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}
