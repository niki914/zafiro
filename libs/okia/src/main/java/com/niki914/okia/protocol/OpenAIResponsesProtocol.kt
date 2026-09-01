package com.niki914.okia.protocol

import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.message.Usage
import com.niki914.okia.tooling.ToolDescriptor
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.SseEventParser
import com.niki914.okia.transport.SseLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * OpenAI Responses API（Messages 形态）协议。输入是 item 数组（非 messages），
 * 输出是命名事件流（event: response.*）。真实流格式经 DeepSeek /responses
 * 网关实测（2026-08-18）：
 * response.created → response.in_progress → response.output_item.added →
 * response.content_part.added → response.output_text.delta* →
 * response.function_call_arguments.delta* → response.output_item.done →
 * response.completed（带 status / usage / output）。
 * 事件细节（实测）：
 * - function_call item 携带双 id：item.id（事件引用）与 item.call_id（工具结果
 *   引用）；arguments delta 用 item_id 关联，须映射回 call_id
 * - 思考：DeepSeek 走 response.reasoning_text.delta；OpenAI 官方走
 *   response.reasoning_summary_text.delta（摘要）。两者都解析为 ThinkingDelta。
 *   OpenAI 官方 reasoning item（含 encrypted_content）在 output_item.done 捕获，
 *   以 ThinkingOpaquePayload 全量保存（envelope = openai-responses:reasoning:v1: +
 *   {"items":[...]}），回放时原样还原——不转明文（有损）。
 * - response.completed status=completed → Stop / ToolUse（按 output 是否含
 *   function_call）；status=incomplete 且 reason=max_tokens / max_output_tokens
 *   → Length；其余不完整或 failed → Error。
 * Design source: pi api/openai-responses.ts + openai-responses-shared.ts；
 * 实测 DeepSeek /responses 字节流（文本/工具/推理/usage 全形态）。
 */
