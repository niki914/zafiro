package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshHostKeyPolicy
import com.niki914.libterm.SshOpenOptions
import com.niki914.zafiro.chat.agentic.ToolExecutionPreflight
import com.niki914.zafiro.chat.agentic.shell.SessionRegistry
import com.niki914.zafiro.chat.agentic.shell.TerminalInteractiveReadOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalReadMode
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.chat.agentic.shell.TerminalToolResponse
import kotlinx.coroutines.delay
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
open class SshBuiltin(
    preflight: ToolExecutionPreflight = ToolExecutionPreflight(),
) : BaseSessionBuiltin(preflight) {

    override val name: String = "ssh"

    override val description: String =
        "Execute commands on remote machines via SSH with persistent session state.\n\n" +
                "Canonical Invocations:\n" +
                "1. Connect: action='connect' with 'host', 'username', and 'password' (optional: 'port', 'alias', 'workdir').\n" +
                "2. Command: pass 'session' (or 'alias') and 'command'.\n" +
                "3. Stdin & Interrupt: action='submit' (sends newline) or action='write' (raw text, or \"\\u0003\" for Ctrl-C).\n" +
                "4. Session Management: action='read' to poll background output, action='list' to view sessions, action='close' to disconnect.\n\n" +
                "Rules:\n" +
                "- Credentials: Pass credentials only with action='connect'. Existing sessions reject credentials.\n" +
                "- Reconnect: If a session drops, call action='connect' with the same 'alias' and credentials to replace it.\n" +
                "- Non-interactive: Use non-interactive flags for blocking commands (e.g. ping -c 3, top -b -n 1).\n" +
                "- In-flight lock: A running command blocks new commands until completed or interrupted (Ctrl-C via action='write')."

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
            "Either 'command' or action='connect' with connection credentials ('host', 'username', 'password') is required."
        )
    }

    override suspend fun handleAction(args: BaseSessionArgs): String {
        if (args.action == SessionAction.CONNECT) {
            val sshArgs = args as? SshArgs
                ?: return TerminalToolResponse.invalidRequest("Missing SSH connection parameters.")
            return handleConnect(sshArgs)
        }
        if (args.action != SessionAction.LIST) {
            val sshArgs = args as? SshArgs
            if (sshArgs?.host != null || sshArgs?.username != null || sshArgs?.password != null) {
                return TerminalToolResponse.invalidRequest(
                    "Cannot specify connection credentials ('host', 'username', 'password') with action '${args.action?.name?.lowercase()}'. Use action='connect' to establish a new session."
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
        val allSessions = sessionRegistry.all()
            .filter { it.scope == sessionScope && isSessionAlive(it.sessionId) }
            .map { entry ->
                val item = linkedMapOf<String, JsonElement>(
                    "session_id" to JsonPrimitive(entry.sessionId),
                    "created_at" to JsonPrimitive(entry.createdAt),
                )
                entry.alias?.let { item["alias"] = JsonPrimitive(it) }
                if (entry.metadata.isNotEmpty()) {
                    item["metadata"] =
                        JsonObject(entry.metadata.mapValues { JsonPrimitive(it.value) })
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

    override val supportsCtrlCAbort: Boolean get() = true

    override val sessionScope: String get() = SessionRegistry.SCOPE_SSH

    override suspend fun resolveSessionTarget(args: BaseSessionArgs): SessionTarget {
        val sshArgs = args as? SshArgs
        val hasCredentials =
            sshArgs?.host != null || sshArgs?.username != null || sshArgs?.password != null

        val explicitSession = args.session
        if (explicitSession != null) {
            val registered = sessionRegistry.resolveSessionId(explicitSession, sessionScope)
            if (registered != null) {
                if (isSessionAlive(registered)) {
                    if (hasCredentials) {
                        return SessionTarget.Error(
                            TerminalToolResponse.invalidRequest(
                                "Cannot specify connection credentials ('host', 'username', 'password') when targeting a live session."
                            )
                        )
                    }
                    return SessionTarget.Resolved(registered)
                }
                // 登记项是僵尸（连接已断）：释放句柄与别名，带凭据则落到下方重建
                sessionRegistry.unregister(registered)
                TerminalSessionPool.close(registered)
                if (!hasCredentials) {
                    return SessionTarget.Error(TerminalToolResponse.sessionGone(explicitSession))
                }
            } else {
                // 句柄不在本域登记里（含别的工具的句柄）：不带凭据就是找不到；带凭据按新连接处理，
                // 否则模型只能对着一个用不了的句柄反复试
                if (!hasCredentials) {
                    return SessionTarget.Error(TerminalToolResponse.sessionNotFound(explicitSession))
                }
            }
        }

        val explicitAlias = args.alias
        if (explicitAlias != null) {
            val existing = sessionRegistry.findByAlias(explicitAlias, sessionScope)
            if (existing != null) {
                if (isSessionAlive(existing.sessionId)) {
                    if (hasCredentials) {
                        return SessionTarget.Error(
                            TerminalToolResponse.invalidRequest(
                                "Cannot specify connection credentials ('host', 'username', 'password') when targeting a live session alias '$explicitAlias'."
                            )
                        )
                    }
                    return SessionTarget.Resolved(existing.sessionId)
                }
                sessionRegistry.unregister(existing.sessionId)
                TerminalSessionPool.close(existing.sessionId)
                if (!hasCredentials) {
                    return SessionTarget.Error(TerminalToolResponse.sessionGone(explicitAlias))
                }
                // 带凭据：落到下方重新建联，register 会重新占用该别名
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
                awaitShellSettled(outcome.session)
                sessionRegistry.register(
                    sessionId = outcome.session,
                    alias = explicitAlias,
                    scope = sessionScope,
                    metadata = mapOf(SessionRegistry.META_IDENTITY to "ssh", "host" to host, "user" to username),
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

    protected open suspend fun openSshSession(
        options: SshOpenOptions,
        cwd: String?
    ): TerminalOpenOutcome {
        return TerminalSessionPool.openSsh(options = options, cwd = cwd)
    }

    /**
     * 建联后等登录输出（banner/提示符）安静下来再放行第一条命令：否则命令写入时远端
     * shell 未就绪，回显与 banner 交错（实测新会话首条命令输出必丢的触发条件）。
     * 静默窗口 150ms，上限 2s；会话不在池里（测试 fake）直接放行。
     */
    private suspend fun awaitShellSettled(sessionId: String) {
        val deadline = System.currentTimeMillis() + 2_000L
        var lastSequence = -1L
        var lastChangeAt = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val sequence = when (val outcome = TerminalSessionPool.readInteractive(
                session = sessionId,
                mode = TerminalReadMode.SNAPSHOT,
                maxBytes = Int.MAX_VALUE,
            )) {
                is TerminalInteractiveReadOutcome.Success -> outcome.sequence
                else -> return
            }
            val now = System.currentTimeMillis()
            if (sequence != lastSequence) {
                lastSequence = sequence
                lastChangeAt = now
            } else if (now - lastChangeAt >= 150L) {
                return
            }
            delay(25L)
        }
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
                  "description": "Target session ID or alias of an active session."
                },
                "alias": {
                  "type": "string",
                  "description": "Human-friendly alias for session naming (e.g. 'nas', 'prod-db'). Registers alias during action='connect', or targets existing session."
                },
                "host": {
                  "type": "string",
                  "description": "Remote hostname or IP address (required for action='connect')."
                },
                "port": {
                  "type": "integer",
                  "description": "SSH port (default 22)."
                },
                "username": {
                  "type": "string",
                  "description": "SSH username (required for action='connect')."
                },
                "password": {
                  "type": "string",
                  "description": "SSH password (required for action='connect')."
                },
                "workdir": {
                  "type": "string",
                  "description": "Remote initial working directory."
                },
                "timeout": {
                  "type": "integer",
                  "minimum": 1,
                  "description": "Max seconds to wait for command execution before degrading to background (default 30)."
                },
                "background": {
                  "type": "boolean",
                  "description": "Run the command in background immediately and return status='running'. Use action='read' to collect output."
                },
                "action": {
                  "type": "string",
                  "enum": ["connect", "list", "read", "submit", "write", "close"],
                  "description": "Session action: 'connect' to establish session, 'list' to show active sessions, 'read'/'submit'/'write'/'close' for session interaction."
                },
                "text": {
                  "type": "string",
                  "description": "Text to send for 'submit' (adds newline) or 'write' (raw characters) actions."
                }
              },
              "required": []
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
