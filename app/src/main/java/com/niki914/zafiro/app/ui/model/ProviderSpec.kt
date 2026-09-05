package com.niki914.zafiro.app.ui.model

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.niki914.zafiro.app.R

sealed interface ProviderSpec {
    val id: String
    val brandName: String
    val officialEndpoint: String
    val exampleModelId: String
    val allowsCustomEndpointInNewConfig: Boolean

    /** 新建配置时预填的协议（LlmProtocol.wireId）。 */
    val defaultProtocol: String

    @get:DrawableRes
    val iconRes: Int
    val tintIcon: Boolean
    val visualTokens: ProviderVisualTokens
}

data class ProviderVisualTokens(
    val button: ProviderButtonTokens,
)

data class ProviderButtonTokens(
    @ColorRes val darkContainerColorRes: Int? = null,
    @ColorRes val lightContainerColorRes: Int? = null,
    @ColorRes val darkContentColorRes: Int? = null,
    @ColorRes val lightContentColorRes: Int? = null,
    /** 非空时优先于 contentColor 作为图标 tint 色（如 OpenRouter 品牌色图标 + 黑白文字）。 */
    @ColorRes val darkIconColorRes: Int? = null,
    @ColorRes val lightIconColorRes: Int? = null,
)

object ProviderSpecs {
    val default: ProviderSpec = DeepSeekSpec

    val all: List<ProviderSpec> = listOf(
        AnthropicSpec,
        OpenAiSpec,
        OpenRouterSpec,
        DeepSeekSpec,
        GoogleSpec,
        KimiSpec,
        BailianSpec,
        SiliconFlowSpec,
        OpenCodeSpec,
        CommandCodeSpec,
    )

    fun find(providerId: String?): ProviderSpec {
        return all.firstOrNull { it.id == providerId } ?: default
    }
}

private data object DeepSeekSpec : ProviderSpec {
    override val id: String = "deepseek"
    override val brandName: String = "DeepSeek"
    override val officialEndpoint: String = "https://api.deepseek.com/responses"
    override val exampleModelId: String = "deepseek-v4-pro"
    override val allowsCustomEndpointInNewConfig: Boolean = false

    // DeepSeek 官方 /responses 网关兼容 OpenAI Responses 协议（实测可用，2026-02）
    override val defaultProtocol: String = "openai-responses"
    override val iconRes: Int = R.drawable.deepseek
    override val tintIcon: Boolean = true
    override val visualTokens: ProviderVisualTokens = ProviderVisualTokens(
        button = ProviderButtonTokens(
            darkContainerColorRes = R.color.provider_deepseek_button_dark_container,
            lightContainerColorRes = R.color.provider_deepseek_button_light_container,
            darkContentColorRes = R.color.provider_deepseek_button_dark_content,
            lightContentColorRes = R.color.provider_deepseek_button_light_content,
        ),
    )
}

private data object OpenAiSpec : ProviderSpec {
    override val id: String = "openai"
    override val brandName: String = "OpenAI"
    override val officialEndpoint: String = "https://api.openai.com/v1/responses"
    override val exampleModelId: String = "gpt-5.4"
    override val allowsCustomEndpointInNewConfig: Boolean = true
    override val defaultProtocol: String = "openai-responses"
    override val iconRes: Int = R.drawable.openai
    override val tintIcon: Boolean = true
    override val visualTokens: ProviderVisualTokens = ProviderVisualTokens(
        button = ProviderButtonTokens(
            darkContainerColorRes = R.color.provider_openai_button_dark_container,
            lightContainerColorRes = R.color.provider_openai_button_light_container,
            darkContentColorRes = R.color.provider_openai_button_dark_content,
            lightContentColorRes = R.color.provider_openai_button_light_content,
        ),
    )
}

private data object AnthropicSpec : ProviderSpec {
    override val id: String = "anthropic"
    override val brandName: String = "Anthropic"
    override val officialEndpoint: String = "https://api.anthropic.com/v1/messages"
    override val exampleModelId: String = "claude-sonnet-4-6"
    override val allowsCustomEndpointInNewConfig: Boolean = true
    override val defaultProtocol: String = "anthropic-messages"
    override val iconRes: Int = R.drawable.anthropic
    override val tintIcon: Boolean = true
    override val visualTokens: ProviderVisualTokens = ProviderVisualTokens(
        button = ProviderButtonTokens(
            darkContainerColorRes = R.color.provider_anthropic_button_dark_container,
            lightContainerColorRes = R.color.provider_anthropic_button_light_container,
            darkContentColorRes = R.color.provider_anthropic_button_dark_content,
            lightContentColorRes = R.color.provider_anthropic_button_light_content,
        ),
    )
}

private data object GoogleSpec : ProviderSpec {
    override val id: String = "google"
    override val brandName: String = "Google"
    override val officialEndpoint: String =
        "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    override val exampleModelId: String = "gemini-3.5-flash"
    override val allowsCustomEndpointInNewConfig: Boolean = true

    // Google 品牌走官方 OpenAI 兼容端点，如实展示 openai 协议（不提供 gemini-native）
    override val defaultProtocol: String = "openai-chat-completions"
    override val iconRes: Int = R.drawable.gemini
    override val tintIcon: Boolean = false
    override val visualTokens: ProviderVisualTokens = ProviderVisualTokens(
        button = ProviderButtonTokens(),
    )
}

