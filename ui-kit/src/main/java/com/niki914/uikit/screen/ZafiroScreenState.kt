package com.niki914.uikit.screen

import androidx.compose.runtime.Composable
import com.niki914.uikit.infra.LiquidScreenState
import com.niki914.uikit.infra.LiquidScreenTitle
import com.niki914.uikit.infra.rememberLiquidScreenState

typealias ZafiroScreenState = LiquidScreenState

@Composable
fun rememberZafiroScreenState(
    title: ZafiroScreenTitle = LiquidScreenTitle.Text(""),
    isTitleCollapsible: Boolean = false,
    showLeftButton: Boolean = true,
    showRightButton: Boolean = true,
    showBlurLayer: Boolean = true,
    onLeftClick: (() -> Unit)? = null,
    onRightClick: (() -> Unit)? = null,
): ZafiroScreenState = rememberLiquidScreenState(
    title = title,
    isTitleCollapsible = isTitleCollapsible,
    showLeftButton = showLeftButton,
    showRightButton = showRightButton,
    showBlurLayer = showBlurLayer,
    onLeftClick = onLeftClick,
    onRightClick = onRightClick,
)
