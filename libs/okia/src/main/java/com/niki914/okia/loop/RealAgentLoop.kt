package com.niki914.okia.loop

import com.niki914.okia.error.CallbackException
import com.niki914.okia.error.LLMError
import com.niki914.okia.error.LLMErrorCode
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.hooks.HttpRequestHolder
import com.niki914.okia.hooks.InputHolder
import com.niki914.okia.hooks.SerializationHolder
import com.niki914.okia.hooks.ToolCallHolder
import com.niki914.okia.hooks.ToolResultHolder
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.message.Usage
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.tooling.ToolCallContext
import com.niki914.okia.tooling.ToolExecutor
import com.niki914.okia.transport.SseLine
import com.niki914.okia.transport.StreamResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/**
 * 回合驱动（T6 工具循环 + T7 分层重试与 idle）：模型 ↔ 工具循环，直到
 * 最后一条消息不是工具请求。每轮 = 一次模型往返：流式收集（text /
 * thinking / toolCall 块）→ 整条 Assistant 消息 commit → finish_reason=ToolUse
 * 时执行工具（消息完整后才执行，多条并发、结果按调用顺序保序提交，对齐 pi
 * executeToolCallsParallel）→ 下一轮；Stop / Length → TurnCompleted / Completed。
 * 工具执行契约（T6 裁决）：beforeToolCall 可改写参数（write）或阻断
 * （writeOutcome，短路后续 hook 且不执行）；工具段 hook 异常 → 该工具
 * Failure outcome（§8.4 #13），回合继续；executor 违反「永不抛异常」
 * 契约 → 回合 Failed(ToolExecutionFailed)；未知工具 → Failure 结果回喂
 * （模型可自纠，回合继续，见 executeTools）；被阻断 / hook 失败的调用不执行
 * 也不走 afterToolCall
 * （对齐 pi immediate result 语义）。
 * 分层重试（T7，G4/G5/G6 裁决）：每轮段执行 = buildRequest → 发送阶段
 * （传输层重试：网络错误 / 可重试状态码，config.retryPolicy，Retry-After
 * 优先，对齐 pi provider-retry）→ 流收集 → 消息 commit。发送阶段耗尽 →
 * 段失败 → 回合层段首重试（turnRetryPolicy，嵌套对齐 pi retryAssistantCall）：
 * 重新 buildRequest(history)，history 不变（未 commit 任何产出），已提交的
 * 工具结果全部复用（G5：重发当前段请求，不重放历史轮次）；流中断（收集阶段）
 * 同路径：partial 丢弃（未 commit）→ 段首重试。不可重试（Auth/Quota/
 * ContextOverflow/Parse 等）直接 Failed；回合层耗尽 → Failed(RetryExhausted)。
 * idle 检测（T7，G7 裁决）：agent 活跃度 = ProtocolEvent 到达（Text* /
 * Thinking* / ToolCall* 任何事件都重置计时），keep-alive（SseLine null /
 * 空行，不产出 ProtocolEvent）不重置；计时只在流收集段活，工具执行段不计
 * （工具开始即离开收集段）。超时 → partial 消息 commit 进历史 +
 * TurnIdleTimeout 事件 + IdleTimeout 终态（超时是独立终态，不重试、不取消）。
 * 消息提交：每条模型往返消息在流结束（Completed）时整条 commit（§8.11 #1，
 * 含 ToolCall 块）；ToolResult 消息批量 commit（原子，保序）；流式期间只发
 * 事件（partial 快照），live 不变量由门面保证。
 * 取消契约（§8.8 #2）：外部取消在 NonCancellable 清理（commit 部分产出）后
 * 重新抛出；Aborted 终态与 TurnAborted 事件由协调器产生，loop 不产生。
 * 工具执行阶段取消经 coroutineScope 传播；待决工具结果的补全（onInterrupt）
 * 由协调器 kill-then-stop（beforeStop，§5.11）保证。
 * Hooks 时机顺序（T6 全量 + T7 重试）：TurnStarted → beforeInput → afterInput
 * → 每段（beforeSerialization → buildRequest → afterSerialization，每段一次）
 * → 每次发送尝试（beforeRequest → stream → afterRequest，含传输层重试）→
 * 流事件 → [ToolUse → beforeToolCall → ToolRunning → 执行 → afterToolCall →
 * 事件 + ToolResult commit] → 下一轮。重试时 Serialization 时机不重跑
 * （重发同一请求体）；Request 时机每次发送尝试重跑（hook 幂等由下游负责）。
 * Design source: pi agent-loop.ts / provider-retry.ts / retry.ts；
 * codex retry.rs / responses_retry.rs；kai RoundRunner / PRD §4.7；
 * okia 骨架 AgentLoop 对照基线。
 */
internal class RealAgentLoop : AgentLoop {

