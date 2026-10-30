package com.niki914.zafiro.repo

import com.niki914.zafiro.repo.SettingsJsonCodecUtils.boolean
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.intClamped
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.string
import com.niki914.zafiro.repo.SettingsJsonCodecUtils.stringValues
import com.niki914.zafiro.settings.model.RuntimeMcpServiceConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object McpServiceSettingsCodec {
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PORT = "port"
    private const val KEY_HOST = "host"
    private const val KEY_BEARER_TOKEN = "bearerToken"
    private const val KEY_EXPOSED_TOOLS = "exposedTools"
    private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"

    fun parse(json: String): RuntimeMcpServiceConfig {
        val obj = parseObject(json)
        val enabled = obj.boolean(KEY_ENABLED, default = false)
        // 读盘侧夹回可用区间：手改磁盘越界端口会导致服务无法启动。
        val port = obj.intClamped(
            KEY_PORT,
            default = RuntimeMcpServiceConfig.DEFAULT_PORT,
            min = RuntimeMcpServiceConfig.MIN_PORT,
            max = RuntimeMcpServiceConfig.MAX_PORT,
        )
        val host = obj.string(KEY_HOST).ifBlank { RuntimeMcpServiceConfig.DEFAULT_HOST }
        val token = obj.string(KEY_BEARER_TOKEN)
        val exposedTools = (obj[KEY_EXPOSED_TOOLS] as? JsonArray)?.stringValues()?.toSet()
            ?: RuntimeMcpServiceConfig.DEFAULT_EXPOSED_TOOLS
        val keepScreenOn = obj.boolean(KEY_KEEP_SCREEN_ON, default = true)
        return RuntimeMcpServiceConfig(
            enabled = enabled,
            port = port,
            host = host,
            bearerToken = token,
            exposedTools = exposedTools,
            keepScreenOn = keepScreenOn,
        )
    }

    fun encode(config: RuntimeMcpServiceConfig): String {
        return JsonObject(
            mapOf(
                KEY_ENABLED to JsonPrimitive(config.enabled),
                KEY_PORT to JsonPrimitive(config.port),
                KEY_HOST to JsonPrimitive(config.host),
                KEY_BEARER_TOKEN to JsonPrimitive(config.bearerToken),
                KEY_EXPOSED_TOOLS to JsonArray(config.exposedTools.map { JsonPrimitive(it) }),
                KEY_KEEP_SCREEN_ON to JsonPrimitive(config.keepScreenOn),
            )
        ).toString()
    }
}
