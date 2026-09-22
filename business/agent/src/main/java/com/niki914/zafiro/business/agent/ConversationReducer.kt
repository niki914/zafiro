package com.niki914.zafiro.business.agent

import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationTurn
import com.niki914.zafiro.api.model.ToolInvocation
import com.niki914.zafiro.api.model.ToolOutcome
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.api.model.TurnFailureCode
import com.niki914.zafiro.api.model.TurnId
import com.niki914.zafiro.chat.LlmErrorCode
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.chat.ToolCallStatus

/** 被掐断工具块的失败原因（沿用今天 UI 的字符串）。 */
internal const val FAILED_REASON_INTERRUPTED = "Interrupted by user"

/**
 * 回合 id 规则：值等于该回合在装配列表里的位置。
 *
 * 两条装配路径（流式归约 [ConversationReducer] 与历史装配
 * `ConversationFormatter.toConversation`）用同一规则，因此同一个回合在流式
 * 与恢复两种来源下得到同一个 id。id 不落盘（`Conversation.kt` 的边界说明），
 * 改规则只需改代码，不涉及存量数据。
 */
fun turnIdAt(turnIndex: Int): TurnId = TurnId("t$turnIndex")

/** 块 id 规则：值等于该块在回合里的位置。 */
fun blockIdAt(turnIndex: Int, blockIndex: Int): String = "t$turnIndex:$blockIndex"

/** 归约结果：会话本体 + 归约器的辅助态。 */
internal data class Reduced(
    val conversation: Conversation,
    /**
     * mapper 的回合内思考 id → 该思考块在回合里的位置。
     *
     * 思考事件带的是 mapper 分配的回合内块标识（okia 的 contentIndex 跨工具轮
     * 复用，不能当身份），而块 id 是位置；续接的 `ThinkingStarted` 回声与
     * `ThinkingEnded` 靠本表定位到同一行，而不是新起一行。每回合重置
     * （mapper 的重试路径会把其 id 归零，跨回合沿用会串行）。
     */
    val thinkingSlots: Map<Int, Int> = emptyMap(),
)

/**
 * 流事件 → [Conversation] 的折叠：进程内唯一的折叠点。
 *
 * 纯函数：没有 I/O、不访问引擎、不持有状态，调用方把上一次的结果与事件交回来。
 * 契约的「输出通道唯一」在业务层切到 `Agent.conversation` 之后成立；
 * 在此之前事件仍由 `HomeChatState` 折叠一份。
 *
 * 只折内容，不折 UI 本地态（展开集合、操作行、输入框）与派生标志
 * （生成中、屏幕常亮）：那些由消费方从 `AgentStatus.phase` 与块的
 * `isComplete` 派生。
 */
internal object ConversationReducer {

    /**
     * 发起回合：追加一个新回合，并清掉旧回合上的瞬时卡片
     * （失败卡 / 重试提示——今天 UI 的行为：新回合发起即消失）。
     */
    fun startTurn(
        conversation: Conversation,
        userText: String,
        attachments: List<Attachment>,
    ): Reduced {
        val cleared = conversation.copy(
            turns = conversation.turns.map { turn ->
                turn.copy(blocks = turn.blocks.filterNot { it is TurnBlock.Failure || it is TurnBlock.Retrying })
            },
        )
        return Reduced(
            conversation = cleared.copy(
                turns = cleared.turns + ConversationTurn(
                    id = turnIdAt(cleared.turns.size),
                    userText = userText,
                    attachments = attachments,
                ),
            ),
        )
    }

