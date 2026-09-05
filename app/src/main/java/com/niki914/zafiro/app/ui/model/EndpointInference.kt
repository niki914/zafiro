package com.niki914.zafiro.app.ui.model

import com.niki914.zafiro.settings.model.LlmProtocol

/**
 * 端点与协议匹配校验（纯函数，无 Android 依赖）。
 * 协议是绝对权威：给定协议，检查 endpoint 后缀是否匹配；
 * 更新时只替换末尾的已知路径段，保留 host 与前缀。
 */
internal object EndpointInference {

    private val KNOWN_SUFFIXES = listOf("/chat/completions", "/responses", "/v1/messages")

    private fun suffixOf(protocol: LlmProtocol): String = when (protocol) {
        LlmProtocol.OpenAiChatCompletions, LlmProtocol.DeepSeek -> "/chat/completions"
        LlmProtocol.OpenAiResponses -> "/responses"
        LlmProtocol.AnthropicMessages -> "/v1/messages"
    }

    /** 检查 endpoint 后缀是否匹配给定协议。 */
    fun endpointMatchesProtocol(endpoint: String, protocol: LlmProtocol): Boolean {
        val e = endpoint.trim().trimEnd('/')
        if (e.isBlank()) return false
        return e.endsWith(suffixOf(protocol))
    }

    /**
     * 只替换端点末尾的已知路径段，保留 host 与前缀路径；
     * 无已知后缀时直接追加目标后缀（自定义端点兜底）。
     */
    fun replaceSuffix(currentEndpoint: String, protocol: LlmProtocol): String {
        val e = currentEndpoint.trim().trimEnd('/')
        val base = KNOWN_SUFFIXES.firstOrNull { e.endsWith(it) }
            ?.let { e.removeSuffix(it) }
            ?: e
        return base + suffixOf(protocol)
    }

    /** endpoint 是否为任一预置品牌的官方端点（预置端点切协议时静默更新，不弹窗）。 */
    fun isPresetEndpoint(endpoint: String): Boolean {
        val e = endpoint.trim().trimEnd('/')
        if (e.isBlank()) return false
        return ProviderSpecs.all.any { it.officialEndpoint.trimEnd('/') == e }
    }
}
