package com.niki914.uikit.infra.component

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.ReportTitleBarCollapsed
import com.niki914.uikit.infra.liquidScreenBottomPadding
import com.niki914.uikit.infra.liquidScreenTopPadding

/** 输入框底边与键盘顶边之间保留的边距。 */
private val SettingsFormAvoidanceMargin = 12.dp

/** 避让循环的帧数上限（~2s），避免键盘一直不出现时无限循环。 */
private const val MaxAvoidanceFrames = 120

/** 连续多少帧「键盘不动且输入框已在键盘之上」后结束避让。 */
private const val SettledFramesToStop = 3

/**
 * 设置详情表单脚手架，必须运行在 `LiquidScreen` 内容树内。
 *
 * Preview 或独立样例请用 `ProvideLiquidScreenContentForPreview` 提供壳层上下文。
 *
 * @param contentBottomPadding 滚动内容区底部安全距离。null（默认）走壳层下发的
 * `liquidScreenBottomPadding()`；非空则覆盖重写。
 * @param actionButtonBottomPadding 吸底按钮的底边距。null（默认）走壳层下发的
 * `liquidScreenBottomPadding()`；非空则覆盖重写。与 `contentBottomPadding`
 * 分开暴露：内容区预留与按钮位置是两个独立诉求。
 */
