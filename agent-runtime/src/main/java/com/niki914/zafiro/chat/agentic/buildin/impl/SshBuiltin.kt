package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshHostKeyPolicy
import com.niki914.libterm.SshOpenOptions
import com.niki914.zafiro.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.chat.agentic.shell.TerminalToolResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 远程 SSH 持久化会话终端工具 (ssh)。
 * 支持多机器显式寻址与凭据建联，严禁默认落入本地环境，严禁对已有会话重复携带连接凭据。
 */
open class SshBuiltin : BaseSessionBuiltin() {

    override val name: String = "ssh"

    override val description: String =
        "Execute commands on remote machines via SSH with persistent session state.\n\n" +
                "Session Lifecycle:\n" +
                "1. Connect: Provide 'host', 'username', and 'password' (optional: 'port', 'alias', 'workdir') to establish a new SSH connection. This assigns a session_id and optional alias.\n" +
                "2. Command: Execute commands in an established session by passing 'session' (or 'alias') along with 'command'.\n" +
                "3. Multi-Session: Multiple concurrent SSH connections to different remote hosts can be maintained. Use action='list' to see all active sessions.\n" +
                "4. Stream & Input: Use action='read' to poll output, action='write' or action='submit' to send stdin, and action='close' to disconnect.\n\n" +
                "STRICT RULES:\n" +
                "1. When targeting an existing session via 'session' or 'alias', NEVER pass connection credentials ('host', 'username', 'password'). Doing so will be rejected.\n" +
                "2. Never run blocking interactive commands without non-interactive flags (e.g. do not run top, vim, nano, or unmetered ping).\n" +
                "3. By default, commands run synchronously and return upon completion. If execution exceeds 'timeout' seconds, it degrades to background execution (status='running', timed_out=true).\n" +
                "4. For long-running tasks, prefer setting a larger 'timeout' upfront (e.g. timeout=60). If running in background, do NOT busy-poll with action='read' immediately; wait sensibly (e.g. run a blocking 'sleep 5' to 'sleep 30' depending on task scale) before reading output."

    override val inputSchemaJson: String get() = SSH_SCHEMA