    override suspend fun run(
        request: LoopRequest,
        onEvent: suspend (TurnEvent) -> Unit
    ): TurnResult {
        onEvent(TurnEvent.TurnStarted(request.input))

        // Input 时机（回合入口）：before 可改写输入。改写落点 = 请求历史投影
        // （替换 history 末尾 User 的文本块，树不变；作用域 = 本回合第一次请求，
        // 见 §5.10 分层预期）。事件仍发原始 input（事件反映事实，与树一致）。
        val inputHolder = InputHolder(request.input)
        hookStep(request, onEvent, "beforeInput") { it.beforeInput(inputHolder) }?.let { return it }
        val baseHistory = if (inputHolder.text != request.input) {
            replaceLastUserText(request.history, inputHolder.text)
        } else {
            request.history
        }
        hookStep(request, onEvent, "afterInput") { it.afterInput(inputHolder) }?.let { return it }

        // 回合累积历史：初始 = 请求历史（含当前输入），每轮产出追加。
        // 下一轮 buildRequest 用它（工具结果已回喂）。
        val history = baseHistory.toMutableList()

        while (true) {
            // 段执行（含回合层段首重试）；Finished = 回合终态已内部处理
            val assistant = when (val outcome = runSegment(request, onEvent, history)) {
                is SegmentOutcome.Finished -> return outcome.result
                is SegmentOutcome.Success -> outcome.assistant
            }

            // 整条 commit（含 ToolCall；§8.11 #1）
            request.onCommit(listOf(Message.Assistant(assistant)))
            history += Message.Assistant(assistant)

            // ToolUse → 执行工具 → 下一轮；Stop / Length → 回合结束
            if (assistant.stopReason == StopReason.ToolUse) {
                val toolCalls = assistant.content.filterIsInstance<ContentBlock.ToolCall>()
                if (toolCalls.isEmpty()) {
                    // 防御：ToolUse 但无工具调用块（协议不一致）→ 按 Stop 结束，避免死循环
                    onEvent(TurnEvent.TurnCompleted(assistant))
                    return TurnResult.Completed(CompletionReason.Stop)
                }
                when (val toolOutcome = executeTools(request, onEvent, assistant, toolCalls)) {
                    is ToolExecutionOutcome.Failure -> return toolOutcome.result
                    is ToolExecutionOutcome.Success -> {
                        // ToolResult 同步进累积历史（树经 onCommit 已提交，loop 侧保持一致）
                        history += toolOutcome.messages
                    }
                }
                continue
            }

            val reason = when (assistant.stopReason) {
                StopReason.Length -> CompletionReason.Length
                else -> CompletionReason.Stop
            }
            onEvent(TurnEvent.TurnCompleted(assistant))
            return TurnResult.Completed(reason)
        }
    }

    // ── 段执行（一层模型往返；含回合层段首重试） ──────────────────────────

    /**
     * 跑一轮模型段：buildRequest → 发送阶段（传输层重试）→ 流收集 → 段成功。
     * 回合层段首重试包住整段（G6 嵌套）：发送阶段耗尽或流中断 → 段失败且
     * error.code.isRetryable → 重新整段（fresh buildRequest，history 不变，
     * partial 丢弃不 commit——重试安全性由「段内未 commit 任何产出」保证）。
     * 段失败不可重试 / 回合层耗尽 → fail（commitPartial + TurnFailed + Failed）。
     */
    private suspend fun runSegment(
        request: LoopRequest,
        onEvent: suspend (TurnEvent) -> Unit,
        history: List<Message>
    ): SegmentOutcome {
        val turnPolicy = request.options.turnRetryPolicy
        val turnMax = turnPolicy?.maxAttempts ?: 0
        var turnAttempt = 0

        while (true) {
            // 每次段尝试独立 StreamState：重试丢弃上次尝试的 partial
            val state = StreamState()

            // 1. Serialization 时机 + buildRequest（每段尝试 fresh request，对齐 pi）
            val httpRequest = try {
                // G5 快照整改（§8.18）：工具描述每段尝试现取——快照表达「每段
                // 发送时的工具集」而非 send 时固定值；合法变更（MCP 刷新 /
                // Okia.update）走回合外，段间可见。
                val serializationHolder = SerializationHolder(
                    request.snapshot.copy(tools = request.toolRegistry.snapshot().map { it.descriptor }),
                    history
                )
                hookStep(request, onEvent, "beforeSerialization") {
                    it.beforeSerialization(serializationHolder)
                }?.let { return SegmentOutcome.Finished(it) }
                // 历史快照：toList() 防御——history 是 loop 内部累积的可变列表，
                // 本轮之后会追加产出（commit），协议层/测试 fake 不得看到事后修改
                val built = request.protocolMapper.buildRequest(
                    serializationHolder.snapshot,
                    serializationHolder.history.toList()
                )
                hookStep(request, onEvent, "afterSerialization") {
                    it.afterSerialization(serializationHolder, built)
                }?.let { return SegmentOutcome.Finished(it) }
                built
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return SegmentOutcome.Finished(
                    fail(request, onEvent, state, LLMError(LLMErrorCode.Parse, "request build failed", e))
                )
            }

            // 2-3. 发送阶段（传输层重试）：beforeRequest → stream → afterRequest → 前置校验
            when (val send = sendWithTransportRetry(request, onEvent, httpRequest)) {
                is SendResult.Failed -> {
                    if (send.finalized) {
                        // hook 失败已 fail（TurnFailed 已发、partial 已 commit）
                        return SegmentOutcome.Finished(TurnResult.Failed(send.error))
                    }
                    // 传输层耗尽 / 不可重试 → 回合层判断（嵌套 G6）
                    if (send.error.code.isRetryable && turnAttempt < turnMax) {
                        turnAttempt++
                        val delay = turnPolicy!!.delayMs(turnAttempt)
                        onEvent(
                            TurnEvent.RetryScheduled(
                                turnAttempt, turnMax, delay, "segment ${send.error.code.name}"
                            )
                        )
                        delay(delay)
                        continue
                    }
                    return SegmentOutcome.Finished(
                        fail(request, onEvent, state, exhaustedError(send.error, turnPolicy != null))
                    )
                }
                is SendResult.Ok -> {
                    // 4. 流收集（idle 检测内嵌）
                    try {
                        val assistant = collectEvents(request, onEvent, send.response.lines, state)
                        return SegmentOutcome.Success(assistant)
                    } catch (e: StreamTerminated) {
                        if (e.error.code.isRetryable && turnAttempt < turnMax) {
                            turnAttempt++
                            val delay = turnPolicy!!.delayMs(turnAttempt)
                            onEvent(
                                TurnEvent.RetryScheduled(
                                    turnAttempt, turnMax, delay, "stream ${e.error.code.name}"
                                )
                            )
                            delay(delay)
                            continue
                        }
                        return SegmentOutcome.Finished(
                            fail(request, onEvent, state, exhaustedError(e.error, turnPolicy != null))
                        )
                    } catch (e: StreamIdleTimedOut) {
                        // 事件 + partial commit 已在收集内完成；独立终态，不重试
                        return SegmentOutcome.Finished(TurnResult.IdleTimeout)
                    } catch (e: CancellationException) {
                        withContext(NonCancellable) { commitPartial(request, state) }
                        throw e
                    }
                }
            }
        }
    }

