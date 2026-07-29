package com.niki914.nexus.agentic.chat.agentic

import com.niki914.nexus.agentic.chat.LocalTool
import com.niki914.nexus.agentic.chat.McpServerDefinition
import com.niki914.nexus.agentic.chat.ResolvedTools
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeSkillMetadata
import com.niki914.s3ss10n.McpDiscoverySnapshot
import com.niki914.s3ss10n.McpDiscoveryState
import com.niki914.s3ss10n.McpServerDiscoverySnapshot

data class PromptComposeResult(
    val finalSystemPrompt: String,
)

data class PromptComposerInput(
    val additionalInstructions: String,
    val memoryItems: List<String> = emptyList(),
    val tools: ResolvedTools = ResolvedTools(),
    val mcpDiscoverySnapshot: McpDiscoverySnapshot? = null,
    val enabledSkills: List<RuntimeSkillMetadata> = emptyList(),
)

class PromptComposer {

    fun compose(input: PromptComposerInput): PromptComposeResult {
        val finalSystemPrompt = listOfNotNull(
            buildStableTier(input),
            buildContextTier(input),
            buildVolatileTier(input),
        ).joinToString(separator = "\n\n")

        return PromptComposeResult(finalSystemPrompt = finalSystemPrompt)
    }

    // --- Stable tier: identity, tools, skills, guidance (cacheable across turns) ---

    private fun buildStableTier(input: PromptComposerInput): String {
        return listOfNotNull(
            DEFAULT_AGENT_IDENTITY,
            renderToolContext(input.tools, input.mcpDiscoverySnapshot),
            renderSkillContext(input.enabledSkills),
            TASK_COMPLETION_GUIDANCE,
            TOOL_USE_ENFORCEMENT_GUIDANCE,
            MEMORY_GUIDANCE.takeIf { hasBuiltinTool(input, "memorize") },
            SKILLS_GUIDANCE.takeIf { hasBuiltinTool(input, "load_skill") },
        ).joinToString(separator = "\n\n")
    }

    // --- Context tier: user-supplied instructions ---

    private fun buildContextTier(input: PromptComposerInput): String? {
        val text = input.additionalInstructions.trim()
        if (text.isEmpty()) return null
        return "## Additional instructions\n\n$text"
    }

    // --- Volatile tier: memory snapshot (per-session) ---

    private fun buildVolatileTier(input: PromptComposerInput): String? {
        val items = input.memoryItems.map(String::trim).filter(String::isNotBlank)
        if (items.isEmpty()) return null
        return buildString {
            appendLine("## Agent Memory")
            appendLine()
            appendLine("<memory>")
            items.forEach { appendLine("- $it") }
            append("</memory>")
        }
    }

    // --- Tool context ---

    private fun renderToolContext(
        tools: ResolvedTools,
        snapshot: McpDiscoverySnapshot?,
    ): String? {
        val blocks = listOfNotNull(
            renderNameBlock("builtin_tools", tools.builtinTools.map { it.name }),
            renderNameBlock("custom_tools", tools.customTools.map { it.name }),
            renderMcpServers(tools, snapshot),
        )
        if (blocks.isEmpty()) return null
        return "## Tool Context\n\n${blocks.joinToString(separator = "\n\n")}"
    }

    private fun renderNameBlock(tag: String, names: List<String>): String? {
        val normalized = names.map(String::trim).filter(String::isNotBlank).distinct().sorted()
        if (normalized.isEmpty()) return null
        return normalized.joinToString(
            separator = "\n",
            prefix = "<$tag>\n",
            postfix = "\n</$tag>",
        ) { "- $it" }
    }

    private fun renderMcpServers(
        tools: ResolvedTools,
        snapshot: McpDiscoverySnapshot?,
    ): String? {
        val enabled = tools.mcpServers
            .filter(McpServerDefinition::enabled)
            .map { it.name.trim() }
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
        if (enabled.isEmpty()) return null
        val snapshotByName = snapshot?.servers?.values.orEmpty().associateBy { it.serverName }
        return enabled.joinToString(
            separator = "\n",
            prefix = "<mcp_servers>\n",
            postfix = "\n</mcp_servers>",
        ) { name -> "- ${snapshotByName[name]?.let(::renderMcpStatus) ?: "$name: idle"}" }
    }

