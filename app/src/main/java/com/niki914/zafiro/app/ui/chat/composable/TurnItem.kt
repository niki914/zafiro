package com.niki914.zafiro.app.ui.chat.composable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.ui.chat.composable.section.SectionList
import com.niki914.zafiro.app.ui.chat.model.TurnSection
import com.niki914.zafiro.app.ui.chat.model.TurnUiModel
import com.niki914.zafiro.app.ui.chat.composable.action.TurnActionRow
import com.niki914.zafiro.app.ui.chat.composable.attachment.HomeChatAttachmentRow
import com.niki914.zafiro.app.ui.chat.composable.bubble.UserBubbleCornerRadius
import com.niki914.zafiro.app.ui.chat.composable.bubble.UserBubblePosition
import com.niki914.zafiro.app.ui.chat.composable.bubble.UserMessageBubble
import com.niki914.zafiro.app.ui.chat.composable.spacing.AssistantToActionSpacing
import com.niki914.zafiro.app.ui.chat.composable.spacing.UserAttachmentToTextSpacing
import com.niki914.zafiro.app.ui.chat.composable.spacing.UserBubbleToActionSpacing
import com.niki914.zafiro.app.ui.chat.composable.spacing.UserToAssistantSpacing
import com.niki914.zafiro.app.ui.model.home.ActionSource
import com.niki914.zafiro.app.ui.model.home.HomeChatImage
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
    safeZoneMinHeight: Dp = 0.dp,
    userMessageMaxHeight: Dp = Dp.Unspecified,
    onViewFullUserText: (() -> Unit)? = null,
    onImageClick: ((HomeChatImage) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val alwaysVisible = actionsDisplay == MessageActionsDisplay.Always
    val rootModifier = if (safeZoneMinHeight > 0.dp) {
        modifier.fillMaxWidth().heightIn(min = safeZoneMinHeight)
    } else {
        modifier.fillMaxWidth()
    }

    Column(modifier = rootModifier) {
        // ── 1. 用户内容流 ──
        val hasAttachments = turn.userImages.isNotEmpty() || turn.userFiles.isNotEmpty()
        if (hasAttachments) {
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
                    onImageClick = onImageClick,
                )
            }
        }

        if (hasAttachments && turn.userText.isNotEmpty()) {
            UserAttachmentToTextSpacing()
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
                UserMessageBubble(
                    text = turn.userText,
                    position = userBubblePosition,
                    maxHeight = userMessageMaxHeight,
                    onViewFull = onViewFullUserText,
                )
            }
        }

        // 用户操作栏
        AnimatedVisibility(
            visible = isUserGroupTail && userRowEligible && (alwaysVisible || showUserActionRow),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                UserBubbleToActionSpacing()
                TurnActionRow(
                    source = ActionSource.User,
                    onCopy = { onCopyText(userGroupText) },
                    onReGenerate = onReGenerate,
                    onFork = onFork,
                    onRewind = onRewind,
                )
            }
        }

        // ── 2. 助手生成流 ──
        if (turn.sections.isNotEmpty()) {
            UserToAssistantSpacing()
            Column(
                modifier = Modifier.fillMaxWidth(),
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
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        AssistantToActionSpacing()
                        TurnActionRow(
                            source = ActionSource.Agent,
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
}
