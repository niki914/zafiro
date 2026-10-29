package com.niki914.zafiro.app.ui.chat.composable.section

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.niki914.zafiro.app.ui.chat.composable.action.StandaloneActionCard
import com.niki914.zafiro.app.ui.chat.composable.spacing.AssistantSectionSpacing
import com.niki914.zafiro.app.ui.chat.composable.text.AssistantOutputText
import com.niki914.zafiro.app.ui.chat.model.TurnSection

/**
 * Level 2: 助手段落分流器。
 * 纯粹的 when cases 分流：
 * - Text: 助手 Markdown 正文
 * - Step: 独立步骤卡片（单步穿透或流式平铺）
 * - WrappedSteps: 复合步骤收纳块
 * - Error: 系统/网络错误卡片
 * - Retrying: 重试中卡片
 */
@Composable
internal fun SectionList(
    sections: List<TurnSection>,
    onContentTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        sections.forEachIndexed { index, section ->
            key(section.key) {
                if (index > 0) {
                    AssistantSectionSpacing()
                }
                when (section) {
                    is TurnSection.Text -> {
                        AssistantOutputText(
                            text = section.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onContentTap,
                                ),
                        )
                    }

                    is TurnSection.Step -> {
                        StandaloneActionCard(action = section.action)
                    }

                    is TurnSection.WrappedSteps -> {
                        WrappedStepsContainer(section = section)
                    }

                    is TurnSection.Error -> {
                        StandaloneActionCard(action = section.action)
                    }

                    is TurnSection.Retrying -> {
                        StandaloneActionCard(action = section.action)
                    }
                }
            }
        }
    }
}
