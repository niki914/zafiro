package com.niki914.nexus.agentic.repo

import com.niki914.nexus.agentic.mod.LocalSettings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig as LlmConfig
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer as McpServer
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpTool as McpTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverRule as TakeoverRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverTarget as TakeoverTarget

internal object LocalSettingsCodec {
    fun parseLlm(settings: LocalSettings): LlmConfig {
        return LlmConfig(
            provider = settings.provider,
            endpoint = settings.endpoint,
            apiKey = settings.apiKey,
            model = settings.model,
            prompt = settings.prompt,
            proxy = settings.proxy,
            memoryPrompt = settings.memoryPrompt,
            memories = parseMemories(settings),
            takeoverKeywords = settings.takeoverKeywords,
        )
    }

    fun withLlm(settings: LocalSettings, config: LlmConfig): LocalSettings {
        val props = settings.props.toMutableMap()
        props[PROVIDER_KEY] = JsonPrimitive(config.provider)
        props[ENDPOINT_KEY] = JsonPrimitive(config.endpoint)
        props[API_KEY_KEY] = JsonPrimitive(config.apiKey)
        props[MODEL_KEY] = JsonPrimitive(config.model)
        props[PROMPT_KEY] = JsonPrimitive(config.prompt)
        props[PROXY_KEY] = JsonPrimitive(config.proxy)
        props[MEMORY_PROMPT_KEY] = JsonPrimitive(config.memoryPrompt)
        props[MEMORIES_KEY] = JsonArray(normalizeMemories(config.memories).map(::JsonPrimitive))
        props[TAKEOVER_KEYWORDS_KEY] = JsonArray(config.takeoverKeywords.map(::JsonPrimitive))
        return LocalSettings(JsonObject(props))
    }

    fun parseMemories(settings: LocalSettings): List<String> {
        return normalizeMemories(settings.memories)
    }

    fun withMemories(settings: LocalSettings, memories: List<String>): LocalSettings {
        return settings.withTopLevel(
            MEMORIES_KEY,
            JsonArray(normalizeMemories(memories).map(::JsonPrimitive)),
        )
    }

    fun withLlmAccess(
        settings: LocalSettings,
        provider: String,
        endpoint: String,
        model: String,
        apiKey: String,
    ): LocalSettings {
        val props = settings.props.toMutableMap()
        props[PROVIDER_KEY] = JsonPrimitive(provider)
        props[ENDPOINT_KEY] = JsonPrimitive(endpoint)
        props[MODEL_KEY] = JsonPrimitive(model)
        props[API_KEY_KEY] = JsonPrimitive(apiKey)
        return LocalSettings(JsonObject(props))
    }

    fun withPrompt(settings: LocalSettings, prompt: String): LocalSettings {
        return settings.withTopLevel(PROMPT_KEY, JsonPrimitive(prompt))
    }

    fun parseMcpServers(settings: LocalSettings): List<McpServer> {
        return settings.mcpServers
            .orEmptyObjects()
            .mapNotNull { obj ->
                val name =
                    obj.string(NAME_KEY).trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = obj.string(URL_KEY).ifBlank {
                    obj.obj(TRANSPORT_KEY)?.string(URL_KEY).orEmpty()
                }.trim()
                if (url.isBlank()) {
                    return@mapNotNull null
                }
                McpServer(
                    name = name,
                    url = url,
                    enabled = obj.boolean(ENABLED_KEY, default = true),
                    headers = obj.obj(HEADERS_KEY)
                        ?.mapValues { (_, value) -> value.jsonPrimitive.contentOrNull.orEmpty() }
                        ?: emptyMap(),
                )
            }
    }

