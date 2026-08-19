package com.niki914.nexus.agentic.chat.agentic.stream

import com.niki914.logging.Logger
import com.niki914.nexus.agentic.chat.LlmStreamEvent
import com.niki914.nexus.agentic.chat.ToolCallKind
import com.niki914.nexus.agentic.chat.ToolCallStatus
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.ToolCallOutcome

/**
 * TurnEvent → LlmStreamEvent 映射器（OKIA 接入 T1 重写）。
 * OKIA 终态以 send 返回值承载（TurnResult），事件流只承担中间过程；
 * 本映射只负责单条事件的投影，流结束语义由 LLMController 按返回值处理。
 * 工具事件映射为 T2 铺路：T1 无工具注册，事件不会出现，但映射逻辑完整。
 * 错误的 Nexus 侧 code 映射留 T4（可重试维度，LlmErrorCode 暂不扩展）。
 */
object LlmStreamEventMapper {
    private const val LOG_TAG = "niki914_nexus_LlmStreamEventMapper"

    fun map(
        event: TurnEvent,
        startedAtMs: Long,
        defaultErrorMessage: String,
    ): LlmStreamEvent? { // <--- TODO 梳理 LlmStreamEvent | Thinking impl
        val mapped = when (event) {
            is TurnEvent.TurnStarted -> LlmStreamEvent.RoundStarted

            is TurnEvent.TextDelta -> {
                val fullText = event.partial.textContent()
                LlmStreamEvent.TextDelta(
                    delta = event.delta,
                    fullText = fullText,
                    charsPerSecond = charsPerSecond(fullText, startedAtMs),
                )
            }

            is TurnEvent.ToolRunning -> LlmStreamEvent.ToolRunning(event.toolCall.toStatus())

            is TurnEvent.ToolSucceeded -> event.toToolSucceededOrFailed()

            is TurnEvent.ToolFailed -> LlmStreamEvent.ToolFailed(
                call = event.toolCall.toStatus(),
                message = event.outcome.messageTextOf(),
                resultText = event.outcome.contentText(),
            )

            is TurnEvent.TurnCompleted -> LlmStreamEvent.Completed(event.message.textContent())

            is TurnEvent.TurnFailed -> LlmStreamEvent.Error(
                message = event.error.message.trim().ifEmpty { defaultErrorMessage },
                throwable = event.error.cause,
                code = null,
            )

            is TurnEvent.TurnIdleTimeout -> LlmStreamEvent.Error(
                message = defaultErrorMessage,
                throwable = null,
                code = null,
            )

            // Thinking 与工具意图阶段：UI 不渲染 thinking（D5）；工具意图无消费端（T2）。
            // TextStarted/TextEnded 不发射：TextDelta 已携带累积 partial 文本，
            // UI 逐 delta 追加即得完整结果，Started/Ended 是多余的边界事件。
            // TurnAborted（用户停止）不映射为错误事件：停止由消费端 cancel 表达。
            is TurnEvent.TextStarted, is TurnEvent.TextEnded,
            is TurnEvent.ThinkingStarted, is TurnEvent.ThinkingDelta, is TurnEvent.ThinkingEnded,
            is TurnEvent.ToolCallStarted, is TurnEvent.ToolCallDelta, is TurnEvent.ToolCallReady,
            is TurnEvent.RetryScheduled,
            is TurnEvent.TurnAborted -> null
        }
        if (event !is TurnEvent.TextDelta) {
            Logger.d(
                LOG_TAG,
                "mapped turnEvent=${event::class.simpleName} " +
                    "-> ${mapped?.let { it::class.simpleName } ?: "null"}"
            )
        }
        return mapped
    }

    private fun TurnEvent.ToolSucceeded.toToolSucceededOrFailed(): LlmStreamEvent {
        val call = toolCall.toStatus()
        val outcome = this.outcome
        return when (outcome) {
            is ToolCallOutcome.Success -> LlmStreamEvent.ToolSucceeded(call, outcome.content)
            is ToolCallOutcome.Intercepted ->
                if (outcome.isError) LlmStreamEvent.ToolFailed(call, outcome.reason, outcome.content)
                else LlmStreamEvent.ToolSucceeded(call, outcome.content)
            else -> LlmStreamEvent.ToolFailed(call, outcome.messageTextOf(), outcome.contentText())
        }
    }

    private fun ToolCallOutcome.messageTextOf(): String = when (this) {
        is ToolCallOutcome.Success -> ""
        is ToolCallOutcome.Failure -> message
        is ToolCallOutcome.Intercepted -> reason
        is ToolCallOutcome.Interrupted -> "interrupted"
        is ToolCallOutcome.Unknown -> message
    }

    private fun ToolCallOutcome.contentText(): String? = when (this) {
        is ToolCallOutcome.Success -> content
        is ToolCallOutcome.Failure -> content
        is ToolCallOutcome.Intercepted -> content
        is ToolCallOutcome.Interrupted -> content
        is ToolCallOutcome.Unknown -> content
    }

    private fun ContentBlock.ToolCall.toStatus(): ToolCallStatus =
        ToolCallStatus(callId = id, name = name, label = name, kind = ToolCallKind.Unknown)

    private fun AssistantMessage.textContent(): String =
        content.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
    private fun charsPerSecond(fullText: String, startedAtMs: Long): Float {
        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1)
        return fullText.length * 1000f / elapsedMs
    }
}