@Composable
fun SettingsDetailFormScaffold(
    actionText: String,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    inlineErrorText: String? = null,
    actionEnabled: Boolean = true,
    onBackgroundTap: (() -> Unit)? = null,
    contentBottomPadding: Dp? = null,
    actionButtonBottomPadding: Dp? = null,
    actionButtonDarkContainerColor: Color = Color.Unspecified,
    actionButtonLightContainerColor: Color = Color.Unspecified,
    actionButtonDarkContentColor: Color = Color.Unspecified,
    actionButtonLightContentColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState =
        rememberSaveable(saver = ScrollState.Saver, init = { ScrollState(initial = 0) })
    // 表单脚手架含多行输入框（如自定义 Python 代码），BasicTextField 行数封顶后
    // 自带的内部滚动会作为已消费增量冒泡到壳层，被误计入页面滚离量（页面停在顶部、
    // 顶栏却变实体）。用自身的滚动状态精确接管，输入框内部滚动不再参与。
    ReportTitleBarCollapsed { scrollState.value > 0 }

    // 键盘避让：表单输入框展开并获焦时，把内容滚到键盘之上。
    // 只收窄视口是不够的——视口变矮只是提供了可滚余量，没有任何东西会去滚它；
    // Compose 自带的 bringIntoView 只在获焦瞬间请求一次，而键盘是之后才长出来的，
    // 请求早已结束。这里自己按 IME inset 逐帧收敛，滚定即停（不锁，之后可手动滚动）。
    val keyboardAvoidance = remember { SettingsFormKeyboardAvoidance() }
    val hostView = LocalView.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val latestImeBottomPx by rememberUpdatedState(imeBottomPx)
    val avoidanceMarginPx = with(density) { SettingsFormAvoidanceMargin.toPx() }
    val activeAvoidanceKey = keyboardAvoidance.activeKey
    LaunchedEffect(activeAvoidanceKey) {
        if (activeAvoidanceKey == null) return@LaunchedEffect
        var frames = 0
        var settledFrames = 0
        var lastImePx = -1
        // 上次滚动时依据的 bounds 底边。bounds 要等下一次布局才回填（低端机滞后 1-2 帧），
        // 若每帧都按旧 bounds 累加 delta，一次避让会被重复施加 2-3 次，页面冲过头甚至顶到
        // maxValue。只允许在 bounds 刷新过之后再次滚动，把累加控制器变成反馈闭环。
        var actedBoundsBottom = Float.NaN
        while (frames < MaxAvoidanceFrames && settledFrames < SettledFramesToStop) {
            withFrameNanos { }
            frames++
            val imePx = latestImeBottomPx
            val bounds = keyboardAvoidance.activeBounds
            val keyboardMoving = imePx != lastImePx
            lastImePx = imePx
            // 输入框底边需要高出键盘顶边一个边距；未超出则无需滚动。
            val deltaPx = if (imePx > 0 && bounds != null) {
                bounds.bottom + avoidanceMarginPx - (hostView.height - imePx)
            } else {
                0f
            }
            if (deltaPx > 0f && bounds != null && bounds.bottom != actedBoundsBottom) {
                val beforeScroll = scrollState.value
                val target = (beforeScroll + deltaPx).toInt()
                    .coerceIn(0, scrollState.maxValue)
                scrollState.scrollTo(target)
                // 只有真的滚动了才登记。滚动被 maxValue 截断时不登记，保留重试资格：
                // 键盘动画期间视口还在收窄，余量稍后会出现。
                actedBoundsBottom =
                    if (scrollState.value != beforeScroll) bounds.bottom else Float.NaN
            }
            if (imePx > 0 && !keyboardMoving && deltaPx <= 0f) {
                settledFrames++
            } else {
                settledFrames = 0
            }
        }
    }

    val resolvedContentBottomPadding = contentBottomPadding ?: liquidScreenBottomPadding()
    val resolvedActionButtonBottomPadding =
        actionButtonBottomPadding ?: liquidScreenBottomPadding()
    val contentModifier = if (onBackgroundTap != null) {
        Modifier.pointerInput(onBackgroundTap) {
            detectTapGestures(onTap = { onBackgroundTap() })
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize(),
    ) {
        Column(
            modifier = contentModifier
                .fillMaxSize()
                // 键盘弹起时收窄本滑动列的可视区（inset 加在 verticalScroll 之前，
                // 只缩滚动视口，不缩吸底按钮）：视口变矮后，内容可以真实滚动到
                // 键盘上方，上方的卡片也能滚回来；顶栏随之按真实滚动变实体。
                .imePadding()
                .verticalScroll(scrollState)
                .padding(
                    horizontal = SettingsDetailPageDefaults.HorizontalPadding,
                )
                .padding(
                    top = liquidScreenTopPadding(
                        SettingsDetailPageDefaults.VerticalPadding
                    ),
                    // 底部预留必须 ≥ 按钮位高（底边距 + 按钮高），否则最后一张卡片会被吸底按钮盖住；
                    // 与下方按钮的 bottom 同源解析，两处必须一起改。
                    bottom = resolvedContentBottomPadding +
                            SettingsDetailPageDefaults.RootVerticalSpacing +
                            SettingsDetailPageDefaults.ActionButtonReservedHeight,
                ),
            verticalArrangement = Arrangement.spacedBy(
                SettingsDetailPageDefaults.ContentVerticalSpacing,
            ),
        ) {
            if (!description.isNullOrBlank()) {
                PageDescriptionText(text = description)
            }
            CompositionLocalProvider(
                LocalSettingsFormKeyboardAvoidance provides keyboardAvoidance,
            ) {
                content()
            }
            if (!inlineErrorText.isNullOrBlank()) {
                Text(
                    text = inlineErrorText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(
                        horizontal = SettingsDetailPageDefaults.InlineErrorHorizontalPadding,
                    ),
                )
            }
        }

        TintLiquidButton(
            text = actionText,
            enabled = actionEnabled,
            onClick = onActionClick,
            buttonHeight = 56.dp,
            darkContainerColor = actionButtonDarkContainerColor,
            lightContainerColor = actionButtonLightContainerColor,
            darkContentColor = actionButtonDarkContentColor,
            lightContentColor = actionButtonLightContentColor,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = SettingsDetailPageDefaults.HorizontalPadding,
                    end = SettingsDetailPageDefaults.HorizontalPadding,
                    bottom = resolvedActionButtonBottomPadding,
                ),
        )
    }
}
