package com.niki914.nexus.agentic.chat


sealed interface LlmStreamEvent {
    data object RoundStarted : LlmStreamEvent

    data class TextDelta(
        val delta: String,
        val fullText: String,
        val charsPerSecond: Float? = null,
    ) : LlmStreamEvent

    data class ToolRunning(
        val call: ToolCallStatus,
    ) : LlmStreamEvent

    data class ToolSucceeded(
        val call: ToolCallStatus,
        val outputText: String? = null,
    ) : LlmStreamEvent

    data class ToolFailed(
        val call: ToolCallStatus,
        val message: String,
    ) : LlmStreamEvent

    data class Error(
        val message: String,
        val throwable: Throwable? = null,
        val code: LlmErrorCode? = null,
    ) : LlmStreamEvent

    data class Completed(
        val fullText: String,
    ) : LlmStreamEvent
}

enum class LlmErrorCode {
    ConfigRequired,
    TurnConflict,
}

data class ToolCallStatus(
    val callId: String? = null,
    val name: String,
    val label: String = name,
    val kind: ToolCallKind = ToolCallKind.Unknown,
)

enum class ToolCallKind {
    Local,
    Mcp,
    Unknown,
}
