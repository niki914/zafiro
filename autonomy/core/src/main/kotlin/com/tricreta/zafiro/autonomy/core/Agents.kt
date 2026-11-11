package com.tricreta.zafiro.autonomy.core

/** Text builders and rules for agent profiles, delegation and self-learning. Pure, so every branch is unit-tested. */
object AgentNames {
    private val allowed = Regex("""^[A-Za-z0-9][A-Za-z0-9 _-]{1,39}$""")

    /** @return an error message, or null when [name] is usable. */
    fun validate(name: String, existing: Collection<String>, ownName: String? = null): String? {
        val n = name.trim()
        if (!allowed.matches(n)) return "Name must be 2 to 40 characters: letters, digits, spaces, - or _."
        val clash = existing.any { it.equals(n, ignoreCase = true) && !it.equals(ownName?.trim(), ignoreCase = true) }
        return if (clash) "Another agent already has this name." else null
    }

    /** Case-insensitive match used by `agent(call)`, tolerant of extra spaces. */
    fun find(name: String, names: Collection<String>): String? =
        names.firstOrNull { it.trim().equals(name.trim(), ignoreCase = true) }
}

object AgentBrief {
    /**
     * The prompt an agent receives. The tool/skill lists are strong instructions, not a sandbox: upstream has no per-agent
     * tool filtering, so the UI says "guidance". Delegated agents are told not to delegate further (no runaway chains).
     */
    fun compose(
        agentName: String,
        instructions: String,
        tools: List<String>,
        skills: List<String>,
        task: String,
        delegated: Boolean,
        history: String = "",
    ): String = buildString {
        appendLine("You are the agent \"$agentName\".")
        appendLine(instructions.trim())
        appendLine()
        if (tools.isNotEmpty()) appendLine("Use ONLY these tools unless the task is impossible without another: ${tools.joinToString(", ")}.")
        if (skills.isNotEmpty()) appendLine("Before starting, load these skills with load_skill and follow them: ${skills.joinToString(", ")}.")
        if (delegated) {
            appendLine("You were called by another agent. Do only the task below, do not call other agents, and do not use the screen unless the task needs it.")
        }
        appendLine("Never bypass CAPTCHA, login, 2FA, consent screens or payments: ask for a human with take_over instead.")
        appendLine(StatusProtocol.INSTRUCTION)
        if (history.isNotBlank()) {
            appendLine()
            appendLine(history.trim())
        }
        appendLine()
        appendLine("TASK:")
        append(task.trim())
    }
}

data class PastRun(val whenText: String, val state: String, val summary: String?, val error: String?)

/** Deterministic memory of earlier runs of the same task, injected into the next prompt (no model call, no cost). */
object RunHistory {
    private const val MAX_RUNS = 3
    private const val MAX_CHARS = 280

    fun format(runs: List<PastRun>): String {
        val usable = runs.take(MAX_RUNS)
        if (usable.isEmpty()) return ""
        return buildString {
            appendLine("PREVIOUS RUNS OF THIS TASK (most recent first; learn from them, do not repeat their mistakes):")
            usable.forEach { r ->
                val note = (r.error?.takeIf { it.isNotBlank() } ?: r.summary.orEmpty()).replace('\n', ' ').trim()
                appendLine("- ${r.whenText}: ${r.state}${if (note.isNotEmpty()) " - ${note.take(MAX_CHARS)}" else ""}")
            }
        }.trimEnd()
    }
}

object Reflection {
    /** Sent as one extra turn after a run; the agent saves lessons with its memory tool. Skipped when over budget. */
    const val PROMPT: String =
        "Run finished. Reflect briefly: save at most 3 durable lessons with the memory tool " +
            "(what worked, what failed and why, user preferences you discovered). " +
            "Each lesson must be specific and reusable on a future run. Do not save anything already in memory, " +
            "secrets, or one-off details. If there is nothing worth keeping, save nothing. Reply with the single word DONE."
}
