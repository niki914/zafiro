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