    override fun parseArguments(argumentsJson: String): SshArgs {
        val obj = Json.parseToJsonElement(argumentsJson).jsonObject
        val action = obj["action"]?.jsonPrimitive?.content?.let { SessionAction.fromWireName(it) }
        val session = obj["session"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
        val alias = obj["alias"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
        val command = obj["command"]?.jsonPrimitive?.content
        val host = obj["host"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
        val port = obj["port"]?.jsonPrimitive?.intOrNull
        val username = obj["username"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
        val password = obj["password"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
        val workdir = obj["workdir"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
        val text = obj["text"]?.jsonPrimitive?.content
        val background = obj["background"]?.jsonPrimitive?.booleanOrNull ?: false
        val timeout = obj["timeout"]?.jsonPrimitive?.longOrNull
        return SshArgs(
            command = command,
            session = session,
            alias = alias,
            host = host,
            port = port,
            username = username,
            password = password,
            workdir = workdir,
            action = action,
            text = text,
            background = background,
            timeout = timeout,
        )
    }

    override suspend fun dispatchRequest(args: BaseSessionArgs): String {
        val sshArgs = args as? SshArgs
        if (args.action != null) {
            return handleAction(args)
        }
        if (args.command != null) {
            return handleCommand(args)
        }
        if (sshArgs?.host != null || sshArgs?.username != null || sshArgs?.password != null) {
            return handleConnect(sshArgs)
        }
        return TerminalToolResponse.invalidRequest(
            "Either 'command', 'action', or SSH connection parameters ('host', 'username', 'password') are required."
        )
    }

    override suspend fun handleAction(args: BaseSessionArgs): String {
        if (args.action != SessionAction.LIST) {
            val sshArgs = args as? SshArgs
            if (sshArgs?.host != null || sshArgs?.username != null || sshArgs?.password != null) {
                return TerminalToolResponse.invalidRequest(
                    "Cannot specify connection credentials ('host', 'username', 'password') with action '${args.action?.name?.lowercase()}'."
                )
            }
        }
        return super.handleAction(args)
    }

    private suspend fun handleConnect(args: SshArgs): String {
        val sessionId = when (val target = resolveSessionTarget(args)) {
            is SessionTarget.Resolved -> target.sessionId
            is SessionTarget.Error -> return target.responseJson
        }
        val meta = sessionRegistry.findById(sessionId)
        val allSessions = sessionRegistry.all().map { entry ->
            val item = linkedMapOf<String, JsonElement>(
                "session_id" to JsonPrimitive(entry.sessionId),
                "created_at" to JsonPrimitive(entry.createdAt),
            )
            entry.alias?.let { item["alias"] = JsonPrimitive(it) }
            if (entry.metadata.isNotEmpty()) {
                item["metadata"] = JsonObject(entry.metadata.mapValues { JsonPrimitive(it.value) })
            }
            JsonObject(item)
        }
        val payload = linkedMapOf<String, JsonElement>(
            "status" to JsonPrimitive("connected"),
            "session_id" to JsonPrimitive(sessionId),
            "identity" to JsonPrimitive("ssh"),
            "active_sessions" to JsonArray(allSessions),
            "hint" to JsonPrimitive(
                "Connection established. Use 'session'='$sessionId' with 'command' or action='submit' to interact."
            ),
        )
        meta?.alias?.let { payload["alias"] = JsonPrimitive(it) }
        meta?.metadata?.get("host")?.let { payload["host"] = JsonPrimitive(it) }
        meta?.metadata?.get("user")?.let { payload["user"] = JsonPrimitive(it) }
        return JsonObject(payload).toString()
    }

    override suspend fun onDefaultSession(): String? = null

    override suspend fun resolveSessionTarget(args: BaseSessionArgs): SessionTarget {
        val sshArgs = args as? SshArgs
        val hasCredentials = sshArgs?.host != null || sshArgs?.username != null || sshArgs?.password != null

        val explicitSession = args.session
        if (explicitSession != null) {
            if (hasCredentials) {
                return SessionTarget.Error(
                    TerminalToolResponse.invalidRequest(
                        "Cannot specify connection credentials ('host', 'username', 'password') when targeting an existing session."
                    )
                )
            }
            val resolved = sessionRegistry.resolveSessionId(explicitSession)
            if (resolved != null) return SessionTarget.Resolved(resolved)
            if (TerminalSessionPool.get(explicitSession) != null) return SessionTarget.Resolved(explicitSession)
            return SessionTarget.Error(TerminalToolResponse.invalidRequest("Session '$explicitSession' not found."))
        }

        val explicitAlias = args.alias
        if (explicitAlias != null) {
            val existing = sessionRegistry.findByAlias(explicitAlias)
            if (existing != null) {
                if (hasCredentials) {
                    return SessionTarget.Error(
                        TerminalToolResponse.invalidRequest(
                            "Cannot specify connection credentials ('host', 'username', 'password') when targeting an existing session alias '$explicitAlias'."
                        )
                    )
                }
                return SessionTarget.Resolved(existing.sessionId)
            }
        }

        val host = sshArgs?.host?.trim()?.takeIf(String::isNotBlank)
        val username = sshArgs?.username?.trim()?.takeIf(String::isNotBlank)
        val password = sshArgs?.password?.takeIf(String::isNotBlank)
        if (host == null || username == null || password == null) {
            return SessionTarget.Error(
                TerminalToolResponse.invalidRequest(
                    "SSH connection requires 'host', 'username', and 'password' when opening a new session."
                )
            )
        }

        val options = SshOpenOptions(
            host = host,
            port = sshArgs.port ?: SshOpenOptions.DEFAULT_PORT,
            username = username,
            auth = SshAuth.Password(password),
            hostKeyPolicy = SshHostKeyPolicy.AcceptAny,
            connectTimeoutMillis = SshOpenOptions.DEFAULT_CONNECT_TIMEOUT_MILLIS,
            serverAliveIntervalMillis = SshOpenOptions.DEFAULT_SERVER_ALIVE_INTERVAL_MILLIS,
        )

        return when (val outcome = openSshSession(options, sshArgs.workdir)) {
            is TerminalOpenOutcome.Success -> {
                sessionRegistry.register(
                    sessionId = outcome.session,
                    alias = explicitAlias,
                    metadata = mapOf("identity" to "ssh", "host" to host, "user" to username),
                )
                SessionTarget.Resolved(outcome.session)
            }
            is TerminalOpenOutcome.Failure -> {
                SessionTarget.Error(
                    TerminalToolResponse.failure(
                        failure = outcome.failure,
                        elapsedSeconds = outcome.elapsedSeconds,
                        identity = "ssh",
                    )
                )
            }
            is TerminalOpenOutcome.InvalidRequest -> {
                SessionTarget.Error(TerminalToolResponse.invalidRequest(outcome.message))
            }
        }
    }

    protected open suspend fun openSshSession(options: SshOpenOptions, cwd: String?): TerminalOpenOutcome {
        return TerminalSessionPool.openSsh(options = options, cwd = cwd)
    }

    companion object {
        private const val DEFAULT_TIMEOUT_SEC = 30L

        private val SSH_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "Command to execute on the remote machine."
                },
                "session": {
                  "type": "string",
                  "description": "Target session ID or alias. If provided, connection credentials must NOT be included."
                },
                "alias": {
                  "type": "string",
                  "description": "Human-friendly alias for session naming (e.g. 'nas', 'prod-db'). Registers alias during connect, or targets existing."
                },
                "host": {
                  "type": "string",
                  "description": "Remote hostname or IP address (required when opening a new session)."
                },
                "port": {
                  "type": "integer",
                  "description": "SSH port (default 22)."
                },
                "username": {
                  "type": "string",
                  "description": "SSH username (required when opening a new session)."
                },
                "password": {
                  "type": "string",
                  "description": "SSH password (required when opening a new session)."
                },
                "workdir": {
                  "type": "string",
                  "description": "Remote initial working directory."
                },
                "timeout": {
                  "type": "integer",
                  "minimum": 1,
                  "description": "Max seconds to wait for command execution before degrading to background (default 30). Set larger (e.g. 60 or 120) for longer commands."
                },
                "background": {
                  "type": "boolean",
                  "description": "Run the command in background immediately and return status='running'. Avoid tight polling; use sensible sleep intervals (e.g. 5-30s) before checking."
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

class SshArgs(
    command: String? = null,
    session: String? = null,
    alias: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null,
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
