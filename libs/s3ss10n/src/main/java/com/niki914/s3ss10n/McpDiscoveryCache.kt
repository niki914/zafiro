package com.niki914.s3ss10n

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class McpDiscoveredTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>
)

internal data class McpDiscoveryEntry(
    val serverName: String,
    val fingerprint: String,
    val tools: List<McpDiscoveredTool>,
    val state: McpDiscoveryState,
    val errorMessage: String?,
    val lastSuccessAtMillis: Long?,
    val updatedAtMillis: Long
)

internal class McpDiscoveryCache {
    private val entries = mutableMapOf<CacheKey, McpDiscoveryEntry>()
    private val inFlight = mutableMapOf<CacheKey, Deferred<*>>()
    private val mutex = Mutex()

    suspend fun snapshot(serverName: String, fingerprint: String): List<McpDiscoveredTool>? = mutex.withLock {
        entries[CacheKey(serverName, fingerprint)]
            ?.takeIf { it.hasUsableToolsCache() }
            ?.tools
    }

    suspend fun put(serverName: String, fingerprint: String, tools: List<McpDiscoveredTool>) = mutex.withLock {
        val nowMillis = System.currentTimeMillis()
        entries[CacheKey(serverName, fingerprint)] = McpDiscoveryEntry(
            serverName = serverName,
            fingerprint = fingerprint,
            tools = tools,
            state = McpDiscoveryState.Available,
            errorMessage = null,
            lastSuccessAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )
    }

    suspend fun stateSnapshot(
        serverName: String,
        fingerprint: String,
        enabled: Boolean
    ): McpServerDiscoverySnapshot? = mutex.withLock {
        entries[CacheKey(serverName, fingerprint)]?.toSnapshot(enabled)
    }

    suspend fun markDiscovering(
        serverName: String,
        fingerprint: String,
        nowMillis: Long
    ): McpServerDiscoverySnapshot = mutex.withLock {
        val key = CacheKey(serverName, fingerprint)
        val current = entries[key]
        val updated = McpDiscoveryEntry(
            serverName = serverName,
            fingerprint = fingerprint,
            tools = current?.tools.orEmpty(),
            state = McpDiscoveryState.Discovering,
            errorMessage = null,
            lastSuccessAtMillis = current?.lastSuccessAtMillis,
            updatedAtMillis = nowMillis
        )
        entries[key] = updated
        updated.toSnapshot(enabled = true)
    }

    suspend fun commitSuccess(
        serverName: String,
        fingerprint: String,
        tools: List<McpDiscoveredTool>,
        nowMillis: Long
    ): McpServerDiscoverySnapshot = mutex.withLock {
        val updated = McpDiscoveryEntry(
            serverName = serverName,
            fingerprint = fingerprint,
            tools = tools,
            state = McpDiscoveryState.Available,
            errorMessage = null,
            lastSuccessAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )
        entries[CacheKey(serverName, fingerprint)] = updated
        updated.toSnapshot(enabled = true)
    }

    suspend fun commitFailure(
        serverName: String,
        fingerprint: String,
        message: String,
        nowMillis: Long
    ): Pair<McpServerDiscoverySnapshot, McpCachePolicy> = mutex.withLock {
        val key = CacheKey(serverName, fingerprint)
        val current = entries[key]
        val hasUsableCache = current?.lastSuccessAtMillis != null
        val state = if (hasUsableCache) McpDiscoveryState.UsingStaleCache else McpDiscoveryState.Failed
        val policy = if (hasUsableCache) McpCachePolicy.UsingStaleCache else McpCachePolicy.NoUsableCache
        val updated = McpDiscoveryEntry(
            serverName = serverName,
            fingerprint = fingerprint,
            tools = current?.tools.orEmpty(),
            state = state,
            errorMessage = message,
            lastSuccessAtMillis = current?.lastSuccessAtMillis,
            updatedAtMillis = nowMillis
        )
        entries[key] = updated
        updated.toSnapshot(enabled = true) to policy
    }

    suspend fun commitIgnoredBecauseConfigChanged(
        serverName: String,
        fingerprint: String,
        message: String,
        nowMillis: Long
    ): McpServerDiscoverySnapshot = mutex.withLock {
        val key = CacheKey(serverName, fingerprint)
        val current = entries[key]
        val updated = McpDiscoveryEntry(
            serverName = serverName,
            fingerprint = fingerprint,
            tools = current?.tools.orEmpty(),
            state = McpDiscoveryState.Failed,
            errorMessage = message,
            lastSuccessAtMillis = current?.lastSuccessAtMillis,
            updatedAtMillis = nowMillis
        )
        entries[key] = updated
        updated.toSnapshot(enabled = true)
    }

    suspend fun <T> acquireRefresh(
        serverName: String,
        fingerprint: String,
        scope: CoroutineScope,
        block: suspend () -> T
    ): Pair<Deferred<T>, Boolean> = mutex.withLock {
        val key = CacheKey(serverName, fingerprint)
        @Suppress("UNCHECKED_CAST")
        val existing = inFlight[key] as? Deferred<T>
        if (existing != null && existing.isActive) {
            existing to false
        } else {
            if (existing != null) {
                inFlight.remove(key)
            }
            val deferred = scope.async { block() }
            inFlight[key] = deferred
            deferred to true
        }
    }

    suspend fun clearRefresh(serverName: String, fingerprint: String, deferred: Deferred<*>) = mutex.withLock {
        val key = CacheKey(serverName, fingerprint)
        if (inFlight[key] === deferred) {
            inFlight.remove(key)
        }
    }

    private data class CacheKey(
        val serverName: String,
        val fingerprint: String
    )

    private fun McpDiscoveryEntry.hasUsableToolsCache(): Boolean {
        return when (state) {
            McpDiscoveryState.Available,
            McpDiscoveryState.UsingStaleCache -> true
            McpDiscoveryState.Discovering -> lastSuccessAtMillis != null
            McpDiscoveryState.Idle,
            McpDiscoveryState.Failed -> false
        }
    }

    private fun McpDiscoveryEntry.toSnapshot(enabled: Boolean): McpServerDiscoverySnapshot {
        return McpServerDiscoverySnapshot(
            serverName = serverName,
            enabled = enabled,
            fingerprint = fingerprint,
            state = state,
            errorMessage = errorMessage,
            lastSuccessAtMillis = lastSuccessAtMillis,
            discoveredToolCount = tools.size,
            stale = state == McpDiscoveryState.UsingStaleCache
        )
    }
}
