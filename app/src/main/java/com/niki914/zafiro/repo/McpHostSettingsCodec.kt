package com.niki914.zafiro.repo

import com.niki914.zafiro.repo.SettingsJsonCodecUtils.boolean
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.int
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.stringValues
import com.niki914.zafiro.settings.model.RuntimeMcpHostConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object McpHostSettingsCodec {
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PORT = "port"
    private const val KEY_HOST = "host"
    private const val KEY_BEARER_TOKEN = "bearerToken"
    private const val KEY_EXPOSED_TOOLS = "exposedTools"

    fun parse(json: String): RuntimeMcpHostConfig {
        val obj = parseObject(json)
        val enabled = obj.boolean(KEY_ENABLED, default = false)
        val port = obj.int(KEY_PORT, default = RuntimeMcpHostConfig.DEFAULT_PORT)
        val host = obj.string(KEY_HOST).ifBlank { RuntimeMcpHostConfig.DEFAULT_HOST }
        val token = obj.string(KEY_BEARER_TOKEN)
        val exposedTools = (obj[KEY_EXPOSED_TOOLS] as? JsonArray)?.stringValues()?.toSet()
            ?: RuntimeMcpHostConfig.DEFAULT_EXPOSED_TOOLS
        return RuntimeMcpHostConfig(
            enabled = enabled,
            port = port,
            host = host,
            bearerToken = token,
            exposedTools = exposedTools,
        )
    }

    fun encode(config: RuntimeMcpHostConfig): String {
        return JsonObject(
            mapOf(
                KEY_ENABLED to JsonPrimitive(config.enabled),
                KEY_PORT to JsonPrimitive(config.port),
                KEY_HOST to JsonPrimitive(config.host),
                KEY_BEARER_TOKEN to JsonPrimitive(config.bearerToken),
                KEY_EXPOSED_TOOLS to JsonArray(config.exposedTools.map { JsonPrimitive(it) }),
            )
        ).toString()
    }
}
