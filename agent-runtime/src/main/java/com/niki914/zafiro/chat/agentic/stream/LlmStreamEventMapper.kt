package com.niki914.zafiro.chat.agentic.stream

import com.niki914.logging.Logger
import com.niki914.zafiro.chat.LlmErrorCode
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.chat.ToolCallKind
import com.niki914.zafiro.chat.ToolCallStatus
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
 * 错误的 Zafiro 侧 code 映射留 T4（可重试维度，LlmErrorCode 暂不扩展）。
 */
object LlmStreamEventMapper {
    private const val LOG_TAG = "niki914_nexus_LlmStreamEventMapper"

    /**
     * 当前正在流式的文本块已累积文本（跨事件状态）。
     * OKIA 把第一个 text delta 发在 TextStarted（不携带增量文本，只在 partial 里），
     * 后续 TextDelta.delta 才是增量——若直接丢弃 TextStarted，UI 会缺第一个 delta。
     * 这里以 partial 全文为基线，TextStarted 发全量、TextDelta 发增量。
     */
    private var accumulatedText: String = ""

    /** 思考块在途状态：OKIA 的 content index 仅单轮内唯一（StreamState 每轮新建），
     *  跨工具轮会复用 → 身份用 Mapper 分配的回合内单调 id。
     *  nextBlockId 在 TurnFailed 时重置：重试会重发同一块，id 归零让 HomeChat 覆盖旧块。 */
    private var nextThinkingId = 0
    private var activeThinkingId: Int? = null
    private var activeThinkingIndex: Int? = null
    private var activeThinkingText: String = ""

    fun map(
        event: TurnEvent,
        startedAtMs: Long,
        defaultErrorMessage: String,
    ): LlmStreamEvent? {
        val mapped = when (event) {
            is TurnEvent.TurnStarted -> {
                accumulatedText = ""
                resetThinkingState()
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
                // removePrefix 不匹配前缀时返回接收者自身，无需分支
                val delta = fullText.removePrefix(accumulatedText)
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
                resetThinkingState()
                LlmStreamEvent.Completed
            }

            is TurnEvent.TurnFailed -> {
                accumulatedText = ""
                resetThinkingState()
                LlmStreamEvent.Error(
                    message = event.error.message.trim().ifEmpty { defaultErrorMessage },
                    throwable = event.error.cause,
                    code = event.error.code.toZafiroCode(),
                )
            }

            // 超时也视为回合终止：清思考在途态（不合成——正常流不会走到这里）
            is TurnEvent.TurnIdleTimeout -> {
                resetThinkingState()
                LlmStreamEvent.Error(
                    message = defaultErrorMessage,
                    throwable = null,
                    code = LlmErrorCode.Transport,
                )
            }

            // 思考块：全量直传两态，块身份 = Mapper 回合内单调 id。
            // ThinkingStarted 恒为新块（OKIA 每块只发一次 Started）；Delta 续接当前块。
            // 文本为空不发事件（"一个字都没有就不显示块"）。
            is TurnEvent.ThinkingStarted -> {
                if (event.partial.thinkingContent().isBlank()) {
                    clearActiveThinking()
                    null
                } else {
                    activeThinkingId = nextThinkingId++
                    activeThinkingIndex = event.index
                    activeThinkingText = event.partial.thinkingContent()
                    LlmStreamEvent.ThinkingStarted(activeThinkingId!!, activeThinkingText)
                }
            }

            is TurnEvent.ThinkingDelta -> thinkingInProgress(event.index, event.partial.thinkingContent())

            is TurnEvent.ThinkingEnded -> {
                if (activeThinkingIndex != event.index) {
                    // 已关闭块再次 ended（异常流）：不重复处理
                    null
                } else {
                    val content = event.content
                    val id = activeThinkingId
                    clearActiveThinking()
                    content.takeIf { it.isNotBlank() }?.let { LlmStreamEvent.ThinkingEnded(id!!, it) }
                }
            }

            // 工具意图阶段无消费端（T2）；TextEnded 是文本块边界：重置累积（多段/跨工具轮）。
            is TurnEvent.TextEnded -> {
                accumulatedText = ""
                null
            }

            // 工具意图阶段：名字已知、参数在途——透传为 ToolPending，UI 以 Running 占位（转圈、不可展开）。
            // 身份取自事件字段：发起中的调用未进 partial.content，从 partial 里取不到。
            // Delta 不透传（无需参数实时预览）；Ready 与 Running 几乎同时，走 Running 即可。
            is TurnEvent.ToolCallStarted -> LlmStreamEvent.ToolPending(
                ToolCallStatus(
                    callId = event.callId.ifEmpty { null },
                    name = event.toolName,
                )
            )

            is TurnEvent.ToolCallDelta, is TurnEvent.ToolCallReady,
            is TurnEvent.RetryScheduled -> null

            // 用户停止：不映射为错误事件（停止由消费端 cancel 表达）；
            // 若思考块仍在途，为最后一块合成 ThinkingEnded（被掐也算完成）。
            is TurnEvent.TurnAborted -> {
                accumulatedText = ""
                completeInterruptedThinking()
            }
        }
        if (event !is TurnEvent.TextDelta && event !is TurnEvent.ThinkingDelta) {
            Logger.d(
                LOG_TAG,
                "mapped turnEvent=${event::class.simpleName} " +
                    "-> ${mapped?.let { it::class.simpleName } ?: "null"}"
            )
        }
        return mapped
    }

    /** 思考中续接：Delta 沿用当前块 id，全量替换文本。 */
    private fun thinkingInProgress(index: Int, text: String): LlmStreamEvent? {
        if (text.isBlank()) return null
        // 防御：无 Started 直接来 Delta 时按新块处理
        if (activeThinkingIndex != index || activeThinkingId == null) {
            activeThinkingId = nextThinkingId++
        }
        activeThinkingIndex = index
        activeThinkingText = text
        return LlmStreamEvent.ThinkingStarted(activeThinkingId!!, text)
    }

    /** 回合非正常终止时，若思考在途则补发完成事件（被掐 = 完成）。 */
    private fun completeInterruptedThinking(): LlmStreamEvent? {
        val id = activeThinkingId ?: return null
        val text = activeThinkingText
        resetThinkingState()
        return LlmStreamEvent.ThinkingEnded(id, text)
    }

    private fun resetThinkingState() {
        nextThinkingId = 0
        clearActiveThinking()
    }

    /** 块结束：只清在途块，保留 id 计数器（下一轮思考块继续递增，避免跨轮合并）。 */
    private fun clearActiveThinking() {
        activeThinkingId = null
        activeThinkingIndex = null
        activeThinkingText = ""
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
     * okia LLMErrorCode → Zafiro LlmErrorCode。ContextOverflow 归 Parse
     * （上下文溢出，用户可感知的模型端内容问题）。
     */
    private fun OkiaLLMErrorCode.toZafiroCode(): LlmErrorCode = when (this) {
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
        ToolCallStatus(
            callId = id,
            name = name,
            label = name,
            kind = ToolCallKind.Unknown,
            argumentsJson = argumentsJson,
        )

    private fun AssistantMessage.textContent(): String =
        content.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }

    private fun AssistantMessage.thinkingContent(): String =
        content.filterIsInstance<ContentBlock.Thinking>().joinToString("") { it.text }
    private fun charsPerSecond(fullText: String, startedAtMs: Long): Float {
        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1)
        return fullText.length * 1000f / elapsedMs
    }
}