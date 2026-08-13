package com.niki914.okia.transport

import kotlinx.coroutines.flow.Flow

/**
 * 流式交换。响应头先于 body 行到达，status / headers 先读用于重试决策；
 * 传输失败时两者可能缺失，status 可空。null SseLine 是 keep-alive 活动，
 * 仍计入 idle 检测的网络生命。
 * Design source: pi openai-completions onResponse 头回调、codex
 * TransportError::Http，kai PRD §4.4 / §4.7；okia 骨架对照基线。
 */
data class StreamResponse(
    val statusCode: Int?,
    val headers: Map<String, String>,
    val lines: Flow<SseLine>
) {

    // 响应 headers 脱敏；lines 是冷流，只输出类型，不消费
    override fun toString(): String =
        "StreamResponse(statusCode=$statusCode, headers=${redactHeaders(headers)}, " +
            "lines=Flow<SseLine>)"
}