private data object BailianSpec : ProviderSpec {
    override val id: String = "bailian"
    override val brandName: String = "阿里百炼"
    override val officialEndpoint: String = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    override val exampleModelId: String = "qwen-max"
    override val allowsCustomEndpointInNewConfig: Boolean = false
    override val defaultProtocol: String = "openai-chat-completions"
    override val iconRes: Int = R.drawable.bailian
    override val tintIcon: Boolean = false
    override val visualTokens: ProviderVisualTokens = ProviderVisualTokens(
        button = ProviderButtonTokens(),
    )
}

private data object KimiSpec : ProviderSpec {
    override val id: String = "kimi"
    override val brandName: String = "Kimi"
    override val officialEndpoint: String = "https://api.moonshot.cn/v1/chat/completions"
    override val exampleModelId: String = "kimi-latest"
    override val allowsCustomEndpointInNewConfig: Boolean = false
    override val defaultProtocol: String = "openai-chat-completions"
    override val iconRes: Int = R.drawable.kimi
    override val tintIcon: Boolean = false
    override val visualTokens: ProviderVisualTokens = ProviderVisualTokens(
        button = ProviderButtonTokens(),
    )
}

private data object SiliconFlowSpec : ProviderSpec {
    override val id: String = "siliconflow"
    override val brandName: String = "硅基流动"
    override val officialEndpoint: String = "https://api.siliconflow.cn/v1/chat/completions"
    override val exampleModelId: String = "Qwen3-72B"
    override val allowsCustomEndpointInNewConfig: Boolean = false
    override val defaultProtocol: String = "openai-chat-completions"
    override val iconRes: Int = R.drawable.siliconflow
    override val tintIcon: Boolean = true
    override val visualTokens: ProviderVisualTokens = ProviderVisualTokens(
        button = ProviderButtonTokens(
            darkContainerColorRes = R.color.provider_siliconflow_button_dark_container,
            lightContainerColorRes = R.color.provider_siliconflow_button_light_container,
            darkContentColorRes = R.color.provider_siliconflow_button_dark_content,
            lightContentColorRes = R.color.provider_siliconflow_button_light_content,
        ),
    )
}

private data object OpenRouterSpec : ProviderSpec {
    override val id: String = "openrouter"
    override val brandName: String = "OpenRouter"
    override val officialEndpoint: String = "https://openrouter.ai/api/v1/chat/completions"
    override val exampleModelId: String = "auto"
    override val allowsCustomEndpointInNewConfig: Boolean = false
    override val defaultProtocol: String = "openai-chat-completions"
    override val iconRes: Int = R.drawable.openrouter
    override val tintIcon: Boolean = true
    override val visualTokens: ProviderVisualTokens = ProviderVisualTokens(
        button = ProviderButtonTokens(
            darkContainerColorRes = R.color.provider_openrouter_button_dark_container,
            lightContainerColorRes = R.color.provider_openrouter_button_light_container,
            darkContentColorRes = R.color.provider_openrouter_button_dark_content,
            lightContentColorRes = R.color.provider_openrouter_button_light_content,
            darkIconColorRes = R.color.provider_openrouter_icon_dark,
            lightIconColorRes = R.color.provider_openrouter_icon_light,
        ),
    )
}

private data object CommandCodeSpec : ProviderSpec {
    override val id: String = "command-code"
    override val brandName: String = "Command Code"
    override val officialEndpoint: String = "https://api.commandcode.ai/provider/v1/chat/completions"
    override val exampleModelId: String = "z-ai/glm-5.3-flash"
    override val allowsCustomEndpointInNewConfig: Boolean = false
    override val defaultProtocol: String = "openai-chat-completions"
    override val iconRes: Int = R.drawable.command_code
    override val tintIcon: Boolean = true
    override val visualTokens: ProviderVisualTokens = ProviderVisualTokens(
        button = ProviderButtonTokens(
            darkContainerColorRes = R.color.provider_command_code_button_dark_container,
            lightContainerColorRes = R.color.provider_command_code_button_light_container,
            darkContentColorRes = R.color.provider_command_code_button_dark_content,
            lightContentColorRes = R.color.provider_command_code_button_light_content,
        ),
    )
}

private data object OpenCodeSpec : ProviderSpec {
    override val id: String = "opencode"
    override val brandName: String = "OpenCode"
    override val officialEndpoint: String = "https://opencode.ai/zen/go/v1/chat/completions"
    override val exampleModelId: String = "deepseek-v4-flash"
    override val allowsCustomEndpointInNewConfig: Boolean = false
    override val defaultProtocol: String = "openai-chat-completions"
    override val iconRes: Int = R.drawable.opencode
    override val tintIcon: Boolean = true
    override val visualTokens: ProviderVisualTokens = ProviderVisualTokens(
        button = ProviderButtonTokens(
            darkContainerColorRes = R.color.provider_opencode_button_dark_container,
            lightContainerColorRes = R.color.provider_opencode_button_light_container,
            darkContentColorRes = R.color.provider_opencode_button_dark_content,
            lightContentColorRes = R.color.provider_opencode_button_light_content,
        ),
    )
}
