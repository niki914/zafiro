package com.niki914.okia.loop

import com.niki914.okia.error.LLMError
import com.niki914.okia.error.LLMErrorCode
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.hooks.HttpRequestHolder
import com.niki914.okia.hooks.InputHolder
import com.niki914.okia.hooks.SerializationHolder
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.message.Usage
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.transport.SseLine
import com.niki914.okia.transport.StreamResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * 最小回合驱动（T2 切片）：单次模型往返，文本流式，终态 commit。
 * 消息产出规则（对齐 PRD §5.4 + 2026-08-16 对齐）：流式期间只发事件
 * （partial 快照），消息完整（Completed / 失败 / 取消）才经 onCommit 提交；
 * 不变量由门面保证：live 非空 ⇒ history 不含该消息。
 * 取消契约（§8.8 #2）：外部取消在 NonCancellable 清理（commit 部分产出）后
 * 重新抛出；Aborted 终态与 TurnAborted 事件由协调器产生，loop 不产生。
 * Hooks（T5）：Input / Serialization / Request 三对时机已接入（顺序：
 * TurnStarted → beforeInput → afterInput → beforeSerialization →
 * buildRequest → afterSerialization → beforeRequest → stream → afterRequest）；
 * hook 异常 → 回合 Failed（HookFailed，§8.4 #13）；ToolCall / Stop 时机
 * 随 T6 工具循环 / T7 停止流程接入。
 * 工具 / 思考事件未实现（T6），遇到显式 TODO 失败——明确失败优于自动修复。
 * Design source: pi agentLoop；okia 骨架 AgentLoop 对照基线。
 */
internal class RealAgentLoop : AgentLoop {

    override suspend fun run(
        request: LoopRequest,
        onEvent: suspend (TurnEvent) -> Unit
    ): TurnResult {
        onEvent(TurnEvent.TurnStarted(request.input))
        val state = StreamState()

        // Input 时机（回合入口）：before 可改写输入。改写落点 = 请求历史投影
        // （替换 history 末尾 User 的文本块，树不变；作用域 = 本回合第一次请求，
        // 见 §5.10 分层预期）。事件仍发原始 input（事件反映事实，与树一致）。
        val inputHolder = InputHolder(request.input)
        hookStep(request, onEvent, state, "beforeInput") { it.beforeInput(inputHolder) }?.let { return it }
        val effectiveHistory = if (inputHolder.text != request.input) {
            replaceLastUserText(request.history, inputHolder.text)
        } else {
            request.history
        }
        hookStep(request, onEvent, state, "afterInput") { it.afterInput(inputHolder) }?.let { return it }

        val httpRequest = try {
            // Serialization 时机：before 可改写请求输入（脱敏主战场）；
            // after 拿 buildRequest 产物（只读，审计 / 埋点）
            val serializationHolder = SerializationHolder(request.snapshot, effectiveHistory)
            hookStep(request, onEvent, state, "beforeSerialization") {
                it.beforeSerialization(serializationHolder)
            }?.let { return it }
            val built = request.protocolMapper.buildRequest(
                serializationHolder.snapshot,
                serializationHolder.history
            )
            hookStep(request, onEvent, state, "afterSerialization") {
                it.afterSerialization(serializationHolder, built)
            }?.let { return it }
            built
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(request, onEvent, state, LLMError(LLMErrorCode.Parse, "request build failed", e))
        }

        val response = try {
            // Request 时机：before 可改写请求（http 层兜底脱敏）；after 只读
            // 实际发出的请求（改写后），不接触 response——body 流归 loop 独占
            val requestHolder = HttpRequestHolder(httpRequest)
            hookStep(request, onEvent, state, "beforeRequest") {
                it.beforeRequest(requestHolder)
            }?.let { return it }
            val sent = requestHolder.request
            val resp = request.httpEngine.stream(sent)
            hookStep(request, onEvent, state, "afterRequest") { it.afterRequest(sent) }?.let { return it }
            resp
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(request, onEvent, state, LLMError(LLMErrorCode.Transport, "stream failed", e))
        }

        // 前置校验（T3）：2xx 才进 SSE 解析；非 2xx 的 body 是错误文本，
        // 不进 parseStream（风控 HTML / JSON 错误不会被当 SSE 解析）
        return when (response) {
            is StreamResponse.Error -> {
                val code = when {
                    response.statusCode == 429 -> LLMErrorCode.RateLimit
                    response.statusCode == 401 || response.statusCode == 403 -> LLMErrorCode.Auth
                    response.statusCode in 500..599 -> LLMErrorCode.Overloaded
                    else -> LLMErrorCode.Transport
                }
                // body 截断：错误详情供 UI 展示，非完整响应（HTML 页可能很大）
                fail(
                    request, onEvent, state,
                    LLMError(code, response.body.take(MAX_ERROR_BODY_CHARS), null, response.statusCode)
                )
            }
            is StreamResponse.Ok -> {
                val contentType = response.headers.entries
                    .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
                if (contentType?.startsWith("text/html", ignoreCase = true) == true) {
                    // 黑名单快速失败：content-type 已明确非流式/JSON（如风控页）
                    fail(
                        request, onEvent, state,
                        LLMError(LLMErrorCode.Parse, "unsupported content type: $contentType", null, response.statusCode)
                    )
                } else {
                    try {
                        collectEvents(request, onEvent, response.lines, state)
                    } catch (e: CancellationException) {
                        withContext(NonCancellable) { commitPartial(request, state) }
                        throw e
                    }
                }
            }
        }
    }

