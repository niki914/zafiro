package com.niki914.nexus.agentic.chat.agentic.custom

import com.niki914.nexus.agentic.chat.LocalTool
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CustomToolExecutor(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val safetyPolicy: ShellCommandSafetyPolicy = ShellCommandSafetyPolicy(),
    private val commandExecutor: suspend (command: String, timeoutMs: Long) -> CustomCommandExecutionResult =
        ::executeTerminalCommand,
) {
    suspend fun execute(tool: LocalTool.Custom): String {
        val command = tool.command.trim()
        if (command.isBlank()) {
            return buildFailureJson(
                command = command,
                message = "Custom tool '${tool.name}' has empty command.",
            )
        }
        val decision = safetyPolicy.evaluate(command)
        if (!decision.allowed) {
            return buildFailureJson(
                command = command,
                message = decision.reason.ifBlank { "Command blocked by safety policy." },
            )
        }
        val result = commandExecutor(command, timeoutMs)
        return if (result.ok) {
            buildSuccessJson(command = command, stdout = result.stdout)
        } else {
            buildFailureJson(
                command = command,
                message = result.message,
            )
        }
    }

    private fun buildSuccessJson(command: String, stdout: String): String {
        return JsonObject(
            mapOf(
                "ok" to JsonPrimitive(true),
                "command" to JsonPrimitive(command),
                "stdout" to JsonPrimitive(stdout),
            )
        ).toString()
    }

    private fun buildFailureJson(command: String, message: String): String {
        return JsonObject(
            mapOf(
                "ok" to JsonPrimitive(false),
                "command" to JsonPrimitive(command),
                "message" to JsonPrimitive(message),
            )
        ).toString()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 10_000L
    }
}

data class CustomCommandExecutionResult(
    val ok: Boolean,
    val stdout: String = "",
    val message: String = "",
)

private suspend fun executeTerminalCommand(
    command: String,
    timeoutMs: Long
): CustomCommandExecutionResult {
    return when (val outcome =
        TerminalSessionPool.executeCustomCommand(command = command, timeoutMs = timeoutMs)) {
        is TerminalCommandOutcome.Success -> {
            val exitCode = outcome.result.exitCode ?: -1
            val stdout = outcome.result.stdout.toByteArray().decodeToString() +
                    outcome.result.stderr.toByteArray().decodeToString()
            if (exitCode == 0) {
                CustomCommandExecutionResult(ok = true, stdout = stdout)
            } else {
                CustomCommandExecutionResult(
                    ok = false,
                    message = stdout.ifBlank { "Command exited with code $exitCode." },
                )
            }
        }

        is TerminalCommandOutcome.Timeout -> CustomCommandExecutionResult(
            ok = false,
            message = "Command timed out after ${timeoutMs}ms.",
        )

        is TerminalCommandOutcome.Failure -> CustomCommandExecutionResult(
            ok = false,
            message = outcome.failure.message ?: "Terminal command failed.",
        )

        is TerminalCommandOutcome.SessionNotFound -> CustomCommandExecutionResult(
            ok = false,
            message = "Terminal session '${outcome.session}' not found.",
        )

        is TerminalCommandOutcome.Busy -> CustomCommandExecutionResult(
            ok = false,
            message = "Another custom tool command is already running.",
        )

        is TerminalCommandOutcome.UnexpectedError -> CustomCommandExecutionResult(
            ok = false,
            message = outcome.throwable.message ?: "Unexpected terminal error.",
        )
    }
}
