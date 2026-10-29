package com.niki914.zafiro.app.ui.chat.composable.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.chat.composable.action.EmbeddedActionStep
import com.niki914.zafiro.app.ui.chat.composable.action.StandaloneActionCard
import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.chat.model.ActionUiModel
import com.niki914.zafiro.app.ui.chat.model.TurnSection
import com.niki914.zafiro.app.ui.content.BlockHeaderInsetExpanded
import com.niki914.zafiro.app.ui.model.ToolPresentation

/**
 * 复合步骤收纳容器：展示连续 N >= 2 个执行步骤（Thinking / Tool）。
 *
 * 1. 折叠态：展示「已折叠 N 个步骤 >」单行胶囊；
 * 2. 展开态：Header 切换为「收起 N 个步骤 ⬇️」，下方展开子步骤列表；
 * 3. 内部子步骤：默认呈收起单行态，用户可独立点击具体子步骤展开详情；
 * 4. 再次点击 Header：整体自下而上折入 Header 底边。
 */
@Composable
internal fun WrappedStepsContainer(
    section: TurnSection.WrappedSteps,
    modifier: Modifier = Modifier,
) {
    if (section.steps.isEmpty()) return

    val titleRes = if (section.isExpanded) {
        R.plurals.ui_steps_collapse_count
    } else {
        R.plurals.ui_steps_wrapped_count
    }

    val outerAction = ActionUiModel(
        key = section.key,
        iconSource = ToolPresentation.IconSource.Vector(ToolPresentation.Multi),
        title = pluralStringResource(
            titleRes,
            section.steps.size,
            section.steps.size,
        ),
        isExpanded = section.isExpanded,
        onToggle = section.onToggle,
        body = ActionBodyUi.Standard.Simple(),
    )

    StandaloneActionCard(
        action = outerAction,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = BlockHeaderInsetExpanded,
            end = BlockHeaderInsetExpanded,
            top = 6.dp,
            bottom = 6.dp,
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            section.steps.forEach { stepAction ->
                key(stepAction.key) {
                    EmbeddedActionStep(action = stepAction)
                }
            }
        }
    }
}
