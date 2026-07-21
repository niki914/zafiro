package com.niki914.nexus.agentic.chat.agentic.shell

import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.nexus.agentic.util.TextPatternMatcher
import com.niki914.nexus.xposed.api.util.LockState
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode

data class ShellCommandPolicyDecision(
    val allowed: Boolean,
    val code: String = "OK",
    val reason: String = "",
    val matchedRuleId: String? = null,
    val matchedRuleName: String? = null,
    val matchedPattern: String? = null,
)

class ShellCommandSafetyPolicy(
    private val listExecutionRules: suspend () -> List<ExecutionRule> = {
        RuntimeEnvironment.awaitSettingsGateway().listExecutionRules()
    },
    private val isUnlocked: suspend () -> Boolean = { LockState.isUnlocked() },
) {
    suspend fun evaluate(command: String): ShellCommandPolicyDecision {
        val rules = listExecutionRules()
        if (rules.isEmpty()) {
            return ShellCommandPolicyDecision(allowed = true)
        }
        val unlocked = if (rules.any { it.enabledMode == ExecutionRuleEnabledMode.LOCKED_ONLY }) {
            isUnlocked()
        } else {
            true
        }
        val candidates = command.matchCandidates()
        rules.asSequence()
            .filter { it.isActive(unlocked) }
            .forEach { rule ->
                rule.patterns.asSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .forEach { pattern ->
                        if (candidates.any { candidate ->
                                TextPatternMatcher.matches(
                                    candidate,
                                    pattern
                                )
                            }) {
                            return ShellCommandPolicyDecision(
                                allowed = false,
                                code = "RULE_BLOCKED",
                                reason = "Command blocked by execution rule '${rule.name}' with pattern '$pattern'.",
                                matchedRuleId = rule.id,
                                matchedRuleName = rule.name,
                                matchedPattern = pattern,
                            )
                        }
                    }
            }
        return ShellCommandPolicyDecision(allowed = true)
    }

    private fun String.shellLikeTokens(): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false

        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
        }

        for (char in this) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }

                char == '\\' -> escaped = true
                quote != null -> {
                    if (char == quote) {
                        quote = null
                    } else {
                        current.append(char)
                    }
                }

                char == '\'' || char == '"' -> quote = char
                char.isWhitespace() || char in SHELL_TOKEN_SEPARATORS -> flush()
                else -> current.append(char)
            }
        }
        if (escaped) {
            current.append('\\')
        }
        flush()
        return tokens
    }

    private fun String.matchCandidates(): List<String> {
        val candidates = linkedSetOf<String>()
        collectMatchCandidates(depth = 0, candidates = candidates)
        return candidates.toList()
    }

    private fun String.collectMatchCandidates(depth: Int, candidates: MutableSet<String>) {
        if (depth > MAX_SHELL_PAYLOAD_DEPTH) {
            return
        }
        val tokens = shellLikeTokens()
        val normalizedTokens = tokens
            .map { it.normalizedShellToken() }
            .filter { it.isNotBlank() }
        candidates += this
        candidates += normalizedTokens.joinToString(separator = " ")
        normalizedTokens.nestedShellPayloads().forEach { payload ->
            candidates += payload
            payload.collectMatchCandidates(depth = depth + 1, candidates = candidates)
        }
    }

    private fun String.normalizedShellToken(): String {
        return lowercase()
            .trim()
            .trim('"', '\'')
    }

    private fun List<String>.nestedShellPayloads(): List<String> {
        val payloads = mutableListOf<String>()
        for (index in indices) {
            val executable = this[index].executableName()
            val payload = when {
                executable in SHELL_COMMANDS -> shellCommandPayloadAfterC(startIndex = index + 1)
                executable == "eval" -> drop(index + 1).joinToString(" ").takeIf { it.isNotBlank() }
                else -> null
            }
            if (payload != null) {
                payloads += payload
            }
        }
        return payloads
    }

    private fun List<String>.shellCommandPayloadAfterC(startIndex: Int): String? {
        for (index in startIndex until size) {
            val token = this[index]
            if (token == "-c") {
                return getOrNull(index + 1)
            }
            if (!token.startsWith("-")) {
                return null
            }
        }
        return null
    }

    private fun String.executableName(): String {
        return substringAfterLast('/')
    }

    private fun ExecutionRule.isActive(unlocked: Boolean): Boolean {
        return when (enabledMode) {
            ExecutionRuleEnabledMode.ALWAYS -> true
            ExecutionRuleEnabledMode.LOCKED_ONLY -> !unlocked
            ExecutionRuleEnabledMode.DISABLED -> false
        }
    }

    companion object {
        private const val MAX_SHELL_PAYLOAD_DEPTH = 8
        private val SHELL_TOKEN_SEPARATORS = setOf(';', '&', '|', '`', '$', '(', ')', '<', '>')
        private val SHELL_COMMANDS = setOf("sh", "bash", "mksh")
    }
}
