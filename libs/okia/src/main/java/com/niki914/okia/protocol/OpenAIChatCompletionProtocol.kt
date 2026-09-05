package com.niki914.okia.protocol

import com.niki914.okia.ImageLoader
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
import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 通用 OpenAI Chat Completions 协议。厂商差异全部由 compat 驱动（构造参数）：
 * - DeepSeekCompat（默认，M0 形态）：max_tokens 字段、reasoning_content 思考、
 *   assistant 历史必须带 reasoning_content（可为空串）
 * - OpenAIChatCompletionCompat：max_completion_tokens、reasoning_effort 思考
 *   （delta.reasoning 对象）、assistant 历史不接受 reasoning_content（思考转文本）
 * 其他 OpenAI 兼容厂商（xAI / Groq / Kimi / qwen / OpenRouter 等）用 compat
 * 表达差异，协议本体不感知厂商（对齐 pi openai-completions 单实现 26 厂商）。
 * 产品策略不包含（重试 / 缓存 / 成本在框架其他层或下游）。
 * 边界：Completed 是单次模型请求结束（消息级），stopReason=ToolUse 时回合未
 * 结束（T6 工具循环继续）；错误工具结果内容由下游决定，本类不加工
 * （outcome.content 原样，null 用空字符串）。
 * Design source: pi api/openai-completions.ts；okia T4 DeepSeek 实现（D21 裁决
 * 已由用户 2026-08-18 推翻：独立实现改为通用实现 + compat 驱动）。
 */
