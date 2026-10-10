package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.ToolExecutionPreflight
import com.niki914.zafiro.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalReadMode
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.chat.agentic.shell.TerminalToolResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 本地持久化会话终端工具 (shell)。
 * 默认自动开辟并持久复用 alias="default" 的本地交互式会话，支持工作目录和环境变量的连续保持。
 * 同时支持通过 alias 开辟独立隔离会话，并通过 ToolExecutionPreflight 进行权限前检。
 */
open class ShellBuiltin(
    private val preflight: ToolExecutionPreflight = ToolExecutionPreflight(),
) : BaseSessionBuiltin() {

    override val name: String = "shell"

    override val description: String =
        "Execute commands in a local Android shell environment with persistent session state. " +
                "Working directory, exported environment variables, and shell history persist between calls.\n\n" +
                "Default session: If 'session' and 'alias' are omitted, commands automatically run in a shared default session (alias='default').\n" +
                "Custom session: Provide 'alias' to create or target an isolated session (e.g. alias='build_worker').\n" +
                "Execution mode: By default, commands run synchronously and return upon completion. If execution exceeds 'timeout' seconds, it degrades to background execution (status='running', timed_out=true); use action='read' to poll output later.\n" +
                "Identity: Choose 'user' (default local shell), 'root' (via su), or 'shizuku'.\n\n" +
                "IMPORTANT RULES:\n" +
                "1. Never run blocking interactive commands without non-interactive flags (e.g. do not run top, vim, nano, or unmetered ping).\n" +
                "2. To send interactive input to a running session, use action='write' or action='submit'."

    override val inputSchemaJson: String get() = SHELL_SCHEMA

    override fun parseArguments(argumentsJson: String): ShellArgs {
        val obj = Json.parseToJsonElement(argumentsJson).jsonObject
        val action = obj["action"]?.jsonPrimitive?.content?.let { SessionAction.fromWireName(it) }
        val session = obj["session"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
        val alias = obj["alias"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
        val command = obj["command"]?.jsonPrimitive?.content
        val identity = obj["identity"]?.jsonPrimitive?.content?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        val workdir = obj["workdir"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
        val text = obj["text"]?.jsonPrimitive?.content
        val background = obj["background"]?.jsonPrimitive?.booleanOrNull ?: false
        val timeout = obj["timeout"]?.jsonPrimitive?.longOrNull
        return ShellArgs(
            command = command,
            session = session,
            alias = alias,
            identity = identity ?: DEFAULT_IDENTITY,
            workdir = workdir,
            action = action,
            text = text,
            background = background,
            timeout = timeout,
        )
    }

    override suspend fun dispatchRequest(args: BaseSessionArgs): String {
        val shellArgs = args as? ShellArgs
        val command = args.command
        if (command != null) {
            val decision = preflight.evaluate(command, toolName = name)
            if (!decision.allowed) {
                return TerminalToolResponse.policyBlocked(decision)
            }
            preflight.ensurePathAccess(command + " " + shellArgs?.workdir.orEmpty())
        }
        return super.dispatchRequest(args)
    }

    override suspend fun resolveSessionTarget(args: BaseSessionArgs): SessionTarget {
        val shellArgs = args as? ShellArgs
        val explicitSession = args.session
        if (explicitSession != null) {
            val resolved = sessionRegistry.resolveSessionId(explicitSession)
            if (resolved != null) return SessionTarget.Resolved(resolved)
            if (TerminalSessionPool.get(explicitSession) != null) return SessionTarget.Resolved(explicitSession)
            return SessionTarget.Error(TerminalToolResponse.invalidRequest("Session '$explicitSession' not found."))
        }

        val explicitAlias = args.alias
        if (explicitAlias != null) {
            val existing = sessionRegistry.findByAlias(explicitAlias)
            if (existing != null) return SessionTarget.Resolved(existing.sessionId)

            val identity = shellArgs?.identity ?: DEFAULT_IDENTITY
            val workdir = shellArgs?.workdir
            return when (val outcome = openLocalSession(identity = identity, workdir = workdir)) {
                is TerminalOpenOutcome.Success -> {
                    sessionRegistry.register(
                        sessionId = outcome.session,
                        alias = explicitAlias,
                        metadata = mapOf("identity" to identity),
                    )
                    SessionTarget.Resolved(outcome.session)
                }
                is TerminalOpenOutcome.Failure -> {
                    SessionTarget.Error(
                        TerminalToolResponse.invalidRequest(
                            outcome.failure.message ?: "Failed to open shell session for alias '$explicitAlias'."
                        )
                    )
                }
                is TerminalOpenOutcome.InvalidRequest -> {
                    SessionTarget.Error(TerminalToolResponse.invalidRequest(outcome.message))
                }
            }
        }

        val defaultSession = onDefaultSession()
        return if (defaultSession != null) {
            SessionTarget.Resolved(defaultSession)
        } else {
            SessionTarget.Error(TerminalToolResponse.invalidRequest("Failed to open default shell session."))
        }
    }

    override suspend fun onDefaultSession(): String? {
        val existing = sessionRegistry.findByAlias(DEFAULT_ALIAS)
        if (existing != null) {
            return existing.sessionId
        }

        return when (val outcome = openLocalSession(identity = DEFAULT_IDENTITY, workdir = null)) {
            is TerminalOpenOutcome.Success -> {
                sessionRegistry.register(
                    sessionId = outcome.session,
                    alias = DEFAULT_ALIAS,
                    metadata = mapOf("identity" to DEFAULT_IDENTITY),
                )
                outcome.session
            }
            is TerminalOpenOutcome.Failure -> null
            is TerminalOpenOutcome.InvalidRequest -> null
        }
    }

    protected open suspend fun openLocalSession(identity: String, workdir: String?): TerminalOpenOutcome {
        return TerminalSessionPool.open(
            identity = identity,
            cwd = workdir,
            collectOutput = true,
        )
    }

    companion object {
        private const val LOG_TAG = "niki914_zafiro_ShellBuiltin"
        const val DEFAULT_ALIAS = "default"
        const val DEFAULT_IDENTITY = "user"
        private const val DEFAULT_TIMEOUT_SEC = 30L

        private val SHELL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "Shell command to execute. Output and environment persist in the session."
                },
                "session": {
                  "type": "string",
                  "description": "Session ID or alias to target. If omitted along with alias, reuses the persistent default session (alias='default')."
                },
                "alias": {
                  "type": "string",
                  "description": "Human-friendly alias for session naming and isolation (e.g. 'dev', 'compiler'). Creates a new session if it does not exist."
                },
                "identity": {
                  "type": "string",
                  "enum": ["user", "root", "shizuku"],
                  "description": "Shell execution identity (default 'user')."
                },
                "workdir": {
                  "type": "string",
                  "description": "Working directory for session initialization (absolute path)."
                },
                "timeout": {
                  "type": "integer",
                  "minimum": 1,
                  "description": "Max seconds to wait before degrading to background (default 30)."
                },
                "background": {
                  "type": "boolean",
                  "description": "Run the command in background immediately and return status='running'."
                },
                "action": {
                  "type": "string",
                  "enum": ["list", "read", "submit", "write", "close"],
                  "description": "Session action: 'read', 'submit', 'write', 'close', 'list'."
                },
                "text": {
                  "type": "string",
                  "description": "Text to send for 'submit' or 'write' actions."
                }
              }
            }
        """.trimIndent()
    }
}

class ShellArgs(
    command: String? = null,
    session: String? = null,
    alias: String? = null,
    val identity: String = ShellBuiltin.DEFAULT_IDENTITY,
    val workdir: String? = null,
    action: SessionAction? = null,
    text: String? = null,
    timeout: Long? = null,
    background: Boolean = false,
) : BaseSessionArgs(
    command = command,
    session = session,
    alias = alias,
    action = action,
    text = text,
    timeout = timeout,
    background = background,
)
