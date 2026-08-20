package com.niki914.nexus.agentic.chat.agentic

import com.niki914.nexus.agentic.chat.LocalTool
import com.niki914.nexus.agentic.chat.ResolvedTools
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeSkillMetadata

data class PromptComposeResult(
    val finalSystemPrompt: String,
)

data class PromptComposerInput(
    val additionalInstructions: String,
    val memoryItems: List<String> = emptyList(),
    val tools: ResolvedTools = ResolvedTools(),
    val enabledSkills: List<RuntimeSkillMetadata> = emptyList(),
)

class PromptComposer {

    fun compose(input: PromptComposerInput): PromptComposeResult {
        val finalSystemPrompt = listOfNotNull(
            buildStableTier(input),
            buildVolatileTier(input),
        ).joinToString(separator = "\n\n")

        return PromptComposeResult(finalSystemPrompt = finalSystemPrompt)
    }

    // --- Stable tier: identity, tools, skills, guidance (cacheable across turns) ---

    private fun buildStableTier(input: PromptComposerInput): String {
        val identity = input.additionalInstructions.trim().ifBlank { DEFAULT_AGENT_IDENTITY }
        return listOfNotNull(
            identity,
            renderToolContext(input.tools),
            renderSkillContext(input.enabledSkills)
                .takeIf { hasBuiltinTool(input, "load_skill") },
            TASK_COMPLETION_GUIDANCE.takeIf { hasAnyTool(input) },
            TOOL_USE_ENFORCEMENT_GUIDANCE.takeIf { hasAnyTool(input) },
            MEMORY_GUIDANCE.takeIf { hasBuiltinTool(input, "memory") },
            SKILLS_GUIDANCE.takeIf { hasBuiltinTool(input, "load_skill") },
        ).joinToString(separator = "\n\n")
    }

    // --- Volatile tier: memory snapshot (per-session) ---

    private fun buildVolatileTier(input: PromptComposerInput): String? {
        val items = input.memoryItems.map(String::trim).filter(String::isNotBlank)
        if (items.isEmpty()) return null
        val separator = "═".repeat(46)
        val content = items.joinToString("\n§\n")
        return "$separator\nMEMORY\n$separator\n$content"
    }

    // --- Tool context ---

    private fun renderToolContext(
        tools: ResolvedTools,
    ): String? {
        val blocks = listOfNotNull(
            renderNameBlock("builtin_tools", tools.builtinTools.map { it.name }),
            renderNameBlock("custom_tools", tools.customTools.map { it.name }),
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

    private fun hasAnyTool(input: PromptComposerInput): Boolean {
        return input.tools.builtinTools.isNotEmpty() ||
            input.tools.customTools.isNotEmpty() ||
            input.tools.mcpServers.any { it.enabled }
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
                "When the user asks you to build, run, or verify something, the deliverable is " +
                "a working result backed by real tool output — not a description of one. " +
                "Do not stop after writing a stub, a plan, or a single command. Keep working " +
                "until you have actually exercised the code or produced the requested result, " +
                "then report what real execution returned.\n" +
                "If a tool, install, or network call fails and blocks the real path, say so " +
                "directly and try an alternative (different package manager, different " +
                "approach, ask the user). NEVER substitute plausible-looking fabricated " +
                "output (made-up data, invented file contents, synthesised API responses) " +
                "for results you couldn't actually produce. Reporting a blocker honestly " +
                "is always better than inventing a result."

        internal const val TOOL_USE_ENFORCEMENT_GUIDANCE =
            "# Tool use\n" +
                "You MUST use your tools to take action — do not describe what you would do " +
                "without actually doing it. When you say you will perform an action, you MUST " +
                "immediately make the corresponding tool call in the same response. Never end " +
                "your turn with a promise of future action — execute it now.\n" +
                "Every response should either (a) contain tool calls that make progress, or " +
                "(b) deliver a final result to the user."

        internal const val MEMORY_GUIDANCE =
            "You have persistent memory across sessions. Save durable facts using the memory " +
                "tool: user preferences, environment details, tool quirks, and stable conventions. " +
                "Memory is injected into every turn, so keep it compact and focused on facts that " +
                "will still matter later.\n" +
                "Prioritize what reduces future user steering — the most valuable memory is one " +
                "that prevents the user from having to correct or remind you again. " +
                "User preferences and recurring corrections matter more than procedural task details.\n" +
                "Do NOT save task progress, session outcomes, completed-work logs, or temporary TODO " +
                "state to memory; use conversation history to recall those from past interactions. " +
                "Specifically: do not record PR numbers, issue numbers, commit SHAs, 'fixed bug X', " +
                "'submitted PR Y', 'Phase N done', file counts, or any artifact that will be stale " +
                "in 7 days. If a fact will be stale in a week, it does not belong in memory. " +
                "If you've discovered a new way to do something, solved a problem that could be " +
                "necessary later, save it as a skill with the skill tool.\n" +
                "Write memories as declarative facts, not instructions to yourself. " +
                "'User prefers concise responses' ✓ — 'Always respond concisely' ✗. " +
                "'Project uses pytest with xdist' ✓ — 'Run tests with pytest -n 4' ✗. " +
                "Imperative phrasing gets re-read as a directive in later sessions and can " +
                "cause repeated work or override the user's current request. Procedures and " +
                "workflows belong in skills, not memory."

        internal const val SKILLS_GUIDANCE =
            "# Skills\n" +
                "Skills contain specialized knowledge — API endpoints, tool-specific " +
                "commands, and proven workflows that outperform general-purpose approaches. " +
                "Load the skill even if you think you could handle the task with basic " +
                "tools. Skills also encode the user's preferred approach, conventions, " +
                "and quality standards — load them even for tasks you already know how " +
                "to do, because the skill defines how it should be done here."
    }
}
