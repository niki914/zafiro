package com.niki914.zafiro.app.ui.chat.composable.action

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.ui.chat.composable.body.ActionBodyDispatcher
import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.chat.model.ActionUiModel
import com.niki914.zafiro.app.ui.chat.composable.spacing.BlockIconDot

/**
 * 复合步骤收纳容器内的轻量内嵌步骤行：
 * 剥离独立卡片外壳（背景、形状与过度边距），作为列表行无缝融入父容器。
 */
@Composable
internal fun EmbeddedActionStep(
    action: ActionUiModel,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val canToggle = !action.nonCollapsible
    val contentHorizontalPad = if (action.body is ActionBodyUi.Custom.Command) 0.dp else (BlockIconDot / 2)

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        // 单行 Header：整行具备充裕的点击热区（~38-40dp），保证无论展开收起均可精准点击
        ActionHeader(
            action = action,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (canToggle) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = action.onToggle,
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = 7.dp),
        )

        AnimatedVisibility(
            visible = action.isExpanded,
            enter = expandVertically(
                animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
            ) + fadeIn(
                animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
            ),
            exit = shrinkVertically(
                animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
            ) + fadeOut(
                animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
            ),
            modifier = Modifier.clipToBounds(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .blockClicksWhenCollapsing(action.isExpanded)
                    .padding(
                        start = contentHorizontalPad,
                        end = contentHorizontalPad,
                        top = 4.dp,
                        bottom = 6.dp,
                    ),
            ) {
                ActionBodyDispatcher(ui = action.body)
            }
        }
    }
}

/**
 * 在折叠/退出动画期间拦截子项轻触（如 tapToOpenViewer），防止向上收拢时误触进入全屏 Viewer；
 * 仅拦截消费点击（Down/Up），放行滑动（Move）事件，保证外层列表滑动不受任何干扰。
 */
private fun Modifier.blockClicksWhenCollapsing(isExpanded: Boolean): Modifier =
    if (isExpanded) this else pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { change ->
                    if (change.changedToDownIgnoreConsumed() || change.changedToUpIgnoreConsumed()) {
                        change.consume()
                    }
                }
            }
        }
    }
