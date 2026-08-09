package com.niki914.okia.event

import com.niki914.okia.error.LLMError
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock

/**
 * 回合事件协议：库的公开契约，事实源。每个块级事件携带完整部分快照，
 * 消费者无需累积 delta 即可渲染流式输出。失败编码为 TurnFailed，不抛出。
 * 宿主 IPC（RenderFrame 流式回调）走事件形态；UI 另观察 StateFlow<Conversation>
 * 投影（开放问题 6.2 候选 A）。
 * Design source: pi AssistantMessageEvent 集合，kai PRD §4.2；
 * Turn = 用户输入到最终回答的整轮（codex turn 语义），非单次模型往返。
 */
sealed interface TurnEvent {

    /** 回合以用户输入开始。只发一次。 */
    data class TurnStarted(val input: String) : TurnEvent

    /** contentIndex 处文本块开始。 */
    data class TextStarted(val index: Int, val partial: AssistantMessage) : TurnEvent

    /** contentIndex 处文本块追加 delta。 */
    data class TextDelta(val index: Int, val delta: String, val partial: AssistantMessage) : TurnEvent

    /** contentIndex 处文本块完成。 */
    data class TextEnded(val index: Int, val content: String, val partial: AssistantMessage) : TurnEvent

    /** contentIndex 处思考块开始。 */
    data class ThinkingStarted(val index: Int, val partial: AssistantMessage) : TurnEvent

    /** contentIndex 处思考块追加 delta。 */
    data class ThinkingDelta(val index: Int, val delta: String, val partial: AssistantMessage) : TurnEvent

    /** contentIndex 处思考块完成。 */
    data class ThinkingEnded(val index: Int, val content: String, val partial: AssistantMessage) : TurnEvent

    /** contentIndex 处工具调用块开始。 */
    data class ToolCallStarted(val index: Int, val partial: AssistantMessage) : TurnEvent

    /** contentIndex 处工具调用参数 delta。 */
    data class ToolCallDelta(val index: Int, val delta: String, val partial: AssistantMessage) : TurnEvent

    /** contentIndex 处工具调用完成，带最终参数。 */
    data class ToolCallEnded(val index: Int, val toolCall: ContentBlock.ToolCall, val partial: AssistantMessage) : TurnEvent

    /** 一次重试已排定；attempt 从 1 起。 */
    data class RetryScheduled(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val reason: String
    ) : TurnEvent

    /** 回合正常结束。 */
    data class TurnCompleted(val message: AssistantMessage, val reason: FinishReason) : TurnEvent

    /** 回合失败或中止。error 在存在分类时携带。 */
    data class TurnFailed(
        val message: AssistantMessage,
        val reason: FinishReason,
        val error: LLMError? = null
    ) : TurnEvent
}

/**
 * 整个回合结束的原因。排除 toolUse：那是消息级 StopReason，
 * 回合内的正常中间状态。
 * Design source: kai PRD §4.2 FinishReason。
 */
enum class FinishReason {
    Stop,
    Length,
    Error,
    Aborted,
    IdleTimeout,
    RetryExhausted
}

/**
 * 回合被取消的原因，由 Okia 协调器在取消回合 job 时记录并随取消携带。
 * Replace 已删除：库内无 Replace 语义（PRD §5.2，由 stop() + send() 表达），
 * 故取消源只剩用户 stop 与外部取消。IdleTimeout 是 FinishReason，不是 StopCause。
 * Design source: kai PRD §4.4 停止语义。
 */
enum class StopCause {
    UserStop,
    External
}
