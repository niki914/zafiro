package com.niki914.nexus.store

object StoreDescriptorRegistry {

    const val WEB_SETTINGS_ID = "web_settings"
    const val LOCAL_SETTINGS_ID = "local_settings"
    const val AGENT_REGISTRY_ID = "agents.registry"
    const val AGENT_MAIN_CONFIG_ID = "agent.main.config"
    const val AGENT_MAIN_MEMORY_ID = "agent.main.memory"
    const val TOOLS_BUILTIN_ID = "tools.builtin"
    const val TOOLS_CUSTOM_ID = "tools.custom"
    const val TOOLS_MCP_SERVERS_ID = "tools.mcp.servers"
    const val RULES_EXECUTION_ID = "rules.execution"
    const val RULES_TAKEOVER_ID = "rules.takeover"
    const val APP_STATE_ID = "app.state"
    const val AGENT_CONFIG_PREFIX = "agent.config."
    const val MCP_CACHE_PREFIX = "tools.mcp.cache."
    const val MAIN_AGENT_ID = "main"

    private val safeAgentIdPattern = Regex("[a-z][a-z0-9_-]{1,31}")
    private val safeServerIdPattern = Regex("[a-zA-Z0-9_-]{1,64}")

    private val staticDescriptors = listOf(
        StoreDescriptor(WEB_SETTINGS_ID, "settings/hooks.json"),
        StoreDescriptor(LOCAL_SETTINGS_ID, "local_settings.json"),
        StoreDescriptor(AGENT_REGISTRY_ID, "settings/agents/registry.json", """{"agents":[]}"""),
        StoreDescriptor(AGENT_MAIN_CONFIG_ID, "settings/agents/main/config.json"),
        StoreDescriptor(
            AGENT_MAIN_MEMORY_ID,
            "settings/agents/main/memory.json",
            """{"memories":[]}"""
        ),
        StoreDescriptor(
            TOOLS_BUILTIN_ID,
            "settings/tools/builtin_tools.json",
            """{"enabled_for_agents":{}}"""
        ),
        StoreDescriptor(TOOLS_CUSTOM_ID, "settings/tools/custom_tools.json", """{"tools":[]}"""),
        StoreDescriptor(
            TOOLS_MCP_SERVERS_ID,
            "settings/tools/mcp/servers.json",
            """{"servers":[]}"""
        ),
        StoreDescriptor(
            RULES_EXECUTION_ID,
            "settings/rules/execution_rules.json",
            """{"rules":[]}"""
        ),
        StoreDescriptor(
            RULES_TAKEOVER_ID,
            "settings/rules/takeover_rules.json",
            """{"rules":[]}"""
        ),
        StoreDescriptor(APP_STATE_ID, "settings/app_state.json")
    )

    private val staticDescriptorById = staticDescriptors.associateBy(StoreDescriptor::id)

    fun find(storeId: String): StoreDescriptor? {
        return staticDescriptorById[storeId]
    }

    fun require(storeId: String): StoreDescriptor {
        return resolveDynamic(storeId) ?: throw IllegalArgumentException("Unknown storeId=$storeId")
    }

    fun mcpCacheStoreId(serverId: String): String? {
        return serverId
            .takeIf { safeServerIdPattern.matches(it) }
            ?.let { MCP_CACHE_PREFIX + it }
    }

    fun agentConfigStoreId(agentId: String): String? {
        return agentId.trim().lowercase()
            .takeIf { safeAgentIdPattern.matches(it) }
            ?.let { AGENT_CONFIG_PREFIX + it }
    }

    fun resolveDynamic(storeId: String): StoreDescriptor? {
        find(storeId)?.let { return it }

        val agentId = storeId.removePrefix(AGENT_CONFIG_PREFIX)
        if (agentId != storeId && safeAgentIdPattern.matches(agentId)) {
            return StoreDescriptor(
                id = storeId,
                relativePath = "settings/agents/$agentId/config.json"
            )
        }

        val serverId = storeId.removePrefix(MCP_CACHE_PREFIX)
        if (serverId == storeId || !safeServerIdPattern.matches(serverId)) return null
        return StoreDescriptor(
            id = storeId,
            relativePath = "settings/tools/mcp/cache/$serverId.json",
            defaultJson = """{"server_id":"$serverId","fingerprint":"","updated_at":0,"tools":[]}"""
        )
    }

    fun allStatic(): List<StoreDescriptor> {
        return staticDescriptors
    }
}
