package com.niki914.nexus.agentic.app.ui.infra

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class TitleDirection { Forward, Back, None }

@Stable
class LiquidScreenState(
    initialTitle: String = "",
    initialShowLeftButton: Boolean = true,
    initialShowRightButton: Boolean = true,
    initialShowBlurLayer: Boolean = true,
    initialOnLeftClick: (() -> Unit)? = null,
    initialOnRightClick: (() -> Unit)? = null,
) {
    private val _actionBarHeight = mutableStateOf(0.dp)
    val actionBarHeight: State<Dp> = _actionBarHeight
    val viewportAvoidanceController = LiquidViewportAvoidanceController()

    internal var title by mutableStateOf(initialTitle)
    internal var showLeftButton by mutableStateOf(initialShowLeftButton)
    internal var showRightButton by mutableStateOf(initialShowRightButton)
    internal var showBlurLayer by mutableStateOf(initialShowBlurLayer)
    internal var onLeftClick: (() -> Unit)? by mutableStateOf(initialOnLeftClick)
    internal var onRightClick: (() -> Unit)? by mutableStateOf(initialOnRightClick)
    internal var titleDirection by mutableStateOf(TitleDirection.None)
    val navigationDirection: TitleDirection
        get() = titleDirection

    internal fun setActionBarHeight(h: Dp) {
        _actionBarHeight.value = h
    }

    fun navigateForward(
        title: String,
        showLeftButton: Boolean = this.showLeftButton,
        showRightButton: Boolean = this.showRightButton,
        showBlurLayer: Boolean = this.showBlurLayer,
        onLeftClick: (() -> Unit)? = this.onLeftClick,
        onRightClick: (() -> Unit)? = this.onRightClick,
    ) {
        titleDirection = TitleDirection.Forward
        apply(title, showLeftButton, showRightButton, showBlurLayer, onLeftClick, onRightClick)
    }

    fun navigateBack(
        title: String,
        showLeftButton: Boolean = this.showLeftButton,
        showRightButton: Boolean = this.showRightButton,
        showBlurLayer: Boolean = this.showBlurLayer,
        onLeftClick: (() -> Unit)? = this.onLeftClick,
        onRightClick: (() -> Unit)? = this.onRightClick,
    ) {
        titleDirection = TitleDirection.Back
        apply(title, showLeftButton, showRightButton, showBlurLayer, onLeftClick, onRightClick)
    }

    fun update(
        title: String,
        showLeftButton: Boolean = this.showLeftButton,
        showRightButton: Boolean = this.showRightButton,
        showBlurLayer: Boolean = this.showBlurLayer,
        onLeftClick: (() -> Unit)? = this.onLeftClick,
        onRightClick: (() -> Unit)? = this.onRightClick,
    ) {
        titleDirection = TitleDirection.None
        apply(title, showLeftButton, showRightButton, showBlurLayer, onLeftClick, onRightClick)
    }

    private fun apply(
        title: String,
        showLeftButton: Boolean,
        showRightButton: Boolean,
        showBlurLayer: Boolean,
        onLeftClick: (() -> Unit)?,
        onRightClick: (() -> Unit)?,
    ) {
        this.title = title
        this.showLeftButton = showLeftButton
        this.showRightButton = showRightButton
        this.showBlurLayer = showBlurLayer
        this.onLeftClick = onLeftClick
        this.onRightClick = onRightClick
    }
}

@Composable
fun rememberLiquidScreenState(
    title: String = "",
    showLeftButton: Boolean = true,
    showRightButton: Boolean = true,
    showBlurLayer: Boolean = true,
    onLeftClick: (() -> Unit)? = null,
    onRightClick: (() -> Unit)? = null,
): LiquidScreenState {
    return remember {
        LiquidScreenState(
            initialTitle = title,
            initialShowLeftButton = showLeftButton,
            initialShowRightButton = showRightButton,
            initialShowBlurLayer = showBlurLayer,
            initialOnLeftClick = onLeftClick,
            initialOnRightClick = onRightClick,
        )
    }
}
