package com.niki914.s3ss10n

import com.niki914.s3ss10n.json.JsonCodec
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal class McpDiscoveryCoordinator(
    private val mcpClient: McpClient,
    private val scope: CoroutineScope,
    private val codec: JsonCodec,
    private val latestConfig: suspend () -> SessionConfig,
    private val cache: McpDiscoveryCache = McpDiscoveryCache()
) {
    private data class ServerDiscoveryOutcome(
        val serverName: String,
        val fingerprint: String,
        val tools: List<McpDiscoveredTool>?,
        val failureMessage: String?,
        val cacheCommitted: Boolean
    )

    suspend fun discoveredToolsSnapshot(config: SessionConfig): Map<String, List<McpDiscoveredTool>> {
        return config.mcpRegistry.servers.mapNotNull { (serverName, server) ->
            if (!server.enabled) return@mapNotNull null
            val fingerprint = server.discoveryFingerprint(serverName)
            val tools = cache.snapshot(serverName, fingerprint)
            tools?.let { serverName to it }
        }.toMap()
    }

    suspend fun refreshEnabledServers(
        config: SessionConfig,
        refreshCached: Boolean
    ): McpRefreshResult {
        val enabledServers = config.mcpRegistry.servers.filterValues { it.enabled }
        if (enabledServers.isEmpty()) {
            return McpRefreshResult(
                refreshedServers = emptyList(),
                failedServers = emptyList(),
                discoveredToolCount = 0
            )
        }
        val outcomes = coroutineScope {
            enabledServers.map { (serverName, serverConfig) ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    refreshServerTools(
                        serverName = serverName,
                        serverConfig = serverConfig,
                        refreshCached = refreshCached
                    )
                }
            }.awaitAll()
        }
        return McpRefreshResult(
            refreshedServers = outcomes
                .filter { it.failureMessage == null && it.cacheCommitted }
                .map { it.serverName },
            failedServers = outcomes.mapNotNull { outcome ->
                outcome.failureMessage?.let { message ->
                    McpServerRefreshFailure(
                        serverName = outcome.serverName,
                        message = message
                    )
                }
            },
            discoveredToolCount = outcomes
                .filter { it.failureMessage == null && it.cacheCommitted }
                .sumOf { it.tools?.size ?: 0 }
        )
    }

    suspend fun getSnapshot(config: SessionConfig): McpDiscoverySnapshot {
        val servers = config.mcpRegistry.servers.mapValues { (serverName, server) ->
            val fingerprint = server.discoveryFingerprint(serverName)
            cache.stateSnapshot(
                serverName = serverName,
                fingerprint = fingerprint,
                enabled = server.enabled
            ) ?: McpServerDiscoverySnapshot(
                serverName = serverName,
                enabled = server.enabled,
                fingerprint = fingerprint,
                state = McpDiscoveryState.Idle,
                errorMessage = null,
                lastSuccessAtMillis = null,
                discoveredToolCount = 0,
                stale = false
            )
        }
        val finalToolRegistry = config.buildToolCatalog(
            codec = codec,
            discoveredMcpTools = discoveredToolsSnapshot(config)
        ).registrySnapshot
        return McpDiscoverySnapshot(
            servers = servers,
            finalToolRegistry = finalToolRegistry
        )
    }

    fun scheduleDiscovery(
        config: SessionConfig,
        refreshCached: Boolean = false
    ) {
        config.mcpRegistry.servers.forEach { (serverName, serverConfig) ->
            if (!serverConfig.enabled) return@forEach
            scope.launch {
                refreshServerTools(
                    serverName = serverName,
                    serverConfig = serverConfig,
                    refreshCached = refreshCached
                )
            }
        }
    }

    private suspend fun refreshServerTools(
        serverName: String,
        serverConfig: McpServerConfig,
        refreshCached: Boolean
    ): ServerDiscoveryOutcome {
        val fingerprint = serverConfig.discoveryFingerprint(serverName)
        if (!refreshCached) {
            val cachedTools = cache.snapshot(serverName, fingerprint)
            if (cachedTools != null) {
                return ServerDiscoveryOutcome(
                    serverName = serverName,
                    fingerprint = fingerprint,
                    tools = cachedTools,
                    failureMessage = null,
                    cacheCommitted = true
                )
            }
        }

        val serverSnapshot = serverConfig.deepCopy()
        val (deferred, created) = cache.acquireRefresh(
            serverName = serverName,
            fingerprint = fingerprint,
            scope = scope
        ) {
            runServerDiscovery(
                serverName = serverName,
                fingerprint = fingerprint,
                serverConfig = serverSnapshot
            )
        }
        if (created) {
            deferred.invokeOnCompletion {
                scope.launch {
                    cache.clearRefresh(
                        serverName = serverName,
                        fingerprint = fingerprint,
                        deferred = deferred
                    )
                }
            }
        }
        return deferred.await()
    }

    private suspend fun runServerDiscovery(
        serverName: String,
        fingerprint: String,
        serverConfig: McpServerConfig
    ): ServerDiscoveryOutcome {
        val discoveringSnapshot = cache.markDiscovering(
            serverName = serverName,
            fingerprint = fingerprint,
            nowMillis = System.currentTimeMillis()
        )
        notifyDiscoveryStateChanged(serverName, discoveringSnapshot)
        return xTrySuspend(
            "McpDiscoveryCoordinator.runServerDiscovery",
            onError = { t ->
                val message = t.message ?: "MCP discovery failed"
                val (failureSnapshot, policy) = cache.commitFailure(
                    serverName = serverName,
                    fingerprint = fingerprint,
                    message = message,
                    nowMillis = System.currentTimeMillis()
                )
                notifyDiscoveryStateChanged(serverName, failureSnapshot)
                notifyDiscoveryFailed(serverName, t, policy)
                ServerDiscoveryOutcome(
                    serverName = serverName,
                    fingerprint = fingerprint,
                    tools = null,
                    failureMessage = message,
                    cacheCommitted = false
                )
            }
        ) {
            val tools = mcpClient.listTools(serverConfig)
            val latestServer = latestConfig().mcpRegistry.servers[serverName]
            if (latestServer?.enabled == true &&
                latestServer.discoveryFingerprint(serverName) == fingerprint
            ) {
                val successSnapshot = cache.commitSuccess(
                    serverName = serverName,
                    fingerprint = fingerprint,
                    tools = tools,
                    nowMillis = System.currentTimeMillis()
                )
                notifyDiscoveryStateChanged(serverName, successSnapshot)
                notifyToolsDiscovered(serverName, tools)
                ServerDiscoveryOutcome(
                    serverName = serverName,
                    fingerprint = fingerprint,
                    tools = tools,
                    failureMessage = null,
                    cacheCommitted = true
                )
            } else {
                val message = "MCP discovery ignored because config changed"
                val failureSnapshot = cache.commitIgnoredBecauseConfigChanged(
                    serverName = serverName,
                    fingerprint = fingerprint,
                    message = message,
                    nowMillis = System.currentTimeMillis()
                )
                notifyDiscoveryStateChanged(serverName, failureSnapshot)
                notifyDiscoveryFailed(
                    serverName = serverName,
                    error = IllegalStateException(message),
                    policy = McpCachePolicy.IgnoredBecauseConfigChanged
                )
                ServerDiscoveryOutcome(
                    serverName = serverName,
                    fingerprint = fingerprint,
                    tools = null,
                    failureMessage = message,
                    cacheCommitted = false
                )
            }
        }
    }

    private suspend fun notifyToolsDiscovered(
        serverName: String,
        tools: List<McpDiscoveredTool>
    ) {
        val hook = latestConfig().mcpHooksBlock?.onToolsDiscovered ?: return
        xTrySuspend("McpDiscoveryCoordinator.notifyToolsDiscovered") {
            hook(serverName, tools)
        }
    }

    private suspend fun notifyDiscoveryFailed(
        serverName: String,
        error: Throwable,
        policy: McpCachePolicy
    ) {
        val hook = latestConfig().mcpHooksBlock?.onDiscoveryFailed ?: return
        xTrySuspend("McpDiscoveryCoordinator.notifyDiscoveryFailed") {
            hook(serverName, error, policy)
        }
    }

    private suspend fun notifyDiscoveryStateChanged(
        serverName: String,
        snapshot: McpServerDiscoverySnapshot
    ) {
        val hook = latestConfig().mcpHooksBlock?.onDiscoveryStateChanged ?: return
        xTrySuspend("McpDiscoveryCoordinator.notifyDiscoveryStateChanged") {
            hook(serverName, snapshot)
        }
    }
}
