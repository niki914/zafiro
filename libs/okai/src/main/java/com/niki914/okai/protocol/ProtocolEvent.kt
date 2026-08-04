package com.niki914.okai.protocol

/**
 * Protocol-neutral stream events between ChatProtocol.parseStream and the loop.
 * The loop maps these to TurnEvent. Errors carry the cause; classification
 * happens at the error layer.
 *
 * Design source: existing kai (s3ss10n) ProtocolEvent, per kai PRD section 4.3.
 */
sealed interface ProtocolEvent {

    /** A text delta. */
    data class TextDelta(val text: String) : ProtocolEvent

    /** A thinking delta. */
    data class ThinkingDelta(val text: String) : ProtocolEvent

    /** The thinking signature, arriving after the thinking deltas. */
    data class ThinkingSignature(val signature: String) : ProtocolEvent

    /** A complete tool call with final arguments JSON. */
    data class ToolCallReady(
        val callId: String,
        val toolName: String,
        val argumentsJson: String
    ) : ProtocolEvent

    /** Stream ended normally. */
    data object Completed : ProtocolEvent

    /** Stream failed. */
    data class Error(val cause: Throwable) : ProtocolEvent
}
