package com.niki914.okai.protocol

import com.niki914.okai.codec.JsonCodec
import com.niki914.okai.message.Message
import com.niki914.okai.transport.HttpRequest
import com.niki914.okai.transport.SseLine
import kotlinx.coroutines.flow.Flow

/**
 * One LLM API dialect: builds requests and parses streams. Transport stays outside,
 * so tests drive the protocol with plain SseLine flows and fake engines.
 *
 * Design source: existing kai (s3ss10n) ChatProtocol, per kai PRD section 4.3.
 */
interface ChatProtocol {

    fun withCodec(codec: JsonCodec): ChatProtocol

    fun useApiKey(apiKey: String): Map<String, String>

    fun buildRequest(
        snapshot: RequestSnapshot,
        history: List<Message>,
        pendingUserInput: String?
    ): HttpRequest

    fun parseStream(rawSseLines: Flow<SseLine>): Flow<ProtocolEvent>

    fun encodeToolResult(callId: String, toolName: String, resultJson: String): Message

    val compat: Compat
}
