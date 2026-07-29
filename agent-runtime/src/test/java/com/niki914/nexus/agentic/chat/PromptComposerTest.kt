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

    // --- Identity slot (stable tier) ---

    @Test
    fun compose_stableTierAlwaysContainsIdentity() {
        val result = PromptComposer().compose(
            PromptComposerInput(additionalInstructions = "")
        )

        assertTrue(result.finalSystemPrompt.contains(PromptComposer.DEFAULT_AGENT_IDENTITY))
    }

    @Test
    fun compose_emptyAdditionalInstructionsUsesDefaultIdentity() {
        val result = PromptComposer().compose(
            PromptComposerInput(additionalInstructions = " ")
        )

        assertTrue(result.finalSystemPrompt.contains(PromptComposer.DEFAULT_AGENT_IDENTITY))
        assertTrue(result.finalSystemPrompt.startsWith(PromptComposer.DEFAULT_AGENT_IDENTITY))
    }

    @Test
    fun compose_additionalInstructionsReplacesDefaultIdentity() {
        val customIdentity = "You are a custom test assistant."
        val result = PromptComposer().compose(
            PromptComposerInput(additionalInstructions = customIdentity)
        )

        assertFalse(result.finalSystemPrompt.contains(PromptComposer.DEFAULT_AGENT_IDENTITY))
        assertTrue(result.finalSystemPrompt.startsWith(customIdentity))
    }

    @Test
    fun compose_tiersOrderedStableBeforeVolatile() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "ctx",
                memoryItems = listOf("mem"),
            )
        )

        val stableIdx = result.finalSystemPrompt.indexOf("ctx")
        val volatileIdx = result.finalSystemPrompt.indexOf("## Agent Memory")

        assertTrue(stableIdx < volatileIdx)
    }

    // --- Memory section (volatile tier) ---

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
    fun compose_wrapsMemoryItemsInSingleXmlBlock() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "base",
                memoryItems = listOf(" A ", "B", " "),
            )
        )

        assertTrue(result.finalSystemPrompt.contains("## Agent Memory"))
        assertTrue(result.finalSystemPrompt.contains("<memory>\n- A\n- B\n</memory>"))
    }

    // --- Tool context (stable tier) ---

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
                        mcpSnapshot("cached", McpDiscoveryState.UsingStaleCache, discoveredToolCount = 3),
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

    // --- Skill context (stable tier) ---

    @Test
    fun compose_omitsSkillContextWhenNoEnabledSkills() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "base",
                enabledSkills = emptyList(),
            )
        )

        assertFalse(result.finalSystemPrompt.contains("## Skills (mandatory)"))
        assertFalse(result.finalSystemPrompt.contains("<available_skills>"))
    }

    @Test
    fun compose_omitsSkillContextWhenLoadSkillToolAbsent() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                enabledSkills = listOf(
                    skill(id = "skill-a", name = "Skill A", description = "Description A")
                ),
                tools = ResolvedTools(),
            )
        )

        assertFalse(result.finalSystemPrompt.contains("## Skills (mandatory)"))
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
                tools = ResolvedTools(
                    builtinTools = listOf(loadSkillBuiltin()),
                ),
            )
        )

        assertTrue(result.finalSystemPrompt.contains("## Skills (mandatory)"))
        assertTrue(
            result.finalSystemPrompt.contains(
                "  <skill>\n    <id>skill-a</id>\n    <name>Skill A</name>\n    <description>Description A</description>\n    <dir>/skills/skill-a</dir>\n  </skill>"
            )
        )
    }

    @Test
    fun compose_skillsPromptUsesMandatoryLanguage() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                enabledSkills = listOf(skill(id = "s1", name = "S1", description = "D1")),
                tools = ResolvedTools(
                    builtinTools = listOf(loadSkillBuiltin()),
                ),
            )
        )

        assertTrue(result.finalSystemPrompt.contains("you MUST load it"))
        assertTrue(result.finalSystemPrompt.contains("Err on the side of loading"))
    }

    @Test
    fun compose_rendersEnabledSkillsSortedById() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                enabledSkills = listOf(
                    skill(id = "skill-b", name = "Skill B", description = "Description B"),
                    skill(id = "group-a/skill-a", name = "Group Skill", description = "Group description"),
                ),
                tools = ResolvedTools(
                    builtinTools = listOf(loadSkillBuiltin()),
                ),
            )
        )

        val prompt = result.finalSystemPrompt
        assertTrue(prompt.indexOf("<id>group-a/skill-a</id>") < prompt.indexOf("<id>skill-b</id>"))
    }

    @Test
    fun compose_doesNotRenderSkillContent() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                enabledSkills = listOf(
                    skill(id = "skill-a", name = "Skill A", description = "Description A")
                ),
                tools = ResolvedTools(
                    builtinTools = listOf(loadSkillBuiltin()),
                ),
            )
        )

        assertTrue(result.finalSystemPrompt.contains("<id>skill-a</id>"))
        assertFalse(result.finalSystemPrompt.contains("DO_NOT_RENDER_SKILL_CONTENT"))
    }

    // --- Conditional guidance injection ---

    @Test
    fun compose_injectsMemoryGuidanceWhenMemorizeToolEnabled() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                tools = ResolvedTools(
                    builtinTools = listOf(
                        LocalTool.Builtin(
                            name = "memorize",
                            description = "Add to persistent memory",
                            tool = FakeBuiltinTool(name = "memorize"),
                        )
                    )
                ),
            )
        )

        assertTrue(result.finalSystemPrompt.contains(PromptComposer.MEMORY_GUIDANCE))
    }

    @Test
    fun compose_omitsMemoryGuidanceWhenMemorizeToolAbsent() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                tools = ResolvedTools(),
            )
        )

        assertFalse(result.finalSystemPrompt.contains(PromptComposer.MEMORY_GUIDANCE))
    }

    @Test
    fun compose_injectsSkillsGuidanceWhenLoadSkillToolEnabled() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                tools = ResolvedTools(
                    builtinTools = listOf(loadSkillBuiltin()),
                ),
            )
        )

        assertTrue(result.finalSystemPrompt.contains(PromptComposer.SKILLS_GUIDANCE))
    }

    @Test
    fun compose_omitsSkillsGuidanceWhenLoadSkillToolAbsent() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                tools = ResolvedTools(),
            )
        )

        assertFalse(result.finalSystemPrompt.contains(PromptComposer.SKILLS_GUIDANCE))
    }

    @Test
    fun compose_omitsTaskCompletionGuidanceWhenNoTools() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                tools = ResolvedTools(),
            )
        )

        assertFalse(result.finalSystemPrompt.contains(PromptComposer.TASK_COMPLETION_GUIDANCE))
    }

    @Test
    fun compose_injectsTaskCompletionGuidanceWhenToolsPresent() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                tools = ResolvedTools(
                    builtinTools = listOf(loadSkillBuiltin()),
                ),
            )
        )

        assertTrue(result.finalSystemPrompt.contains(PromptComposer.TASK_COMPLETION_GUIDANCE))
    }

    @Test
    fun compose_omitsToolUseEnforcementGuidanceWhenNoTools() {
        val result = PromptComposer().compose(
            PromptComposerInput(
                additionalInstructions = "",
                tools = ResolvedTools(),
            )
        )

        assertFalse(result.finalSystemPrompt.contains(PromptComposer.TOOL_USE_ENFORCEMENT_GUIDANCE))
    }

    // --- LLMController.buildMemoryItems ---

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

    // --- Helpers ---

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

    private fun loadSkillBuiltin(): LocalTool.Builtin {
        return LocalTool.Builtin(
            name = "load_skill",
            description = "Load a skill",
            tool = FakeBuiltinTool(name = "load_skill"),
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
