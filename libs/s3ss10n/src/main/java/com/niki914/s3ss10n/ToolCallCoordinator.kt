package com.niki914.s3ss10n

import com.niki914.s3ss10n.json.JsonCodec

internal class ToolCallCoordinator(
    private val mcpClient: McpClient,
    private val codec: JsonCodec
) {
    internal suspend fun handle(
        toolCall: ToolCallSpec,
        snapshot: SessionSnapshot,
        emitEvent: suspend (SessionEvent) -> Unit
    ): Message.Tool {
        if (snapshot.tools.find(toolCall.toolName) == null) {
            val message = "Unknown tool '${toolCall.toolName}'"
            val resultJson = codec.encode(mapOf("error" to message))
            emitEvent(
                SessionEvent.ToolFailed(
                    callId = toolCall.callId,
                    toolName = toolCall.toolName,
                    kind = ToolCallKind.Local,
                    message = message,
                    resultJson = resultJson
                )
            )
            emitToolError(emitEvent, message)
            return Message.Tool(
                callId = toolCall.callId,
                toolName = toolCall.toolName,
                contentJson = resultJson
            )
        }

        val request = buildToolCallRequest(toolCall, snapshot)
        emitEvent(
            SessionEvent.ToolRunning(
                callId = request.id,
                toolName = request.name,
                kind = request.kind
            )
        )

        val hooks = snapshot.hooksBlock
        if (hooks == null && request is LocalToolCallRequest) {
            emitEvent(
                SessionEvent.ToolFailed(
                    callId = request.id,
                    toolName = request.name,
                    kind = request.kind,
                    message = "No hooks configured",
                    resultJson = null
                )
            )
            emitToolError(emitEvent, "no hooks configured")
            return request.error("No hooks configured")
        }

        val toolMsg = xTrySuspend("ToolCallCoordinator.handle", onError = { t ->
            val message = t.message ?: "hooks threw exception"
            emitEvent(
                SessionEvent.ToolFailed(
                    callId = request.id,
                    toolName = request.name,
                    kind = request.kind,
                    message = message,
                    resultJson = null
                )
            )
            emitToolError(emitEvent, message, t)
            request.error(message)
        }) {
            if (hooks != null) {
                request.hooks()
            } else {
                request.delegate()
            }
        }

        when (val outcome = request.lastOutcome()) {
            is ToolCallOutcome.Success -> {
                emitEvent(
                    SessionEvent.ToolSucceeded(
                        callId = request.id,
                        toolName = request.name,
                        kind = request.kind,
                        resultJson = outcome.resultJson
                    )
                )
            }

            is ToolCallOutcome.Failure -> {
                emitEvent(
                    SessionEvent.ToolFailed(
                        callId = request.id,
                        toolName = request.name,
                        kind = request.kind,
                        message = outcome.errorMessage,
                        resultJson = outcome.resultJson
                    )
                )
                emitToolError(emitEvent, outcome.errorMessage)
            }

            null -> {
                emitEvent(
                    SessionEvent.ToolFailed(
                        callId = request.id,
                        toolName = request.name,
                        kind = request.kind,
                        message = "No outcome recorded",
                        resultJson = toolMsg.contentJson
                    )
                )
            }
        }
        return toolMsg
    }

    private fun buildToolCallRequest(
        toolCall: ToolCallSpec,
        snapshot: SessionSnapshot
    ): ToolCallRequest {
        val descriptor = snapshot.tools.find(toolCall.toolName)
        return when (val kind = descriptor?.kind) {
            ToolCallKind.Local -> LocalToolCallRequest(
                toolCall = toolCall,
                appParams = snapshot.appParams,
                codec = codec
            )

            is ToolCallKind.Mcp -> McpToolCallRequest(
                toolCall = toolCall,
                serverName = kind.serverName,
                appParams = snapshot.appParams,
                server = snapshot.mcpServer(kind.serverName),
                mcpClient = mcpClient,
                codec = codec
            )

            null -> error("Unknown tool '${toolCall.toolName}'")
        }
    }

    private suspend fun emitToolError(
        emitEvent: suspend (SessionEvent) -> Unit,
        message: String,
        cause: Throwable? = null
    ) {
        emitEvent(
            SessionEvent.Error(
                stage = SessionEvent.Stage.Tool,
                message = message,
                cause = cause
            )
        )
    }

    private fun ToolCallRequest.lastOutcome(): ToolCallOutcome? {
        return when (this) {
            is LocalToolCallRequest -> lastOutcome
            is McpToolCallRequest -> lastOutcome
        }
    }
}
