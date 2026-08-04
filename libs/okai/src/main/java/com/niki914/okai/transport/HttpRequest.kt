package com.niki914.okai.transport

/**
 * Immutable HTTP request built by ChatProtocol and executed by HttpEngine.
 *
 * Design source: inherited from existing kai (s3ss10n) HttpRequest.
 */
data class HttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String?,
    val timeouts: HttpTimeouts
)

/** Timeout values in milliseconds. */
data class HttpTimeouts(
    val connectMs: Long,
    val readMs: Long,
    val writeMs: Long
)
