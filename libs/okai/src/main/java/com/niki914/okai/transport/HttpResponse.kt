package com.niki914.okai.transport

/**
 * Structured response with status code and headers. Required by transport-level
 * retry and error classification; status must never be flattened into a message string.
 *
 * Design source: existing kai (s3ss10n) HttpResponse, promoted from unary-only
 * to the shared error path (pi provider-retry / codex ApiError: Transport).
 */
data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray
)
