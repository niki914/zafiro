package com.niki914.kai

sealed interface KaiEvent {
    data class RoundStarted(val input: String) : KaiEvent

    data class TextDelta(
        val delta: String,
        val fullText: String
    ) : KaiEvent

    data class ToolRunning(
        val callId: String,
        val toolName: String,
        val kind: ToolCallKind
    ) : KaiEvent

    data class ToolSucceeded(
        val callId: String,
        val toolName: String,
        val kind: ToolCallKind,
        val resultJson: String
    ) : KaiEvent

    data class ToolFailed(
        val callId: String,
        val toolName: String,
        val kind: ToolCallKind,
        val message: String,
        val resultJson: String? = null
    ) : KaiEvent

    data class RoundCompleted(
        val fullText: String,
        val finishReason: FinishReason = FinishReason.Completed
    ) : KaiEvent

    data class Error(
        val stage: Stage,
        val message: String,
        val cause: Throwable? = null
    ) : KaiEvent

    enum class Stage {
        Transport,
        Parse,
        Tool,
        Session
    }

    enum class FinishReason {
        Completed,
        Stopped,
        IdleTimeout,
        Error,
        Cancelled
    }
}