    fun withMcpServers(settings: LocalSettings, servers: List<McpServer>): LocalSettings {
        return settings.withTopLevel(
            MCP_SERVERS_KEY,
            JsonArray(
                servers.map { server ->
                    val fields = linkedMapOf<String, JsonElement>(
                        NAME_KEY to JsonPrimitive(server.name),
                        URL_KEY to JsonPrimitive(server.url),
                        ENABLED_KEY to JsonPrimitive(server.enabled),
                    )
                    if (server.headers.isNotEmpty()) {
                        fields[HEADERS_KEY] = JsonObject(
                            server.headers
                                .filterKeys { it.isNotBlank() }
                                .mapValues { (_, value) -> JsonPrimitive(value) }
                        )
                    }
                    JsonObject(fields)
                }
            ),
        )
    }

    fun parseMcpCache(settings: LocalSettings, server: McpServer): List<McpTool> {
        val key = mcpCacheKey(url = server.url, headers = server.headers)
        return (settings.mcpDiscoveredToolsCache?.get(key) as? JsonObject)
            ?.array(TOOLS_KEY)
            .orEmptyObjects()
            .mapNotNull { obj ->
                val name =
                    obj.string(NAME_KEY).trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val inputSchema = obj.obj(INPUT_SCHEMA_KEY) ?: return@mapNotNull null
                McpTool(
                    name = name,
                    description = obj.string(DESCRIPTION_KEY),
                    inputSchemaJson = inputSchema.toString(),
                )
            }
    }

    fun withMcpCache(
        settings: LocalSettings,
        url: String,
        headers: Map<String, String>,
        tools: List<McpTool>,
    ): LocalSettings {
        val cache = settings.mcpDiscoveredToolsCache?.toMutableMap() ?: mutableMapOf()
        cache[mcpCacheKey(url = url, headers = headers)] = JsonObject(
            mapOf(
                TOOLS_KEY to JsonArray(
                    tools.map { tool ->
                        JsonObject(
                            mapOf(
                                NAME_KEY to JsonPrimitive(tool.name),
                                DESCRIPTION_KEY to JsonPrimitive(tool.description),
                                INPUT_SCHEMA_KEY to tool.inputSchemaJson.asJsonObjectOrEmpty(),
                            )
                        )
                    }
                )
            )
        )
        return settings.withTopLevel(MCP_CACHE_KEY, JsonObject(cache))
    }

    fun withoutMcpCache(settings: LocalSettings, servers: Collection<McpServer>): LocalSettings {
        val cache = settings.mcpDiscoveredToolsCache?.toMutableMap() ?: mutableMapOf()
        servers.forEach { server ->
            cache.remove(mcpCacheKey(url = server.url, headers = server.headers))
        }
        return settings.withTopLevel(MCP_CACHE_KEY, JsonObject(cache))
    }

    fun parseCustomTools(settings: LocalSettings): List<CustomTool> {
        return settings.customTools
            .orEmptyObjects()
            .mapNotNull { obj ->
                val name = obj.string(NAME_KEY).trim()
                val command = obj.string(COMMAND_KEY).trim()
                if (name.isBlank() || command.isBlank()) {
                    return@mapNotNull null
                }
                CustomTool(
                    name = name,
                    description = obj.string(DESCRIPTION_KEY).trim(),
                    command = command,
                    enabled = obj.boolean(ENABLED_KEY, default = true),
                )
            }
    }

    fun withCustomTools(settings: LocalSettings, tools: List<CustomTool>): LocalSettings {
        return settings.withTopLevel(
            CUSTOM_TOOLS_KEY,
            JsonArray(
                tools.map { tool ->
                    JsonObject(
                        mapOf(
                            NAME_KEY to JsonPrimitive(tool.name),
                            DESCRIPTION_KEY to JsonPrimitive(tool.description),
                            COMMAND_KEY to JsonPrimitive(tool.command),
                            ENABLED_KEY to JsonPrimitive(tool.enabled),
                        )
                    )
                }
            ),
        )
    }

