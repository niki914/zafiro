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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Anthropic Messages API 协议。真实流格式经 DeepSeek /anthropic 网关实测
 * （2026-08-18）：event: message_start / content_block_start / content_block_
 * delta / content_block_stop / message_delta / message_stop / ping / error。
 * 约束（Anthropic 强规则，均在此实现内解决）：
 * - max_tokens 必填（snapshot.maxTokens 非空，直接使用）
 * - system 是顶层字段，不是 role
 * - user/assistant 严格交替：连续同角色消息合并；工具结果并入 user 消息的
 *   tool_result 块（assistant tool_use 之后紧随一个 user 消息携带全部结果）
 * - thinking 块历史回带必须带 signature；无 signature 的思考转文本
 * - 认证走 x-api-key 头 + 固定 anthropic-version 头
 * 图片输入 M2 前不支持（与 OpenAI 协议一致，抛 IllegalStateException）。
 * Design source: pi api/anthropic-messages.ts；实测 DeepSeek Anthropic 网关
 * 字节流（thinking/tool_use/usage/stop_reason 全形态）。
 */
class AnthropicMessagesProtocol(
    private val codec: Json = Json,
    override val compat: Compat = AnthropicMessagesCompat()
) : ChatProtocol {

    override val id: String get() = compat.id
    override val defaultEndpoint: String? get() = compat.defaultEndpoint

    override fun withCodec(codec: Json): ChatProtocol = AnthropicMessagesProtocol(codec, compat)

    override fun useApiKey(apiKey: String): Map<String, String> =
        if (apiKey.isEmpty()) emptyMap() else mapOf("x-api-key" to apiKey)

    override fun buildRequest(snapshot: RequestSnapshot, history: List<Message>): HttpRequest =
        HttpRequest(
            url = snapshot.endpoint,
            method = "POST",
            headers = snapshot.headers + ANTHROPIC_HEADERS + useApiKey(snapshot.apiKey),
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
                when (event.event) {
                    "message_start" -> handleMessageStart(event.data, state)
                    "content_block_start" -> handleBlockStart(event.data, state, ::emit)
                    "content_block_delta" -> handleBlockDelta(event.data, state, ::emit)
                    "content_block_stop" -> handleBlockStop(event.data, state, ::emit)
                    "message_delta" -> handleMessageDelta(event.data, state)
                    // 流正常结束：message_stop 是最后一个事件
                    "message_stop" -> finishStream(state, ::emit)
                    "ping" -> Unit  // keep-alive，无事件
                    "error" -> {
                        emit(ProtocolEvent.Error(parseError(event.data)))
                        failed = true
                    }
                    else -> Unit  // 未知事件（citations 等）忽略
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SerializationException) {
                emit(ProtocolEvent.Error(e))
                failed = true
            } catch (e: IllegalStateException) {
                // 协议自身错误（stop_reason 非法 / 状态异常）；loop 的流终止哨兵
                // （StreamCompleted 等）不是 IllegalStateException，自然传播
                emit(ProtocolEvent.Error(e))
                failed = true
            }
        }
        // 流结束但没有 message_stop：协议不完整
        if (!failed && !state.finished) {
            emit(ProtocolEvent.Error(IllegalStateException("stream ended without message_stop")))
        }
    }

    override fun encodeToolResult(call: ContentBlock.ToolCall, outcome: ToolCallOutcome): Message =
        Message.ToolResult(call.id, call.name, outcome)

    // ── 请求体 ─────────────────────────────────────────────────────────────

    /** 每条真实消息：role + content 块数组（合并后的最终形态）。 */
    private class MergedMessage(val role: String, val blocks: MutableList<JsonObject>)

    private fun buildRequestBody(snapshot: RequestSnapshot, history: List<Message>): JsonObject =
        buildJsonObject {
            put("model", snapshot.model)
            put("max_tokens", snapshot.maxTokens)  // Anthropic 必填
            put("stream", true)
            snapshot.systemPrompt?.let { put("system", it) }
            if (snapshot.tools.isNotEmpty()) {
                put("tools", buildJsonArray { snapshot.tools.forEach { add(convertTool(it)) } })
            }
            put("messages", buildJsonArray {
                mergeMessages(history).forEach { merged ->
                    add(buildJsonObject {
                        put("role", merged.role)
                        put("content", buildJsonArray { merged.blocks.forEach { add(it) } })
                    })
                }
            })
        }

    /**
     * 历史 → Anthropic 消息序列：文本/思考/工具调用各自映射为 content 块；
     * 工具结果映射为 user 消息的 tool_result 块；连续同角色消息合并
     * （Anthropic 严格交替）。工具结果与后续用户输入合并进同一 user 消息。
     */
    private fun mergeMessages(history: List<Message>): List<MergedMessage> {
        val merged = mutableListOf<MergedMessage>()
        for (message in history) {
            val (role, blocks) = when (message) {
                is Message.User -> "user" to userBlocks(message.content)
                is Message.Assistant -> "assistant" to assistantBlocks(message.message)
                is Message.ToolResult -> "user" to listOf(toolResultBlock(message))
            }
            val last = merged.lastOrNull()
            if (last != null && last.role == role) {
                last.blocks += blocks
            } else {
                merged += MergedMessage(role, blocks.toMutableList())
            }
        }
        return merged
    }

    private fun userBlocks(blocks: List<ContentBlock>): List<JsonObject> {
        val image = blocks.firstOrNull { it is ContentBlock.Image }
        if (image != null) {
            throw IllegalStateException(
                "image content is not supported by AnthropicMessagesProtocol before M2"
            )
        }
        return blocks.filterIsInstance<ContentBlock.Text>().map { text ->
            buildJsonObject {
                put("type", "text")
                put("text", text.text)
            }
        }
    }

    private fun assistantBlocks(message: AssistantMessage): List<JsonObject> =
        message.content.mapNotNull { block ->
            when (block) {
                is ContentBlock.Text -> buildJsonObject {
                    put("type", "text")
                    put("text", block.text)
                }
                is ContentBlock.Thinking ->
                    // 思考回带必须带 signature（Anthropic 规则）；无 signature（非
                    // Anthropic 来源 / 网关空签名）转文本，避免伪造签名被 API 拒绝
                    if (block.signature != null) buildJsonObject {
                        put("type", "thinking")
                        put("thinking", block.text)
                        put("signature", block.signature)
                    } else buildJsonObject {
                        put("type", "text")
                        put("text", block.text)
                    }
                is ContentBlock.ToolCall -> buildJsonObject {
                    put("type", "tool_use")
                    put("id", block.id)
                    put("name", block.name)
                    put("input", parseArguments(block.argumentsJson))
                }
                is ContentBlock.Image -> null  // M2 前不支持，user 侧已先行抛错；此处防御忽略
            }
        }

    private fun toolResultBlock(result: Message.ToolResult): JsonObject =
        buildJsonObject {
            put("type", "tool_result")
            put("tool_use_id", result.callId)
            put("content", result.outcome.providerContent())
            if (result.outcome.isProviderError()) put("is_error", true)
        }

    private fun parseArguments(argumentsJson: String): JsonObject = try {
        codec.parseToJsonElement(argumentsJson) as? JsonObject ?: buildJsonObject { }
    } catch (e: SerializationException) {
        buildJsonObject { }  // 参数非法：按空对象回带，Provider 可接受
    }

    private fun convertTool(tool: ToolDescriptor): JsonObject =
        buildJsonObject {
            put("name", tool.wireName)
            put("description", tool.description)
            tool.inputSchemaJson?.let { put("input_schema", codec.parseToJsonElement(it)) }
        }

    // ── 流解析 ─────────────────────────────────────────────────────────────

    private fun handleMessageStart(
        data: String,
        state: StreamState
    ) {
        val message = (codec.parseToJsonElement(data) as? JsonObject)?.get("message") as? JsonObject
        (message?.get("model") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let {
            if (state.responseModel == null) state.responseModel = it
        }
        (message?.get("usage") as? JsonObject)?.let { usage ->
            val input = (usage["input_tokens"] as? JsonPrimitive)?.longOrNull ?: 0
            val cacheRead = (usage["cache_read_input_tokens"] as? JsonPrimitive)?.longOrNull ?: 0
            val cacheWrite = (usage["cache_creation_input_tokens"] as? JsonPrimitive)?.longOrNull ?: 0
            // Anthropic 语义：input_tokens 已为「非缓存输入」，总输入 =
            // input_tokens + cache_creation_input_tokens + cache_read_input_tokens
            // （官方 Usage 定义，区别于 OpenAI 的 input_tokens 含缓存）。直接取，
            // 不扣减 cache——否则 openai 式双重扣减会丢掉真实非缓存 token（CR3 #3）。
            state.usage = Usage(
                inputTokens = input,
                outputTokens = 0,
                cacheReadTokens = cacheRead,
                cacheWriteTokens = cacheWrite,
                reasoningTokens = 0
            )
        }
    }

    private suspend fun handleBlockStart(
        data: String,
        state: StreamState,
        emit: suspend (ProtocolEvent) -> Unit
    ) {
        val obj = codec.parseToJsonElement(data) as? JsonObject
        val index = (obj?.get("index") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val block = obj?.get("content_block") as? JsonObject
            ?: throw SerializationException("content_block_start without content_block")
        when ((block["type"] as? JsonPrimitive)?.contentOrNull) {
            "text" -> state.blocks[index] = TextBlock()
            "thinking" -> state.blocks[index] = ThinkingBlock()
            "redacted_thinking" -> {
                // 加密思考：只携带 signature，无明文。emit 签名（无 ThinkingDelta）
                val signature = (block["data"] as? JsonPrimitive)?.contentOrNull
                if (!signature.isNullOrEmpty()) emit(ProtocolEvent.ThinkingSignature(signature))
                state.blocks[index] = ThinkingBlock(signature = signature)
            }
            "tool_use" -> {
                val id = (block["id"] as? JsonPrimitive)?.contentOrNull ?: ""
                val name = (block["name"] as? JsonPrimitive)?.contentOrNull ?: ""
                state.blocks[index] = ToolBlock(id, name)
                emit(ProtocolEvent.ToolCallStarted(id, name))
            }
            else -> Unit  // 未知块类型忽略
        }

    }

    private suspend fun handleBlockDelta(
        data: String,
        state: StreamState,
        emit: suspend (ProtocolEvent) -> Unit
    ) {
        val obj = codec.parseToJsonElement(data) as? JsonObject
        val index = (obj?.get("index") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val delta = obj?.get("delta") as? JsonObject
            ?: throw SerializationException("content_block_delta without delta")
        val block = state.blocks[index]
        when ((delta["type"] as? JsonPrimitive)?.contentOrNull) {
            "text_delta" -> {
                val text = (delta["text"] as? JsonPrimitive)?.contentOrNull
                if (!text.isNullOrEmpty()) {
                    (block as? TextBlock)?.text?.append(text)
                    emit(ProtocolEvent.TextDelta(text))
                }
            }
            "thinking_delta" -> {
                val text = (delta["thinking"] as? JsonPrimitive)?.contentOrNull
                if (!text.isNullOrEmpty()) {
                    (block as? ThinkingBlock)?.text?.append(text)
                    emit(ProtocolEvent.ThinkingDelta(text))
                }
            }
            "signature_delta" -> {
                val signature = (delta["signature"] as? JsonPrimitive)?.contentOrNull
                if (!signature.isNullOrEmpty()) {
                    (block as? ThinkingBlock)?.signature = signature
                    emit(ProtocolEvent.ThinkingSignature(signature))
                }
            }
            "input_json_delta" -> {
                val partial = (delta["partial_json"] as? JsonPrimitive)?.contentOrNull
                if (!partial.isNullOrEmpty()) {
                    val tool = block as? ToolBlock ?: throw SerializationException(
                        "input_json_delta outside tool_use block"
                    )
                    tool.arguments.append(partial)
                    emit(ProtocolEvent.ToolCallDelta(tool.id, tool.name, partial))
                }
            }
            else -> Unit  // citations_delta 等忽略
        }
    }

    private suspend fun handleBlockStop(
        data: String,
        state: StreamState,
        emit: suspend (ProtocolEvent) -> Unit
    ) {
        val obj = codec.parseToJsonElement(data) as? JsonObject
        val index = (obj?.get("index") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val block = state.blocks.remove(index)
        if (block is ToolBlock) {
            emit(ProtocolEvent.ToolCallReady(block.id, block.name, block.arguments.toString()))
        }
    }

    private fun handleMessageDelta(
        data: String,
        state: StreamState
    ) {
        val obj = codec.parseToJsonElement(data) as? JsonObject
        val delta = obj?.get("delta") as? JsonObject
        val stopReason = (delta?.get("stop_reason") as? JsonPrimitive)?.contentOrNull
        stopReason?.let { state.stopReasonRaw = it }
        // message_delta 的 usage 携带输出 token（message_start 的是输入）。
        // cache 字段真实 API 只在 message_start 出现，delta 缺失时从 state.usage
        // 保留（否则覆盖为 0 会丢失 cacheRead/cacheWrite，prompt caching 下
        // 每轮必现）；delta 明确携带（含 0）时以新值覆盖。
        (obj?.get("usage") as? JsonObject)?.let { usage ->
            val input = state.usage?.inputTokens ?: 0
            val cacheRead = (usage["cache_read_input_tokens"] as? JsonPrimitive)?.longOrNull
                ?: state.usage?.cacheReadTokens ?: 0
            val cacheWrite = (usage["cache_creation_input_tokens"] as? JsonPrimitive)?.longOrNull
                ?: state.usage?.cacheWriteTokens ?: 0
            val output = (usage["output_tokens"] as? JsonPrimitive)?.longOrNull ?: 0
            state.usage = Usage(
                inputTokens = input,
                outputTokens = output,
                cacheReadTokens = cacheRead,
                cacheWriteTokens = cacheWrite,
                reasoningTokens = 0
            )
        }
    }

    private suspend fun finishStream(state: StreamState, emit: suspend (ProtocolEvent) -> Unit) {
        val stopReason = state.stopReasonRaw
        val stop = when (stopReason) {
            null -> null  // 流结束无 stop_reason：上层按协议不完整失败
            "end_turn", "stop_sequence" -> StopReason.Stop
            "max_tokens" -> StopReason.Length
            "tool_use" -> StopReason.ToolUse
            "refusal" -> StopReason.Error
            else -> StopReason.Error
        }
        if (stop == null) {
            throw SerializationException("stream ended without stop_reason")
        }
        if (stop == StopReason.Error) {
            throw IllegalStateException("provider stop_reason: $stopReason")
        }
        state.finished = true
        emit(ProtocolEvent.Completed(state.usage, state.responseModel, stop))
    }

    private fun parseError(data: String): Throwable {
        val message = (codec.parseToJsonElement(data) as? JsonObject)?.get("message") as? JsonPrimitive
        return IllegalStateException("anthropic stream error: ${message?.contentOrNull ?: data}")
    }

    // ── 流状态 ─────────────────────────────────────────────────────────────

    private class StreamState {
        // 活跃块按 index 存放：start 时入，stop 时携带 index 移除。
        // Anthropic 的块按序号依次出现，index 不重复。
        val blocks = mutableMapOf<Int, Block>()
        var stopReasonRaw: String? = null
        var usage: Usage? = null
        var responseModel: String? = null
        var finished = false
    }

    private sealed interface Block
    private class TextBlock(val text: StringBuilder = StringBuilder()) : Block
    private class ThinkingBlock(
        val text: StringBuilder = StringBuilder(),
        var signature: String? = null
    ) : Block
    private class ToolBlock(
        val id: String,
        val name: String,
        val arguments: StringBuilder = StringBuilder()
    ) : Block

    private companion object {
        // Anthropic 固定请求头（版本协商）；x-api-key 由 useApiKey 提供
        val ANTHROPIC_HEADERS = mapOf("anthropic-version" to "2023-06-01")
    }
}

/** outcome → tool_result content：内容原样，null 用空串。 */
private fun ToolCallOutcome.providerContent(): String = when (this) {
    is ToolCallOutcome.Success -> content
    is ToolCallOutcome.Failure -> content ?: ""
    is ToolCallOutcome.Intercepted -> content ?: ""
    is ToolCallOutcome.Interrupted -> content ?: ""
    is ToolCallOutcome.Unknown -> content ?: ""
}

/** 是否标记 is_error（Provider 语义：失败 / 拦截错误 / 中断 / 未知均视为错误）。 */
private fun ToolCallOutcome.isProviderError(): Boolean = when (this) {
    is ToolCallOutcome.Success -> false
    is ToolCallOutcome.Failure -> true
    is ToolCallOutcome.Intercepted -> isError
    is ToolCallOutcome.Interrupted -> true
    is ToolCallOutcome.Unknown -> true
}