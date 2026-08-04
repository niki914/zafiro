package com.niki914.okai.transport

/**
 * A frame emitted by HttpEngine.frames. Heartbeat represents any network activity
 * without payload (SSE keep-alive comment line, empty chunk) and drives idle detection.
 *
 * Design source: inherited from existing kai (s3ss10n) HttpFrame; Heartbeat added
 * to satisfy the transport-activity idle rule in the kai PRD section 4.4.
 */
sealed interface HttpFrame {

    /** A parsed SSE data payload. */
    data class SseData(val value: String) : HttpFrame

    /** Network activity without payload. Keeps the stream alive. */
    data object Heartbeat : HttpFrame

    /** A plain text line from a non-SSE stream. */
    data class Text(val value: String) : HttpFrame
}