class OpenAIResponsesProtocol(
    private val codec: Json = Json,
    override val compat: Compat = OpenAIResponsesCompat()
) : ChatProtocol {

    override val id: String get() = compat.id
    override val defaultEndpoint: String? get() = compat.defaultEndpoint

    override fun withCodec(codec: Json): ChatProtocol = OpenAIResponsesProtocol(codec, compat)

    override fun useApiKey(apiKey: String): Map<String, String> =
        if (apiKey.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")

    override fun buildRequest(snapshot: RequestSnapshot, history: List<Message>): HttpRequest =
        HttpRequest(
            url = snapshot.endpoint,
            method = "POST",
            headers = snapshot.headers + useApiKey(snapshot.apiKey),
            body = codec.encodeToString(
                JsonObject.serializer(),
                buildRequestBody(snapshot, history)
            ),
            timeouts = snapshot.timeouts,
            sensitiveHeaderNames = compat.sensitiveHeaderNames
        )

    override fun parseStream(rawSseLines: Flow<SseLine>): Flow<ProtocolEvent> = flow {
        val state = StreamState()
        var failed = false
        SseEventParser().parse(rawSseLines).collect { event ->
            if (failed) return@collect
            try {
                when (event.event) {
                    // 工具调用 item 注册（item.id → call_id/name 映射）与生命周期起点
                    "response.output_item.added" -> handleItemAdded(event.data, state, ::emit)
                    // 文本增量：正文 → TextDelta；推理（DeepSeek reasoning_text /
                    // OpenAI 官方 reasoning_summary_text 摘要）→ ThinkingDelta
                    "response.output_text.delta" -> handleTextDelta(event.data, ::emit)
                    "response.reasoning_text.delta" -> handleThinkingDelta(event.data, ::emit)
                    "response.reasoning_summary_text.delta" -> handleThinkingDelta(
                        event.data,
                        ::emit
                    )
                    // 工具参数增量
                    "response.function_call_arguments.delta" ->
                        handleArgsDelta(event.data, state, ::emit)

                    "response.function_call_arguments.done" ->
                        handleArgsDone(event.data, state, ::emit)
                    // 工具调用完成：item 携带完整 arguments
                    "response.output_item.done" -> handleItemDone(event.data, state, ::emit)
                    // 终态
                    "response.completed" -> handleCompleted(event.data, state, ::emit)
                    // 官方独立终态事件（OpenAI Streaming Events：达到输出上限时发
                    // response.incomplete，reason=max_tokens；不是 completed 的变体）
                    "response.incomplete" -> handleIncomplete(event.data, state, ::emit)
                    "response.failed" -> {
                        emit(
                            ProtocolEvent.Error(
                                IllegalStateException(
                                    "response.failed: ${
                                        errorMessage(
                                            event.data
                                        )
                                    }"
                                )
                            )
                        )
                        failed = true
                    }

                    "error" -> {
                        emit(
                            ProtocolEvent.Error(
                                IllegalStateException(
                                    "responses stream error: ${
                                        errorMessage(
                                            event.data
                                        )
                                    }"
                                )
                            )
                        )
                        failed = true
                    }

                    else -> Unit  // created / in_progress / content_part.* / output_text.done 等忽略
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SerializationException) {
                emit(ProtocolEvent.Error(e))
                failed = true
            } catch (e: IllegalStateException) {
                // 协议自身错误（status 异常等）；loop 的流终止哨兵（StreamCompleted）
                // 不是 IllegalStateException，自然传播
                emit(ProtocolEvent.Error(e))
                failed = true
            }
        }
        // 流结束无 response.completed：协议不完整
        if (!failed && !state.finished) {
            emit(ProtocolEvent.Error(IllegalStateException("stream ended without response.completed")))
        }
    }

    override fun encodeToolResult(call: ContentBlock.ToolCall, outcome: ToolCallOutcome): Message =
        Message.ToolResult(call.id, call.name, outcome)

    // ── 请求体 ─────────────────────────────────────────────────────────────

    private fun buildRequestBody(snapshot: RequestSnapshot, history: List<Message>): JsonObject =
        buildJsonObject {
            put("model", snapshot.model)
            put("input", buildJsonArray {
                history.forEach { message -> addInputItem(message).forEach { add(it) } }
            })
            snapshot.systemPrompt?.let { put("instructions", it) }
            put("stream", true)
            put("max_output_tokens", snapshot.maxTokens)
            put("temperature", snapshot.temperature)
            if (snapshot.tools.isNotEmpty()) {
                put("tools", buildJsonArray { snapshot.tools.forEach { add(convertTool(it)) } })
            }
        }

    /**
     * 消息 → Responses input item 列表。一条助手消息可能产出多个 item：
     * 文本 role 消息 + 每条工具调用一个 function_call item。
     * 思考回放：带合法 OpenAI reasoning payload 的 Thinking 块原样还原为
     * reasoning item（不把其文本拼进 message，避免重复）；无 payload 或前缀
     * 不认识的思考块继续按明文合并进文本（DeepSeek 网关形态）。
     */
    private fun addInputItem(message: Message): List<JsonObject> = when (message) {
        is Message.User -> listOf(buildJsonObject {
            put("role", "user")
            put("content", userContent(message.content))
        })

        is Message.Assistant -> {
            val items = mutableListOf<JsonObject>()
            val textBlocks = message.message.content.filterIsInstance<ContentBlock.Text>()
            val thinkingBlocks = message.message.content.filterIsInstance<ContentBlock.Thinking>()
            val toolCalls = message.message.content.filterIsInstance<ContentBlock.ToolCall>()
            // 带合法 OpenAI reasoning payload 的块 → 原样回放 reasoning items（保序）
            thinkingBlocks.forEach { block ->
                extractReasoningItems(block.opaquePayload)?.forEach { items += it }
            }
            // 无 payload / 前缀不认识 / envelope 损坏的思考文本 → 明文合并路径
            val plainThinkingText = thinkingBlocks
                .filter { extractReasoningItems(it.opaquePayload) == null }
                .joinToString("\n") { it.text }
            val text = textBlocks.joinToString("") { it.text }
            val content = when {
                plainThinkingText.isEmpty() -> text
                text.isEmpty() -> plainThinkingText
                else -> "$plainThinkingText\n$text"
            }
            if (content.isNotEmpty()) {
                items += buildJsonObject {
                    put("role", "assistant")
                    put("content", content)
                }
            }
            toolCalls.forEach { call ->
                items += buildJsonObject {
                    put("type", "function_call")
                    put("call_id", call.id)
                    put("name", call.name)
                    put("arguments", call.argumentsJson)
                }
            }
            items
        }

        is Message.ToolResult -> listOf(buildJsonObject {
            put("type", "function_call_output")
            put("call_id", message.callId)
            put("output", message.outcome.providerContent())
        })
    }

    // ── reasoning opaque payload（envelope 封装 / 解析） ───────────────────

    /** 带前缀的 reasoning envelope：openai-responses:reasoning:v1:{"items":[...]}。
     *  前缀路由 + 版本化；items 恒为数组（单元素也包装），未来多块不升级格式。 */
    private fun reasoningEnvelope(items: List<String>): String =
        REASONING_PAYLOAD_PREFIX + codec.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("items", buildJsonArray { items.forEach { add(codec.parseToJsonElement(it)) } })
            }
        )

    /** 解析带前缀 envelope → reasoning items；null = 无 payload / 前缀不认识 / 损坏。 */
    private fun extractReasoningItems(payload: String?): List<JsonObject>? {
        if (payload == null || !payload.startsWith(REASONING_PAYLOAD_PREFIX)) return null
        val envelope = try {
            codec.parseToJsonElement(payload.removePrefix(REASONING_PAYLOAD_PREFIX)) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return null
        return (envelope["items"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
    }

    /** 累积的 reasoning items 已就绪（阶段边界 / 终态）→ 发一次完整 envelope。 */
    private suspend fun emitReasoningEnvelope(
        state: StreamState,
        emit: suspend (ProtocolEvent) -> Unit
    ) {
        if (state.reasoningItems.isEmpty()) return
        emit(ProtocolEvent.ThinkingOpaquePayload(reasoningEnvelope(state.reasoningItems)))
        state.reasoningItems.clear()
    }

    private fun userContent(blocks: List<ContentBlock>): String {
        val image = blocks.firstOrNull { it is ContentBlock.Image }
        if (image != null) {
            throw IllegalStateException(
                "image content is not supported by OpenAIResponsesProtocol before M2"
            )
        }
        return blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
    }

    private fun convertTool(tool: ToolDescriptor): JsonObject =
        buildJsonObject {
            put("type", "function")
            put("name", tool.wireName)
            put("description", tool.description)
            tool.inputSchemaJson?.let { put("parameters", codec.parseToJsonElement(it)) }
        }

    // ── 流解析 ─────────────────────────────────────────────────────────────

    private suspend fun handleItemAdded(
        data: String,
        state: StreamState,
        emit: suspend (ProtocolEvent) -> Unit
    ) {
        val obj = codec.parseToJsonElement(data) as? JsonObject ?: return
        val item = obj["item"] as? JsonObject ?: return
        val type = (item["type"] as? JsonPrimitive)?.contentOrNull ?: return
        // reasoning 阶段结束边界：非 reasoning item 开始 → 先把已累积的 reasoning
        // envelope 发出（loop 的思考块在后续文本/工具 delta 到达时 flush，须先于
        // 它们收到 payload）
        if (type != "reasoning") emitReasoningEnvelope(state, emit)
        if (type != "function_call") return
        // item.id 是事件引用 id；call_id 是工具结果引用 id（Anthropic 语义同源）。
        val itemId = (item["id"] as? JsonPrimitive)?.contentOrNull ?: ""
        val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull ?: itemId
        val name = (item["name"] as? JsonPrimitive)?.contentOrNull ?: ""
        state.itemToCall[itemId] = itemId to callId
        state.toolArgs.putIfAbsent(itemId, StringBuilder())
        emit(ProtocolEvent.ToolCallStarted(callId, name))
    }

    private suspend fun handleTextDelta(data: String, emit: suspend (ProtocolEvent) -> Unit) {
        val obj = codec.parseToJsonElement(data) as? JsonObject ?: return
        val delta = (obj["delta"] as? JsonPrimitive)?.contentOrNull
        if (!delta.isNullOrEmpty()) emit(ProtocolEvent.TextDelta(delta))
    }

    private suspend fun handleThinkingDelta(data: String, emit: suspend (ProtocolEvent) -> Unit) {
        val obj = codec.parseToJsonElement(data) as? JsonObject ?: return
        val delta = (obj["delta"] as? JsonPrimitive)?.contentOrNull
        if (!delta.isNullOrEmpty()) emit(ProtocolEvent.ThinkingDelta(delta))
    }

    private suspend fun handleArgsDelta(
        data: String,
        state: StreamState,
        emit: suspend (ProtocolEvent) -> Unit
    ) {
        val obj = codec.parseToJsonElement(data) as? JsonObject ?: return
        val itemId = (obj["item_id"] as? JsonPrimitive)?.contentOrNull ?: return
        val delta = (obj["delta"] as? JsonPrimitive)?.contentOrNull
        if (delta.isNullOrEmpty()) return
        val (_, callId) = state.itemToCall[itemId] ?: return
        state.toolArgs[itemId]?.append(delta)
        emit(ProtocolEvent.ToolCallDelta(callId, "", delta))
    }

    /** 参数补全：delta 缺省时（某些 Provider 不发逐片 delta），
     *  从 arguments.done 的顶层 arguments 或 item.done 的 item.arguments 全量补发。
     *  返回该工具调用的最终参数 JSON（空串 = 无参数）。 */
    private suspend fun completeArguments(
        obj: JsonObject,
        state: StreamState,
        emit: suspend (ProtocolEvent) -> Unit
    ): String {
        val item = obj["item"] as? JsonObject
        val itemId = (item?.get("id") as? JsonPrimitive)?.contentOrNull
            ?: (obj["item_id"] as? JsonPrimitive)?.contentOrNull
            ?: return ""
        val full = (item?.get("arguments") as? JsonPrimitive)?.contentOrNull
            ?: (obj["arguments"] as? JsonPrimitive)?.contentOrNull
        val (_, callId) = state.itemToCall[itemId] ?: return ""
        val buf = state.toolArgs[itemId] ?: StringBuilder().also { state.toolArgs[itemId] = it }
        // 已通过 delta 累积过：不重复发（避免参数翻倍）
        if (buf.isEmpty() && !full.isNullOrEmpty()) {
            buf.append(full)
            emit(ProtocolEvent.ToolCallDelta(callId, "", full))
        }
        return buf.toString()
    }

    private suspend fun handleArgsDone(
        data: String,
        state: StreamState,
        emit: suspend (ProtocolEvent) -> Unit
    ) {
        val obj = codec.parseToJsonElement(data) as? JsonObject ?: return
        completeArguments(obj, state, emit)
    }

    private suspend fun handleItemDone(
        data: String,
        state: StreamState,
        emit: suspend (ProtocolEvent) -> Unit
    ) {
        val obj = codec.parseToJsonElement(data) as? JsonObject ?: return
        val item = obj["item"] as? JsonObject ?: return
        val type = (item["type"] as? JsonPrimitive)?.contentOrNull ?: return
        // reasoning item 完成：完整 item（id / summary / content / encrypted_content）
        // 原样累积，阶段边界（下一个非 reasoning item）或终态时统一封装发出。
        // 支持 payload-only：即使没有 reasoning 文本 delta，item 也完整保存。
        if (type == "reasoning") {
            state.reasoningItems += codec.encodeToString(JsonObject.serializer(), item)
            return
        }
        if (type != "function_call") return
        // 无 delta 场景补齐全量参数；同时取回最终参数供 Ready 携带
        val fullArgs = completeArguments(obj, state, emit)
        val itemId = (item["id"] as? JsonPrimitive)?.contentOrNull ?: ""
        val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull ?: itemId
        val name = (item["name"] as? JsonPrimitive)?.contentOrNull ?: ""
        emit(ProtocolEvent.ToolCallReady(callId, name, fullArgs))
    }

    private suspend fun handleCompleted(
        data: String,
        state: StreamState,
        emit: suspend (ProtocolEvent) -> Unit
    ) {
        val obj = codec.parseToJsonElement(data) as? JsonObject ?: return
        val response = obj["response"] as? JsonObject ?: return
        (response["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            if (state.responseModel == null) state.responseModel = it
        }
        val status = (response["status"] as? JsonPrimitive)?.contentOrNull
        val usage = (response["usage"] as? JsonObject)?.let { parseUsage(it) }
        when (status) {
            "completed" -> {
                // 按 response.output 是否含 function_call 决定 stopReason（与
                // chat 的 finish_reason=tool_calls 同语义；循环按 ToolUse 执行工具）。
                val hasToolUse = (response["output"] as? JsonArray)
                    ?.any { it is JsonObject && (it["type"] as? JsonPrimitive)?.contentOrNull == "function_call" }
                    ?: false
                state.finished = true
                // reasoning-only 回合（无后续非 reasoning item）：终态前补发 envelope
                emitReasoningEnvelope(state, emit)
                emit(
                    ProtocolEvent.Completed(
                        usage,
                        state.responseModel,
                        if (hasToolUse) StopReason.ToolUse else StopReason.Stop
                    )
                )
            }

            "incomplete" -> {
                val reason = (response["incomplete_details"] as? JsonObject)
                    ?.get("reason")?.let { (it as? JsonPrimitive)?.contentOrNull }
                when (reason) {
                    // 官方 reason=max_tokens；DeepSeek 网关形态 = max_output_tokens
                    "max_tokens", "max_output_tokens" -> {
                        state.finished = true
                        emitReasoningEnvelope(state, emit)
                        emit(ProtocolEvent.Completed(usage, state.responseModel, StopReason.Length))
                    }

                    else -> throw IllegalStateException("response incomplete, reason: $reason")
                }
            }

            else -> throw IllegalStateException("unexpected response status: $status")
        }
    }

    private suspend fun handleIncomplete(
        data: String,
        state: StreamState,
        emit: suspend (ProtocolEvent) -> Unit
    ) {
        val obj = codec.parseToJsonElement(data) as? JsonObject ?: return
        val response = obj["response"] as? JsonObject ?: return
        (response["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            if (state.responseModel == null) state.responseModel = it
        }
        val usage = (response["usage"] as? JsonObject)?.let { parseUsage(it) }
        val reason = (response["incomplete_details"] as? JsonObject)
            ?.get("reason")?.let { (it as? JsonPrimitive)?.contentOrNull }
        when (reason) {
            "max_tokens", "max_output_tokens" -> {
                state.finished = true
                // 与 handleCompleted 的 incomplete 分支一致：最后一批 reasoning item
                // 在终态前补发 envelope——否则 response.incomplete 是独立终态（无后续
                // completed），reasoningItems 留在 buffer 里永久丢失（CR3 #4）。
                emitReasoningEnvelope(state, emit)
                emit(ProtocolEvent.Completed(usage, state.responseModel, StopReason.Length))
            }

            else -> throw IllegalStateException("response incomplete, reason: $reason")
        }
    }

    private fun parseUsage(usage: JsonObject): Usage {
        val inputTokens = (usage["input_tokens"] as? JsonPrimitive)?.longOrNull ?: 0
        val outputTokens = (usage["output_tokens"] as? JsonPrimitive)?.longOrNull ?: 0
        val inputDetails = usage["input_tokens_details"] as? JsonObject
        val cacheRead =
            inputDetails?.let { (it["cached_tokens"] as? JsonPrimitive)?.longOrNull } ?: 0
        val outputDetails = usage["output_tokens_details"] as? JsonObject
        val reasoning =
            outputDetails?.let { (it["reasoning_tokens"] as? JsonPrimitive)?.longOrNull } ?: 0
        return Usage(
            inputTokens = (inputTokens - cacheRead).coerceAtLeast(0),
            outputTokens = outputTokens,
            cacheReadTokens = cacheRead,
            cacheWriteTokens = 0,
            reasoningTokens = reasoning
        )
    }

    private fun errorMessage(data: String): String {
        val response =
            (codec.parseToJsonElement(data) as? JsonObject)?.get("response") as? JsonObject
        val err = response?.get("error")
        return if (err is JsonObject) {
            (err["message"] as? JsonPrimitive)?.contentOrNull ?: response.toString()
        } else {
            data.take(300)
        }
    }

    // ── 流状态 ─────────────────────────────────────────────────────────────

    private class StreamState {
        // item.id → (itemId, callId)：arguments delta / done 用 item_id 关联，
        // 但工具结果引用 call_id，需要映射
        val itemToCall = mutableMapOf<String, Pair<String, String>>()

        // item.id → 已累积参数（append 顺序 = 字节序；可能首次 delta 就有全量）
        val toolArgs = mutableMapOf<String, StringBuilder>()

        // 本段已完成的 reasoning item JSON（原样保存，阶段边界/终态统一封装）
        val reasoningItems = mutableListOf<String>()
        var responseModel: String? = null
        var finished = false
    }

    private companion object {
        // reasoning opaque payload envelope 前缀（provider + 版本标记）；
        // 内容 = {"items":[<reasoning item>...]}。
        const val REASONING_PAYLOAD_PREFIX = "openai-responses:reasoning:v1:"
    }
}

/** outcome → tool 消息 output：内容原样，null 用空串。 */
private fun ToolCallOutcome.providerContent(): String = when (this) {
    is ToolCallOutcome.Success -> content
    is ToolCallOutcome.Failure -> content ?: ""
    is ToolCallOutcome.Intercepted -> content ?: ""
    is ToolCallOutcome.Interrupted -> content ?: ""
    is ToolCallOutcome.Unknown -> content ?: ""
}