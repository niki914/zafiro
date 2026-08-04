package com.niki914.s3ss10n

internal class ToolRegistryResolver(
    private val policy: ToolConflictPolicy = ToolConflictPolicy.DropConflictingMcpTools
) {
    fun resolve(
        localTools: List<ToolDescriptor>,
        mcpServers: List<McpServerToolCandidates>
    ): ToolRegistryResolution {
        when (policy) {
            ToolConflictPolicy.DropConflictingMcpTools -> Unit
        }

        val conflicts = mutableListOf<ToolConflict>()
        val droppedTools = mutableListOf<ToolRegistryEntry>()
        val localEntries = localTools.map { it.toRegistryEntry(ToolRegistrySource.Local) }
        val localNames = localEntries.map { it.name }.toSet()

        val mcpCandidates = mcpServers.flatMap { server ->
            resolveServerCandidates(server, conflicts, droppedTools)
        }

        val hiddenByLocal = mcpCandidates.filter { it.entry.name in localNames }
        hiddenByLocal
            .groupBy { it.entry.name }
            .forEach { (name, candidates) ->
                val localEntry = localEntries.first { it.name == name }
                conflicts += ToolConflict(
                    name = name,
                    reason = ToolConflictReason.HiddenByLocal,
                    candidates = listOf(localEntry) + candidates.map { it.entry }
                )
                droppedTools += candidates.map { it.entry }
            }

        val visibleMcpCandidates = mcpCandidates.filterNot { it.entry.name in localNames }
        val crossServerConflicts = visibleMcpCandidates
            .groupBy { it.entry.name }
            .filterValues { candidates -> candidates.map { it.serverName }.distinct().size > 1 }

        crossServerConflicts.forEach { (name, candidates) ->
            conflicts += ToolConflict(
                name = name,
                reason = ToolConflictReason.CrossServerConflict,
                candidates = candidates.map { it.entry }
            )
            droppedTools += candidates.map { it.entry }
        }

        val crossServerConflictNames = crossServerConflicts.keys
        val keptMcpCandidates = visibleMcpCandidates.filterNot { it.entry.name in crossServerConflictNames }
        val descriptors = localTools + keptMcpCandidates.map { it.descriptor }
        val tools = localEntries + keptMcpCandidates.map { it.entry }

        return ToolRegistryResolution(
            descriptors = descriptors,
            snapshot = ToolRegistrySnapshot(
                tools = tools,
                conflicts = conflicts,
                droppedTools = droppedTools
            )
        )
    }

    private fun resolveServerCandidates(
        server: McpServerToolCandidates,
        conflicts: MutableList<ToolConflict>,
        droppedTools: MutableList<ToolRegistryEntry>
    ): List<McpToolCandidate> {
        val explicitCandidates = server.explicit.map {
            McpToolCandidate(
                serverName = server.serverName,
                descriptor = it,
                entry = it.toRegistryEntry(ToolRegistrySource.McpExplicit, server.serverName)
            )
        }
        val explicitByName = explicitCandidates.associateBy { it.entry.name }
        val discoveredCandidates = server.discovered.map {
            McpToolCandidate(
                serverName = server.serverName,
                descriptor = it,
                entry = it.toRegistryEntry(ToolRegistrySource.McpDiscovered, server.serverName)
            )
        }

        val discoveredOverriddenByExplicit = discoveredCandidates.filter { it.entry.name in explicitByName }
        discoveredOverriddenByExplicit
            .groupBy { it.entry.name }
            .forEach { (name, candidates) ->
                conflicts += ToolConflict(
                    name = name,
                    reason = ToolConflictReason.ExplicitOverridesDiscovered,
                    candidates = listOf(explicitByName.getValue(name).entry) + candidates.map { it.entry }
                )
                droppedTools += candidates.map { it.entry }
            }

        val discoveredWithoutExplicit = discoveredCandidates.filterNot { it.entry.name in explicitByName }
        val keptDiscovered = mutableListOf<McpToolCandidate>()
        discoveredWithoutExplicit
            .groupBy { it.entry.name }
            .forEach { (name, candidates) ->
                val first = candidates.first()
                keptDiscovered += first
                if (candidates.size > 1) {
                    val duplicates = candidates.drop(1)
                    conflicts += ToolConflict(
                        name = name,
                        reason = ToolConflictReason.DuplicateInServer,
                        candidates = candidates.map { it.entry }
                    )
                    droppedTools += duplicates.map { it.entry }
                }
            }

        return keptDiscovered + explicitCandidates
    }

    private fun ToolDescriptor.toRegistryEntry(
        source: ToolRegistrySource,
        serverName: String? = (kind as? ToolCallKind.Mcp)?.serverName
    ): ToolRegistryEntry {
        return ToolRegistryEntry(
            name = name,
            kind = kind,
            serverName = serverName,
            source = source
        )
    }

    private data class McpToolCandidate(
        val serverName: String,
        val descriptor: ToolDescriptor,
        val entry: ToolRegistryEntry
    )
}

internal data class McpServerToolCandidates(
    val serverName: String,
    val discovered: List<ToolDescriptor>,
    val explicit: List<ToolDescriptor>
)

internal data class ToolRegistryResolution(
    val descriptors: List<ToolDescriptor>,
    val snapshot: ToolRegistrySnapshot
)
