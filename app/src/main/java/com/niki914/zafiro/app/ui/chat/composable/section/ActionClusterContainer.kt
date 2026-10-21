package com.niki914.zafiro.app.ui.chat.composable.section

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.chat.composable.action.StandaloneActionCard
import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.chat.model.ActionUiModel
import com.niki914.zafiro.app.ui.chat.model.TurnSection
import com.niki914.zafiro.app.ui.content.BlockSpacing
import com.niki914.zafiro.app.ui.model.ToolPresentation
import kotlinx.coroutines.delay

/**
 * 工具链容器分流器：遵循卡片化工具链交互规范。
 *
 * 1. 单工具（actions.size == 1）：直接外露展示单张 [StandaloneActionCard]；
 * 2. 多工具（actions.size >= 2）：外层展示标题为「%d 个工具调用」的 [StandaloneActionCard]，
 *    展开后内部展示多张独立子卡片的列表，带阶梯入场动效；
 * 3. 彻底干掉自然语言时态文案、薄线分割与 Slot 1 吸收动效。
 */
@Composable
internal fun ActionClusterContainer(
    section: TurnSection.ToolRun,
    modifier: Modifier = Modifier,
) {
    if (section.actions.isEmpty()) return

    if (section.actions.size == 1) {
        val singleAction = section.actions.first()
        StandaloneActionCard(
            action = singleAction,
            modifier = modifier,
        )
    } else {
        val outerAction = ActionUiModel(
            key = section.key,
            iconSource = ToolPresentation.IconSource.Vector(ToolPresentation.Multi),
            title = pluralStringResource(
                R.plurals.ui_tool_chain_count,
                section.actions.size,
                section.actions.size,
            ),
            isExpanded = section.isExpanded,
            onToggle = section.onToggle,
            body = ActionBodyUi.Standard.Simple(),
        )

        StandaloneActionCard(
            action = outerAction,
            modifier = modifier,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(BlockSpacing)) {
                section.actions.forEachIndexed { index, toolAction ->
                    StaggeredEntry(index = index, staggerMs = index * 40L) {
                        StandaloneActionCard(action = toolAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun StaggeredEntry(
    index: Int,
    staggerMs: Long,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        delay(staggerMs)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) +
                slideInVertically(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) { it / 4 },
        exit = fadeOut(tween(80)),
    ) {
        content()
    }
}
