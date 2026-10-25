package com.niki914.uikit.menu

import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.GlobalTouchDispatcher
import com.niki914.uikit.infra.LocalLiquidDialogHostState
import com.niki914.uikit.infra.PopoverAlignment
import com.niki914.uikit.infra.nextLiquidDialogHostEntryId
import com.niki914.uikit.infra.shape.G2FieldShape

@Composable
internal fun MaterialDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    anchorBounds: Rect? = null,
    alignment: ZafiroMenuAlignment = ZafiroMenuAlignment.BottomEnd,
    containerColor: Color? = null,
    scrollable: Boolean = false,
    onProgressChanged: ((Float) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hostState = LocalLiquidDialogHostState.current
    val menuId = remember { nextLiquidDialogHostEntryId() }

    if (hostState != null) {
        SideEffect {
            hostState.upsert(menuId, expanded) {
                MaterialMenuSurface(
                    expanded = expanded,
                    onDismissRequest = onDismissRequest,
                    anchorBounds = anchorBounds,
                    alignment = alignment,
                    containerColor = containerColor,
                    scrollable = scrollable,
                    onProgressChanged = onProgressChanged,
                    modifier = modifier,
                    content = content,
                )
            }
        }

        DisposableEffect(hostState, menuId) {
            onDispose {
                hostState.remove(menuId)
            }
        }
    } else {
        MaterialMenuSurface(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            anchorBounds = anchorBounds,
            alignment = alignment,
            containerColor = containerColor,
            scrollable = scrollable,
            onProgressChanged = onProgressChanged,
            modifier = modifier,
            content = content,
        )
    }
}

