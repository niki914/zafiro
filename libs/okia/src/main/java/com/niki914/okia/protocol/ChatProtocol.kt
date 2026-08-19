package com.niki914.okia.protocol

import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.SseLine
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * 一种 LLM API 方言：构建请求并解析流。传输在库外，测试用 SseLine 流
 * 和 fake engine 驱动协议。
 * 协议 id 稳定但不进入会话数据：host 恢复时重新 open<P>() 提供 Provider。
 * id 与 defaultEndpoint 都是从 compat 取的（协议类不各自持有，withCodec
 * 后身份不变）。工具结果从共享 ToolCallOutcome 编码，isError 由 outcome 派生。
 * 内置协议：OpenAIChatCompletionProtocol（含 DeepSeek compat 形态）、
 * OpenAIResponsesProtocol、AnthropicMessagesProtocol、GeminiProtocol。
 * Design source: pi api 层（openai-completions / openai-responses 拆分），
 * kai PRD §4.3；okia 骨架对照基线。
 */
interface ChatProtocol {

    // 稳定协议 id（如 "deepseek"），从 compat 取
    val id: String

    // 协议自带的默认端点（如 DeepSeek 官方 API），从 compat 取。调用方在
    // config.endpoint 显式设置时覆盖；两者皆空时 open() fail-fast（方案 A，
    // §8.17）。null = 协议不自带默认端点，调用方必须提供 endpoint。
    // Gemini 端点含 {model} 占位符，buildRequest 时替换为 snapshot.model。
    val defaultEndpoint: String?

    // 注入 JSON 编解码器（kotlinx.serialization 标准替代 JsonCodec）
    fun withCodec(codec: Json): ChatProtocol

    // apiKey → 认证头；apiKey 为空时返回空 map
    fun useApiKey(apiKey: String): Map<String, String>

    // 协议无关数据 → Provider 请求。history 包含当前输入（send 已先提交
    // User 消息），不存在独立的 pendingUserInput。
    fun buildRequest(
        snapshot: RequestSnapshot,
        history: List<Message>
    ): HttpRequest

    // 原始 SSE 流 → 协议无关中间事件
    fun parseStream(rawSseLines: Flow<SseLine>): Flow<ProtocolEvent>

    // 工具结果编码（isError 由 outcome 派生；Interrupted / Unknown 编码为错误文本）
    fun encodeToolResult(call: ContentBlock.ToolCall, outcome: ToolCallOutcome): Message

    // 协议兼容性事实
    val compat: Compat
}