    // 发送阶段：带传输层重试。失败统一走「错误构造 → 重试判断」：
    // 可重试（网络 Transport / 状态码 ∈ Compat.retryableStatusCodes）且预算未
    // 耗尽 → 退避（Retry-After 优先于指数退避）→ 重发；耗尽 → Failed。
    private suspend fun sendWithTransportRetry(
        request: LoopRequest,
        onEvent: suspend (TurnEvent) -> Unit,
        httpRequest: com.niki914.okia.transport.HttpRequest
    ): SendResult {
        val policy = request.retryPolicy
        val max = policy.maxAttempts
        var attempt = 0
        while (true) {
            val failure = try {
                val requestHolder = HttpRequestHolder(httpRequest)
                hookStep(request, onEvent, "beforeRequest") { it.beforeRequest(requestHolder) }?.let {
                    return SendResult.Failed(
                        LLMError(LLMErrorCode.HookFailed, "beforeRequest hook failed"),
                        finalized = true
                    )
                }
                val sent = requestHolder.request
                val resp = request.httpEngine.stream(sent)
                hookStep(request, onEvent, "afterRequest") { it.afterRequest(sent) }?.let {
                    return SendResult.Failed(
                        LLMError(LLMErrorCode.HookFailed, "afterRequest hook failed"),
                        finalized = true
                    )
                }
                // 前置校验（T3）：2xx 才进 SSE 解析；非 2xx / HTML 不进 parseStream
                when (resp) {
                    is StreamResponse.Error -> LLMError(
                        code = classifyStatus(resp.statusCode),
                        message = resp.body.take(MAX_ERROR_BODY_CHARS),
                        statusCode = resp.statusCode,
                        retryDelayMs = parseRetryAfter(resp.headers)
                    )
                    is StreamResponse.Ok -> {
                        val contentType = resp.headers.entries
                            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
                        if (contentType?.startsWith("text/html", ignoreCase = true) == true) {
                            // 黑名单快速失败：content-type 已明确非流式/JSON（如风控页）
                            LLMError(
                                LLMErrorCode.Parse, "unsupported content type: $contentType",
                                null, resp.statusCode
                            )
                        } else {
                            return SendResult.Ok(resp)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 网络错误（连接/超时/流中断前）：可重试
                LLMError(LLMErrorCode.Transport, "stream failed", e)
            }

            if (failure.code.isRetryable && attempt < max) {
                attempt++
                val delay = failure.retryDelayMs ?: policy.delayMs(attempt)
                val reason = failure.statusCode?.let { "HTTP $it" } ?: "stream failed"
                onEvent(TurnEvent.RetryScheduled(attempt, max, delay, reason))
                delay(delay)
                continue
            }
            return SendResult.Failed(failure)
        }
    }

    // ── 流收集（每段尝试） ────────────────────────────────────────────────

    private suspend fun collectEvents(
        request: LoopRequest,
        onEvent: suspend (TurnEvent) -> Unit,
        lines: Flow<SseLine>,
        state: StreamState
    ): AssistantMessage {
        val timeoutMs = request.idleTimeoutSeconds?.takeIf { it > 0 }?.times(1000)
        return try {
            if (timeoutMs == null) {
                request.protocolMapper.parseStream(lines).collect { event ->
                    handleProtocolEvent(request, onEvent, event, state)
                }
            } else {
                collectWithIdle(request, onEvent, lines, state, timeoutMs)
            }
            // 流正常结束但没有 Completed 事件 = 协议不完整，明确失败
            throw StreamTerminated(
                LLMError(LLMErrorCode.Parse, "stream ended without Completed")
            )
        } catch (e: StreamCompleted) {
            e.assistant
        } catch (e: StreamTerminated) {
            throw e
        } catch (e: StreamIdleTimedOut) {
            // idle 超时哨兵：非段失败（Exception 子类，须在兜底前重抛）
            throw e
        } catch (e: CallbackException) {
            // 事件分发失败（业务 onEvent 抛异常）：host 侧代码问题，不可重试。
            // 与协议流异常分离——不伪装成 Transport（否则配置 turnRetryPolicy 时
            // 同一次 LLM 请求被重发：重复计费 / 重复事件 / 掩盖业务错误，问题 1）
            throw StreamTerminated(
                LLMError(LLMErrorCode.HookFailed, "onEvent callback failed", e.cause)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 流中断（传输层断连：socket / keep-alive 超时等）：转 Transport，
            // 段首重试判断由 runSegment 处理（可重试 → 丢弃 partial 重发）
            throw StreamTerminated(
                LLMError(LLMErrorCode.Transport, "stream interrupted", e)
            )
        }
    }

    // idle 检测版收集：channel + select。任何 ProtocolEvent 到达重置计时
    // （agent 活跃度，G7）；keep-alive（SseLine 层，不产出 ProtocolEvent）
    // 不重置；流结束（channel close）但无 Completed → 协议不完整失败。
    private suspend fun collectWithIdle(
        request: LoopRequest,
        onEvent: suspend (TurnEvent) -> Unit,
        lines: Flow<SseLine>,
        state: StreamState,
        timeoutMs: Long
    ): Unit = coroutineScope {
        val channel = Channel<ProtocolEvent>(Channel.UNLIMITED)
        val producer = launch {
            try {
                request.protocolMapper.parseStream(lines).collect { channel.send(it) }
            } finally {
                channel.close()
            }
        }
        while (true) {
            when (val signal = select<StreamSignal> {
                channel.onReceiveCatching { result ->
                    if (result.isSuccess) StreamSignal.Event(result.getOrThrow()) else StreamSignal.Closed
                }
                onTimeout(timeoutMs) { StreamSignal.Idle }
            }) {
                is StreamSignal.Event -> handleProtocolEvent(request, onEvent, signal.event, state)
                is StreamSignal.Closed -> throw StreamTerminated(
                    LLMError(LLMErrorCode.Parse, "stream ended without Completed")
                )
                is StreamSignal.Idle -> {
                    // 超时也写入（G7 裁决）：partial 消息 commit 进历史，不丢弃
                    val partial = state.partialMessage()
                    if (state.hasAnyContent()) {
                        request.onCommit(listOf(Message.Assistant(partial)))
                    }
                    onEvent(TurnEvent.TurnIdleTimeout(partial))
                    throw StreamIdleTimedOut
                }
            }
        }
    }

    // 单个协议事件 → state 变更 + 事件发射。Completed 抛 StreamCompleted（段成功）；
    // Error / 异常 stopReason 抛 StreamTerminated（段失败，fail 由外层统一处理）。
    private suspend fun handleProtocolEvent(
        request: LoopRequest,
        onEvent: suspend (TurnEvent) -> Unit,
        event: ProtocolEvent,
        state: StreamState
    ) {
        when (event) {
            is ProtocolEvent.TextDelta -> {
                // 块切换：thinking 先行（DeepSeek 顺序），到 text 时收尾 thinking
                flushThinking(state, onEvent)
                if (!state.textStarted) {
                    state.textStarted = true
                    state.text.append(event.text)
                    onEvent(TurnEvent.TextStarted(state.blocks.size, state.partialMessage()))
                } else {
                    state.text.append(event.text)
                    onEvent(TurnEvent.TextDelta(state.blocks.size, event.text, state.partialMessage()))
                }
            }
            is ProtocolEvent.ThinkingDelta -> {
                flushText(state, onEvent)
                if (!state.thinkingStarted) {
                    state.thinkingStarted = true
                    state.thinking.append(event.text)
                    onEvent(TurnEvent.ThinkingStarted(state.blocks.size, state.partialMessage()))
                } else {
                    state.thinking.append(event.text)
                    onEvent(TurnEvent.ThinkingDelta(state.blocks.size, event.text, state.partialMessage()))
                }
            }
            is ProtocolEvent.ThinkingSignature -> {
                state.reasoningSignature = event.signature
                // 块级签名（评审发现）：签名绑定到当前进行中的块（Anthropic 思考块 /
                // Gemini 文本·思考块），flush 时写入块。无进行中块（Anthropic
                // redacted_thinking 无文本 / Gemini functionCall 签名走 ToolCallReady）
                // 时仅保留消息级。
                if (state.thinkingStarted || state.textStarted) {
                    state.pendingBlockSignature = event.signature
                }
            }
            // 协议私有 opaque payload（如 OpenAI reasoning envelope）：只挂载，不解析。
            // 即使没有思考文本（payload-only）也须落 Thinking 块。
            is ProtocolEvent.ThinkingOpaquePayload -> state.pendingThinkingPayloads += event.payload
            is ProtocolEvent.ToolCallStarted -> {
                state.pendingToolCalls += PendingToolCall(event.callId, event.toolName)
                onEvent(
                    TurnEvent.ToolCallStarted(
                        toolCallIndex(state, state.pendingToolCalls.lastIndex),
                        state.partialMessage()
                    )
                )
            }
            is ProtocolEvent.ToolCallDelta -> {
                // 完整响应 API 可能无 Started 直接发 Delta / Ready，找不到时创建
                val pending = findPending(state, event.callId)
                    ?: PendingToolCall(event.callId, event.toolName).also { state.pendingToolCalls += it }
                if (pending.id.isEmpty()) pending.id = event.callId
                if (pending.name.isEmpty()) pending.name = event.toolName
                pending.arguments.append(event.delta)
                onEvent(
                    TurnEvent.ToolCallDelta(
                        toolCallIndex(state, state.pendingToolCalls.indexOf(pending)),
                        event.delta,
                        state.partialMessage()
                    )
                )
            }
            is ProtocolEvent.ToolCallReady -> {
                // 完整响应 API 直接 Ready（无 Started）：pending 不存在时创建
                val pending = findPending(state, event.callId)
                    ?: PendingToolCall(event.callId, event.toolName).also { state.pendingToolCalls += it }
                val call = ContentBlock.ToolCall(
                    id = pending.id.ifEmpty { event.callId },
                    name = pending.name.ifEmpty { event.toolName },
                    // Ready 携带最终参数 JSON（事件契约），以它为最终事实源——
                    // 只有 Ready、无 Delta 的协议执行器不会收到空串。ifEmpty
                    // 回退累积 delta：兼容 Ready 不重复携带参数的流式协议。
                    argumentsJson = event.argumentsJson.ifEmpty { pending.arguments.toString() },
                    // Gemini 3 思维内工具调用签名（事件契约，原样回带）
                    signature = event.signature
                )
                state.pendingToolCalls.remove(pending)
                // 块交接：插入前先 flush 进行中的 thinking/text，工具调用按流到达
                // 顺序落进统一 blocks（交错流 [thinking, tool_use, thinking, text]
                // 不丢边界与顺序；Ready 后 index 即终值，不再随后续 flush 漂移）
                flushBlocks(state, onEvent)
                state.blocks += call
                onEvent(
                    TurnEvent.ToolCallReady(
                        state.blocks.lastIndex,
                        call,
                        state.partialMessage()
                    )
                )
            }
            is ProtocolEvent.Completed -> {
                state.usage = event.usage
                state.responseModel = event.responseModel
                state.stopReason = event.stopReason
                when (event.stopReason) {
                    null, StopReason.Stop, StopReason.Length, StopReason.ToolUse -> {
                        flushBlocks(state, onEvent)
                        throw StreamCompleted(buildFinalAssistant(state))
                    }
                    else -> throw StreamTerminated(
                        LLMError(
                            LLMErrorCode.Parse, "abnormal completion stopReason: ${event.stopReason}"
                        )
                    )
                }
            }
            is ProtocolEvent.Error -> throw StreamTerminated(
                LLMError(
                    // retryable（协议层判定的临时错误，如 Anthropic overloaded_error）→
                    // Transport 可重试；其余（畸形 JSON 等）→ Parse 不可重试（问题 2）
                    if (event.retryable) LLMErrorCode.Transport else LLMErrorCode.Parse,
                    "stream parse error",
                    event.cause
                )
            )
        }
    }

    // ── 工具执行段落（Phase 1 准备 → Phase 2 并发执行 → Phase 3 保序提交） ──

    private suspend fun executeTools(
        request: LoopRequest,
        onEvent: suspend (TurnEvent) -> Unit,
        assistant: AssistantMessage,
        toolCalls: List<ContentBlock.ToolCall>
    ): ToolExecutionOutcome {

        // Phase 1（顺序）：解析 + beforeToolCall 链。outcome 非空 = 已定
        // （阻断 / hook 失败 / 未知工具），不执行；outcome 空 = 待执行。
        // holder 为 null = 无工具上下文（未知工具）；可执行 plan 必有 holder。
        data class Plan(
            val toolCall: ContentBlock.ToolCall,
            val holder: ToolCallHolder?,
            val executor: ToolExecutor?,
            val context: ToolCallContext?,
            var outcome: ToolCallOutcome?
        )
        val plans = mutableListOf<Plan>()
        for (call in toolCalls) {
            val registered = request.toolRegistry.find(call.name)
            if (registered == null) {
                // 模型命名错误（wireName 未注册）→ Failure 结果回喂，回合继续：
                // 模型可自纠（换名 / 放弃调用），与「MCP 服务器不存在 → Failure」
                // 同层（§8.18 Q1/Q3）；区别于 ToolExecutionFailed（executor 违反
                // 「永不抛异常」契约，模型无法修复代码 bug，回合失败，§8.15 #5）。
                // 默认文案为纯文本（message 与 content 同值），下游如需定制可经
                // outcome 后续扩展点表达（当前无消费者，不新增 API）。
                val message = "Unknown tool '${call.name}'"
                plans += Plan(
                    toolCall = call,
                    holder = null,
                    executor = null,
                    context = null,
                    outcome = ToolCallOutcome.Failure(message = message, content = message)
                )
                continue
            }
            val holder = ToolCallHolder(call.id, call.name, call.argumentsJson, registered.descriptor)
            var hookFailed: ToolCallOutcome? = null
            try {
                for (hook in request.hooks) {
                    hook.beforeToolCall(holder)
                    if (holder.outcome != null) break // 阻断：后续 hook 不执行
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 工具段 hook 异常 → Failure outcome（§8.4 #13），回合继续
                hookFailed = ToolCallOutcome.Failure("beforeToolCall hook failed: ${e.message}")
            }
            when {
                hookFailed != null -> plans += Plan(call, holder, null, null, hookFailed)
                holder.outcome != null -> plans += Plan(call, holder, null, null, holder.outcome)
                else -> plans += Plan(
                    call, holder, registered.executor,
                    ToolCallContext(call.id, call.name, registered.descriptor, holder.argumentsJson),
                    null
                )
            }
        }

        // Phase 2：按调用顺序发 ToolRunning，然后并发执行（coroutineScope
        // 结构化并发，取消传播）。executor 违反「永不抛异常」契约 → 回合
        // Failed(ToolExecutionFailed)（业务方 bug 显形，不打包回喂模型）。
        val executedOutcomes: Map<Int, ToolCallOutcome> = try {
            val executable = plans.mapIndexedNotNull { index, plan ->
                if (plan.outcome == null) index to plan else null
            }
            if (executable.isEmpty()) {
                emptyMap()
            } else {
                for ((_, plan) in executable) {
                    onEvent(
                        TurnEvent.ToolRunning(
                            assistant.content.indexOf(plan.toolCall),
                            plan.toolCall,
                            assistant
                        )
                    )
                }
                coroutineScope {
                    executable.map { (index, plan) ->
                        async {
                            index to try {
                                plan.executor!!.execute(plan.context!!)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                throw ToolExecutionException(plan.toolCall, e)
                            }
                        }
                    }.awaitAll()
                }.toMap()
            }
        } catch (e: ToolExecutionException) {
            return ToolExecutionOutcome.Failure(
                failTurn(
                    onEvent, assistant,
                    LLMError(LLMErrorCode.ToolExecutionFailed, "tool ${e.toolCall.name} execution failed", e.failure)
                )
            )
        }

        // Phase 3（顺序，保序）：执行过的调用走 afterToolCall（可替换结果，
        // 异常 → Failure outcome）→ encodeToolResult → 批量 commit + 事件。
        // 已定 outcome（阻断 / hook 失败）的调用不执行也不走 afterToolCall。
        val resultMessages = mutableListOf<Message>()
        for ((index, plan) in plans.withIndex()) {
            var outcome = executedOutcomes[index] ?: plan.outcome!!
            if (plan.outcome == null) {
                // outcome == null ⇒ 可执行 plan ⇒ holder 必非空
                // （unknown tool 的 plan 带已定 outcome，不进入本分支）
                val holder = requireNotNull(plan.holder)
                val resultHolder = ToolResultHolder(outcome)
                try {
                    for (hook in request.hooks) hook.afterToolCall(holder, resultHolder)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    resultHolder.write(
                        ToolCallOutcome.Failure("afterToolCall hook failed: ${e.message}"), "okia"
                    )
                }
                outcome = resultHolder.outcome
            }
            resultMessages += request.protocolMapper.encodeToolResult(plan.toolCall, outcome)
            onEvent(toolOutcomeEvent(assistant, plan.toolCall, outcome))
        }
        request.onCommit(resultMessages)
        return ToolExecutionOutcome.Success(resultMessages)
    }

    // 工具执行段落结果：成功携带提交的 ToolResult 消息（loop 同步进累积历史），
    // 失败携带回合终态（ToolExecutionFailed）。
    private sealed interface ToolExecutionOutcome {
        data class Success(val messages: List<Message>) : ToolExecutionOutcome
        data class Failure(val result: TurnResult) : ToolExecutionOutcome
    }

    // outcome 5 态 → ToolSucceeded / ToolFailed 事件（T6 裁决：Intercepted 按
    // isError；Interrupted / Unknown 归失败）。事件携带完整 outcome，UI 不丢信息。
    private fun toolOutcomeEvent(
        assistant: AssistantMessage,
        toolCall: ContentBlock.ToolCall,
        outcome: ToolCallOutcome
    ): TurnEvent {
        val index = assistant.content.indexOf(toolCall)
        return when (outcome) {
            is ToolCallOutcome.Success -> TurnEvent.ToolSucceeded(index, toolCall, outcome, assistant)
            is ToolCallOutcome.Failure -> TurnEvent.ToolFailed(index, toolCall, outcome, assistant)
            is ToolCallOutcome.Intercepted ->
                if (outcome.isError) TurnEvent.ToolFailed(index, toolCall, outcome, assistant)
                else TurnEvent.ToolSucceeded(index, toolCall, outcome, assistant)
            is ToolCallOutcome.Interrupted -> TurnEvent.ToolFailed(index, toolCall, outcome, assistant)
            is ToolCallOutcome.Unknown -> TurnEvent.ToolFailed(index, toolCall, outcome, assistant)
        }
    }

    // ── 收尾与工具函数 ─────────────────────────────────────────────────────

    // 失败收尾：commit 部分产出（若有）+ 发 TurnFailed + 返回 Failed 终态。
    // 只在段最终失败时调用（不可重试 / 重试耗尽）；段首重试路径不 commit partial。
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

    // 工具段失败收尾：Assistant 已 commit（执行前提交），只发事件 + 返回终态
    private suspend fun failTurn(
        onEvent: suspend (TurnEvent) -> Unit,
        message: AssistantMessage,
        error: LLMError
    ): TurnResult {
        onEvent(TurnEvent.TurnFailed(message, error))
        return TurnResult.Failed(error)
    }

    // 有部分产出时提交（取消清理与最终失败收尾共用）
    private suspend fun commitPartial(request: LoopRequest, state: StreamState) {
        if (state.hasAnyContent()) {
            request.onCommit(listOf(Message.Assistant(state.partialMessage())))
        }
    }

    // 模型段 hook 链分发：按注册顺序执行（前一个的 mutation 对后一个可见）；
    // hook 异常 → 回合 Failed（§8.4 #13：模型段 hook 失败 = 该步骤失败，
    // 明确失败优于自动修复），取消传播。返回 null = 继续，非 null = 失败终态。
    private suspend fun hookStep(
        request: LoopRequest,
        onEvent: suspend (TurnEvent) -> Unit,
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
            return fail(request, onEvent, StreamState(), LLMError(LLMErrorCode.HookFailed, "$phase hook failed", e))
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

    // 每轮消息完整：flush 后的最终 Assistant（stopReason null 默认 Stop）
    private fun buildFinalAssistant(state: StreamState): AssistantMessage = AssistantMessage(
        content = state.blocks.toList(),
        stopReason = state.stopReason ?: StopReason.Stop,
        usage = state.usage,
        responseModel = state.responseModel,
        reasoningSignature = state.reasoningSignature
    )

    // G4 状态码 → 错误分类（约定俗成，对照 pi provider-retry / codex retry）：
    // 可重试 = 408/409/429/全部 5xx/网络；401/403 Auth、402 Quota、
    // 400 系 Parse 不可重试。503 语义 = Overloaded，其余 5xx = Transport。
    private fun classifyStatus(status: Int): LLMErrorCode = when (status) {
        401, 403 -> LLMErrorCode.Auth
        402 -> LLMErrorCode.Quota
        429 -> LLMErrorCode.RateLimit
        503 -> LLMErrorCode.Overloaded
        408, 409 -> LLMErrorCode.Transport
        in 500..599 -> LLMErrorCode.Transport
        else -> LLMErrorCode.Parse // 400 系 / 3xx：客户端错误，不可重试
    }

    // Retry-After 解析：retry-after-ms（毫秒）优先，retry-after（数字秒）次之。
    // HTTP-date 形式不解析（M0 约定俗成：数字形式为主，罕见形式走指数退避）。
    private fun parseRetryAfter(headers: Map<String, String>): Long? {
        headers.entries.firstOrNull { it.key.equals("retry-after-ms", ignoreCase = true) }
            ?.value?.toLongOrNull()?.let { return it }
        headers.entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }
            ?.value?.toDoubleOrNull()?.let { return (it * 1000).toLong() }
        return null
    }

    // 段最终失败的错误：可重试错误 + 回合层已配置（但预算耗尽）→ RetryExhausted
    // （库声明的重试全部试过，host 无需再试）；可重试 + 回合层未配置 → 原错误
    // （库不自动重试，如实返回，host 自行决策）；不可重试 → 原错误。statusCode
    // 保留供 host 文案映射。
    private fun exhaustedError(error: LLMError, turnConfigured: Boolean): LLMError =
        if (error.code.isRetryable && turnConfigured) {
            LLMError(
                LLMErrorCode.RetryExhausted,
                "retry exhausted (${error.code.name})",
                null,
                error.statusCode,
                error.retryDelayMs
            )
        } else {
            error
        }

    // ── 块事件工具 ─────────────────────────────────────────────────────────

    private suspend fun flushText(state: StreamState, onEvent: suspend (TurnEvent) -> Unit) {
        if (!state.textStarted) return
        val content = state.text.toString()
        val index = state.blocks.size
        state.blocks += ContentBlock.Text(content, signature = state.pendingBlockSignature)
        state.pendingBlockSignature = null
        state.textStarted = false
        state.text.clear()
        onEvent(TurnEvent.TextEnded(index, content, state.partialMessage()))
    }

    private suspend fun flushThinking(state: StreamState, onEvent: suspend (TurnEvent) -> Unit) {
        val hasText = state.thinkingStarted
        val hasPayload = state.pendingThinkingPayloads.isNotEmpty()
        if (!hasText && !hasPayload) return
        val content = state.thinking.toString()
        val index = state.blocks.size
        state.blocks += ContentBlock.Thinking(
            text = content,
            signature = state.pendingBlockSignature,
            // parser 已封装完整 envelope（含 provider 前缀），loop 只透传；
            // 单个 reasoning 阶段 = 一个 envelope（多阶段合并超出当前协议形态）
            opaquePayload = state.pendingThinkingPayloads.firstOrNull()
        )
        state.pendingBlockSignature = null
        state.pendingThinkingPayloads.clear()
        state.thinkingStarted = false
        state.thinking.clear()
        // payload-only 块（无文本）不发 ThinkingEnded（无对应 Started 事件）
        if (hasText) onEvent(TurnEvent.ThinkingEnded(index, content, state.partialMessage()))
    }

    private suspend fun flushBlocks(state: StreamState, onEvent: suspend (TurnEvent) -> Unit) {
        flushThinking(state, onEvent)
        flushText(state, onEvent)
    }

    // 进行中工具调用按 callId 匹配；Started 时 id 可能为空（协议增量补全），
    // 防御：找不到时用第一个未定 id 的调用（协议按 index 顺序 emit）。
    private fun findPending(state: StreamState, callId: String): PendingToolCall? {
        if (callId.isNotEmpty()) {
            state.pendingToolCalls.firstOrNull { it.id == callId }?.let { return it }
        }
        return state.pendingToolCalls.firstOrNull { it.id.isEmpty() }
    }

    // 进行中工具调用在 partial 中的预估块位置（Ready 前的最终位置；
    // Ready 前不占位，partial 不含未 Ready 调用，§5.4）
    private fun toolCallIndex(state: StreamState, pendingIndex: Int): Int =
        state.blocks.size + pendingIndex
}

// ── 段执行结果 / 发送结果 / 流信号与哨兵 ─────────────────────────────────

/** 段执行出口：Success = 该轮完整 Assistant；Finished = 回合终态已内部处理。 */
private sealed interface SegmentOutcome {
    data class Success(val assistant: AssistantMessage) : SegmentOutcome
    data class Finished(val result: TurnResult) : SegmentOutcome
}

/** 发送阶段出口：Ok = 可进 SSE 解析；Failed = 重试耗尽 / 不可重试 / hook 已 fail。 */
private sealed interface SendResult {
    data class Ok(val response: StreamResponse.Ok) : SendResult
    data class Failed(val error: LLMError, val finalized: Boolean = false) : SendResult
}

/** idle 收集循环的选择信号。 */
private sealed interface StreamSignal {
    data class Event(val event: ProtocolEvent) : StreamSignal
    object Closed : StreamSignal
    object Idle : StreamSignal
}

/** 每轮流式累积状态：partial 快照由它派生（thinking/text 进行中、toolCall 已 Ready）。 */
private class StreamState {
    val blocks = mutableListOf<ContentBlock>() // 已完成的块（text / thinking / toolCall），顺序即流到达顺序
    val text = StringBuilder() // 进行中 text
    var textStarted = false
    val thinking = StringBuilder() // 进行中 thinking
    var thinkingStarted = false
    val pendingToolCalls = mutableListOf<PendingToolCall>() // 流式组装中（未 Ready，不占位）
    var usage: Usage? = null
    var responseModel: String? = null
    var stopReason: StopReason? = null
    var reasoningSignature: String? = null
    // 待绑定到下一块 flush 的签名（ThinkingSignature 事件绑定到进行中的块）
    var pendingBlockSignature: String? = null
    // 待绑定到下一思考块 flush 的 opaque payload（ThinkingOpaquePayload 事件，
    // 协议私有、loop 不解析；即使无思考文本也落块）
    val pendingThinkingPayloads = mutableListOf<String>()

    fun partialContent(): List<ContentBlock> = buildList {
        addAll(blocks)
        if (textStarted) add(ContentBlock.Text(text.toString()))
        if (thinkingStarted) add(ContentBlock.Thinking(thinking.toString()))
    }

    fun partialMessage(): AssistantMessage = AssistantMessage(
        content = partialContent(),
        stopReason = stopReason ?: StopReason.Pending,
        usage = usage,
        responseModel = responseModel,
        reasoningSignature = reasoningSignature
    )

    fun hasAnyContent(): Boolean =
        blocks.isNotEmpty() || textStarted || thinkingStarted
}

/** 流式组装中的工具调用（Ready 后转为 ContentBlock.ToolCall 占位）。 */
private class PendingToolCall(
    var id: String,
    var name: String,
    val arguments: StringBuilder = StringBuilder()
)

/** 每轮消息完整信号：中断流收集的内部哨兵，携带该轮完整 Assistant 消息。 */
private class StreamCompleted(val assistant: AssistantMessage) : Exception()

/**
 * 终态信号：中断流收集的内部哨兵（非 CancellationException，不被取消机制误判）。
 * 携带段失败错误；fail（commitPartial + TurnFailed）由段执行统一决定（重试或终态）。
 * collect 被异常终止时会退订上游流（无限流场景必需，T2 实测暴露）。
 */
private class StreamTerminated(val error: LLMError) : Exception()

/** idle 超时信号：partial 已 commit、TurnIdleTimeout 已发；段执行转 IdleTimeout 终态。 */
private object StreamIdleTimedOut : Exception()

/** executor 违反「永不抛异常」契约的哨兵：Phase 2 并发中传播，外层转 Failed。 */
private class ToolExecutionException(val toolCall: ContentBlock.ToolCall, val failure: Throwable) : Exception()

// 非 2xx 错误 body 进 LLMError.message 的最大字符数（UI 详情，非完整响应）
private const val MAX_ERROR_BODY_CHARS = 2000
