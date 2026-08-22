package com.niki914.nexus.agentic.chat.agentic.stream

import com.niki914.logging.Logger
import com.niki914.nexus.agentic.chat.LlmErrorCode
import com.niki914.nexus.agentic.chat.LlmStreamEvent
import com.niki914.nexus.agentic.chat.ToolCallKind
import com.niki914.nexus.agentic.chat.ToolCallStatus
import com.niki914.okia.error.LLMErrorCode as OkiaLLMErrorCode
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

    /**
     * 当前正在流式的文本块已累积文本（跨事件状态）。
     * OKIA 把第一个 text delta 发在 TextStarted（不携带增量文本，只在 partial 里），
     * 后续 TextDelta.delta 才是增量——若直接丢弃 TextStarted，UI 累积会缺第一个
     * delta，导致 appendFinalText 的 removePrefix 失败产生双份文本。
     * 这里以 partial 全文为基线，TextStarted 发全量、TextDelta 发增量。
     */
    private var accumulatedText: String = ""

    fun map(
        event: TurnEvent,
        startedAtMs: Long,
        defaultErrorMessage: String,
    ): LlmStreamEvent? { // <--- TODO 梳理 LlmStreamEvent | Thinking impl
        val mapped = when (event) {
            is TurnEvent.TurnStarted -> {
                accumulatedText = ""
                LlmStreamEvent.RoundStarted
            }

            // 文本块开始：partial 含第一个 delta（OKIA 不单发），以全量作 delta
            is TurnEvent.TextStarted -> {
                val fullText = event.partial.textContent()
                accumulatedText = fullText
                LlmStreamEvent.TextDelta(
                    delta = fullText,
                    fullText = fullText,
                    charsPerSecond = charsPerSecond(fullText, startedAtMs),
                )
            }

            is TurnEvent.TextDelta -> {
                val fullText = event.partial.textContent()
                val delta = if (fullText.startsWith(accumulatedText)) {
                    fullText.removePrefix(accumulatedText)
                } else {
                    // 防御：partial 与累积不一致（正常不会发生），全量兜底避免丢字
                    fullText
                }
                accumulatedText = fullText
                LlmStreamEvent.TextDelta(
                    delta = delta,
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

            is TurnEvent.TurnCompleted -> {
                accumulatedText = ""
                LlmStreamEvent.Completed(event.message.textContent())
            }

            is TurnEvent.TurnFailed -> {
                accumulatedText = ""
                LlmStreamEvent.Error(
                    message = event.error.message.trim().ifEmpty { defaultErrorMessage },
                    throwable = event.error.cause,
                    code = event.error.code.toNexusCode(),
                )
            }

            is TurnEvent.TurnIdleTimeout -> LlmStreamEvent.Error(
                message = defaultErrorMessage,
                throwable = null,
                code = LlmErrorCode.Transport,
            )

            // Thinking 与工具意图阶段：UI 不渲染 thinking（D5）；工具意图无消费端（T2）。
            // TextEnded 是文本块边界：重置累积（多段/跨工具轮）。
            // TurnAborted（用户停止）不映射为错误事件：停止由消费端 cancel 表达。
            is TurnEvent.TextEnded -> {
                accumulatedText = ""
                null
            }

            is TurnEvent.ThinkingStarted, is TurnEvent.ThinkingDelta, is TurnEvent.ThinkingEnded,
            is TurnEvent.ToolCallStarted, is TurnEvent.ToolCallDelta, is TurnEvent.ToolCallReady,
            is TurnEvent.RetryScheduled -> null

            is TurnEvent.TurnAborted -> {
                accumulatedText = ""
                null
            }
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

    /**
     * okia LLMErrorCode → Nexus LlmErrorCode。ContextOverflow 归 Parse
     * （上下文溢出，用户可感知的模型端内容问题）。
     */
    private fun OkiaLLMErrorCode.toNexusCode(): LlmErrorCode = when (this) {
        OkiaLLMErrorCode.Auth -> LlmErrorCode.Auth
        OkiaLLMErrorCode.Quota -> LlmErrorCode.Quota
        OkiaLLMErrorCode.RateLimit -> LlmErrorCode.RateLimit
        OkiaLLMErrorCode.Overloaded -> LlmErrorCode.Overloaded
        OkiaLLMErrorCode.ContextOverflow -> LlmErrorCode.Parse
        OkiaLLMErrorCode.Transport -> LlmErrorCode.Transport
        OkiaLLMErrorCode.Parse -> LlmErrorCode.Parse
        OkiaLLMErrorCode.HookFailed -> LlmErrorCode.HookFailed
        OkiaLLMErrorCode.ToolExecutionFailed -> LlmErrorCode.ToolExecutionFailed
        OkiaLLMErrorCode.RetryExhausted -> LlmErrorCode.RetryExhausted
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