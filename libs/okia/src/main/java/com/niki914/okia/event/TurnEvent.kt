package com.niki914.okia.event

import com.niki914.okia.error.LLMError
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.ToolCallOutcome

/**
 * 回合事件协议：库的公开契约，事实源。每个块级事件携带完整部分快照，
 * 消费者无需累积 delta 即可渲染流式输出。终态编码为 TurnCompleted / TurnFailed /
 * TurnAborted / TurnIdleTimeout，不抛出。
 * 宿主 IPC（RenderFrame 流式回调）走事件形态；UI 另观察 StateFlow<Conversation>
 * 投影（开放问题 6.2 候选 A）。
 * 工具调用分两个生命周期，前缀区分：ToolCall* = 模型产出调用意图（参数流式
 * 组装，ToolCallReady 为组装完成、待执行）；Tool* = 工具执行状态
 * （ToolRunning / ToolSucceeded / ToolFailed）。
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

    /** contentIndex 处工具调用参数组装完成，带最终参数；之后进入执行阶段。 */
    data class ToolCallReady(val index: Int, val toolCall: ContentBlock.ToolCall, val partial: AssistantMessage) : TurnEvent

    /** contentIndex 处工具调用开始执行。 */
    data class ToolRunning(val index: Int, val toolCall: ContentBlock.ToolCall, val partial: AssistantMessage) : TurnEvent

    /** contentIndex 处工具调用执行成功。 */
    data class ToolSucceeded(val index: Int, val toolCall: ContentBlock.ToolCall, val outcome: ToolCallOutcome, val partial: AssistantMessage) : TurnEvent

    /** contentIndex 处工具调用执行失败。 */
    data class ToolFailed(val index: Int, val toolCall: ContentBlock.ToolCall, val outcome: ToolCallOutcome, val partial: AssistantMessage) : TurnEvent

    /** 一次重试已排定；attempt 从 1 起。 */
    data class RetryScheduled(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val reason: String
    ) : TurnEvent

    /** 回合正常结束。最终消息已带 stopReason（Stop / Length）。 */
    data class TurnCompleted(val message: AssistantMessage) : TurnEvent

    /** 回合失败。error 必带（覆盖 Error / RetryExhausted）。 */
    data class TurnFailed(
        val message: AssistantMessage,
        val error: LLMError
    ) : TurnEvent

    /** 回合被取消。cause 必带（UserStop / External）。 */
    data class TurnAborted(
        val message: AssistantMessage,
        val cause: StopCause
    ) : TurnEvent

    /** 模型流 idle 超时（框架检测）。 */
    data class TurnIdleTimeout(val message: AssistantMessage) : TurnEvent
}

/**
 * 回合被取消的原因，由 Okia 协调器在取消回合 job 时记录并随取消携带。
 * Replace 已删除：库内无 Replace 语义（PRD §5.2，由 stop() + send() 表达），
 * 故取消源只剩用户 stop 与外部取消。IdleTimeout 是回合级超时（走
 * TurnIdleTimeout 事件 / TurnResult.IdleTimeout），不是 StopCause。
 * Design source: kai PRD §4.4 停止语义。
 */
enum class StopCause {
    UserStop,
    External
}
