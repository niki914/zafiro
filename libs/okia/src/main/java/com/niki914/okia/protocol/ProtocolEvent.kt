package com.niki914.okia.protocol

import com.niki914.okia.message.Usage

/**
 * ChatProtocol.parseStream 与 loop 之间的协议无关流事件。
 * loop 把它们映射到库级 TurnEvent（两层映射）。错误携带 cause，
 * 分类在错误层完成。
 * Design source: pi 流事件，kai PRD §4.3；okia 骨架对照基线。
 */
sealed interface ProtocolEvent {

    /** 文本 delta。 */
    data class TextDelta(val text: String) : ProtocolEvent

    /** 思考 delta。 */
    data class ThinkingDelta(val text: String) : ProtocolEvent

    /** 思考签名，在思考 delta 之后到达。 */
    data class ThinkingSignature(val signature: String) : ProtocolEvent

    /**
     * 工具调用开始。流式 API 随后发出 ToolCallDelta；
     * 完整响应 API 直接跳到 ToolCallReady。
     */
    data class ToolCallStarted(
        val callId: String,
        val toolName: String
    ) : ProtocolEvent

    /** ToolCallStarted 发起的调用的参数 delta。 */
    data class ToolCallDelta(
        val callId: String,
        val toolName: String,
        val delta: String
    ) : ProtocolEvent

    /** 携带最终参数 JSON 的完整工具调用。 */
    data class ToolCallReady(
        val callId: String,
        val toolName: String,
        val argumentsJson: String
    ) : ProtocolEvent

    /** 流正常结束。usage / responseModel 可能缺失，保持可空。 */
    data class Completed(
        val usage: Usage? = null,
        val responseModel: String? = null
    ) : ProtocolEvent

    /** 流失败。 */
    data class Error(val cause: Throwable) : ProtocolEvent
}
