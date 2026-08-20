package com.niki914.nexus.agentic.repo

import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.array
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.enabledForAgent
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.obj
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.orEmptyObjects
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.parseObject
import com.niki914.nexus.agentic.repo.SettingsJsonCodecUtils.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer as McpServer

internal object McpSettingsCodec {
    fun normalizeServerId(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return null
        val normalized = trimmed
            .lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
            .take(SERVER_ID_MAX_LENGTH)
        return normalized
            .ifBlank { "server_${Integer.toHexString(trimmed.hashCode())}" }
            .takeIf { SAFE_SERVER_ID.matches(it) }
    }

    fun parseServers(json: String, agentId: String = MAIN_AGENT_ID): List<McpServer> {
        return parseObject(json)
            .array(SERVERS_KEY)
            .orEmptyObjects()
            .mapNotNull { obj ->
                val id = obj.string(ID_KEY).trim()
                val name = obj.string(NAME_KEY).trim()
                val url = obj.string(URL_KEY).trim()
                if (id.isBlank() || name.isBlank() || url.isBlank()) return@mapNotNull null
                McpServer(
                    name = name,
                    url = url,
                    enabled = enabledForAgent(obj[ENABLED_FOR_AGENTS_KEY], agentId) == true,
                    headers = obj.obj(HEADERS_KEY)
                        ?.mapNotNull { (key, value) ->
                            val primitive = value as? JsonPrimitive ?: return@mapNotNull null
                            if (!primitive.isString) return@mapNotNull null
                            primitive.contentOrNull?.let { headerValue -> key to headerValue }
                        }
                        ?.toMap()
                        ?: emptyMap(),
                )
            }
    }

    fun encodeServers(servers: List<McpServer>, agentId: String = MAIN_AGENT_ID): String {
        return JsonObject(
            mapOf(
                SERVERS_KEY to JsonArray(
                    servers.mapNotNull { server ->
                        val id = normalizeServerId(server.name) ?: return@mapNotNull null
                        JsonObject(
                            mapOf(
                                ID_KEY to JsonPrimitive(id),
                                NAME_KEY to JsonPrimitive(server.name),
                                URL_KEY to JsonPrimitive(server.url),
                                HEADERS_KEY to JsonObject(
                                    server.headers
                                        .filterKeys(String::isNotBlank)
                                        .mapValues { (_, value) -> JsonPrimitive(value) }
                                ),
                                ENABLED_FOR_AGENTS_KEY to JsonArray(
                                    if (server.enabled) listOf(JsonPrimitive(agentId)) else emptyList()
                                ),
                            )
                        )
                    }
                )
            )
        ).toString()
    }

    private const val SERVER_ID_MAX_LENGTH = 64
    private val SAFE_SERVER_ID = Regex("[a-z0-9_-]{1,$SERVER_ID_MAX_LENGTH}")

    private const val MAIN_AGENT_ID = "main"
    private const val SERVERS_KEY = "servers"
    private const val ID_KEY = "id"
    private const val NAME_KEY = "name"
    private const val URL_KEY = "url"
    private const val HEADERS_KEY = "headers"
    private const val ENABLED_FOR_AGENTS_KEY = "enabled_for_agents"
    private const val DESCRIPTION_KEY = "description"
    private const val INPUT_SCHEMA_KEY = "input_schema"
}
