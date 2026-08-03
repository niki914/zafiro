package com.niki914.s3ss10n

class McpHooks internal constructor() {
    var onToolsDiscovered: (suspend (server: String, tools: List<McpDiscoveredTool>) -> Unit)? = null
    var onDiscoveryFailed: (suspend (server: String, error: Throwable, policy: McpCachePolicy) -> Unit)? = null
    var onDiscoveryStateChanged: (suspend (server: String, state: McpServerDiscoverySnapshot) -> Unit)? = null

    internal fun deepCopy(): McpHooks {
        return McpHooks().also { copy ->
            copy.onToolsDiscovered = onToolsDiscovered
            copy.onDiscoveryFailed = onDiscoveryFailed
            copy.onDiscoveryStateChanged = onDiscoveryStateChanged
        }
    }
}

enum class McpCachePolicy {
    NoUsableCache,
    UsingStaleCache,
    CacheUpdated,
    IgnoredBecauseConfigChanged
}
