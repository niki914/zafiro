package com.niki914.okai.transport

import kotlinx.coroutines.flow.Flow

/**
 * Streaming exchange. The response head arrives before body lines, so status
 * and headers are read first for retry decisions; either may be absent on
 * transport failure, hence nullable status. Null SseLine values are keep-alive
 * activity and still count as network life for idle detection.
 *
 * Design source: pi (earendil-works/pi openai-completions onResponse head
 * callback) and codex TransportError::Http, per kai PRD sections 4.4 and 4.7.
 */
data class StreamResponse(
    val statusCode: Int?,
    val headers: Map<String, String>,
    val lines: Flow<SseLine>
)
