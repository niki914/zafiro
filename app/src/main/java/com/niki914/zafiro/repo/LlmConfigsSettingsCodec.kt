package com.niki914.zafiro.repo

import com.niki914.zafiro.repo.SettingsJsonCodecUtils.array
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.orEmptyObjects
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 一份 Saved Configuration（完整可切换的 LLM 接入配置）。
 * prompt 不在其中——prompt 是全局一份的行为层配置，由 [LlmConfigsDocument.prompt] 承载；
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
    val proxy: String,
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
            activeId = root.string(ACTIVE_ID_KEY).takeIf(String::isNotBlank),
            prompt = root.string(PROMPT_KEY),
            configs = root.array(CONFIGS_KEY).orEmptyObjects().mapNotNull(::parseConfig),
        )
    }

    fun encode(document: LlmConfigsDocument): String {
        return JsonObject(
            mapOf(
                ACTIVE_ID_KEY to JsonPrimitive(document.activeId.orEmpty()),
                PROMPT_KEY to JsonPrimitive(document.prompt),
                CONFIGS_KEY to kotlinx.serialization.json.JsonArray(
                    document.configs.map(::encodeConfig)
                ),
            )
        ).toString()
    }

    private fun parseConfig(obj: JsonObject): SavedLlmConfig? {
        val id = obj.string(ID_KEY).trim().takeIf(String::isNotBlank) ?: return null
        return SavedLlmConfig(
            id = id,
            name = obj.string(NAME_KEY).trim(),
            provider = obj.string(PROVIDER_KEY),
            endpoint = obj.string(ENDPOINT_KEY),
            apiKey = obj.string(API_KEY_KEY),
            model = obj.string(MODEL_KEY),
            protocol = obj.string(PROTOCOL_KEY),
            proxy = obj.string(PROXY_KEY),
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
                PROXY_KEY to JsonPrimitive(config.proxy),
                CREATED_AT_KEY to JsonPrimitive(config.createdAt),
                UPDATED_AT_KEY to JsonPrimitive(config.updatedAt),
            )
        )
    }

    private fun JsonObject.long(key: String, default: Long): Long {
        return (this[key] as? JsonPrimitive)?.longOrNull ?: default
    }

    private const val ACTIVE_ID_KEY = "active_id"
    private const val PROMPT_KEY = "prompt"
    private const val CONFIGS_KEY = "configs"
    private const val ID_KEY = "id"
    private const val NAME_KEY = "name"
    private const val PROVIDER_KEY = "provider"
    private const val ENDPOINT_KEY = "endpoint"
    private const val API_KEY_KEY = "api_key"
    private const val MODEL_KEY = "model"
    private const val PROTOCOL_KEY = "protocol"
    private const val PROXY_KEY = "proxy"
    private const val CREATED_AT_KEY = "created_at"
    private const val UPDATED_AT_KEY = "updated_at"
}