    fun parseExecutionRules(settings: LocalSettings): List<ExecutionRule> {
        return settings.shellSafetyPolicies
            .orEmptyObjects()
            .mapNotNull { obj ->
                val id =
                    obj.string(ID_KEY).trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name =
                    obj.string(NAME_KEY).trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ExecutionRule(
                    id = id,
                    name = name,
                    enabledMode = obj.enabledMode(),
                    patterns = obj.array(PATTERNS_KEY)
                        ?.jsonArray
                        ?.mapNotNull { element -> element.jsonPrimitive.contentOrNull?.trim() }
                        ?.filter { it.isNotBlank() }
                        ?: emptyList(),
                )
            }
    }

    fun withExecutionRules(settings: LocalSettings, rules: List<ExecutionRule>): LocalSettings {
        return settings.withTopLevel(
            SHELL_SAFETY_POLICIES_KEY,
            JsonArray(
                rules.map { rule ->
                    JsonObject(
                        mapOf(
                            ID_KEY to JsonPrimitive(rule.id),
                            NAME_KEY to JsonPrimitive(rule.name),
                            ENABLED_MODE_KEY to JsonPrimitive(rule.enabledMode.name),
                            PATTERNS_KEY to JsonArray(
                                rule.patterns
                                    .map(String::trim)
                                    .filter(String::isNotBlank)
                                    .map(::JsonPrimitive)
                            ),
                        )
                    )
                }
            ),
        )
    }

    fun parseTakeoverRules(settings: LocalSettings): List<TakeoverRule> {
        return settings.takeoverRules
            .orEmptyObjects()
            .mapNotNull { obj ->
                val id =
                    obj.string(ID_KEY).trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name =
                    obj.string(NAME_KEY).trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TakeoverRule(
                    id = id,
                    name = name,
                    target = obj.takeoverTarget(),
                    enabled = obj.boolean(ENABLED_KEY, default = true),
                    patterns = obj.array(PATTERNS_KEY)
                        ?.jsonArray
                        ?.mapNotNull { element -> element.jsonPrimitive.contentOrNull?.trim() }
                        ?.filter { it.isNotBlank() }
                        ?: emptyList(),
                )
            }
    }

    fun withTakeoverRules(settings: LocalSettings, rules: List<TakeoverRule>): LocalSettings {
        return settings.withTopLevel(
            TAKEOVER_RULES_KEY,
            JsonArray(
                rules.map { rule ->
                    JsonObject(
                        mapOf(
                            ID_KEY to JsonPrimitive(rule.id),
                            NAME_KEY to JsonPrimitive(rule.name),
                            TARGET_KEY to JsonPrimitive(rule.target.name),
                            ENABLED_KEY to JsonPrimitive(rule.enabled),
                            PATTERNS_KEY to JsonArray(
                                rule.patterns
                                    .map(String::trim)
                                    .filter(String::isNotBlank)
                                    .map(::JsonPrimitive)
                            ),
                        )
                    )
                }
            ),
        )
    }

    fun parseBuiltinFlags(settings: LocalSettings): Map<String, Boolean> {
        return settings.builtinToolFlags
            ?.mapNotNull { (name, value) ->
                val enabled = when (value) {
                    is JsonPrimitive -> value.booleanOrNull
                    is JsonObject -> (value[ENABLED_KEY] as? JsonPrimitive)?.booleanOrNull
                    else -> null
                } ?: return@mapNotNull null
                name to enabled
            }
            ?.toMap()
            ?: emptyMap()
    }

    fun withBuiltinFlag(settings: LocalSettings, name: String, enabled: Boolean): LocalSettings {
        val flags = settings.builtinToolFlags?.toMutableMap() ?: mutableMapOf()
        flags[name] = JsonPrimitive(enabled)
        return settings.withTopLevel(BUILTIN_TOOL_FLAGS_KEY, JsonObject(flags))
    }

    fun withBoolean(settings: LocalSettings, key: String, value: Boolean): LocalSettings {
        return settings.withTopLevel(key, JsonPrimitive(value))
    }

