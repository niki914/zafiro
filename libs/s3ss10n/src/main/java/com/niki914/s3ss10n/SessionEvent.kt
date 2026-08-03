package com.niki914.s3ss10n

sealed interface SessionEvent {
    data class RoundStarted(val input: String) : SessionEvent

    data class TextDelta(
        val delta: String,
        val fullText: String
    ) : SessionEvent

    data class ToolRunning(
        val callId: String,
        val toolName: String,
        val kind: ToolCallKind
    ) : SessionEvent

    data class ToolSucceeded(
        val callId: String,
        val toolName: String,
        val kind: ToolCallKind,
        val resultJson: String
    ) : SessionEvent

    data class ToolFailed(
        val callId: String,
        val toolName: String,
        val kind: ToolCallKind,
        val message: String,
        val resultJson: String? = null
    ) : SessionEvent

    data class RoundCompleted(
        val fullText: String,
        val finishReason: FinishReason = FinishReason.Completed
    ) : SessionEvent

    data class Error(
        val stage: Stage,
        val message: String,
        val cause: Throwable? = null
    ) : SessionEvent

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