    /** 折叠一条事件。没有活跃回合（回合列表为空）时原样返回。 */
    fun reduce(state: Reduced, event: LlmStreamEvent): Reduced {
        val turnIndex = state.conversation.turns.lastIndex
        if (turnIndex < 0) return state
        val turn = state.conversation.turns[turnIndex]
        return when (event) {
            LlmStreamEvent.RoundStarted -> state

            is LlmStreamEvent.TextDelta ->
                state.withTurn(turnIndex, turn.clearRetrying().appendText(turnIndex, event.delta))

            is LlmStreamEvent.ThinkingStarted ->
                thinking(state, turnIndex, event.id, event.text, isComplete = false)

            is LlmStreamEvent.ThinkingEnded ->
                thinking(state, turnIndex, event.id, event.text, isComplete = true)

            is LlmStreamEvent.ToolPending ->
                state.withTurn(turnIndex, turn.clearRetrying().upsertTool(turnIndex, event.call, null))

            is LlmStreamEvent.ToolRunning ->
                state.withTurn(turnIndex, turn.clearRetrying().upsertTool(turnIndex, event.call, null))

            is LlmStreamEvent.ToolSucceeded ->
                state.withTurn(
                    turnIndex,
                    turn.upsertTool(
                        turnIndex,
                        event.call,
                        ToolOutcome.Succeeded(
                            resultText = event.outputText,
                            images = event.images.map { Attachment(it.path, it.mimeType) },
                        ),
                    ),
                )

            is LlmStreamEvent.ToolFailed ->
                state.withTurn(
                    turnIndex,
                    turn.upsertTool(
                        turnIndex,
                        event.call,
                        ToolOutcome.Failed(message = event.message, resultText = event.resultText),
                    ),
                )

            is LlmStreamEvent.Error ->
                state.withTurn(
                    turnIndex,
                    turn.clearRetrying().appendFailure(turnIndex, event),
                )

            is LlmStreamEvent.Retrying ->
                state.withTurn(turnIndex, turn.replaceRetrying(turnIndex, event))

            LlmStreamEvent.Completed -> state
        }
    }

