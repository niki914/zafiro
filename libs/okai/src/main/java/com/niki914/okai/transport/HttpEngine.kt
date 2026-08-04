package com.niki914.okai.transport

import kotlinx.coroutines.flow.Flow

/**
 * Low-level HTTP transport. Decoupled from LLM protocol so tests can inject a fake engine.
 *
 * Design source: inherited from existing kai (s3ss10n) HttpEngine, restructured so
 * error responses carry structured status instead of string messages.
 */
interface HttpEngine {

    fun stream(request: HttpRequest): Flow<String>

    fun frames(request: HttpRequest): Flow<HttpFrame>

    suspend fun unary(request: HttpRequest): HttpResponse

    fun close()
}