    private suspend fun collectEvents(
        request: LoopRequest,
        onEvent: suspend (TurnEvent) -> Unit,
        lines: Flow<SseLine>,
        state: StreamState
    ): TurnResult {
        var result: TurnResult? = null
        try {
            request.protocolMapper.parseStream(lines).collect { event ->
                when (event) {
                    is ProtocolEvent.TextDelta -> {
                        if (!state.started) {
                            state.started = true
                            state.text.append(event.text)
                            onEvent(TurnEvent.TextStarted(0, state.partialMessage()))
                        } else {
                            state.text.append(event.text)
                            onEvent(TurnEvent.TextDelta(0, event.text, state.partialMessage()))
                        }
                    }
                    is ProtocolEvent.Completed -> {
                        val reason = when (event.stopReason) {
                            null, StopReason.Stop -> CompletionReason.Stop
                            StopReason.Length -> CompletionReason.Length
                            else -> throw StreamTerminated(
                                fail(
                                    request, onEvent, state,
                                    LLMError(LLMErrorCode.Parse, "abnormal completion stopReason: ${event.stopReason}")
                                )
                            )
                        }
                        state.usage = event.usage
                        state.responseModel = event.responseModel
                        state.stopReason = event.stopReason
                        if (state.started) {
                            onEvent(TurnEvent.TextEnded(0, state.text.toString(), state.partialMessage()))
                        }
                        val message = AssistantMessage(
                            content = if (state.started) listOf(ContentBlock.Text(state.text.toString())) else emptyList(),
                            stopReason = state.stopReason ?: StopReason.Stop,
                            usage = state.usage,
                            responseModel = state.responseModel
                        )
                        request.onCommit(listOf(Message.Assistant(message)))
                        onEvent(TurnEvent.TurnCompleted(message))
                        // 终态即中断收集：无限流（SharedFlow）不会自然结束，
                        // 只靠 return 退出 action 会让 collect 继续挂起（T2 实测暴露）
                        throw StreamTerminated(TurnResult.Completed(reason))
                    }
                    is ProtocolEvent.Error -> throw StreamTerminated(
                        fail(
                            request, onEvent, state,
                            LLMError(LLMErrorCode.Parse, "stream parse error", event.cause)
                        )
                    )
                    is ProtocolEvent.ToolCallStarted,
                    is ProtocolEvent.ToolCallDelta,
                    is ProtocolEvent.ToolCallReady,
                    is ProtocolEvent.ThinkingDelta,
                    is ProtocolEvent.ThinkingSignature ->
                        TODO("tool loop and thinking blocks land in T6")
                }
            }
        } catch (e: StreamTerminated) {
            return e.result
        }
        // 流正常结束但没有 Completed 事件 = 协议不完整，明确失败
        return result ?: fail(
            request, onEvent, state,
            LLMError(LLMErrorCode.Parse, "stream ended without Completed")
        )
    }

    // 失败收尾：commit 部分产出（若有）+ 发 TurnFailed + 返回 Failed 终态
    private suspend fun fail(
        request: LoopRequest,
        onEvent: suspend (TurnEvent) -> Unit,
        state: StreamState,
        error: LLMError
    ): TurnResult {
        commitPartial(request, state)
        onEvent(TurnEvent.TurnFailed(state.partialMessage(), error))
        return TurnResult.Failed(error)
    }

    // 有部分产出时提交（取消清理与失败收尾共用）
    private suspend fun commitPartial(request: LoopRequest, state: StreamState) {
        if (state.started) {
            request.onCommit(listOf(Message.Assistant(state.partialMessage())))
        }
    }

    // 模型段 hook 链分发：按注册顺序执行（前一个的 mutation 对后一个可见）；
    // hook 异常 → 回合 Failed（§8.4 #13：模型段 hook 失败 = 该步骤失败，
    // 明确失败优于自动修复），取消传播。返回 null = 继续，非 null = 失败终态。
    private suspend fun hookStep(
        request: LoopRequest,
        onEvent: suspend (TurnEvent) -> Unit,
        state: StreamState,
        phase: String,
        block: suspend (Hooks) -> Unit
    ): TurnResult? {
        try {
            for (hook in request.hooks) {
                block(hook)
            }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(request, onEvent, state, LLMError(LLMErrorCode.HookFailed, "$phase hook failed", e))
        }
    }

    // 改写投影：history 末尾 User 消息的文本块替换为改写文本（树不变，§5.8）。
    // 无 User 或无文本块时不替换（防御：改写落点只在 User 文本上）。
    private fun replaceLastUserText(history: List<Message>, newText: String): List<Message> {
        val index = history.indexOfLast { it is Message.User }
        if (index < 0) return history
        val user = history[index] as Message.User
        val textIndex = user.content.indexOfFirst { it is ContentBlock.Text }
        if (textIndex < 0) return history
        val content = user.content.toMutableList().apply {
            this[textIndex] = ContentBlock.Text(newText)
        }
        return history.toMutableList().apply { this[index] = user.copy(content = content) }
    }
}

/** 回合内流式累积状态：partial 快照由它派生。 */
private class StreamState {
    val text = StringBuilder()
    var started = false
    var usage: Usage? = null
    var responseModel: String? = null
    var stopReason: StopReason? = null

    fun partialMessage(): AssistantMessage = AssistantMessage(
        content = if (started) listOf(ContentBlock.Text(text.toString())) else emptyList()
    )
}

/**
 * 终态信号：中断流收集的内部哨兵（非 CancellationException，不被取消机制误判）。
 * collect 被异常终止时会退订上游流（无限流场景必需，T2 实测暴露）。
 */
private class StreamTerminated(val result: TurnResult) : Exception()

// 非 2xx 错误 body 进 LLMError.message 的最大字符数（UI 详情，非完整响应）
private const val MAX_ERROR_BODY_CHARS = 2000
