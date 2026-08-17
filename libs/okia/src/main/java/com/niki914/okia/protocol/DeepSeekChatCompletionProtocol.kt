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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
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
 * M0 协议：DeepSeek Chat Completion（OpenAI 兼容格式）。
 * 独立实现，不复用通用 OpenAI 层：DeepSeek 私有字段（reasoning_content 等）
 * 的调整局限在本类，不牵动通用逻辑。映射语义参考 pi
 * openai-completions（同协议侧实现）；产品策略不包含（重试 / 缓存 /
 * 成本在框架其他层或下游）。
 * 边界（对齐 2026-08 讨论）：Completed 是单次模型请求结束（消息级），
 * stopReason=ToolUse 时回合未结束（T6 工具循环继续）；错误工具结果内容
 * 由下游决定，本类不加工（outcome.content 原样，null 用空字符串）。
 * Design source: pi providers/deepseek.ts + api/openai-completions.ts；
 * okia PRD §5.7 / §5.8 / §8.7。
 */
class DeepSeekChatCompletionProtocol(
    private val codec: Json = Json
) : ChatProtocol {

    override val id: String = "deepseek"

    // DeepSeek 官方 OpenAI 兼容端点；调用方可经 config.endpoint 覆盖（方案 A）
    override val defaultEndpoint: String? = "https://api.deepseek.com/chat/completions"

    override fun withCodec(codec: Json): ChatProtocol = DeepSeekChatCompletionProtocol(codec)

    override fun useApiKey(apiKey: String): Map<String, String> =
        if (apiKey.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")

    override fun buildRequest(snapshot: RequestSnapshot, history: List<Message>): HttpRequest =
        HttpRequest(
            url = snapshot.endpoint,
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

            val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@collect
            (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                state.finishReason = it
            }
            val delta = choice["delta"] as? JsonObject ?: return@collect
            (delta["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                emit(ProtocolEvent.TextDelta(it))
            }
            (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
                emit(ProtocolEvent.ThinkingDelta(it))
            }
            (delta["tool_calls"] as? JsonArray)?.forEach { tc ->
                (tc as? JsonObject)?.let { handleToolCallDelta(it, state, ::emit) }
            }
        }
        if (!failed) finishStream(state, ::emit)
    }

    override fun encodeToolResult(call: ContentBlock.ToolCall, outcome: ToolCallOutcome): Message =
        Message.ToolResult(call.id, call.name, outcome)

    override val compat: Compat = DeepSeekCompat()

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
                history.forEach { message -> convertMessage(message)?.let { add(it) } }
            })
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })
            put("max_tokens", snapshot.maxTokens)
            put("temperature", snapshot.temperature)
            if (snapshot.tools.isNotEmpty()) {
                put("tools", buildJsonArray { snapshot.tools.forEach { add(convertTool(it)) } })
            }
        }

    private fun convertMessage(message: Message): JsonObject? = when (message) {
        is Message.User -> buildJsonObject {
            put("role", "user")
            put("content", userContent(message.content))
        }
        is Message.Assistant -> convertAssistant(message.message)
        is Message.ToolResult -> buildJsonObject {
            put("role", "tool")
            put("content", message.outcome.providerContent())
            put("tool_call_id", message.callId)
        }
    }

    private fun userContent(blocks: List<ContentBlock>): String {
        val image = blocks.firstOrNull { it is ContentBlock.Image }
        if (image != null) {
            throw IllegalStateException(
                "image content is not supported by DeepSeekChatCompletionProtocol before M2"
            )
        }
        return blocks.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
    }

    private fun convertAssistant(message: AssistantMessage): JsonObject? {
        val textBlocks = message.content.filterIsInstance<ContentBlock.Text>()
        val thinkingBlocks = message.content.filterIsInstance<ContentBlock.Thinking>()
        val toolCalls = message.content.filterIsInstance<ContentBlock.ToolCall>()
        val assistantText = textBlocks.joinToString("") { it.text }
        val thinkingText = thinkingBlocks.joinToString("\n") { it.text }

        // 无文本且无工具调用（如被中断的空回复）：跳过，Provider 不接受
        if (assistantText.isEmpty() && toolCalls.isEmpty()) return null

        return buildJsonObject {
            put("role", "assistant")
            if (assistantText.isEmpty()) put("content", JsonNull) else put("content", assistantText)
            // DeepSeek 要求 assistant 消息带 reasoning_content（无思考时为空串）
            if (thinkingText.isEmpty()) put("reasoning_content", "") else put("reasoning_content", thinkingText)
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
                put("name", tool.name)
                put("description", tool.description)
                tool.inputSchemaJson?.let { put("parameters", codec.parseToJsonElement(it)) }
            })
        }

    // ── 流解析 ─────────────────────────────────────────────────────────────

    private fun parseUsage(usage: JsonObject): Usage {
        val promptTokens = (usage["prompt_tokens"] as? JsonPrimitive)?.longOrNull ?: 0
        val completionTokens = (usage["completion_tokens"] as? JsonPrimitive)?.longOrNull ?: 0
        val promptDetails = usage["prompt_tokens_details"] as? JsonObject
        val cacheRead = promptDetails?.let { (it["cached_tokens"] as? JsonPrimitive)?.longOrNull } ?: 0
        val cacheWrite = promptDetails?.let { (it["cache_write_tokens"] as? JsonPrimitive)?.longOrNull } ?: 0
        val completionDetails = usage["completion_tokens_details"] as? JsonObject
        val reasoningTokens = completionDetails?.let { (it["reasoning_tokens"] as? JsonPrimitive)?.longOrNull } ?: 0
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
            PartialToolCall(index).also { emit(ProtocolEvent.ToolCallStarted(id ?: "", name ?: "")) }
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
            "stop", "end" -> emit(ProtocolEvent.Completed(state.usage, state.responseModel, StopReason.Stop))
            "length" -> emit(ProtocolEvent.Completed(state.usage, state.responseModel, StopReason.Length))
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
