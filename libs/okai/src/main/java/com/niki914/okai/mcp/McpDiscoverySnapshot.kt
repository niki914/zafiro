package com.niki914.okai.mcp

/**
 * Current discovery state per server. Hosts read this to compose prompts,
 * surface connectivity problems or persist discovered tools; the library
 * refreshes it whenever config changes. Fingerprint drives incremental
 * refresh decisions.
 *
 * Design source: field requirements validated in the Nexus usage of kai
 * (lastMcpServersFingerprint); architecture follows codex mcp handling,
 * per kai PRD section 5.
 */
data class McpDiscoverySnapshot(
    val servers: Map<String, McpServerDiscoverySnapshot>,
    val conflicts: List<ToolConflict>
)

/** Per-server discovery state. */
data class McpServerDiscoverySnapshot(
    val serverName: String,
    val enabled: Boolean,
    val fingerprint: String?,
    val state: McpDiscoveryState,
    val errorMessage: String?,
    val lastSuccessAtMillis: Long?,
    val discoveredToolCount: Int,
    val stale: Boolean
)

/** Discovery lifecycle state. */
enum class McpDiscoveryState {
    Idle,
    Discovering,
    Available,
    Failed,
    UsingStaleCache
}

/** A tool name claimed by more than one discovery source. */
data class ToolConflict(
    val name: String,
    val reason: ToolConflictReason,
    val candidates: List<String>
)

/** Why a tool name conflicts across discovery sources. */
enum class ToolConflictReason {
    HiddenByLocal,
    ExplicitOverridesDiscovered,
    DuplicateInServer,
    CrossServerConflict
}

/**
 * Outcome of one explicit refresh. Hosts use failedServers to surface
 * connectivity problems without parsing exceptions.
 *
 * Design source: field requirements validated in the Nexus usage of kai.
 */
data class McpRefreshResult(
    val refreshedServers: List<String>,
    val failedServers: List<String>
)
