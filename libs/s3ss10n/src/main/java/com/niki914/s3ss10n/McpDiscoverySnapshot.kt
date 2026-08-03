package com.niki914.s3ss10n

data class McpDiscoverySnapshot(
    val servers: Map<String, McpServerDiscoverySnapshot>,
    val finalToolRegistry: ToolRegistrySnapshot
)

data class McpServerDiscoverySnapshot(
    val serverName: String,
    val enabled: Boolean,
    val fingerprint: String,
    val state: McpDiscoveryState,
    val errorMessage: String?,
    val lastSuccessAtMillis: Long?,
    val discoveredToolCount: Int,
    val stale: Boolean
)

enum class McpDiscoveryState {
    Idle,
    Discovering,
    Available,
    Failed,
    UsingStaleCache
}

data class ToolRegistrySnapshot(
    val tools: List<ToolRegistryEntry> = emptyList(),
    val conflicts: List<ToolConflict> = emptyList(),
    val droppedTools: List<ToolRegistryEntry> = emptyList()
) {
    companion object {
        val Empty = ToolRegistrySnapshot()
    }
}

data class ToolRegistryEntry(
    val name: String,
    val kind: ToolCallKind,
    val serverName: String?,
    val source: ToolRegistrySource
)

enum class ToolRegistrySource {
    Local,
    McpExplicit,
    McpDiscovered
}

data class ToolConflict(
    val name: String,
    val reason: ToolConflictReason,
    val candidates: List<ToolRegistryEntry>
)

enum class ToolConflictReason {
    HiddenByLocal,
    ExplicitOverridesDiscovered,
    DuplicateInServer,
    CrossServerConflict
}

enum class ToolConflictPolicy {
    DropConflictingMcpTools
}
