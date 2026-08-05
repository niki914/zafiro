package com.niki914.okai.protocol

import com.niki914.okai.codec.JsonCodec
import com.niki914.okai.message.ContentBlock
import com.niki914.okai.message.Message
import com.niki914.okai.message.ToolCallOutcome
import com.niki914.okai.transport.HttpRequest
import com.niki914.okai.transport.SseLine
import kotlinx.coroutines.flow.Flow

/**
 * One LLM API dialect: builds requests and parses streams. Transport stays outside,
 * so tests drive the protocol with plain SseLine flows and fake engines.
 * The id is stable and persisted by hosts to restore a session's protocol,
 * e.g. "deepseek", "openai-messages", "openai-completions".
 * Tool results encode from the shared ToolCallOutcome, so the provider's
 * isError flag derives from the outcome and Interrupted or Unknown results
 * encode as error text.
 *
 * Design source: pi (earendil-works/pi) api layer, which splits OpenAI into
 * openai-completions and openai-responses; per kai PRD section 4.3.
 */
interface ChatProtocol {

    val id: String

    fun withCodec(codec: JsonCodec): ChatProtocol

    fun useApiKey(apiKey: String): Map<String, String>

    fun buildRequest(
        snapshot: RequestSnapshot,
        history: List<Message>,
        pendingUserInput: String?
    ): HttpRequest

    fun parseStream(rawSseLines: Flow<SseLine>): Flow<ProtocolEvent>

    fun encodeToolResult(call: ContentBlock.ToolCall, outcome: ToolCallOutcome): Message

    val compat: Compat
}
