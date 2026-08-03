package com.niki914.s3ss10n

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolRegistryResolverTest {
    @Test
    fun `local tool hides mcp tool with same name`() {
        val resolution = ToolRegistryResolver().resolve(
            localTools = listOf(descriptor("shared", ToolCallKind.Local)),
            mcpServers = listOf(
                McpServerToolCandidates(
                    serverName = "docs",
                    discovered = listOf(descriptor("shared", ToolCallKind.Mcp("docs"))),
                    explicit = emptyList()
                )
            )
        )

        assertEquals(listOf("shared"), resolution.descriptors.map { it.name })
        assertEquals(ToolCallKind.Local, resolution.descriptors.single().kind)
        assertEquals(listOf("shared"), resolution.snapshot.tools.map { it.name })
        assertEquals(ToolRegistrySource.Local, resolution.snapshot.tools.single().source)
        assertEquals(ToolConflictReason.HiddenByLocal, resolution.snapshot.conflicts.single().reason)
        assertEquals(listOf(ToolRegistrySource.McpDiscovered), resolution.snapshot.droppedTools.map { it.source })
    }

    @Test
    fun `explicit mcp tool overrides discovered mcp tool`() {
        val resolution = ToolRegistryResolver().resolve(
            localTools = emptyList(),
            mcpServers = listOf(
                McpServerToolCandidates(
                    serverName = "docs",
                    discovered = listOf(descriptor("search", ToolCallKind.Mcp("docs"), description = "discovered")),
                    explicit = listOf(descriptor("search", ToolCallKind.Mcp("docs"), description = "explicit"))
                )
            )
        )

        assertEquals(listOf("search"), resolution.descriptors.map { it.name })
        assertEquals("explicit", resolution.descriptors.single().description)
        assertEquals(ToolRegistrySource.McpExplicit, resolution.snapshot.tools.single().source)
        assertEquals(ToolConflictReason.ExplicitOverridesDiscovered, resolution.snapshot.conflicts.single().reason)
        assertEquals(listOf(ToolRegistrySource.McpDiscovered), resolution.snapshot.droppedTools.map { it.source })
    }

    @Test
    fun `duplicate discovered tools in same server keeps first`() {
        val resolution = ToolRegistryResolver().resolve(
            localTools = emptyList(),
            mcpServers = listOf(
                McpServerToolCandidates(
                    serverName = "docs",
                    discovered = listOf(
                        descriptor("search", ToolCallKind.Mcp("docs"), description = "first"),
                        descriptor("search", ToolCallKind.Mcp("docs"), description = "second")
                    ),
                    explicit = emptyList()
                )
            )
        )

        assertEquals(listOf("search"), resolution.descriptors.map { it.name })
        assertEquals("first", resolution.descriptors.single().description)
        assertEquals(ToolConflictReason.DuplicateInServer, resolution.snapshot.conflicts.single().reason)
        assertEquals(listOf("search"), resolution.snapshot.droppedTools.map { it.name })
        assertEquals(listOf(ToolRegistrySource.McpDiscovered), resolution.snapshot.droppedTools.map { it.source })
    }

    @Test
    fun `same mcp tool name across servers is dropped as conflict`() {
        val resolution = ToolRegistryResolver().resolve(
            localTools = emptyList(),
            mcpServers = listOf(
                McpServerToolCandidates(
                    serverName = "docs",
                    discovered = listOf(descriptor("search", ToolCallKind.Mcp("docs"))),
                    explicit = emptyList()
                ),
                McpServerToolCandidates(
                    serverName = "code",
                    discovered = listOf(descriptor("search", ToolCallKind.Mcp("code"))),
                    explicit = emptyList()
                )
            )
        )

        assertEquals(emptyList<ToolDescriptor>(), resolution.descriptors)
        assertEquals(emptyList<ToolRegistryEntry>(), resolution.snapshot.tools)
        assertEquals(ToolConflictReason.CrossServerConflict, resolution.snapshot.conflicts.single().reason)
        assertEquals(listOf("docs", "code"), resolution.snapshot.droppedTools.map { it.serverName })
        assertEquals(
            listOf(ToolRegistrySource.McpDiscovered, ToolRegistrySource.McpDiscovered),
            resolution.snapshot.droppedTools.map { it.source }
        )
    }

    private fun descriptor(
        name: String,
        kind: ToolCallKind,
        description: String = "$name description"
    ): ToolDescriptor {
        return ToolDescriptor(
            name = name,
            description = description,
            inputSchema = mapOf("type" to "object"),
            kind = kind
        )
    }
}