    private fun renderMcpStatus(server: McpServerDiscoverySnapshot): String = when (server.state) {
        McpDiscoveryState.Available -> "${server.serverName}: loaded ${server.discoveredToolCount} tools"
        McpDiscoveryState.Discovering -> "${server.serverName}: loading"
        McpDiscoveryState.Failed -> {
            val msg = server.errorMessage?.trim().takeUnless { it.isNullOrBlank() }
            if (msg == null) "${server.serverName}: failed"
            else "${server.serverName}: failed, msg: $msg"
        }
        McpDiscoveryState.UsingStaleCache -> "${server.serverName}: using cached ${server.discoveredToolCount} tools"
        McpDiscoveryState.Idle -> "${server.serverName}: idle"
    }

    // --- Skill context ---

    private fun renderSkillContext(skills: List<RuntimeSkillMetadata>): String? {
        val entries = skills
            .mapNotNull { skill ->
                val id = skill.id.trim()
                if (id.isBlank()) return@mapNotNull null
                val name = skill.name.trim().ifBlank { id }
                val desc = skill.description.trim()
                val dir = skill.absoluteDir.trim()
                buildString {
                    appendLine("  <skill>")
                    appendLine("    <id>$id</id>")
                    appendLine("    <name>$name</name>")
                    if (desc.isNotBlank()) appendLine("    <description>$desc</description>")
                    if (dir.isNotBlank()) appendLine("    <dir>$dir</dir>")
                    append("  </skill>")
                }
            }
            .sorted()
        if (entries.isEmpty()) return null

        return buildString {
            appendLine("## Skills (mandatory)")
            appendLine()
            appendLine(
                "Before replying, scan the skills below. If a skill matches or is even partially " +
                    "relevant to your task, you MUST load it with load_skill and follow its " +
                    "instructions. Err on the side of loading — it is always better to have " +
                    "context you don't need than to miss critical steps, pitfalls, or established " +
                    "workflows. Skills contain specialized knowledge and proven approaches that " +
                    "outperform general-purpose methods."
            )
            appendLine()
            appendLine("<available_skills>")
            entries.forEach { appendLine(it) }
            append("</available_skills>")
        }
    }

    // --- Helpers ---

    private fun hasBuiltinTool(input: PromptComposerInput, name: String): Boolean {
        return input.tools.builtinTools.any { it.name == name }
    }

    // --- Guidance constants ---

    companion object {
        internal const val DEFAULT_AGENT_IDENTITY =
            "You are Nexus, an intelligent AI assistant. " +
                "You are helpful, knowledgeable, and direct. " +
                "You assist users with a wide range of tasks including answering questions, " +
                "managing their device, and executing actions via your tools. " +
                "You communicate clearly, admit uncertainty when appropriate, and prioritize " +
                "being genuinely useful over being verbose."

        internal const val TASK_COMPLETION_GUIDANCE =
            "# Finishing the job\n" +
                "When the user asks you to do something, the deliverable is a working result " +
                "backed by real tool output — not a description of one. Do not stop after " +
                "writing a plan or describing what you would do. Keep working until you have " +
                "actually produced the requested result.\n" +
                "If a tool fails and blocks progress, say so directly and try an alternative " +
                "approach. Never substitute plausible-looking fabricated output for results " +
                "you couldn't actually produce. Reporting a blocker honestly is always better " +
                "than inventing a result."

        internal const val TOOL_USE_ENFORCEMENT_GUIDANCE =
            "# Tool use\n" +
                "You MUST use your tools to take action — do not describe what you would do " +
                "without actually doing it. When you say you will perform an action, you MUST " +
                "immediately make the corresponding tool call in the same response. Never end " +
                "your turn with a promise of future action — execute it now.\n" +
                "Every response should either (a) contain tool calls that make progress, or " +
                "(b) deliver a final result to the user."

        internal const val MEMORY_GUIDANCE =
            "# Memory\n" +
                "You have persistent memory across sessions. Use the memorize tool to save " +
                "durable facts: user preferences, environment details, and stable conventions. " +
                "Memory is injected into every turn, so keep it compact and focused on facts " +
                "that will still matter later.\n" +
                "Write memories as declarative facts, not instructions to yourself. " +
                "\"User prefers concise responses\" ✓ — \"Always respond concisely\" ✗. " +
                "\"Project uses Kotlin with coroutines\" ✓ — \"Use coroutines for async\" ✗.\n" +
                "Do NOT save task progress, session outcomes, PR numbers, commit SHAs, " +
                "or anything that will be stale within a week. Procedures and workflows " +
                "belong in skills, not memory."

        internal const val SKILLS_GUIDANCE =
            "# Maintaining skills\n" +
                "After completing a complex task or discovering a non-trivial workflow, " +
                "consider whether the approach should be saved as a skill for reuse. " +
                "When using a skill and finding it outdated, incomplete, or wrong, " +
                "update it immediately — don't wait to be asked. " +
                "Skills that aren't maintained become liabilities."
    }
}
