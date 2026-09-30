package com.niki914.zafiro.settings.model

/**
 * LLM API 协议（okia ChatProtocol 方言的稳定 id）。
 * wireId 即存储值即 UI 显示文案（可读性优先，不做显示层映射）。
 * 与 provider 品牌（ProviderSpec）解耦：品牌只决定端点/示例模型等默认值，
 * 协议由用户显式选择；新建配置时按品牌预填 default。
 */
enum class LlmProtocol(val wireId: String) {
    DeepSeek("deepseek"),
    OpenAiChatCompletions("openai-chat-completions"),

    /** Google Gemini 官方 OpenAI 兼容端点。请求/流形态与 openai-chat-completions 同壳，
     *  差异只有工具调用签名回带（assistant 历史的 tool_calls 必须带
     *  extra_content.google.thought_signature，Gemini 3 思维内工具调用缺失 → 400）。
     *  按协议而非品牌区分：协议由用户显式选择，品牌只决定默认值。 */
    GoogleOpenAi("google-openai"),
    OpenAiResponses("openai-responses"),
    AnthropicMessages("anthropic-messages");

    companion object {
        val Default = OpenAiResponses

        fun fromWire(value: String?): LlmProtocol {
            val trimmed = value?.trim().orEmpty()
            return entries.firstOrNull { it.wireId == trimmed } ?: Default
        }
    }
}
