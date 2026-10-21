package com.niki914.zafiro.app.ui.chat.composable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.ui.chat.composable.section.SectionList
import com.niki914.zafiro.app.ui.chat.model.TurnSection
import com.niki914.zafiro.app.ui.chat.model.TurnUiModel
import com.niki914.zafiro.app.ui.content.BlockSpacing
import com.niki914.zafiro.app.ui.content.HomeChatAttachmentRow
import com.niki914.zafiro.app.ui.content.TurnActionRow
import com.niki914.zafiro.app.ui.content.TurnSeparator
import com.niki914.zafiro.app.ui.content.UserBubbleCornerRadius
import com.niki914.zafiro.app.ui.content.UserBubblePosition
import com.niki914.zafiro.app.ui.content.UserMessageBubble
import com.niki914.zafiro.app.ui.model.home.ActionSource
import com.niki914.zafiro.app.ui.model.home.MessageActionsDisplay

/**
 * Level 1: 回合分流器（TurnItem）。
 *
 * 严格分为两大分支：
 * 1. 用户输入流（UserTurnContent）
 * 2. 助手生成流（SectionList）
 */
@Composable
internal fun TurnItem(
    turn: TurnUiModel,
    userBubblePosition: UserBubblePosition = UserBubblePosition.Single,
    isUserGroupTail: Boolean = true,
    userRowEligible: Boolean = true,
    userGroupTappable: Boolean = true,
    userGroupText: String = turn.userText,
    showUserActionRow: Boolean = false,
    showAgentActionRow: Boolean = false,
    canToggleAgentAction: Boolean = true,
    actionsDisplay: MessageActionsDisplay = MessageActionsDisplay.OnTap,
    onContentTap: () -> Unit = {},
    onToggleUserAction: () -> Unit = {},
    onToggleAgentAction: () -> Unit = {},
    onCopyText: (String) -> Unit = {},
    onReGenerate: () -> Unit = {},
    onFork: () -> Unit = {},
    onRewind: () -> Unit = {},
    branchIndex: Int = 1,
    branchCount: Int = 1,
    onSwitchBranch: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val alwaysVisible = actionsDisplay == MessageActionsDisplay.Always

    Column(modifier = modifier.fillMaxWidth()) {
        // ── 1. 用户内容流 ──
        if (turn.userImages.isNotEmpty() || turn.userFiles.isNotEmpty()) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                HomeChatAttachmentRow(
                    images = turn.userImages,
                    files = turn.userFiles,
                    cardSize = 120.dp,
                    cornerRadius = UserBubbleCornerRadius,
                    modifier = Modifier.widthIn(max = maxWidth * 0.82f),
                )
            }
        }

        if (turn.userText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            onContentTap()
                            if (!alwaysVisible && userGroupTappable) {
                                onToggleUserAction()
                            }
                        },
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                UserMessageBubble(text = turn.userText, position = userBubblePosition)
            }
        }

        // 用户操作栏
        AnimatedVisibility(
            visible = isUserGroupTail && userRowEligible && (alwaysVisible || showUserActionRow),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            TurnActionRow(
                source = ActionSource.User,
                display = actionsDisplay,
                onCopy = { onCopyText(userGroupText) },
                onReGenerate = onReGenerate,
                onFork = onFork,
                onRewind = onRewind,
            )
        }

        // ── 2. 助手生成流 ──
        if (turn.sections.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = if (userBubblePosition == UserBubblePosition.Single) {
                            TurnSeparator - BlockSpacing
                        } else {
                            0.dp
                        }
                    ),
                verticalArrangement = Arrangement.spacedBy(BlockSpacing),
            ) {
                SectionList(
                    sections = turn.sections,
                    onContentTap = {
                        onContentTap()
                        if (!alwaysVisible) {
                            onToggleAgentAction()
                        }
                    },
                )

                // 助手操作栏：严格遵守完成态护栏（生成中绝不露出）
                AnimatedVisibility(
                    visible = canToggleAgentAction && (alwaysVisible || showAgentActionRow),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    TurnActionRow(
                        source = ActionSource.Agent,
                        display = actionsDisplay,
                        onCopy = {
                            val allText = turn.sections
                                .filterIsInstance<TurnSection.Text>()
                                .joinToString("\n\n") { it.text }
                            onCopyText(allText)
                        },
                        onReGenerate = onReGenerate,
                        onFork = onFork,
                        onRewind = onRewind,
                        branchIndex = branchIndex,
                        branchCount = branchCount,
                        onSwitchBranch = onSwitchBranch,
                    )
                }
            }
        }
    }
}