@Composable
internal fun MaterialMenuSurface(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorBounds: Rect?,
    alignment: ZafiroMenuAlignment,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    scrollable: Boolean = false,
    onProgressChanged: ((Float) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val themeStyle = LocalThemeStyle.current
    val isExpressive = themeStyle == ThemeStyle.Material3Expressive
    val density = LocalDensity.current
    val statusBarTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val resolvedContainerColor = containerColor ?: MaterialTheme.colorScheme.surfaceContainerHigh

    val animProgress = remember { Animatable(if (expanded) 1f else 0f) }

    LaunchedEffect(expanded) {
        if (expanded) {
            animProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 150, easing = LinearOutSlowInEasing),
            ) {
                onProgressChanged?.invoke(value)
            }
        } else {
            animProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 100, easing = FastOutLinearInEasing),
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
    var transformOrigin by remember { mutableStateOf(TransformOrigin.Center) }

    DisposableEffect(expanded) {
        if (!expanded) return@DisposableEffect onDispose {}

        val unregister = GlobalTouchDispatcher.register { event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val clickOffset = Offset(event.x, event.y)
                val isInside = (cardBounds?.contains(clickOffset) == true) ||
                    (anchorBounds?.contains(clickOffset) == true)
                if (!isInside) {
                    currentOnDismissRequest()
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
                            left = left,
                            top = statusBarTopPx + 4f,
                            right = left + defaultTitleWidthPx,
                            bottom = statusBarTopPx + 4f + defaultButtonSizePx,
                        )
                    }
                    PopoverAlignment.BottomEnd -> {
                        val right = screenWidthPx - with(density) { 12.dp.toPx() }
                        Rect(
                            left = right - defaultButtonSizePx,
                            top = statusBarTopPx + 4f,
                            right = right,
                            bottom = statusBarTopPx + 4f + defaultButtonSizePx,
                        )
                    }
                    PopoverAlignment.BottomStart -> {
                        val left = with(density) { 12.dp.toPx() }
                        Rect(
                            left = left,
                            top = statusBarTopPx + 4f,
                            right = left + defaultButtonSizePx,
                            bottom = statusBarTopPx + 4f + defaultButtonSizePx,
                        )
                    }
                }
            }

            val marginPx = with(density) { 8.dp.toPx() }
            val screenWidthPx = constraints.maxWidth.toFloat()
            val maxAllowedWidthPx = (screenWidthPx - marginPx * 2).coerceAtLeast(with(density) { 112.dp.toPx() })
            val dynamicMaxWidthDp = with(density) { minOf(280.dp.toPx(), maxAllowedWidthPx).toDp() }

            val gapPx = with(density) { 4.dp.toPx() }
            val screenAvailableHeightPx = (constraints.maxHeight - anchor.bottom.toInt() - with(density) { 16.dp.roundToPx() })
                .coerceAtLeast(with(density) { 100.dp.roundToPx() })
            val maxAvailableHeightPx = if (scrollable) {
                minOf(screenAvailableHeightPx, with(density) { 260.dp.roundToPx() })
            } else {
                screenAvailableHeightPx
            }

            val panelShape = if (isExpressive) G2FieldShape(20.dp) else G2FieldShape(16.dp)
            val animAlpha = progress.coerceIn(0f, 1f)
            val animScale = lerp(0.90f, 1f, progress.coerceIn(0f, 1f))

            val cardMeasurables = subcompose("material_menu_card") {
                Surface(
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = animAlpha
                            scaleX = animScale
                            scaleY = animScale
                            this.transformOrigin = transformOrigin
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .onGloballyPositioned { coordinates ->
                            cardBounds = coordinates.boundsInWindow()
                        },
                    shape = panelShape,
                    color = resolvedContainerColor,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .widthIn(min = 112.dp, max = dynamicMaxWidthDp)
                            .then(modifier)
                            .then(
                                if (scrollable) Modifier.verticalScroll(rememberScrollState())
                                else Modifier
                            )
                            .padding(vertical = 4.dp),
                        content = content,
                    )
                }
            }

            val cardPlaceable = cardMeasurables.first().measure(
                constraints.copy(
                    minWidth = 0,
                    minHeight = 0,
                    maxHeight = maxAvailableHeightPx,
                ),
            )

            val targetWidthPx = cardPlaceable.width.toFloat()
            val targetHeightPx = cardPlaceable.height.toFloat()

            // 竖向位置：优先挂在锚点正下方 4dp 处；如果下方空间不足，则翻转到锚点上方
            val targetTopPx = anchor.bottom + gapPx
            val spaceBelow = constraints.maxHeight - marginPx - targetTopPx
            val spaceAbove = anchor.top - gapPx - statusBarTopPx
            val shouldFlipAbove = spaceBelow < targetHeightPx && spaceAbove > spaceBelow

            val finalTopPx = if (shouldFlipAbove) {
                (anchor.top - gapPx - targetHeightPx).coerceAtLeast(statusBarTopPx)
            } else {
                targetTopPx.coerceIn(
                    statusBarTopPx,
                    (constraints.maxHeight - marginPx - targetHeightPx).coerceAtLeast(statusBarTopPx),
                )
            }

            // 横向位置：按 alignment 精准中轴/左/右对齐
            val idealLeftPx = when (alignment) {
                PopoverAlignment.BottomCenter -> anchor.center.x - targetWidthPx / 2f
                PopoverAlignment.BottomEnd -> anchor.right - targetWidthPx
                PopoverAlignment.BottomStart -> anchor.left
            }
            val finalLeftPx = idealLeftPx.coerceIn(marginPx, screenWidthPx - marginPx - targetWidthPx)

            // 缩放中心：严格对齐锚点中心/右侧/左侧
            val pivotX = if (targetWidthPx > 0f) {
                val anchorFocusX = when (alignment) {
                    PopoverAlignment.BottomCenter -> anchor.center.x
                    PopoverAlignment.BottomEnd -> anchor.right
                    PopoverAlignment.BottomStart -> anchor.left
                }
                ((anchorFocusX - finalLeftPx) / targetWidthPx).coerceIn(0f, 1f)
            } else {
                0.5f
            }
            val pivotY = if (shouldFlipAbove) 1f else 0f
            val newOrigin = TransformOrigin(pivotX, pivotY)
            if (transformOrigin != newOrigin) {
                transformOrigin = newOrigin
            }

            layout(constraints.maxWidth, constraints.maxHeight) {
                cardPlaceable.place(finalLeftPx.toInt(), finalTopPx.toInt())
            }
        }
    }
}
