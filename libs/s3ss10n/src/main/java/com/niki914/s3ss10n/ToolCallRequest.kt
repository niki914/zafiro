package com.niki914.s3ss10n

import com.niki914.s3ss10n.json.JsonCodec

internal sealed interface ToolCallOutcome {
    val resultJson: String

    data class Success(
        override val resultJson: String
    ) : ToolCallOutcome

    data class Failure(
        val errorMessage: String,
        override val resultJson: String
    ) : ToolCallOutcome
}

sealed interface ToolCallRequest {
    val id: String
    val name: String
    val argumentsJson: String
    val kind: ToolCallKind
    val appParams: Map<String, Any?>

    suspend fun delegate(): Message.Tool

    fun ok(contentJson: String): Message.Tool

    fun error(
        message: String,
        contentJson: String = """{"success":false}"""
    ): Message.Tool
}

internal class LocalToolCallRequest(
    private val toolCall: ToolCallSpec,
    override val appParams: Map<String, Any?>,
    private val codec: JsonCodec
) : ToolCallRequest {
    override val id: String get() = toolCall.callId
    override val name: String get() = toolCall.toolName
    override val argumentsJson: String get() = toolCall.argumentsJson
    override val kind: ToolCallKind = ToolCallKind.Local

    internal var lastOutcome: ToolCallOutcome? = null

    override suspend fun delegate(): Message.Tool {
        return error("Local tool '$name' has no built-in implementation. Handle it in hooks { ... }.")
    }

    override fun ok(contentJson: String): Message.Tool {
        lastOutcome = ToolCallOutcome.Success(contentJson)
        return Message.Tool(
            callId = id,
            toolName = name,
            contentJson = contentJson
        )
    }

    override fun error(message: String, contentJson: String): Message.Tool {
        val detailValue = codec.decodeMap(contentJson) ?: contentJson
        val resultJson = codec.encode(mapOf("error" to message, "detail" to detailValue))
        lastOutcome = ToolCallOutcome.Failure(message, resultJson)
        return Message.Tool(
            callId = id,
            toolName = name,
            contentJson = resultJson
        )
    }
}

internal class McpToolCallRequest(
    private val toolCall: ToolCallSpec,
    private val serverName: String,
    override val appParams: Map<String, Any?>,
    private val server: McpServerConfig?,
    private val mcpClient: McpClient,
    private val codec: JsonCodec
) : ToolCallRequest {
    override val id: String get() = toolCall.callId
    override val name: String get() = toolCall.toolName
    override val argumentsJson: String get() = toolCall.argumentsJson
    override val kind: ToolCallKind = ToolCallKind.Mcp(serverName)

    internal var lastOutcome: ToolCallOutcome? = null

    override suspend fun delegate(): Message.Tool {
        val currentServer = server ?: return error("MCP server '$serverName' is not configured")
        return xTrySuspend("McpToolCallRequest.delegate", onError = { t ->
            error(t.message ?: "MCP call failed")
        }) {
            ok(mcpClient.call(currentServer, name, argumentsJson))
        }
    }

    override fun ok(contentJson: String): Message.Tool {
        lastOutcome = ToolCallOutcome.Success(contentJson)
        return Message.Tool(
            callId = id,
            toolName = name,
            contentJson = contentJson
        )
    }

    override fun error(message: String, contentJson: String): Message.Tool {
        val resultJson = codec.encode(mapOf("error" to message))
        lastOutcome = ToolCallOutcome.Failure(message, resultJson)
        return Message.Tool(
            callId = id,
            toolName = name,
            contentJson = resultJson
        )
    }
}