    /**
     * 用户停止：仍处于未结算的工具块记为失败（原因 = 用户打断）。
     * 今天 UI 的 `finalizeRunningTools`。
     */
    fun interrupt(conversation: Conversation): Conversation {
        val turnIndex = conversation.turns.lastIndex
        if (turnIndex < 0) return conversation
        val turn = conversation.turns[turnIndex]
        if (turn.blocks.none { it is TurnBlock.Tool && it.outcome == null }) return conversation
        return conversation.withTurn(
            turnIndex,
            turn.copy(
                blocks = turn.blocks.map { block ->
                    if (block is TurnBlock.Tool && block.outcome == null) {
                        block.copy(outcome = ToolOutcome.Failed(message = FAILED_REASON_INTERRUPTED))
                    } else {
                        block
                    }
                },
            ),
        )
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun thinking(
        state: Reduced,
        turnIndex: Int,
        mapperId: Int,
        text: String,
        isComplete: Boolean,
    ): Reduced {
        // 空文本不建块（mapper 对空思考不发事件，这里是回声为空时的兜底）
        if (text.isBlank()) return state
        val turn = state.conversation.turns[turnIndex].clearRetrying()
        val slot = state.thinkingSlots[mapperId]
        val block = slot?.let { index -> turn.blocks.getOrNull(index) as? TurnBlock.Thinking }
        if (slot != null && block != null) {
            // 续接：原位更新，块位置不变
            return state.withTurn(
                turnIndex,
                turn.copy(
                    blocks = turn.blocks.toMutableList().also { blocks ->
                        blocks[slot] = block.copy(text = text, isComplete = isComplete)
                    },
                ),
            )
        }
        val blockIndex = turn.blocks.size
        return Reduced(
            conversation = state.conversation.withTurn(
                turnIndex,
                turn.copy(
                    blocks = turn.blocks + TurnBlock.Thinking(
                        id = blockIdAt(turnIndex, blockIndex),
                        text = text,
                        isComplete = isComplete,
                    ),
                ),
            ),
            thinkingSlots = state.thinkingSlots + (mapperId to blockIndex),
        )
    }

    private fun Reduced.withTurn(turnIndex: Int, turn: ConversationTurn): Reduced =
        copy(conversation = conversation.withTurn(turnIndex, turn))

    private fun Conversation.withTurn(turnIndex: Int, turn: ConversationTurn): Conversation =
        copy(turns = turns.toMutableList().also { it[turnIndex] = turn })

    private fun ConversationTurn.clearRetrying(): ConversationTurn {
        if (blocks.none { it is TurnBlock.Retrying }) return this
        return copy(blocks = blocks.filterNot { it is TurnBlock.Retrying })
    }

    private fun ConversationTurn.appendText(turnIndex: Int, delta: String): ConversationTurn {
        if (delta.isEmpty()) return this
        val last = blocks.lastOrNull()
        return if (last is TurnBlock.Text) {
            // 同一文本段续接：原位追加，块位置不变
            copy(blocks = blocks.dropLast(1) + last.copy(text = last.text + delta))
        } else {
            // 段边界（工具块之后的新文本段）：新起一块
            copy(
                blocks = blocks + TurnBlock.Text(
                    id = blockIdAt(turnIndex, blocks.size),
                    text = delta,
                ),
            )
        }
    }

    private fun ConversationTurn.upsertTool(
        turnIndex: Int,
        call: ToolCallStatus,
        outcome: ToolOutcome?,
    ): ConversationTurn {
        val index = blocks.indexOfLast { it is TurnBlock.Tool && it.matches(call) }
        if (index == -1) {
            return copy(
                blocks = blocks + TurnBlock.Tool(
                    id = blockIdAt(turnIndex, blocks.size),
                    invocation = call.toInvocation(),
                    outcome = outcome,
                ),
            )
        }
        val existing = blocks[index] as TurnBlock.Tool
        return copy(
            blocks = blocks.toMutableList().also { blocks ->
                blocks[index] = existing.copy(
                    // 占位行的 id 来自名字，参数到位后换成真实 callId（工具身份）
                    invocation = call.toInvocation(previousId = existing.invocation.id),
                    outcome = outcome,
                )
            },
        )
    }

    private fun ConversationTurn.replaceRetrying(
        turnIndex: Int,
        event: LlmStreamEvent.Retrying,
    ): ConversationTurn {
        // 同回合只有一张重试卡：替换旧的，不叠加
        val base = blocks.filterNot { it is TurnBlock.Retrying }
        return copy(
            blocks = base + TurnBlock.Retrying(
                id = blockIdAt(turnIndex, base.size),
                attempt = event.attempt,
                maxAttempts = event.maxAttempts,
                delayMs = event.delayMs,
                reason = event.reason,
            ),
        )
    }

    private fun ConversationTurn.appendFailure(
        turnIndex: Int,
        event: LlmStreamEvent.Error,
    ): ConversationTurn = copy(
        blocks = blocks + TurnBlock.Failure(
            id = blockIdAt(turnIndex, blocks.size),
            // message 为空也保留卡片：code 已承载错误类型，消费方按类型给兜底文案
            message = event.message,
            code = event.code.toFailureCode(),
            attempts = event.attempts,
        ),
    )

    /**
     * 占位行（`ToolPending` 只有名字、没有 callId）用名字匹配，之后的
     * `ToolRunning` 带上 callId 时仍要落回这一行。
     */
    private fun TurnBlock.Tool.matches(call: ToolCallStatus): Boolean {
        val callId = call.callId
        if (callId != null && invocation.id == callId) return true
        return invocation.argumentsJson == null && invocation.name == call.name
    }

    private fun ToolCallStatus.toInvocation(previousId: String? = null): ToolInvocation =
        ToolInvocation(
            id = callId ?: previousId ?: name,
            name = name,
            label = label,
            argumentsJson = argumentsJson,
        )

    private fun LlmErrorCode?.toFailureCode(): TurnFailureCode? = when (this) {
        null -> null
        LlmErrorCode.ConfigRequired -> TurnFailureCode.ConfigRequired
        LlmErrorCode.Auth -> TurnFailureCode.Auth
        LlmErrorCode.Quota -> TurnFailureCode.Quota
        LlmErrorCode.RateLimit -> TurnFailureCode.RateLimit
        LlmErrorCode.Overloaded -> TurnFailureCode.Overloaded
        LlmErrorCode.Transport -> TurnFailureCode.Transport
        LlmErrorCode.Parse -> TurnFailureCode.Parse
        LlmErrorCode.RetryExhausted -> TurnFailureCode.RetryExhausted
        LlmErrorCode.HookFailed -> TurnFailureCode.HookFailed
        LlmErrorCode.ToolExecutionFailed -> TurnFailureCode.ToolExecutionFailed
        LlmErrorCode.IdleTimeout -> TurnFailureCode.IdleTimeout
        // 契约里没有 TurnConflict：已有活跃回合由 stream() 的返回值同步告知
        LlmErrorCode.TurnConflict -> null
    }
}
