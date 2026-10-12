package com.niki914.uikit.infra

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

/**
 * 顶栏标题内容，两种职能：
 *
 * - [Text] 页面标识：随导航变化，切页时按方向左右滑。
 * - [Content] 自定义槽：页面活着时随时可换的活内容（会话标题、选中数量、配置切换器）。
 *
 * 两种都参与切页动画，退场帧与入场帧各渲染自己那一份 —— 不会两帧同屏渲染同一个标题。
 * 命中动画的相等判据是实例本身，所以 [Content] 里的 composable 必须由调用方
 * `remember` 住：每次重组新建实例会被当成一次新的标题变化，反复重放切页动画。
 */
sealed interface LiquidScreenTitle {
    val key: Any?

    data class Text(
        val value: String,
        override val key: Any? = null,
    ) : LiquidScreenTitle

    class Content(
        val content: @Composable () -> Unit,
        override val key: Any? = null,
    ) : LiquidScreenTitle {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Content) return false
            return key == other.key && content == other.content
        }

        override fun hashCode(): Int {
            return 31 * (key?.hashCode() ?: 0) + content.hashCode()
        }
    }
}

@Stable
class LiquidScreenState(
    initialTitle: LiquidScreenTitle = LiquidScreenTitle.Text(""),
    initialIsTitleCollapsible: Boolean = false,
    initialShowLeftButton: Boolean = true,
    initialShowRightButton: Boolean = true,
    initialShowBlurLayer: Boolean = true,
    initialOnLeftClick: (() -> Unit)? = null,
    initialOnRightClick: (() -> Unit)? = null,
) {
    private val _actionBarHeight = mutableStateOf(0.dp)
    val actionBarHeight: State<Dp> = _actionBarHeight

    internal var title by mutableStateOf(initialTitle)
    internal var isTitleCollapsible by mutableStateOf(initialIsTitleCollapsible)
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

    /** 切页（正向）：标题按 Forward 方向滑。 */
    fun navigateForward(
        title: LiquidScreenTitle,
        isTitleCollapsible: Boolean = this.isTitleCollapsible,
        showLeftButton: Boolean = this.showLeftButton,
        showRightButton: Boolean = this.showRightButton,
        showBlurLayer: Boolean = this.showBlurLayer,
        onLeftClick: (() -> Unit)? = this.onLeftClick,
        onRightClick: (() -> Unit)? = this.onRightClick,
    ) {
        titleDirection = TitleDirection.Forward
        apply(
            title,
            isTitleCollapsible,
            showLeftButton,
            showRightButton,
            showBlurLayer,
            onLeftClick,
            onRightClick
        )
    }

    /** 切页（反向）：标题按 Back 方向滑。 */
    fun navigateBack(
        title: LiquidScreenTitle,
        isTitleCollapsible: Boolean = this.isTitleCollapsible,
        showLeftButton: Boolean = this.showLeftButton,
        showRightButton: Boolean = this.showRightButton,
        showBlurLayer: Boolean = this.showBlurLayer,
        onLeftClick: (() -> Unit)? = this.onLeftClick,
        onRightClick: (() -> Unit)? = this.onRightClick,
    ) {
        titleDirection = TitleDirection.Back
        apply(
            title,
            isTitleCollapsible,
            showLeftButton,
            showRightButton,
            showBlurLayer,
            onLeftClick,
            onRightClick
        )
    }

    /** 同页刷新（非切页）：标题瞬切，不参与左右滑。 */
    fun update(
        title: LiquidScreenTitle,
        isTitleCollapsible: Boolean = this.isTitleCollapsible,
        showLeftButton: Boolean = this.showLeftButton,
        showRightButton: Boolean = this.showRightButton,
        showBlurLayer: Boolean = this.showBlurLayer,
        onLeftClick: (() -> Unit)? = this.onLeftClick,
        onRightClick: (() -> Unit)? = this.onRightClick,
    ) {
        titleDirection = TitleDirection.None
        apply(
            title,
            isTitleCollapsible,
            showLeftButton,
            showRightButton,
            showBlurLayer,
            onLeftClick,
            onRightClick
        )
    }

    private fun apply(
        title: LiquidScreenTitle,
        isTitleCollapsible: Boolean,
        showLeftButton: Boolean,
        showRightButton: Boolean,
        showBlurLayer: Boolean,
        onLeftClick: (() -> Unit)?,
        onRightClick: (() -> Unit)?,
    ) {
        this.title = title
        this.isTitleCollapsible = isTitleCollapsible
        this.showLeftButton = showLeftButton
        this.showRightButton = showRightButton
        this.showBlurLayer = showBlurLayer
        this.onLeftClick = onLeftClick
        this.onRightClick = onRightClick
    }
}

@Composable
fun rememberLiquidScreenState(
    title: LiquidScreenTitle = LiquidScreenTitle.Text(""),
    isTitleCollapsible: Boolean = false,
    showLeftButton: Boolean = true,
    showRightButton: Boolean = true,
    showBlurLayer: Boolean = true,
    onLeftClick: (() -> Unit)? = null,
    onRightClick: (() -> Unit)? = null,
): LiquidScreenState {
    return remember {
        LiquidScreenState(
            initialTitle = title,
            initialIsTitleCollapsible = isTitleCollapsible,
            initialShowLeftButton = showLeftButton,
            initialShowRightButton = showRightButton,
            initialShowBlurLayer = showBlurLayer,
            initialOnLeftClick = onLeftClick,
            initialOnRightClick = onRightClick,
        )
    }
}
