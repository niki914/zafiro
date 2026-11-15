package com.niki914.zafiro.repo

import com.niki914.zafiro.repo.SettingsJsonCodecUtils.array
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.boolean
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.long
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.obj
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.orEmptyObjects
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.stringNonBlankOrNull
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.stringValues
import com.niki914.zafiro.settings.model.RuntimeEnvContextConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一份 Saved Configuration（完整可切换的 LLM 接入配置）。
 * 包含可选的独立 prompt（留空则回退至 [LlmConfigsDocument.prompt] 全局提示词）；
 * proxy 属于网络接入层，随配置走。
 */
data class SavedLlmConfig(
    val id: String,
    val name: String,
    val provider: String,
    val endpoint: String,
    val apiKey: String,
    val model: String,
    /** LlmProtocol.wireId；空串 = 未设置，运行时回落默认协议。 */
    val protocol: String,
    /** 视觉模型开关（图片输入支持），默认关。 */
    val supportsImages: Boolean = false,
    /** 独立 system prompt；空串 = 未设置，运行时回落全局提示词。 */
    val prompt: String = "",
    val proxy: String,
    /** ThinkingLevel.wireValue；空串 = 不发送思考字段（Provider 默认行为）。 */
    val thinkingLevel: String = "",
    /** 单次输出上限（max_output_tokens / max_tokens）；落盘原始文本（支持 1M/200k）。
     *  空串/旧格式的 0/缺键 = 未设置，由 gateway 落云端或默认值。 */
    val maxTokensText: String = "",
    /** 上下文窗口（Composer 环的分母）；落盘原始文本。空串/旧格式的 0/缺键 = 未设置。 */
    val contextWindowText: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * llm.saved_configs store 的文档结构：
 * {"active_id": "...", "prompt": "全局 system prompt", "configs": [...]}
 */
data class LlmConfigsDocument(
    val activeId: String? = null,
    val prompt: String = "",
    val envContext: RuntimeEnvContextConfig = RuntimeEnvContextConfig(),
    val configs: List<SavedLlmConfig> = emptyList(),
) {
    fun activeConfig(): SavedLlmConfig? {
        return configs.firstOrNull { it.id == activeId }
    }
}

internal object LlmConfigsSettingsCodec {
    fun parse(json: String): LlmConfigsDocument {
        val root = parseObject(json)
        return LlmConfigsDocument(
            activeId = root.stringNonBlankOrNull(ACTIVE_ID_KEY),
            prompt = root.string(PROMPT_KEY),
            envContext = parseEnvContext(root.obj(ENV_CONTEXT_KEY)),
            configs = root.array(CONFIGS_KEY).orEmptyObjects().mapNotNull(::parseConfig),
        )
    }

    fun encode(document: LlmConfigsDocument): String {
        return JsonObject(
            mapOf(
                ACTIVE_ID_KEY to JsonPrimitive(document.activeId.orEmpty()),
                PROMPT_KEY to JsonPrimitive(document.prompt),
                ENV_CONTEXT_KEY to encodeEnvContext(document.envContext),
                CONFIGS_KEY to JsonArray(
                    document.configs.map(::encodeConfig)
                ),
            )
        ).toString()
    }

    private fun parseEnvContext(obj: JsonObject?): RuntimeEnvContextConfig {
        if (obj == null) return RuntimeEnvContextConfig()
        val enabled = obj.boolean(ENV_ENABLED_KEY, default = false)
        val rawKeys = (obj[ENV_KEYS_KEY] as? JsonArray)?.stringValues().orEmpty()
        val keys = rawKeys
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val rawVariables = (obj[ENV_VARIABLES_KEY] as? JsonObject)?.let { varObj ->
            varObj.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull.orEmpty() }
        } ?: emptyMap()
        val variables = rawVariables
            .mapNotNull { (k, v) ->
                val trimmed = k.trim()
                if (trimmed.isNotBlank() &&
                    trimmed != RuntimeEnvContextConfig.KEY_DATE &&
                    trimmed != RuntimeEnvContextConfig.KEY_TIME &&
                    trimmed != RuntimeEnvContextConfig.KEY_DEVICE
                ) {
                    trimmed to v.trim()
                } else null
            }
            .toMap()
        return RuntimeEnvContextConfig(
            enabled = enabled,
            keys = keys,
            variables = variables,
        )
    }

    private fun encodeEnvContext(config: RuntimeEnvContextConfig): JsonObject {
        val sanitizedKeys = config.keys.map { it.trim() }.filter { it.isNotBlank() }
        val sanitizedVariables = config.variables
            .mapNotNull { (k, v) ->
                val trimmed = k.trim()
                if (trimmed.isNotBlank() &&
                    trimmed != RuntimeEnvContextConfig.KEY_DATE &&
                    trimmed != RuntimeEnvContextConfig.KEY_TIME &&
                    trimmed != RuntimeEnvContextConfig.KEY_DEVICE
                ) {
                    trimmed to v.trim()
                } else null
            }
            .toMap()
        return JsonObject(
            mapOf(
                ENV_ENABLED_KEY to JsonPrimitive(config.enabled),
                ENV_KEYS_KEY to JsonArray(sanitizedKeys.map { JsonPrimitive(it) }),
                ENV_VARIABLES_KEY to JsonObject(sanitizedVariables.mapValues { JsonPrimitive(it.value) }),
            )
        )
    }

    private fun parseConfig(obj: JsonObject): SavedLlmConfig? {
        val id = obj.stringNonBlankOrNull(ID_KEY) ?: return null
        return SavedLlmConfig(
            id = id,
            name = obj.string(NAME_KEY).trim(),
            provider = obj.string(PROVIDER_KEY),
            endpoint = obj.string(ENDPOINT_KEY),
            apiKey = obj.string(API_KEY_KEY),
            model = obj.string(MODEL_KEY),
            protocol = obj.string(PROTOCOL_KEY),
            supportsImages = obj.boolean(SUPPORTS_IMAGES_KEY, default = false),
            prompt = obj.string(PROMPT_KEY),
            proxy = obj.string(PROXY_KEY),
            thinkingLevel = obj.string(THINKING_LEVEL_KEY),
            // 数字值经 obj.string() 读成字面文本（128000 → "128000"）。
            // 空串与 0 都视为未设置。
            maxTokensText = obj.string(MAX_TOKENS_KEY).trim().takeUnless { it == "0" }.orEmpty(),
            contextWindowText = obj.string(CONTEXT_WINDOW_TOKENS_KEY).trim()
                .takeUnless { it == "0" }.orEmpty(),
            createdAt = obj.long(CREATED_AT_KEY, 0L),
            updatedAt = obj.long(UPDATED_AT_KEY, 0L),
        )
    }

    private fun encodeConfig(config: SavedLlmConfig): JsonObject {
        return JsonObject(
            mapOf(
                ID_KEY to JsonPrimitive(config.id),
                NAME_KEY to JsonPrimitive(config.name),
                PROVIDER_KEY to JsonPrimitive(config.provider),
                ENDPOINT_KEY to JsonPrimitive(config.endpoint),
                API_KEY_KEY to JsonPrimitive(config.apiKey),
                MODEL_KEY to JsonPrimitive(config.model),
                PROTOCOL_KEY to JsonPrimitive(config.protocol),
                SUPPORTS_IMAGES_KEY to JsonPrimitive(config.supportsImages),
                PROMPT_KEY to JsonPrimitive(config.prompt),
                PROXY_KEY to JsonPrimitive(config.proxy),
                THINKING_LEVEL_KEY to JsonPrimitive(config.thinkingLevel),
                MAX_TOKENS_KEY to JsonPrimitive(config.maxTokensText),
                CONTEXT_WINDOW_TOKENS_KEY to JsonPrimitive(config.contextWindowText),
                CREATED_AT_KEY to JsonPrimitive(config.createdAt),
                UPDATED_AT_KEY to JsonPrimitive(config.updatedAt),
            )
        )
    }

    private const val ACTIVE_ID_KEY = "active_id"
    private const val PROMPT_KEY = "prompt"
    private const val ENV_CONTEXT_KEY = "env_context"
    private const val ENV_ENABLED_KEY = "enabled"
    private const val ENV_KEYS_KEY = "keys"
    private const val ENV_VARIABLES_KEY = "variables"
    private const val CONFIGS_KEY = "configs"
    private const val ID_KEY = "id"
    private const val NAME_KEY = "name"
    private const val PROVIDER_KEY = "provider"
    private const val ENDPOINT_KEY = "endpoint"
    private const val API_KEY_KEY = "api_key"
    private const val MODEL_KEY = "model"
    private const val PROTOCOL_KEY = "protocol"
    private const val SUPPORTS_IMAGES_KEY = "supports_images"
    private const val PROXY_KEY = "proxy"
    private const val THINKING_LEVEL_KEY = "thinking_level"
    private const val MAX_TOKENS_KEY = "max_tokens"
    private const val CONTEXT_WINDOW_TOKENS_KEY = "context_window_tokens"
    private const val CREATED_AT_KEY = "created_at"
    private const val UPDATED_AT_KEY = "updated_at"
}
