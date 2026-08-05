package com.niki914.okai.protocol

import com.niki914.okai.message.Usage

/**
 * Protocol-neutral stream events between ChatProtocol.parseStream and the loop.
 * The loop maps these to TurnEvent. Errors carry the cause; classification
 * happens at the error layer.
 *
 * Design source: pi (earendil-works/pi) stream events, per kai PRD section 4.3.
 */
sealed interface ProtocolEvent {

    /** A text delta. */
    data class TextDelta(val text: String) : ProtocolEvent

    /** A thinking delta. */
    data class ThinkingDelta(val text: String) : ProtocolEvent

    /** The thinking signature, arriving after the thinking deltas. */
    data class ThinkingSignature(val signature: String) : ProtocolEvent

    /**
     * A tool call started. Streaming APIs emit ToolCallDelta afterwards;
     * full-response APIs jump straight to ToolCallReady.
     */
    data class ToolCallStarted(
        val callId: String,
        val toolName: String
    ) : ProtocolEvent

    /** A tool call arguments delta for the call started by ToolCallStarted. */
    data class ToolCallDelta(
        val callId: String,
        val toolName: String,
        val delta: String
    ) : ProtocolEvent

    /** A complete tool call with final arguments JSON. */
    data class ToolCallReady(
        val callId: String,
        val toolName: String,
        val argumentsJson: String
    ) : ProtocolEvent

    /** Stream ended normally. Usage and response model may be absent, so they stay nullable. */
    data class Completed(
        val usage: Usage? = null,
        val responseModel: String? = null
    ) : ProtocolEvent

    /** Stream failed. */
    data class Error(val cause: Throwable) : ProtocolEvent
}
