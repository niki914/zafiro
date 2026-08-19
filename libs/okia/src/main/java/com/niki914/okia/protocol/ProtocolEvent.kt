package com.niki914.okia.protocol

import com.niki914.okia.message.StopReason
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

    /** 协议私有的不可解释数据（如 OpenAI reasoning item envelope），loop 只
     *  负责挂载到 Thinking 块，不解析内容；由发起的协议负责封装与回放。 */
    data class ThinkingOpaquePayload(val payload: String) : ProtocolEvent

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

    /** 携带最终参数 JSON 的完整工具调用。signature：Gemini 3 思维内工具调用的
     *  thoughtSignature（须原样回带，见 GeminiProtocol.assistantParts）。 */
    data class ToolCallReady(
        val callId: String,
        val toolName: String,
        val argumentsJson: String,
        val signature: String? = null
    ) : ProtocolEvent

    /** 流正常结束。usage / responseModel 可能缺失，保持可空。
     *  stopReason 为协议层映射后的消息级结束原因（Stop / Length / Error / Aborted），
     *  Provider 不支持 finish reason 时为 null（loop 默认按 Stop 处理）。 */
    data class Completed(
        val usage: Usage? = null,
        val responseModel: String? = null,
        val stopReason: StopReason? = null
    ) : ProtocolEvent

    /** 流失败。retryable：协议层判定的临时错误（如 Anthropic overloaded_error /
     *  rate_limit_error，HTTP 200 后仍可能经 SSE error event 到达）——loop 据此
     *  选择可重试（Transport）或不可重试（Parse）分类，配置的重试策略才对这些
     *  临时错误生效（问题 2）。 */
    data class Error(
        val cause: Throwable,
        val retryable: Boolean = false
    ) : ProtocolEvent
}
