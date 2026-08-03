package com.niki914.s3ss10n.ext.protocol.anthropic

import com.niki914.s3ss10n.ChatTurn
import com.niki914.s3ss10n.SessionEvent
import com.niki914.s3ss10n.SessionSnapshot
import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.json.JsonCodecFactory
import com.niki914.s3ss10n.net.HttpRequest
import com.niki914.s3ss10n.ext.protocol.ChatProtocol
import com.niki914.s3ss10n.ext.protocol.ProtocolEvent
import com.niki914.s3ss10n.xLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * ChatProtocol implementation for the Anthropic Messages API.
 *
 * SSE streaming: Unlike OpenAI where every data line is a JSON frame with
 * a uniform structure, Anthropic uses event-typed SSE events. The SseLineParser
 * strips event: lines, so we dispatch by inspecting the "type" field inside
 * each data: JSON payload.
 */
class AnthropicProtocol(
    private val codec: JsonCodec = JsonCodecFactory.create()
) : ChatProtocol {

    override fun withCodec(codec: JsonCodec): ChatProtocol {
        return AnthropicProtocol(codec)
    }

    override fun useApiKey(apiKey: String): Map<String, String> {
        return if (apiKey.isBlank()) emptyMap() else mapOf("x-api-key" to apiKey)
    }

    override fun buildRequest(
        snapshot: SessionSnapshot,
        history: List<ChatTurn>,
        pendingUserInput: String?
    ): HttpRequest {
        val messages = mutableListOf<AnthropicMessage>()

        // Convert history to Anthropic message format
        history.forEach { turn ->
            when (turn) {
                is ChatTurn.System -> {
                    // Anthropic uses top-level "system" field, not a system message
                }

                is ChatTurn.User -> {
                    messages += AnthropicMessage(
                        role = "user",
                        content = listOf(
                            AnthropicContentBlock(type = "text", text = turn.content)
                        )
                    )
                }

                is ChatTurn.Assistant -> {
                    val contentBlocks = mutableListOf<AnthropicContentBlock>()
                    if (turn.reasoningContent != null && turn.reasoningContent.isNotEmpty()) {
                        contentBlocks += AnthropicContentBlock(
                            type = "thinking",
                            thinking = turn.reasoningContent,
                            signature = turn.reasoningSignature
                        )
                    }
                    if (turn.content.isNotEmpty()) {
                        contentBlocks += AnthropicContentBlock(type = "text", text = turn.content)
                    }
                    turn.toolCalls.forEach { tc ->
                        val input = codec.decodeMap(tc.argumentsJson) ?: emptyMap()
                        contentBlocks += AnthropicContentBlock(
                            type = "tool_use",
                            id = tc.callId,
                            name = tc.toolName,
                            input = input
                        )
                    }
                    messages += AnthropicMessage(
                        role = "assistant",
                        content = contentBlocks.ifEmpty {
                            listOf(AnthropicContentBlock(type = "text", text = ""))
                        }
                    )
                }

                is ChatTurn.ToolResult -> {
                    messages += AnthropicMessage(
                        role = "user",
                        content = listOf(
                            AnthropicContentBlock(
                                type = "tool_result",
                                tool_use_id = turn.callId,
                                content = turn.resultJson
                            )
                        )
                    )
                }
            }
        }

        // Append pending user input as the final user message
        pendingUserInput
            ?.takeIf { it.isNotBlank() }
            ?.let {
                messages += AnthropicMessage(
                    role = "user",
                    content = listOf(
                        AnthropicContentBlock(type = "text", text = it)
                    )
                )
            }

        // Build Anthropic tool definitions (flat format, no function wrapper)
        val toolDefs = snapshot.tools.descriptors.map { desc ->
            AnthropicToolDef(
                name = desc.name,
                description = desc.description,
                input_schema = desc.inputSchema
            )
        }.ifEmpty { null }

        val body = AnthropicRequestBody(
            model = snapshot.model,
            messages = messages,
            max_tokens = snapshot.maxTokens,
            system = snapshot.systemPrompt?.takeIf { it.isNotBlank() },
            tools = toolDefs,
            temperature = snapshot.temperature,
            stream = true
        )

        val bodyStr = codec.encode(body)

        return HttpRequest(
            method = "POST",
            url = snapshot.endpoint.trim(),
            headers = mapOf(
                "Content-Type" to "application/json",
                "anthropic-version" to "2023-06-01"
            ),
            body = bodyStr.toByteArray(Charsets.UTF_8),
            timeoutMs = snapshot.timeouts,
            isStreaming = true
        )
    }

    override fun parseStream(rawSseLines: Flow<String>): Flow<ProtocolEvent> = flow {
        var toolUseId: String? = null
        var toolUseName: String? = null
        val toolUseInputBuilder = StringBuilder()
        var trackingToolUse = false

        rawSseLines.collect { line ->
            val data = codec.decodeMap(line)
            if (data == null) {
                xLog(TAG, "AnthropicProtocol.parseStream failed to decode SSE data")
                emit(
                    ProtocolEvent.Error(
                        RuntimeException("Failed to decode SSE frame"),
                        SessionEvent.Stage.Parse
                    )
                )
                return@collect
            }

            val eventType = data["type"] as? String ?: return@collect

            when (eventType) {
                "message_start", "ping", "message_delta" -> {
                    // Lifecycle events — no ProtocolEvent emission needed
                }

                "error" -> {
                    val errorData = data["error"]
                    if (errorData is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        val errMap = errorData as Map<String, Any?>
                        val message = errMap["message"] as? String ?: "Anthropic API error"
                        emit(
                            ProtocolEvent.Error(
                                RuntimeException(message),
                                SessionEvent.Stage.Transport
                            )
                        )
                    }
                }

                "content_block_start" -> {
                    val contentBlock = data["content_block"]
                    if (contentBlock is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        val block = contentBlock as Map<String, Any?>
                        val blockType = block["type"] as? String
                        if (blockType == "tool_use") {
                            toolUseId = block["id"] as? String ?: ""
                            toolUseName = block["name"] as? String ?: ""
                            toolUseInputBuilder.clear()
                            trackingToolUse = true
                        }
                    }
                }

                "content_block_delta" -> {
                    val delta = data["delta"]
                    if (delta is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        val deltaMap = delta as Map<String, Any?>
                        val deltaType = deltaMap["type"] as? String
                        when (deltaType) {
                            "text_delta" -> {
                                val text = deltaMap["text"] as? String ?: ""
                                if (text.isNotEmpty()) {
                                    emit(ProtocolEvent.TextDelta(text))
                                }
                            }
                            "thinking_delta" -> {
                                val thinking = deltaMap["thinking"] as? String ?: ""
                                if (thinking.isNotEmpty()) {
                                    emit(ProtocolEvent.ReasoningDelta(thinking))
                                }
                            }
                            "signature_delta" -> {
                                val signature = deltaMap["signature"] as? String ?: ""
                                if (signature.isNotEmpty()) {
                                    emit(ProtocolEvent.ReasoningSignature(signature))
                                }
                            }
                            "input_json_delta" -> {
                                val partialJson = deltaMap["partial_json"] as? String ?: ""
                                toolUseInputBuilder.append(partialJson)
                            }
                        }
                    }
                }

                "content_block_stop" -> {
                    if (trackingToolUse) {
                        trackingToolUse = false
                        val argumentsJson = toolUseInputBuilder.toString()
                        if (argumentsJson.isNotEmpty()) {
                            emit(
                                ProtocolEvent.ToolCallReady(
                                    callId = toolUseId ?: "",
                                    toolName = toolUseName ?: "",
                                    argumentsJson = argumentsJson
                                )
                            )
                        }
                        toolUseId = null
                        toolUseName = null
                    }
                }

                "message_stop" -> {
                    // Completed is emitted after the collect loop completes
                }
            }
        }

        emit(ProtocolEvent.Completed)
    }

    override fun encodeToolResult(
        callId: String,
        toolName: String,
        resultJson: String
    ): ChatTurn.ToolResult {
        return ChatTurn.ToolResult(
            callId = callId,
            toolName = toolName,
            resultJson = resultJson
        )
    }

    companion object {
        private const val TAG = "AnthropicProtocol"
    }
}