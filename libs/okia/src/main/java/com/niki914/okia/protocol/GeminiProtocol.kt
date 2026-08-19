package com.niki914.okia.protocol

import com.niki914.okia.message.AssistantMessage
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Google Gemini（Generative Language API）协议。
 * 与其余协议的关键差异：
 * - 端点含 {model} 占位符：模型在 URL 路径中（buildRequest 时替换为
 *   snapshot.model），认证走 x-goog-api-key 头
 * - 请求体：contents（role: user / model）+ parts（text / thought /
 *   functionCall / functionResponse），systemInstruction 与 generationConfig
 *   顶层字段，工具为 functionDeclarations
 * - 流式：data: 分片，每 chunk 含 candidates[].content.parts（增量文本，
 *   对齐 @google/genai SDK 语义 / pi google 实现）、finishReason、usageMetadata
 * - 思考：thought: true part + thoughtSignature；工具结果回带为 user 消息的
 *   functionResponse part（响应对象 output / error 二选一）
 * - 工具：functionCall part 可携带 thoughtSignature（Gemini 3 思维内工具调用，
 *   原样回带）；finishReason=STOP 但本段出现 functionCall 时映射 ToolUse
 *   （pi 语义，工具循环依赖 ToolUse 执行）
 * 历史与请求装配：consecutive 同角色内容合并（Anthropic 同规则）；工具结果
 * 并入 user content（functionResponse part），可与用户文本共存。
 * 无集成测试（无真实 key，用户裁决 2026-08-18）：实现经代码走查 + fixture
 * 语义对照 pi google-generative-ai.ts。图片输入 M2 前不支持。
 * Design source: pi api/google-generative-ai.ts + google-shared.ts。
 */
