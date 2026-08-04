package com.niki914.nexus.agentic.app.conversation

import com.niki914.kai.ChatTurn
import com.niki914.kai.ToolCallSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class EncodedChatTurn(
    val kind: StoredChatTurnKind,
    val payloadJson: String,
)

object ChatTurnJsonCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(turn: ChatTurn): EncodedChatTurn? {
        val kind = kindOf(turn) ?: return null
        val payloadJson = when (turn) {
            is ChatTurn.User -> json.encodeToString(
                StoredUserTurnPayload.serializer(),
                StoredUserTurnPayload(content = turn.content),
            )

            is ChatTurn.Assistant -> json.encodeToString(
                StoredAssistantTurnPayload.serializer(),
                StoredAssistantTurnPayload(
                    content = turn.content,
                    toolCalls = turn.toolCalls.map { it.toPayload() },
                    reasoningContent = turn.reasoningContent,
                    reasoningSignature = turn.reasoningSignature,
                ),
            )

            is ChatTurn.ToolResult -> json.encodeToString(
                StoredToolResultTurnPayload.serializer(),
                StoredToolResultTurnPayload(
                    callId = turn.callId,
                    toolName = turn.toolName,
                    resultJson = turn.resultJson,
                ),
            )

            is ChatTurn.System -> return null
        }
        return EncodedChatTurn(kind = kind, payloadJson = payloadJson)
    }

    fun decode(kind: String, payloadJson: String): ChatTurn? {
        return runCatching {
            when (StoredChatTurnKind.entries.find { it.name == kind }) {
                StoredChatTurnKind.User -> {
                    val payload = json.decodeFromString(
                        StoredUserTurnPayload.serializer(),
                        payloadJson,
                    )
                    ChatTurn.User(content = payload.content)
                }

                StoredChatTurnKind.Assistant -> {
                    val payload = json.decodeFromString(
                        StoredAssistantTurnPayload.serializer(),
                        payloadJson,
                    )
                    ChatTurn.Assistant(
                        content = payload.content,
                        toolCalls = payload.toolCalls.map { it.toToolCallSpec() },
                        reasoningContent = payload.reasoningContent,
                        reasoningSignature = payload.reasoningSignature,
                    )
                }

                StoredChatTurnKind.ToolResult -> {
                    val payload = json.decodeFromString(
                        StoredToolResultTurnPayload.serializer(),
                        payloadJson,
                    )
                    ChatTurn.ToolResult(
                        callId = payload.callId,
                        toolName = payload.toolName,
                        resultJson = payload.resultJson,
                    )
                }

                null -> null
            }
        }.getOrNull()
    }

    fun kindOf(turn: ChatTurn): StoredChatTurnKind? = when (turn) {
        is ChatTurn.User -> StoredChatTurnKind.User
        is ChatTurn.Assistant -> StoredChatTurnKind.Assistant
        is ChatTurn.ToolResult -> StoredChatTurnKind.ToolResult
        is ChatTurn.System -> null
    }

    private fun ToolCallSpec.toPayload(): StoredToolCallSpecPayload {
        return StoredToolCallSpecPayload(
            callId = callId,
            toolName = toolName,
            argumentsJson = argumentsJson,
        )
    }

    private fun StoredToolCallSpecPayload.toToolCallSpec(): ToolCallSpec {
        return ToolCallSpec(
            callId = callId,
            toolName = toolName,
            argumentsJson = argumentsJson,
        )
    }
}

@Serializable
private data class StoredUserTurnPayload(
    val content: String,
)

@Serializable
private data class StoredAssistantTurnPayload(
    val content: String,
    val toolCalls: List<StoredToolCallSpecPayload> = emptyList(),
    val reasoningContent: String? = null,
    val reasoningSignature: String? = null,
)

@Serializable
private data class StoredToolResultTurnPayload(
    val callId: String,
    val toolName: String,
    val resultJson: String,
)

@Serializable
private data class StoredToolCallSpecPayload(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
)
