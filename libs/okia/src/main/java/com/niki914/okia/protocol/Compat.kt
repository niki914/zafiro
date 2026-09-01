package com.niki914.okia.protocol

/**
 * 每 Provider 的兼容性事实。loop 在提交历史与重试时查询，不只是构建请求时。
 * 身份字段（id / defaultEndpoint）也在此：端点是 provider 固有事实（D43，
 * 同 useApiKey / compat 一类），id 是同一身份事实的另一半。
 * Design source: pi OpenAICompletionsCompat / AnthropicMessagesCompat /
 * GoogleCompat，kai PRD §4.3。
 */
interface Compat {

    // 稳定协议 id（如 "deepseek"）。协议类从 compat 取 id，保证 withCodec 后身份不变。
    val id: String

    // 协议自带的默认端点（null = 协议不自带，调用方必须提供 endpoint）。
    // 调用方在 config.endpoint 显式设置时覆盖（方案 A，§8.17）。
    // 端点含模型占位符 {model} 时（Gemini），协议在 buildRequest 时替换为 snapshot.model。
    val defaultEndpoint: String?

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
    /** reasoning_content 字段族：思考走 delta.reasoning_content 明文传输，
     *  assistant 历史可回带（DeepSeek 属此格式）。 */
    ReasoningContent,

    /** reasoning_effort 参数族：思考内容默认加密不可回放，历史转文本。 */
    ReasoningEffort,

    /** 模板参数驱动族（chat_template_kwargs.enable_thinking 形态）。 */
    ChatTemplate,

    /** thinking 块族：content 内 thinking 块 + signature，历史原样回带（签名必填）。 */
    ThinkingBlocks,

    /** thought part 族：part 内 thought:true 标记 + thoughtSignature，历史回带 thought 块。 */
    ThoughtParts
}

/**
 * M0 默认兼容配置：DeepSeek OpenAI 兼容 API。
 * DeepSeek 私有语义：max_tokens 字段、reasoning_content 思考、
 * assistant 历史必须带 reasoning_content（可为空串）。
 * Design source: kai PRD §4.3 DeepSeekCompat（M0 范围）。
 */
class DeepSeekCompat : Compat {
    override val id: String = "deepseek"
    override val defaultEndpoint: String? = "https://api.deepseek.com/chat/completions"
    override val maxTokensField: MaxTokensField = MaxTokensField.MaxTokens
    override val thinkingFormat: ThinkingFormat = ThinkingFormat.ReasoningContent
    override val supportsReasoningEffort: Boolean = true
    override val requiresThinkingAsText: Boolean = false
    override val requiresReasoningContentOnAssistantMessages: Boolean = true
    override val requiresAssistantAfterToolResult: Boolean = false
    override val requiresToolResultName: Boolean = false
    override val supportsUsageInStreaming: Boolean = true
    override val supportsFinishReason: Boolean = true

    // 约定俗成集合（G4 裁决，对照 pi provider-retry / codex retry）：
    // 408 / 409 / 429 + 全部 5xx。其余 4xx（400/401/403/402/404 等）不可重试。
    override val retryableStatusCodes: Set<Int> = setOf(408, 409, 429) + (500..599).toSet()
}

/**
 * OpenAI 官方 Chat Completions 兼容配置。
 * 与 DeepSeek 的差异：max_completion_tokens 字段、thinking 走 reasoning_effort
 * 且内容不可原样回放（加密，历史转文本）、assistant 不接受 reasoning_content 字段。
 */
class OpenAIChatCompletionCompat : Compat {
    override val id: String = "openai"
    override val defaultEndpoint: String? = "https://api.openai.com/v1/chat/completions"
    override val maxTokensField: MaxTokensField = MaxTokensField.MaxCompletionTokens
    override val thinkingFormat: ThinkingFormat = ThinkingFormat.ReasoningEffort
    override val supportsReasoningEffort: Boolean = true
    override val requiresThinkingAsText: Boolean = true
    override val requiresReasoningContentOnAssistantMessages: Boolean = false
    override val requiresAssistantAfterToolResult: Boolean = false
    override val requiresToolResultName: Boolean = false
    override val supportsUsageInStreaming: Boolean = true
    override val supportsFinishReason: Boolean = true
    override val retryableStatusCodes: Set<Int> = setOf(408, 409, 429) + (500..599).toSet()
}

/** OpenAI Responses API（Messages 形态）兼容配置。 */
class OpenAIResponsesCompat : Compat {
    override val id: String = "openai-responses"
    override val defaultEndpoint: String? = "https://api.openai.com/v1/responses"

    // Responses 请求体字段为 max_output_tokens，语义 = 输出 token 上限
    override val maxTokensField: MaxTokensField = MaxTokensField.MaxCompletionTokens
    override val thinkingFormat: ThinkingFormat = ThinkingFormat.ReasoningEffort
    override val supportsReasoningEffort: Boolean = true
    override val requiresThinkingAsText: Boolean = true
    override val requiresReasoningContentOnAssistantMessages: Boolean = false
    override val requiresAssistantAfterToolResult: Boolean = false
    override val requiresToolResultName: Boolean = false
    override val supportsUsageInStreaming: Boolean = true
    override val supportsFinishReason: Boolean = true
    override val retryableStatusCodes: Set<Int> = setOf(408, 409, 429) + (500..599).toSet()
}

/**
 * Anthropic Messages API 兼容配置。
 * 约束：max_tokens 必填、system 顶层字段、user/assistant 严格交替
 * （工具结果并入 user 消息的 tool_result 块）、thinking 块带 signature 回带。
 */
class AnthropicMessagesCompat : Compat {
    override val id: String = "anthropic"
    override val defaultEndpoint: String? = "https://api.anthropic.com/v1/messages"
    override val maxTokensField: MaxTokensField = MaxTokensField.MaxTokens
    override val thinkingFormat: ThinkingFormat = ThinkingFormat.ThinkingBlocks
    override val supportsReasoningEffort: Boolean = false
    override val requiresThinkingAsText: Boolean = false
    override val requiresReasoningContentOnAssistantMessages: Boolean = false
    override val requiresAssistantAfterToolResult: Boolean = true
    override val requiresToolResultName: Boolean = false
    override val supportsUsageInStreaming: Boolean = true
    override val supportsFinishReason: Boolean = true
    override val retryableStatusCodes: Set<Int> = setOf(408, 409, 429) + (500..599).toSet()
}

/** Google Gemini（Generative Language API）兼容配置。 */
class GeminiCompat : Compat {
    override val id: String = "gemini"

    // 端点含 {model} 占位符：Gemini 的模型在 URL 路径中，协议 buildRequest 时替换
    override val defaultEndpoint: String? =
        "https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse"
    override val maxTokensField: MaxTokensField = MaxTokensField.MaxTokens
    override val thinkingFormat: ThinkingFormat = ThinkingFormat.ThoughtParts
    override val supportsReasoningEffort: Boolean = false
    override val requiresThinkingAsText: Boolean = false
    override val requiresReasoningContentOnAssistantMessages: Boolean = false
    override val requiresAssistantAfterToolResult: Boolean = false
    override val requiresToolResultName: Boolean = true
    override val supportsUsageInStreaming: Boolean = true
    override val supportsFinishReason: Boolean = true
    override val retryableStatusCodes: Set<Int> = setOf(408, 409, 429) + (500..599).toSet()
}