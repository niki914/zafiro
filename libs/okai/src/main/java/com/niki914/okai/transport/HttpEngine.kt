package com.niki914.okai.transport

/**
 * Low-level HTTP transport. Decoupled from LLM protocol so tests can inject a fake engine.
 * Streaming returns head plus body lines; errors stay structured and never
 * flatten status into a message string. Cancellation contract: the loop
 * collects the lines flow inside the turn coroutine, and cancelling that
 * collection closes the underlying request, so a stopped turn never leaks
 * sockets or connections.
 *
 * Design source: pi (earendil-works/pi) provider-retry and codex
 * TransportError::Http, per kai PRD sections 4.4 and 4.7.
 */
interface HttpEngine {

    fun stream(request: HttpRequest): StreamResponse

    suspend fun unary(request: HttpRequest): HttpResponse

    fun close()
}
