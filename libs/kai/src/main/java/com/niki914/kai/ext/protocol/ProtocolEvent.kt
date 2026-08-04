package com.niki914.kai.ext.protocol

import com.niki914.kai.KaiEvent

sealed interface ProtocolEvent {
    data class TextDelta(val text: String) : ProtocolEvent

    data class ReasoningDelta(val text: String) : ProtocolEvent

    data class ReasoningSignature(val signature: String) : ProtocolEvent

    data class ToolCallReady(
        val callId: String,
        val toolName: String,
        val argumentsJson: String
    ) : ProtocolEvent

    data object Completed : ProtocolEvent

    data class Error(
        val cause: Throwable,
        val stage: KaiEvent.Stage
    ) : ProtocolEvent
}