class GeminiProtocol(
    private val codec: Json = Json,
    override val compat: Compat = GeminiCompat()
) : ChatProtocol {

    override val id: String get() = compat.id
    override val defaultEndpoint: String? get() = compat.defaultEndpoint

    override fun withCodec(codec: Json): ChatProtocol = GeminiProtocol(codec, compat)

    override fun useApiKey(apiKey: String): Map<String, String> =
        if (apiKey.isEmpty()) emptyMap() else mapOf("x-goog-api-key" to apiKey)

    override fun buildRequest(snapshot: RequestSnapshot, history: List<Message>): HttpRequest =
        HttpRequest(
            url = snapshot.endpoint.replace(MODEL_PLACEHOLDER, snapshot.model),
            method = "POST",
            headers = snapshot.headers + useApiKey(snapshot.apiKey),
            body = codec.encodeToString(JsonObject.serializer(), buildRequestBody(snapshot, history)),
            timeouts = snapshot.timeouts,
            sensitiveHeaderNames = compat.sensitiveHeaderNames
        )

    override fun parseStream(rawSseLines: Flow<SseLine>): Flow<ProtocolEvent> = flow {
        val state = StreamState()
        var failed = false
        SseEventParser().parse(rawSseLines).collect { event ->
            if (failed) return@collect
            try {
                val chunk = codec.parseToJsonElement(event.data) as? JsonObject
                if (chunk == null) {
                    throw SerializationException("gemini chunk is not a json object")
                }
                handleChunk(chunk, state, ::emit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SerializationException) {
                emit(ProtocolEvent.Error(e))
                failed = true
            } catch (e: IllegalStateException) {
                // 协议自身错误（finishReason 非法等）；loop 的流终止哨兵
                // （StreamCompleted）不是 IllegalStateException，自然传播
                emit(ProtocolEvent.Error(e))
                failed = true
            }
        }
        if (!failed) finishStream(state, ::emit)
    }

    override fun encodeToolResult(call: ContentBlock.ToolCall, outcome: ToolCallOutcome): Message =
        Message.ToolResult(call.id, call.name, outcome)

    // ── 请求体 ─────────────────────────────────────────────────────────────

    /** 每条真实内容：role（user / model）+ parts 数组（合并后的最终形态）。 */
    private class MergedContent(val role: String, val parts: MutableList<JsonObject>)

    private fun buildRequestBody(snapshot: RequestSnapshot, history: List<Message>): JsonObject =
        buildJsonObject {
            put("contents", buildJsonArray {
                mergeContents(history).forEach { content ->
                    add(buildJsonObject {
                        put("role", content.role)
                        put("parts", buildJsonArray { content.parts.forEach { add(it) } })
                    })
                }
            })
            snapshot.systemPrompt?.let {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", it) })
                    })
                })
            }
            put("generationConfig", buildJsonObject {
                put("maxOutputTokens", snapshot.maxTokens)
                put("temperature", snapshot.temperature)
            })
            if (snapshot.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            snapshot.tools.forEach { add(convertTool(it)) }
                        })
                    })
                })
            }
        }

    /**
     * 历史 → Gemini contents：user content = 文本 parts（+ functionResponse）；
     * 助手 content = text / thought / functionCall parts；连续同角色合并。
     * 工具结果并入 user content（可与用户文本共存）。
     */
    private fun mergeContents(history: List<Message>): List<MergedContent> {
        val merged = mutableListOf<MergedContent>()
        for (message in history) {
            val (role, parts) = when (message) {
                is Message.User -> "user" to userParts(message.content)
                is Message.Assistant -> "model" to assistantParts(message.message)
                is Message.ToolResult -> "user" to listOf(functionResponsePart(message))
            }
            val last = merged.lastOrNull()
            if (last != null && last.role == role) {
                last.parts += parts
            } else {
                merged += MergedContent(role, parts.toMutableList())
            }
        }
        return merged
    }

    private fun userParts(blocks: List<ContentBlock>): List<JsonObject> {
        val image = blocks.firstOrNull { it is ContentBlock.Image }
        if (image != null) {
            throw IllegalStateException(
                "image content is not supported by GeminiProtocol before M2"
            )
        }
        return blocks.filterIsInstance<ContentBlock.Text>().map { text ->
            buildJsonObject { put("text", text.text) }
        }
    }

    private fun assistantParts(message: AssistantMessage): List<JsonObject> =
        message.content.mapNotNull { block ->
            when (block) {
                is ContentBlock.Text -> buildJsonObject {
                    put("text", block.text)
                    block.signature?.let { put("thoughtSignature", it) }
                }
                is ContentBlock.Thinking -> buildJsonObject {
                    put("thought", true)
                    put("text", block.text)
                    block.signature?.let { put("thoughtSignature", it) }
                }
                is ContentBlock.ToolCall -> buildJsonObject {
                    put("functionCall", buildJsonObject {
                        put("name", block.name)
                        put("args", parseArguments(block.argumentsJson))
                        put("id", block.id)
                    })
                    // Gemini 3：functionCall part 的 thoughtSignature 原样回带
                    // （pi 语义，否则下一步返回 400）
                    block.signature?.let { put("thoughtSignature", it) }
                }
                is ContentBlock.Image -> null  // M2 前不支持（user 侧已抛错），防御忽略
            }
        }

    private fun functionResponsePart(result: Message.ToolResult): JsonObject =
        buildJsonObject {
            put("functionResponse", buildJsonObject {
                put("name", result.toolName)
                put("id", result.callId)
                // 成功走 output，失败走 error（SDK 语义，对照 pi）
                if (result.outcome.isProviderError()) {
                    put("response", buildJsonObject {
                        put("error", result.outcome.providerContent())
                    })
                } else {
                    put("response", buildJsonObject {
                        put("output", result.outcome.providerContent())
                    })
                }
            })
        }

    private fun parseArguments(argumentsJson: String): JsonObject = try {
        codec.parseToJsonElement(argumentsJson) as? JsonObject ?: buildJsonObject { }
    } catch (e: SerializationException) {
        buildJsonObject { }  // 参数非法：按空对象回带
    }

    private fun convertTool(tool: ToolDescriptor): JsonObject =
        buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            tool.inputSchemaJson?.let { put("parameters", codec.parseToJsonElement(it)) }
        }

    // ── 流解析 ─────────────────────────────────────────────────────────────

    private suspend fun handleChunk(
        chunk: JsonObject,
        state: StreamState,
        emit: suspend (ProtocolEvent) -> Unit
    ) {
        // usageMetadata：末 chunk 通常带完整计数，每 chunk 出现即覆盖记录
        (chunk["usageMetadata"] as? JsonObject)?.let { state.usage = parseUsage(it) }
        (chunk["modelVersion"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            if (state.responseModel == null) state.responseModel = it
        }

        val candidate = (chunk["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            state.finishReason = it
        }
        val parts = candidate["content"]?.let { (it as? JsonObject)?.get("parts") as? JsonArray }
            ?: return
        parts.forEach { partElement ->
            val part = partElement as? JsonObject ?: return@forEach
            val isThought = (part["thought"] as? JsonPrimitive)?.booleanOrNull == true
            val hasText = part["text"] is JsonPrimitive
            val hasFunctionCall = part["functionCall"] is JsonObject
            when {
                // 思考 part：文本 + 签名；可同时携带 functionCall（Gemini 3 思维内
                // 工具调用，functionCall 与 thoughtSignature 同 part）——functionCall
                // 必须单独解析，不能因 thought 标志跳过
                isThought -> {
                    handleThinkingPart(part, state, emit)
                    if (hasFunctionCall) handleFunctionCallPart(part, state, emit)
                }
                // functionCall part（可同时携带文本 / 签名）
                hasFunctionCall -> {
                    if (hasText) handleTextPart(part, state, emit)
                    handleFunctionCallPart(part, state, emit)
                }
                else -> handleTextPart(part, state, emit)
            }
        }
    }

    private suspend fun handleTextPart(
        part: JsonObject,
        state: StreamState,
        emit: suspend (ProtocolEvent) -> Unit
    ) {
        val text = (part["text"] as? JsonPrimitive)?.contentOrNull ?: return
        if (text.isEmpty()) return
        // 分片语义：每 chunk 的 part.text 是到当前为止的新增文本（对齐 pi /
        // SDK 增量语义）；空文本跳过
        emit(ProtocolEvent.TextDelta(text))
        // 文本块可携带思考签名（回答块带 thoughtSignature，pi 语义：透传）
        (part["thoughtSignature"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            emit(ProtocolEvent.ThinkingSignature(it))
        }
    }

    private suspend fun handleThinkingPart(
        part: JsonObject,
        state: StreamState,
        emit: suspend (ProtocolEvent) -> Unit
    ) {
        val text = (part["text"] as? JsonPrimitive)?.contentOrNull ?: ""
        if (text.isNotEmpty()) emit(ProtocolEvent.ThinkingDelta(text))
        (part["thoughtSignature"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            emit(ProtocolEvent.ThinkingSignature(it))
        }
    }

    private suspend fun handleFunctionCallPart(
        part: JsonObject,
        state: StreamState,
        emit: suspend (ProtocolEvent) -> Unit
    ) {
        val call = part["functionCall"] as JsonObject
        val name = (call["name"] as? JsonPrimitive)?.contentOrNull ?: ""
        val args = call["args"] as? JsonObject
            ?: ((call["args"] as? JsonPrimitive)?.let { parseArguments(it.content) })
            ?: buildJsonObject { }
        // Gemini 老模型不返回 id（gemini-3 起要求）：无 id 时合成稳定 id
        // （工具结果引用用）。
        val providedId = (call["id"] as? JsonPrimitive)?.contentOrNull
        val id = if (providedId.isNullOrEmpty()) "gemini_fc_${call.hashCode()}" else providedId
        val argsJson = codec.encodeToString(JsonObject.serializer(), args)
        // functionCall part 是完整对象（非分片）：一次发全生命周期。
        // 签名：functionCall part 可携带 thoughtSignature（Gemini 3 思维内工具调用，
        // pi 语义：签名原样回带到 functionCall part，见 assistantParts）
        state.sawFunctionCall = true
        val signature = (part["thoughtSignature"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotEmpty() }
        emit(ProtocolEvent.ToolCallStarted(id, name))
        emit(ProtocolEvent.ToolCallDelta(id, name, argsJson))
        emit(ProtocolEvent.ToolCallReady(id, name, argsJson, signature))
    }

    private suspend fun finishStream(state: StreamState, emit: suspend (ProtocolEvent) -> Unit) {
        when (state.finishReason) {
            null -> emit(ProtocolEvent.Error(IllegalStateException("stream ended without finishReason")))
            // pi 语义（api/google-generative-ai.ts）：finishReason=STOP 但内容含
            // functionCall 时映射 ToolUse（工具循环依赖 ToolUse 执行工具；
            // Gemini 返回 functionCall 后通常以正常结束状态收尾）
            "STOP" -> emit(
                ProtocolEvent.Completed(
                    state.usage,
                    state.responseModel,
                    if (state.sawFunctionCall) StopReason.ToolUse else StopReason.Stop
                )
            )
            "MAX_TOKENS" -> emit(ProtocolEvent.Completed(state.usage, state.responseModel, StopReason.Length))
            else -> emit(
                ProtocolEvent.Error(
                    IllegalStateException("unsupported finishReason: ${state.finishReason}")
                )
            )
        }
    }

    private fun parseUsage(usage: JsonObject): Usage {
        val promptTokens = (usage["promptTokenCount"] as? JsonPrimitive)?.longOrNull ?: 0
        val candidatesTokens = (usage["candidatesTokenCount"] as? JsonPrimitive)?.longOrNull ?: 0
        val thoughtsTokens = (usage["thoughtsTokenCount"] as? JsonPrimitive)?.longOrNull ?: 0
        val cachedTokens = (usage["cachedContentTokenCount"] as? JsonPrimitive)?.longOrNull ?: 0
        // pi 语义：input = prompt − cached；output = candidates + thoughts（思考记入输出）
        return Usage(
            inputTokens = (promptTokens - cachedTokens).coerceAtLeast(0),
            outputTokens = candidatesTokens + thoughtsTokens,
            cacheReadTokens = cachedTokens,
            cacheWriteTokens = 0,
            reasoningTokens = thoughtsTokens
        )
    }

    // ── 流状态 ─────────────────────────────────────────────────────────────

    private class StreamState {
        var usage: Usage? = null
        var responseModel: String? = null
        var finishReason: String? = null
        // 本段是否出现过 functionCall（finishReason=STOP 时据此映射 ToolUse，pi 语义）
        var sawFunctionCall = false
    }

    private companion object {
        const val MODEL_PLACEHOLDER = "{model}"
    }
}

/** outcome → functionResponse 内容：内容原样，null 用空串。 */
private fun ToolCallOutcome.providerContent(): String = when (this) {
    is ToolCallOutcome.Success -> content
    is ToolCallOutcome.Failure -> content ?: ""
    is ToolCallOutcome.Intercepted -> content ?: ""
    is ToolCallOutcome.Interrupted -> content ?: ""
    is ToolCallOutcome.Unknown -> content ?: ""
}

/** 是否走 error 响应键（成功 output / 其余 error）。 */
private fun ToolCallOutcome.isProviderError(): Boolean = when (this) {
    is ToolCallOutcome.Success -> false
    is ToolCallOutcome.Failure -> true
    is ToolCallOutcome.Intercepted -> isError
    is ToolCallOutcome.Interrupted -> true
    is ToolCallOutcome.Unknown -> true
}