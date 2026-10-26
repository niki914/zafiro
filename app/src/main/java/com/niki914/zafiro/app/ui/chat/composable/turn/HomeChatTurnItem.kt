package com.niki914.zafiro.app.ui.chat.composable.turn

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.chat.composable.TurnItem
import com.niki914.zafiro.app.ui.chat.composable.bubble.UserBubblePosition
import com.niki914.zafiro.app.ui.chat.composable.section.CompactedDividerItem
import com.niki914.zafiro.app.ui.helper.ZafiroToast
import com.niki914.zafiro.app.ui.chat.helper.honestCopyToClipboard
import com.niki914.zafiro.app.ui.chat.mapper.toTurnUiModel
import com.niki914.zafiro.app.ui.model.home.ActionSource
import com.niki914.zafiro.app.ui.model.home.HomeChatTurn
import com.niki914.zafiro.app.ui.model.home.MessageActionsDisplay
import com.niki914.zafiro.app.ui.viewer.ViewerContent
import kotlinx.coroutines.launch

@Composable
internal fun HomeChatTurnItem(
    turn: HomeChatTurn,
    userBubblePosition: UserBubblePosition,
    isLastTurn: Boolean,
    actionsDisplay: MessageActionsDisplay,
    /** 点组内任意一条时操作行归一到组尾 turn；非组尾成员仅作点击归一目标。 */
    userGroupTailTurnId: Long,
    /** 组尾 turn 的操作行资格（blocks 非空或为最后一条）。 */
    userGroupTappable: Boolean,
    /** 组内全部用户文本（双换行分隔），供组尾操作行复制。 */
    userGroupText: String,
    onContentTap: () -> Unit,
    onReGenerate: (Long) -> Unit,
    onFork: (Long) -> Unit,
    onRewind: (Long) -> Unit,
    expandedActionTurnId: Long?,
    expandedActionSource: ActionSource?,
    activeThinkingKey: String? = null,
    onToggleActionRow: (Long, ActionSource) -> Unit,
    isGenerating: Boolean,
    branchIndex: Int = 1,
    branchCount: Int = 1,
    onSwitchBranch: (Int) -> Unit = {},
    alwaysShowDetailed: Boolean = false,
    userToggledKeys: Map<String, Boolean> = emptyMap(),
    onToggleAction: (key: String, nextExpanded: Boolean) -> Unit = { _, _ -> },
    safeZoneMinHeight: Dp = 0.dp,
    userMessageMaxHeight: Dp = Dp.Unspecified,
    onOpenViewer: (ViewerContent) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (turn.isCompactedDivider) {
        CompactedDividerItem(
            isCompacting = turn.isCompacting,
            modifier = modifier,
        )
        return
    }

    // Agent 操作行：仅正生成中的末轮隐藏；已结束的旧 turn 常显（复制无害，
    // 重新生成/分叉/回退在生成中点按弹 toast，见 guarded 回调）
    val canToggleAction = turn.blocks.isNotEmpty() && (!isGenerating || !isLastTurn)
    // User 操作行资格：生成中同样放开（历史遗留修复：复制无害，重新生成/回退由 Controller 护栏拦截）；
    // 最后一条 turn 即使无内容也放开，使本条 query 仍可复制
    val userRowEligible = turn.blocks.isNotEmpty() || isLastTurn
    val isUserGroupTail = turn.id == userGroupTailTurnId

    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    fun copyText(text: String) {
        scope.launch {
            honestCopyToClipboard(text = text, clipboard = clipboard, hapticFeedback = haptic)
        }
    }

    fun toastGenerating() {
        ZafiroToast.show(R.string.ui_toast_generating)
    }
    // 生成中：危险动作（重新生成/分叉/回退）弹 toast 提示，不透传给 Controller（其护栏会静默吞掉）
    val guardedReGenerate: (Long) -> Unit = { id ->
        if (isGenerating) toastGenerating() else onReGenerate(id)
    }
    val guardedFork: (Long) -> Unit = { id ->
        if (isGenerating) toastGenerating() else onFork(id)
    }
    val guardedRewind: (Long) -> Unit = { id ->
        if (isGenerating) toastGenerating() else onRewind(id)
    }

    val isActionExpanded = expandedActionTurnId == turn.id
    val actionSource = expandedActionSource
    val showActionRow = isActionExpanded

    val turnUi = turn.toTurnUiModel(
        alwaysShowDetailed = alwaysShowDetailed,
        userToggledKeys = userToggledKeys,
        onToggleAction = onToggleAction,
        activeThinkingKey = activeThinkingKey,
    )

    TurnItem(
        turn = turnUi,
        userBubblePosition = userBubblePosition,
        isUserGroupTail = isUserGroupTail,
        userRowEligible = userRowEligible,
        userGroupTappable = userGroupTappable,
        userGroupText = userGroupText,
        showUserActionRow = showActionRow && actionSource == ActionSource.User,
        showAgentActionRow = showActionRow && actionSource == ActionSource.Agent,
        canToggleAgentAction = canToggleAction,
        actionsDisplay = actionsDisplay,
        onContentTap = onContentTap,
        onToggleUserAction = { onToggleActionRow(userGroupTailTurnId, ActionSource.User) },
        onToggleAgentAction = { onToggleActionRow(turn.id, ActionSource.Agent) },
        onCopyText = { copyText(it) },
        onReGenerate = { guardedReGenerate(turn.id) },
        onFork = { guardedFork(turn.id) },
        onRewind = { guardedRewind(turn.id) },
        branchIndex = branchIndex,
        branchCount = branchCount,
        onSwitchBranch = onSwitchBranch,
        safeZoneMinHeight = safeZoneMinHeight,
        userMessageMaxHeight = userMessageMaxHeight,
        onViewFullUserText = { onOpenViewer(ViewerContent.Text(turn.userText)) },
        modifier = modifier,
    )
}
