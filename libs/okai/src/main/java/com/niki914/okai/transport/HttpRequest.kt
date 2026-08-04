package com.niki914.okai.transport

/**
 * Immutable HTTP request built by ChatProtocol and executed by HttpEngine.
 *
 * Design source: independent design; request shape validated in the Nexus
 * usage of kai.
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
