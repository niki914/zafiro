package com.niki914.zafiro.app.ui.model

import com.niki914.zafiro.settings.model.LlmProtocol

/**
 * 端点与协议匹配校验（纯函数，无 Android 依赖）。
 * 协议是绝对权威：给定协议，检查 endpoint 后缀是否匹配；
 * 更新时保留原端点的版本段（/v1、/v2、/v1beta/openai 等），只替换末段 API 路径。
 */
internal object EndpointInference {

    /** 末段 API 路径（不含版本段），按更具体的在前排列。 */
    private val KNOWN_API_TAILS = listOf("/chat/completions", "/responses", "/messages")

    private val VERSION_SEGMENT = Regex("""^v\d+[a-z]*$""")

    private fun apiTailOf(protocol: LlmProtocol): String = when (protocol) {
        LlmProtocol.OpenAiChatCompletions, LlmProtocol.DeepSeek -> "/chat/completions"
        LlmProtocol.OpenAiResponses -> "/responses"
        LlmProtocol.AnthropicMessages -> "/messages"
    }

    /** 检查 endpoint 后缀是否匹配给定协议。 */
    fun endpointMatchesProtocol(endpoint: String, protocol: LlmProtocol): Boolean {
        val e = endpoint.trim().trimEnd('/')
        if (e.isBlank()) return false
        return when (protocol) {
            LlmProtocol.OpenAiChatCompletions, LlmProtocol.DeepSeek ->
                e.endsWith("/chat/completions")
            LlmProtocol.OpenAiResponses -> e.endsWith("/responses")
            LlmProtocol.AnthropicMessages -> e.endsWith("/v1/messages")
        }
    }

    /**
     * 替换端点末段 API 路径，保留 host、前缀路径与版本段：
     * `.../compatible-mode/v1/chat/completions` → messages → `.../compatible-mode/v1/messages`。
     * 原端点无版本段时：DeepSeek 等无 /v1 形态保持裸追加；
     * Anthropic wire 格式硬要求 /v1 前缀，无版本段时补 /v1。
     * 空白端点原样返回，不生成无 host 的脏 URL。
     */
    fun replaceSuffix(currentEndpoint: String, protocol: LlmProtocol): String {
        val e = currentEndpoint.trim().trimEnd('/')
        if (e.isBlank()) return e
        // 只剥末段 API 路径；/v1/messages 剥成 前缀/v1 + api=/messages，
        // /v1 随后作为版本段被保留，避免 v1 双写
        val remainder = KNOWN_API_TAILS.firstOrNull { e.endsWith(it) }
            ?.let { e.removeSuffix(it) }
            ?: e
        val version = remainder.substringAfterLast('/').takeIf { it.matches(VERSION_SEGMENT) }
        val base = version?.let { remainder.removeSuffix("/$it") } ?: remainder
        return when {
            version != null -> "$base/$version${apiTailOf(protocol)}"
            protocol == LlmProtocol.AnthropicMessages -> "$base/v1/messages"
            else -> base + apiTailOf(protocol)
        }
    }

    /** endpoint 是否为任一预置品牌的官方端点形态（预置端点切协议时静默更新，不弹窗）。
     *  按前缀匹配：host + 品牌路径段一致即算预置，官方端点经静默更新派生的
     *  其他协议形态（如 .../provider/v1/responses）同样命中，避免行为漂移。 */
    fun isPresetEndpoint(endpoint: String): Boolean {
        val e = endpoint.trim().trimEnd('/')
        if (e.isBlank()) return false
        return ProviderSpecs.all.any { spec ->
            val prefix = presetPrefixOf(spec.officialEndpoint)
            prefix != null && e.startsWith(prefix)
        }
    }

    /** 官方端点剥掉末段 API 路径与版本段后的品牌前缀，如 https://api.commandcode.ai/provider。 */
    private fun presetPrefixOf(officialEndpoint: String): String? {
        val e = officialEndpoint.trim().trimEnd('/')
        val remainder = KNOWN_API_TAILS.firstOrNull { e.endsWith(it) }
            ?.let { e.removeSuffix(it) }
            ?: return null
        val version = remainder.substringAfterLast('/')
        return if (version.matches(VERSION_SEGMENT)) {
            remainder.removeSuffix("/$version")
        } else {
            remainder
        }
    }
}
