package com.niki914.okai.transport

/**
 * Structured unary response. Status and body are nullable because either may
 * be missing on transport failure; headers default to empty so callers never
 * null-check them. Status must never be flattened into a message string.
 *
 * Design source: pi (earendil-works/pi) provider-retry and codex
 * ApiError::Transport, per kai PRD section 4.7.
 */
data class HttpResponse(
    val statusCode: Int?,
    val headers: Map<String, String>,
    val body: ByteArray?
)
