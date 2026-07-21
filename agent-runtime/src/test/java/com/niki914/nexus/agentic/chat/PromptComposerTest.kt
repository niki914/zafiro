package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.PromptComposer
import com.niki914.nexus.agentic.chat.agentic.PromptComposerInput
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeSkillMetadata
import com.niki914.s3ss10n.LocalToolConfig
import com.niki914.s3ss10n.McpDiscoverySnapshot
import com.niki914.s3ss10n.McpDiscoveryState
import com.niki914.s3ss10n.McpServerDiscoverySnapshot
import com.niki914.s3ss10n.ToolRegistrySnapshot
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptComposerTest {

    @Test
    fun compose_omitsMemorySectionWhenNoMemoryItems() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "base",
                memoryItems = listOf(" "),
            )
        )

        assertFalse(result.finalSystemPrompt.contains("## Agent Memory"))
        assertFalse(result.finalSystemPrompt.contains("<memory>"))
    }

    @Test
    fun compose_omitsToolContextWhenNoToolsOrMcpServers() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "base",
                tools = ResolvedTools(),
            )
        )

        assertFalse(result.finalSystemPrompt.contains("## Tool Context"))
    }

    @Test
    fun compose_omitsSkillContextWhenNoEnabledSkills() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "base",
                enabledSkills = emptyList(),
            )
        )

        assertFalse(result.finalSystemPrompt.contains("## Skill Context"))
        assertFalse(result.finalSystemPrompt.contains("<available_skills>"))
    }

    @Test
    fun compose_rendersOneEnabledSkill() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                enabledSkills = listOf(
                    skill(id = "skill-a", name = "Skill A", description = "Description A")
                ),
            )
        )

        assertTrue(
            result.finalSystemPrompt.contains("## Skill Context")
        )
        assertTrue(
            result.finalSystemPrompt.contains(
                "  <skill>\n    <id>skill-a</id>\n    <name>Skill A</name>\n    <description>Description A</description>\n    <dir>/skills/skill-a</dir>\n  </skill>"
            )
        )
        assertTrue(
            result.finalSystemPrompt.contains(
                "- Scan <available_skills>."
            )
        )
    }

    @Test
    fun compose_rendersEnabledSkillsSortedById() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                enabledSkills = listOf(
                    skill(id = "skill-b", name = "Skill B", description = "Description B"),
                    skill(
                        id = "group-a/skill-a",
                        name = "Group Skill",
                        description = "Group description"
                    ),
                ),
            )
        )

        val prompt = result.finalSystemPrompt
        assertTrue(prompt.indexOf("<id>group-a/skill-a</id>") < prompt.indexOf("<id>skill-b</id>"))
    }

    @Test
    fun compose_doesNotRenderSkillContent() {
        val loadedSkillContent = "DO_NOT_RENDER_SKILL_CONTENT"
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                enabledSkills = listOf(
                    skill(
                        id = "skill-a",
                        name = "Skill A",
                        description = "Description A",
                    )
                ),
            )
        )

        assertTrue(result.finalSystemPrompt.contains("<id>skill-a</id>"))
        assertFalse(result.finalSystemPrompt.contains(loadedSkillContent))
    }

    @Test
    fun compose_rendersOnlyPresentToolBlocks() {
        val customTool = LocalTool.Custom(
            name = "launch_wechat",
            description = "Launch WeChat",
            enabled = true,
            command = "am start",
        )
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                tools = ResolvedTools(customTools = listOf(customTool)),
            )
        )

        assertTrue(result.finalSystemPrompt.contains("<custom_tools>\n- launch_wechat\n</custom_tools>"))
        assertFalse(result.finalSystemPrompt.contains("<builtin_tools>"))
        assertFalse(result.finalSystemPrompt.contains("<mcp_servers>"))
    }

    @Test
    fun compose_rendersBuiltinToolsWithoutDescriptions() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                tools = ResolvedTools(
                    builtinTools = listOf(
                        LocalTool.Builtin(
                            name = "notify",
                            description = "Send a notification",
                            tool = FakeBuiltinTool(name = "notify"),
                        )
                    )
                ),
            )
        )

        assertTrue(result.finalSystemPrompt.contains("<builtin_tools>\n- notify\n</builtin_tools>"))
        assertFalse(result.finalSystemPrompt.contains("Send a notification"))
        assertFalse(result.finalSystemPrompt.contains("<custom_tools>"))
    }

    @Test
    fun compose_rendersMcpStatusWithoutToolNames() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                tools = ResolvedTools(
                    mcpServers = listOf(
                        mcpServer("docs", "secret_tool"),
                        mcpServer("loading", "loading_tool"),
                        mcpServer("broken", "broken_tool"),
                        mcpServer("cached", "cached_tool"),
                    )
                ),
                mcpDiscoverySnapshot = McpDiscoverySnapshot(
                    servers = listOf(
                        mcpSnapshot("docs", McpDiscoveryState.Available, discoveredToolCount = 20),
                        mcpSnapshot("loading", McpDiscoveryState.Discovering),
                        mcpSnapshot("broken", McpDiscoveryState.Failed, errorMessage = "boom"),
                        mcpSnapshot(
                            "cached",
                            McpDiscoveryState.UsingStaleCache,
                            discoveredToolCount = 3
                        ),
                    ).associateBy { it.serverName },
                    finalToolRegistry = ToolRegistrySnapshot.Empty,
                ),
            )
        )

        assertTrue(result.finalSystemPrompt.contains("- docs: loaded 20 tools"))
        assertTrue(result.finalSystemPrompt.contains("- loading: loading"))
        assertTrue(result.finalSystemPrompt.contains("- broken: failed, msg: boom"))
        assertTrue(result.finalSystemPrompt.contains("- cached: using cached 3 tools"))
        assertFalse(result.finalSystemPrompt.contains("secret_tool"))
        assertFalse(result.finalSystemPrompt.contains("loading_tool"))
        assertFalse(result.finalSystemPrompt.contains("broken_tool"))
        assertFalse(result.finalSystemPrompt.contains("cached_tool"))
    }

    @Test
    fun compose_rendersIdleMcpServerWhenSnapshotMissing() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                tools = ResolvedTools(
                    mcpServers = listOf(mcpServer("docs", "secret_tool"))
                ),
                mcpDiscoverySnapshot = null,
            )
        )

        assertTrue(result.finalSystemPrompt.contains("<mcp_servers>\n- docs: idle\n</mcp_servers>"))
        assertFalse(result.finalSystemPrompt.contains("secret_tool"))
    }

    @Test
    fun compose_omitsAdditionalInstructionsWhenBlank() {
        val result = PromptComposer().compose(
            PromptComposerInput(additionalInstructions = " ")
        )

        assertEquals("# System Prompt", result.finalSystemPrompt)
        assertFalse(result.finalSystemPrompt.contains("## Additional instructions"))
    }

    @Test
    fun llmController_prefersMemoriesOverMemoryPrompt() {
        val items = buildMemoryItems(
            RuntimeLlmConfig(
                memoryPrompt = "legacy",
                memories = listOf(" A ", "B", " "),
            )
        )

        assertEquals(listOf("A", "B"), items)
        assertFalse(items.contains("legacy"))
    }

    @Test
    fun compose_wrapsMemoryItemsInSingleXmlBlock() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "base",
                memoryItems = listOf(" A ", "B", " "),
            )
        )

        assertEquals(
            "## Agent Memory\n\n<memory>\n- A\n- B\n</memory>",
            result.sections.single { it.title == "Agent Memory" }.content,
        )
        assertEquals(
            "# System Prompt\n\n## Agent Memory\n\n<memory>\n- A\n- B\n</memory>\n\n## Additional instructions\n\nbase",
            result.finalSystemPrompt,
        )
    }

    private fun buildMemoryItems(config: RuntimeLlmConfig): List<String> {
        val method = LLMController::class.java.getDeclaredMethod(
            "buildMemoryItems",
            RuntimeLlmConfig::class.java,
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(LLMController, config) as List<String>
    }

    private fun skill(
        id: String,
        name: String,
        description: String,
    ): RuntimeSkillMetadata {
        return RuntimeSkillMetadata(
            id = id,
            name = name,
            description = description,
            relativePath = "$id/SKILL.md",
            absolutePath = "/skills/$id/SKILL.md",
            absoluteDir = "/skills/$id",
            enabled = true,
        )
    }

    private fun mcpServer(name: String, cachedToolName: String): McpServerDefinition.Http {
        return McpServerDefinition.Http(
            name = name,
            url = "https://example.com/$name",
            cachedTools = listOf(
                McpCachedTool(
                    name = cachedToolName,
                    description = "hidden",
                    inputSchema = JsonObject(emptyMap()),
                )
            ),
        )
    }

    private fun mcpSnapshot(
        name: String,
        state: McpDiscoveryState,
        errorMessage: String? = null,
        discoveredToolCount: Int = 0,
    ): McpServerDiscoverySnapshot {
        return McpServerDiscoverySnapshot(
            serverName = name,
            enabled = true,
            fingerprint = name,
            state = state,
            errorMessage = errorMessage,
            lastSuccessAtMillis = null,
            discoveredToolCount = discoveredToolCount,
            stale = false,
        )
    }

    private class FakeBuiltinTool(
        override val name: String,
    ) : BuiltinTool() {
        override fun configure(config: LocalToolConfig) = Unit

        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
            return BuiltinToolResult.success(message = "ok")
        }
    }
}
