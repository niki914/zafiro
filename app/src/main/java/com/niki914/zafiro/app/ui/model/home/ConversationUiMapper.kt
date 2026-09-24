package com.niki914.zafiro.app.ui.model.home

import com.niki914.zafiro.api.model.AgentPhase
import com.niki914.okia.message.ContentBlock
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.DraftImage
import com.niki914.zafiro.api.model.ToolInvocation
import com.niki914.zafiro.api.model.ToolOutcome
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.api.model.TurnFailureCode
import com.niki914.zafiro.app.ui.model.ToolPresentation
import com.niki914.zafiro.chat.LlmErrorCode

// Tool status is contract-derived; renderer keeps its existing three-state UI model.
/**
 * 契约类型到 UI 模型的映射。
 *
 * UI 模型（`HomeChatTurn` 等）是渲染层的边界：content 包不依赖 `business:api`，
 * 契约字段变化只改这一处。`HomeChatTurn.id` 取回合在列表里的位置，与契约的
 * `TurnId`（`"t$index"`）一一对应，展开态 key 与 intent 的 Long 口径不用动。
 */

/** 契约回合列表 → UI 回合列表。 */
internal fun Conversation.toHomeTurns(): List<HomeChatTurn> =
    turns.mapIndexed { turnIndex, turn ->
        HomeChatTurn(
            id = turnIndex.toLong(),
            userText = turn.userText,
            images = turn.attachments.map { it.toHomeImage() },
            blocks = turn.blocks.mapIndexed { blockIndex, block -> block.toHomeBlock(blockIndex) },
        )
    }

/** 契约阶段 → 生成中。除 Idle 外都算生成中（含工具执行与等待授权）。 */
internal fun AgentPhase.isGenerating(): Boolean = this != AgentPhase.Idle

/** 草稿图片 → 待发送图片条。Pending 还没有落盘路径，用 uri 占位，发送时由实现侧跳过。 */
internal fun DraftImage.toHomeImage(): HomeChatImage? = when (this) {
    // The existing image row renders file paths. Keep the old behavior (show after ingest).
    is DraftImage.Pending -> null
    is DraftImage.Ready -> attachment.toHomeImage()
}

internal fun ContentBlock.Image.toHomeImage(): HomeChatImage =
    HomeChatImage(id = path.hashCode().toString(), path = path)

private fun Attachment.toHomeImage(): HomeChatImage =
    HomeChatImage(id = path.hashCode().toString(), path = path)

private fun TurnBlock.toHomeBlock(blockIndex: Int): HomeChatBlock = when (this) {
    is TurnBlock.Text -> HomeChatBlock.Text(text)
    is TurnBlock.Thinking -> HomeChatBlock.Thinking(id = blockIndex, text = text, isComplete = isComplete)
    is TurnBlock.Tool -> HomeChatBlock.Tool(invocation.toStatus(outcome))
    is TurnBlock.Failure -> HomeChatBlock.Error(
        message = message,
        code = code.toLlmErrorCode(),
        attempts = attempts,
    )

    is TurnBlock.Retrying -> HomeChatBlock.Retrying(attempt, maxAttempts, delayMs, reason)
}

private fun ToolInvocation.toStatus(outcome: ToolOutcome?): HomeToolStatus {
    val (state, resultText, failedReason) = when (outcome) {
        null -> Triple(HomeToolState.Running, null, null)
        is ToolOutcome.Succeeded -> Triple(HomeToolState.Succeeded, outcome.resultText, null)
        is ToolOutcome.Failed -> Triple(HomeToolState.Failed, outcome.resultText, outcome.message)
    }
    return HomeToolStatus(
        callId = id.takeUnless { it == name },
        name = label,
        state = state,
        resultText = resultText,
        failedReason = failedReason,
        displayNameRes = ToolPresentation.displayNameResOf(name),
        inputText = ToolPresentation.inputOf(name, argumentsJson),
        images = (outcome as? ToolOutcome.Succeeded)?.images.orEmpty().map { it.toHomeImage() },
    )
}

/** UI 错误卡按 `LlmErrorCode` 选文案；契约的 `TurnConflict` 已删除，映射不产生它。 */
private fun TurnFailureCode?.toLlmErrorCode(): LlmErrorCode? = when (this) {
    null -> null
    TurnFailureCode.ConfigRequired -> LlmErrorCode.ConfigRequired
    TurnFailureCode.Auth -> LlmErrorCode.Auth
    TurnFailureCode.Quota -> LlmErrorCode.Quota
    TurnFailureCode.RateLimit -> LlmErrorCode.RateLimit
    TurnFailureCode.Overloaded -> LlmErrorCode.Overloaded
    TurnFailureCode.Transport -> LlmErrorCode.Transport
    TurnFailureCode.Parse -> LlmErrorCode.Parse
    TurnFailureCode.RetryExhausted -> LlmErrorCode.RetryExhausted
    TurnFailureCode.HookFailed -> LlmErrorCode.HookFailed
    TurnFailureCode.ToolExecutionFailed -> LlmErrorCode.ToolExecutionFailed
    TurnFailureCode.IdleTimeout -> LlmErrorCode.IdleTimeout
}
