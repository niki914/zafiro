package com.niki914.s3ss10n.ext.protocol

import com.niki914.s3ss10n.ChatTurn
import com.niki914.s3ss10n.SessionSnapshot
import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.net.HttpRequest
import kotlinx.coroutines.flow.Flow

interface ChatProtocol {
    /**
     * 允许注入自定义的 JSON 编解码器。
     * 协议可以自行决定是否接受该注入，默认实现为 no-op 返回自身。
     */
    fun withCodec(codec: JsonCodec): ChatProtocol = this

    /**
     * 返回该 protocol 从 apiKey 推导出的 auth HTTP headers。
     * apiKey 为空时返回空 map。
     */
    fun useApiKey(apiKey: String): Map<String, String>

    /**
     * tool call delta 的拼接由协议实现自行负责。
     */
    fun buildRequest(
        snapshot: SessionSnapshot,
        history: List<ChatTurn>,
        pendingUserInput: String?
    ): HttpRequest

    fun parseStream(rawSseLines: Flow<String>): Flow<ProtocolEvent>

    fun encodeToolResult(
        callId: String,
        toolName: String,
        resultJson: String
    ): ChatTurn.ToolResult
}
