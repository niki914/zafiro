package com.niki914.okia.loop

import com.niki914.okia.error.LLMError
import com.niki914.okia.error.LLMErrorCode
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.message.Usage
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.transport.SseLine
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
 * 工具 / 思考事件未实现（T6），遇到显式 TODO 失败——明确失败优于自动修复。
 * Design source: pi agentLoop；okia 骨架 AgentLoop 对照基线。
 */
internal class RealAgentLoop : AgentLoop {

    override suspend fun run(
        request: LoopRequest,
        onEvent: suspend (TurnEvent) -> Unit
    ): TurnResult {
        onEvent(TurnEvent.TurnStarted(request.input))

        val httpRequest = try {
            request.protocolMapper.buildRequest(request.snapshot, request.history)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(request, onEvent, StreamState(), LLMError(LLMErrorCode.Parse, "request build failed", e))
        }

        val response = try {
            request.httpEngine.stream(httpRequest)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(request, onEvent, StreamState(), LLMError(LLMErrorCode.Transport, "stream failed", e))
        }

        val state = StreamState()
        return try {
            collectEvents(request, onEvent, response.lines, state)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { commitPartial(request, state) }
            throw e
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
