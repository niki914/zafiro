package com.niki914.okia.protocol

import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.SseLine
import kotlinx.coroutines.flow.Flow

/**
 * 协议无关数据 → Provider 序列化的边界，是 ChatProtocol 的适配壳。
 * 上层（loop / Hooks / UI）只接触自定 dataclass（Message / ContentBlock /
 * RequestSnapshot），loop 不接触 ChatProtocol；数据到这里及以下
 * （ChatProtocol.buildRequest / encodeToolResult）才按 Provider 序列化。
 * 连接方式：ProtocolCompatMapper.from(protocol) 工厂（open(protocol) 内部
 * 经此构造）；host 用抽象 dataclass 实例化、不碰网络 raw data，切换协议无影响。
 * Design source: okia 白板架构图 ProtocolCompatMapper 节点，PRD §5.8。
 */
interface ProtocolCompatMapper {

    // 协议无关数据 → Provider 请求（beforeSerialization 时序在此层前后）。
    // history 包含当前输入，无独立的 pendingUserInput。
    suspend fun buildRequest(
        snapshot: RequestSnapshot,
        history: List<Message>
    ): HttpRequest

    // 工具结果编码（encodeToolResult 边界）
    suspend fun encodeToolResult(call: ContentBlock.ToolCall, outcome: ToolCallOutcome): Message

    // 原始 SSE 流 → 协议无关中间事件
    fun parseStream(rawSseLines: Flow<SseLine>): Flow<ProtocolEvent>

    // 认证头推导
    fun useApiKey(apiKey: String): Map<String, String>

    // 协议兼容性事实（每 Provider 的 retryable status、工具结果后是否需要
    // 助手消息等）；loop 在历史拼装与重试时查询
    val compat: Compat

    companion object {

        // 从协议实例构造适配壳：协议无关层访问 Provider 的唯一入口。
        // open(protocol) 内部经此构造；open(dependencies) 直接注入实例。
        // 纯委托：协议实现持有全部序列化逻辑，壳不加工数据。
        fun from(protocol: ChatProtocol): ProtocolCompatMapper = object : ProtocolCompatMapper {
            override suspend fun buildRequest(
                snapshot: RequestSnapshot,
                history: List<Message>
            ): HttpRequest = protocol.buildRequest(snapshot, history)

            override suspend fun encodeToolResult(
                call: ContentBlock.ToolCall,
                outcome: ToolCallOutcome
            ): Message = protocol.encodeToolResult(call, outcome)

            override fun parseStream(rawSseLines: Flow<SseLine>): Flow<ProtocolEvent> =
                protocol.parseStream(rawSseLines)

            override fun useApiKey(apiKey: String): Map<String, String> =
                protocol.useApiKey(apiKey)

            override val compat: Compat get() = protocol.compat
        }
    }
}