    private fun LocalSettings.withTopLevel(key: String, value: JsonElement): LocalSettings {
        val props = props.toMutableMap()
        props[key] = value
        return LocalSettings(JsonObject(props))
    }

    private fun String.asJsonObjectOrEmpty(): JsonObject {
        if (isBlank()) {
            return JsonObject(emptyMap())
        }
        return try {
            Json.parseToJsonElement(this).jsonObject
        } catch (_: SerializationException) {
            JsonObject(emptyMap())
        } catch (_: IllegalArgumentException) {
            JsonObject(emptyMap())
        }
    }

    private fun JsonArray?.orEmptyObjects(): List<JsonObject> {
        return this?.mapNotNull { it as? JsonObject } ?: emptyList()
    }

    private fun JsonObject.string(key: String): String {
        return (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    private fun JsonObject.boolean(key: String, default: Boolean): Boolean {
        return (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
    }

    private fun JsonObject.enabledMode(): ExecutionRuleEnabledMode {
        val value = string(ENABLED_MODE_KEY)
        return ExecutionRuleEnabledMode.entries.firstOrNull { it.name == value }
            ?: ExecutionRuleEnabledMode.DISABLED
    }

    private fun JsonObject.takeoverTarget(): TakeoverTarget {
        val value = string(TARGET_KEY)
        return TakeoverTarget.entries.firstOrNull { it.name == value }
            ?: TakeoverTarget.NEXUS
    }

    private fun JsonObject.array(key: String): JsonArray? {
        return this[key] as? JsonArray
    }

    private fun JsonObject.obj(key: String): JsonObject? {
        return this[key] as? JsonObject
    }

    private fun normalizeMemories(memories: List<String>): List<String> {
        return memories.map(String::trim).filter(String::isNotBlank)
    }

    private fun mcpCacheKey(url: String, headers: Map<String, String>): String {
        val normalizedHeaders = headers
            .mapKeys { (key, _) -> key.lowercase() }
            .toSortedMap()
        return buildString {
            append(url)
            append("#")
            normalizedHeaders.forEach { (key, value) ->
                append(key)
                append("=")
                append(value)
                append("&")
            }
        }
    }

    private const val PROVIDER_KEY = "provider"
    private const val ENDPOINT_KEY = "endpoint"
    private const val API_KEY_KEY = "api_key"
    private const val MODEL_KEY = "model"
    private const val PROMPT_KEY = "prompt"
    private const val PROXY_KEY = "proxy"
    private const val MEMORY_PROMPT_KEY = "memory_prompt"
    private const val MEMORIES_KEY = "memories"
    private const val TAKEOVER_KEYWORDS_KEY = "takeover_keywords"
    private const val TAKEOVER_RULES_KEY = "takeover_rules"
    private const val MCP_SERVERS_KEY = "mcp_servers"
    private const val MCP_CACHE_KEY = "mcp_discovered_tools_cache"
    private const val CUSTOM_TOOLS_KEY = "custom_tools"
    private const val SHELL_SAFETY_POLICIES_KEY = "shell_safety_policies"
    private const val BUILTIN_TOOL_FLAGS_KEY = "builtin_tool_flags"
    private const val ID_KEY = "id"
    private const val NAME_KEY = "name"
    private const val URL_KEY = "url"
    private const val TRANSPORT_KEY = "transport"
    private const val HEADERS_KEY = "headers"
    private const val ENABLED_KEY = "enabled"
    private const val TOOLS_KEY = "tools"
    private const val INPUT_SCHEMA_KEY = "inputSchema"
    private const val DESCRIPTION_KEY = "description"
    private const val COMMAND_KEY = "command"
    private const val ENABLED_MODE_KEY = "enabled_mode"
    private const val TARGET_KEY = "target"
    private const val PATTERNS_KEY = "patterns"
}
