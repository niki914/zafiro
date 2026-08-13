package com.niki914.okia.protocol

/**
 * 每 Provider 的兼容性事实。loop 在提交历史与重试时查询，不只是构建请求时。
 * Design source: pi OpenAICompletionsCompat，kai PRD §4.3。
 */
interface Compat {

    // 请求体中的 maxTokens 字段名
    val maxTokensField: MaxTokensField

    // 思考格式
    val thinkingFormat: ThinkingFormat

    // 是否支持 reasoning effort
    val supportsReasoningEffort: Boolean

    // 是否要求思考以文本形式出现
    val requiresThinkingAsText: Boolean

    // 是否要求助手消息携带 reasoning content
    val requiresReasoningContentOnAssistantMessages: Boolean

    // 是否要求工具结果后紧跟助手消息
    val requiresAssistantAfterToolResult: Boolean

    // 是否要求工具结果携带名称
    val requiresToolResultName: Boolean

    // 流式响应是否支持 usage
    val supportsUsageInStreaming: Boolean

    // 流式响应是否支持 finish reason
    val supportsFinishReason: Boolean

    // 可重试状态码
    val retryableStatusCodes: Set<Int>

    // 协议层补充的敏感 header 名（默认无）。buildRequest 时填入
    // HttpRequest.sensitiveHeaderNames，用于 toString / 日志脱敏；
    // 通用片段匹配覆盖不到的 Provider 特定名（如 Ocp-Apim-Subscription-Key）在此补充。
    val sensitiveHeaderNames: Set<String> get() = emptySet()
}

/** 请求体中的 maxTokens 字段名。 */
enum class MaxTokensField {
    MaxTokens,
    MaxCompletionTokens
}

/** Provider 如何表达思考。 */
enum class ThinkingFormat {
    DeepSeek,
    OpenAI,
    ChatTemplate
}

/**
 * M0 默认兼容配置：DeepSeek OpenAI 兼容 API。M0 只提供此配置，
 * OpenAI / Anthropic 配置在 M1。
 * Design source: kai PRD §4.3 DeepSeekCompat（M0 范围）。
 */
class DeepSeekCompat : Compat {
    override val maxTokensField: MaxTokensField = MaxTokensField.MaxTokens
    override val thinkingFormat: ThinkingFormat = ThinkingFormat.DeepSeek
    override val supportsReasoningEffort: Boolean = true
    override val requiresThinkingAsText: Boolean = false
    override val requiresReasoningContentOnAssistantMessages: Boolean = true
    override val requiresAssistantAfterToolResult: Boolean = false
    override val requiresToolResultName: Boolean = false
    override val supportsUsageInStreaming: Boolean = true
    override val supportsFinishReason: Boolean = true
    override val retryableStatusCodes: Set<Int> = setOf(408, 409, 429, 500, 502, 503, 504)
}