class OpenAIChatCompletionProtocol(
    private val codec: Json = Json,
    override val compat: Compat = DeepSeekCompat()
) : ChatProtocol {

    override val id: String get() = compat.id
    override val defaultEndpoint: String? get() = compat.defaultEndpoint

    override fun withCodec(codec: Json): ChatProtocol = OpenAIChatCompletionProtocol(codec, compat)

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
            if (event.data == DONE_MARKER) return@collect  // 结尾标记，无事件

            val chunk = try {
                codec.parseToJsonElement(event.data) as? JsonObject
            } catch (e: SerializationException) {
                emit(ProtocolEvent.Error(e))
                failed = true
                return@collect
            }
            if (chunk == null) {
                emit(ProtocolEvent.Error(SerializationException("chunk is not a json object")))
                failed = true
                return@collect
            }

            (chunk["usage"] as? JsonObject)?.let { state.usage = parseUsage(it) }
            (chunk["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                if (state.responseModel == null) state.responseModel = it
            }

            val choice =
                (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@collect
            (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                ?.let {
                    state.finishReason = it
                }
            val delta = choice["delta"] as? JsonObject ?: return@collect
            (delta["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                emit(ProtocolEvent.TextDelta(it))
            }
            // DeepSeek 私有思考字段
            (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                ?.let {
                    emit(ProtocolEvent.ThinkingDelta(it))
                }
            // OpenAI 官方：delta.reasoning 对象（content 明文；encrypted_content 不可读，忽略）
            (delta["reasoning"] as? JsonObject)?.let { reasoning ->
                (reasoning["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                    ?.let {
                        emit(ProtocolEvent.ThinkingDelta(it))
                    }
            }
            (delta["tool_calls"] as? JsonArray)?.forEach { tc ->
                (tc as? JsonObject)?.let { handleToolCallDelta(it, state, ::emit) }
            }
        }
        if (!failed) finishStream(state, ::emit)
    }

    override fun encodeToolResult(call: ContentBlock.ToolCall, outcome: ToolCallOutcome): Message =
        Message.ToolResult(call.id, call.name, outcome)

    // ── 请求体 ─────────────────────────────────────────────────────────────

    private fun buildRequestBody(snapshot: RequestSnapshot, history: List<Message>): JsonObject =
        buildJsonObject {
            put("model", snapshot.model)
            put("messages", buildJsonArray {
                snapshot.systemPrompt?.let {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", it)
                    })
                }
                history.forEach { message -> convertMessages(snapshot, message).forEach { add(it) } }
            })
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })
            // maxTokens 字段名由 compat 决定：DeepSeek 用 max_tokens，OpenAI 官方 o 系列用 max_completion_tokens
            put(
                when (compat.maxTokensField) {
                    MaxTokensField.MaxTokens -> "max_tokens"
                    MaxTokensField.MaxCompletionTokens -> "max_completion_tokens"
                },
                snapshot.maxTokens
            )
            put("temperature", snapshot.temperature)
            if (snapshot.tools.isNotEmpty()) {
                put("tools", buildJsonArray { snapshot.tools.forEach { add(convertTool(it)) } })
            }
        }

    /** 一条历史消息 → 0..n 条 Chat Completions 消息（ToolResult 带图可产两条）。 */
    private fun convertMessages(snapshot: RequestSnapshot, message: Message): List<JsonObject> = when (message) {
        is Message.User -> listOf(buildJsonObject {
            put("role", "user")
            val content = userContent(snapshot, message.content)
            if (content is JsonPrimitive) {
                put("content", content.content)
            } else {
                put("content", content)
            }
        })

        is Message.Assistant -> listOfNotNull(convertAssistant(message.message))
        is Message.ToolResult -> toolResultMessages(snapshot, message)
    }

    /**
     * ToolResult → Chat Completions 消息。OpenAI tool 角色 content 只接受字符串，
     * 不支持图片 content part（规范限制；pi 同：openai-completions.ts 把工具结果
     * 图片拆到独立的 user 消息）。图片加载失败 / 不支持时退回单条 tool 字符串消息。
     */
    private fun toolResultMessages(snapshot: RequestSnapshot, message: Message.ToolResult): List<JsonObject> {
        val toolMessage = buildJsonObject {
            put("role", "tool")
            put("tool_call_id", message.callId)
            put("content", message.outcome.providerContent())
        }
        val image = (message.outcome as? ToolCallOutcome.Success)?.image ?: return listOf(toolMessage)
        if (!snapshot.supportsImages) return listOf(toolMessage)
        val loader = snapshot.imageLoader
        val bytes = loader?.load(image.path) ?: return listOf(toolMessage)
        val dataUrl = "data:${image.mimeType};base64,${Base64.encode(bytes)}"
        return listOf(toolMessage, buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "Attached image(s) from tool result:")
                })
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", dataUrl) })
                })
            })
        })
    }

    private fun userContent(snapshot: RequestSnapshot, blocks: List<ContentBlock>): kotlinx.serialization.json.JsonElement {
        val image = blocks.firstOrNull { it is ContentBlock.Image }
        val text = blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        if (image == null) {
            return JsonPrimitive(text)
        }
        if (!snapshot.supportsImages) {
            return JsonPrimitive("$text\n[image omitted: model does not support images]")
        }
        val loader = snapshot.imageLoader
        val bytes = loader?.load((image as ContentBlock.Image).path)
        if (bytes == null) {
            return JsonPrimitive("$text\n[image omitted: file not found]")
        }
        val dataUrl = "data:${image.mimeType};base64,${Base64.encode(bytes)}"
        return buildJsonArray {
            blocks.filterIsInstance<ContentBlock.Text>().forEach { t ->
                add(buildJsonObject {
                    put("type", "text")
                    put("text", t.text)
                })
            }
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", dataUrl) })
            })
        }
    }

    private fun convertAssistant(message: AssistantMessage): JsonObject? {
        val textBlocks = message.content.filterIsInstance<ContentBlock.Text>()
        val thinkingBlocks = message.content.filterIsInstance<ContentBlock.Thinking>()
        val toolCalls = message.content.filterIsInstance<ContentBlock.ToolCall>()
        val thinkingText = thinkingBlocks.joinToString("\n") { it.text }

        // 无文本且无工具调用（如被中断的空回复）：跳过，Provider 不接受
        if (textBlocks.isEmpty() && toolCalls.isEmpty()) return null

        return buildJsonObject {
            put("role", "assistant")
            // requiresReasoningContentOnAssistantMessages（DeepSeek）：
            //   reasoning_content 字段原样回带（无思考补空串）。
            // 否则（OpenAI 官方）：reasoning 不可回放，requiresThinkingAsText 时
            //   思考合并进 content 文本；无思考则 content 为空串/JsonNull。
            val text = textBlocks.joinToString("") { it.text }
            val content = if (!compat.requiresReasoningContentOnAssistantMessages &&
                thinkingText.isNotEmpty() && compat.requiresThinkingAsText
            ) {
                if (text.isEmpty()) thinkingText else "$thinkingText\n$text"
            } else {
                text
            }
            if (content.isEmpty()) put("content", JsonNull) else put("content", content)
            if (compat.requiresReasoningContentOnAssistantMessages) {
                if (thinkingText.isEmpty()) put(
                    "reasoning_content",
                    ""
                ) else put("reasoning_content", thinkingText)
            }
            if (toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    toolCalls.forEach { call ->
                        add(buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", call.name)
                                put("arguments", call.argumentsJson)
                            })
                        })
                    }
                })
            }
        }
    }

    private fun convertTool(tool: ToolDescriptor): JsonObject =
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", tool.wireName)
                put("description", tool.description)
                tool.inputSchemaJson?.let { put("parameters", codec.parseToJsonElement(it)) }
            })
        }

    // ── 流解析 ─────────────────────────────────────────────────────────────

    private fun parseUsage(usage: JsonObject): Usage {
        val promptTokens = (usage["prompt_tokens"] as? JsonPrimitive)?.longOrNull ?: 0
        val completionTokens = (usage["completion_tokens"] as? JsonPrimitive)?.longOrNull ?: 0
        val promptDetails = usage["prompt_tokens_details"] as? JsonObject
        val cacheRead =
            promptDetails?.let { (it["cached_tokens"] as? JsonPrimitive)?.longOrNull } ?: 0
        val cacheWrite =
            promptDetails?.let { (it["cache_write_tokens"] as? JsonPrimitive)?.longOrNull } ?: 0
        val completionDetails = usage["completion_tokens_details"] as? JsonObject
        val reasoningTokens =
            completionDetails?.let { (it["reasoning_tokens"] as? JsonPrimitive)?.longOrNull } ?: 0
        // pi 语义：input = prompt − cacheRead − cacheWrite（cached 计入 cacheRead）
        val input = (promptTokens - cacheRead - cacheWrite).coerceAtLeast(0)
        return Usage(
            inputTokens = input,
            outputTokens = completionTokens,
            cacheReadTokens = cacheRead,
            cacheWriteTokens = cacheWrite,
            reasoningTokens = reasoningTokens
        )
    }

    private suspend fun handleToolCallDelta(
        delta: JsonObject,
        state: StreamState,
        emit: suspend (ProtocolEvent) -> Unit
    ) {
        val index = (delta["index"] as? JsonPrimitive)?.int ?: 0
        val id = (delta["id"] as? JsonPrimitive)?.contentOrNull
        val function = delta["function"] as? JsonObject
        val name = function?.let { (it["name"] as? JsonPrimitive)?.contentOrNull }
        val args = function?.let { (it["arguments"] as? JsonPrimitive)?.contentOrNull }

        val call = state.toolCalls.getOrPut(index) {
            PartialToolCall(index).also {
                emit(
                    ProtocolEvent.ToolCallStarted(
                        id ?: "",
                        name ?: ""
                    )
                )
            }
        }
        if (id != null && call.id.isEmpty()) call.id = id
        if (name != null && call.name.isEmpty()) call.name = name
        // 空 arguments 分片无信息量，不产出 Delta（对齐 pi 行为）
        if (args != null && args.isNotEmpty()) {
            call.arguments.append(args)
            emit(ProtocolEvent.ToolCallDelta(call.id, call.name, args))
        }
    }

    private suspend fun finishStream(state: StreamState, emit: suspend (ProtocolEvent) -> Unit) {
        when (state.finishReason) {
            null -> emit(ProtocolEvent.Error(IllegalStateException("stream ended without finish_reason")))
            "stop", "end" -> emit(
                ProtocolEvent.Completed(
                    state.usage,
                    state.responseModel,
                    StopReason.Stop
                )
            )

            "length" -> emit(
                ProtocolEvent.Completed(
                    state.usage,
                    state.responseModel,
                    StopReason.Length
                )
            )

            "function_call", "tool_calls" -> {
                state.toolCalls.values.forEach { call ->
                    emit(ProtocolEvent.ToolCallReady(call.id, call.name, call.arguments.toString()))
                }
                emit(ProtocolEvent.Completed(state.usage, state.responseModel, StopReason.ToolUse))
            }

            else -> emit(
                ProtocolEvent.Error(IllegalStateException("unsupported finish_reason: ${state.finishReason}"))
            )
        }
    }

    /** 流内累积状态：每次 collect 独立（冷流）。 */
    private class StreamState {
        var usage: Usage? = null
        var responseModel: String? = null
        var finishReason: String? = null
        val toolCalls = LinkedHashMap<Int, PartialToolCall>()
    }

    /** 按 index 归属的工具调用分片累积。 */
    private class PartialToolCall(
        val index: Int,
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder()
    )

    private companion object {
        const val DONE_MARKER = "[DONE]"
    }
}

/** outcome → tool 消息 content：内容原样（下游决定错误表达），null 用空串。 */
private fun ToolCallOutcome.providerContent(): String = when (this) {
    is ToolCallOutcome.Success -> content
    is ToolCallOutcome.Failure -> content ?: ""
    is ToolCallOutcome.Intercepted -> content ?: ""
    is ToolCallOutcome.Interrupted -> content ?: ""
    is ToolCallOutcome.Unknown -> content ?: ""
}