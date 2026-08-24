package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshHostKeyPolicy
import com.niki914.libterm.SshOpenOptions
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalAsyncStartOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalCloseOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalInteractiveReadOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalInteractiveWriteOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalReadMode
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalReadOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.libterm.runtime.CommandResult
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalToolResponse
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalToolResponse.stdoutText
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalToolResponse.stderrText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class TerminalBuiltin(
    private val safetyPolicy: ShellCommandSafetyPolicy = ShellCommandSafetyPolicy(),
) : BuiltinTool(), RawJsonBuiltinTool {
    override val name: String = "terminal"

    override val description: String =
        "Execute shell commands in an Android terminal environment. " +
                "Filesystem and current working directory persist between calls within a session. " +
                "Exported environment variables persist within a session but reset when the session is closed.\n" +
                "\n" +
                "Reserve terminal for: builds, installs, git, processes, scripts, network, package managers, " +
                "and anything that needs a shell.\n" +
                "\n" +
                "Foreground (default): Commands return INSTANTLY when done, even if the timeout is high. " +
                "Set timeout=300 for long builds/scripts — you'll still get the result in seconds if it's fast. " +
                "Prefer foreground for short commands. Foreground mode is available for the local backend only.\n" +
                "\n" +
                "Background: Set background=true to run a command asynchronously. " +
                "Almost always pair with notify_on_complete=true — background without notify runs silently. " +
                "Two legitimate uses:\n" +
                "  (1) Long-lived processes that never exit (servers, watchers, daemons) — silent is correct, " +
                "there's no exit to notify on.\n" +
                "  (2) Long-running bounded tasks (tests, builds, deploys, batch jobs) — MUST set " +
                "notify_on_complete=true. Without it you'll either forget to check or sit blocked waiting.\n" +
                "For servers/watchers, do NOT use shell-level background wrappers (nohup/disown/setsid/trailing '&') " +
                "in foreground mode. Use background=true so the runtime can track lifecycle and output.\n" +
                "After starting a server, verify readiness with a health check or log signal, " +
                "then run tests in a separate terminal() call. Avoid blind sleep loops.\n" +
                "\n" +
                "Working directory: Use 'workdir' for per-command cwd.\n" +
                "\n" +
                "Backend: Set backend to \"local\" (default) for the Android device shell, " +
                "or \"ssh\" for a remote host. SSH backend requires background=true; " +
                "it cannot reliably detect command completion in foreground mode.\n" +
                "- backend=\"local\": Use identity to pick the execution user — \"user\" (default, unprivileged), " +
                "\"root\" (via su), or \"shizuku\". Shizuku requires device support, a running service, " +
                "and granted authorization.\n" +
                "- backend=\"ssh\": Connect to a remote host. Provide host, username, and password. " +
                "host_key_policy defaults to \"accept_any\"; use \"known_hosts_file\" with known_hosts_path " +
                "for host verification.\n" +
                "\n" +
                "Background tasks return a session_id. Use action=\"read\" to poll output and status " +
                "(status is \"running\" while the process is active, \"exited\" once it finishes — with exit_code), " +
                "action=\"write\" to send stdin input (newline is NOT appended automatically — add \\n when needed), " +
                "action=\"submit\" to send stdin input plus a newline, and action=\"close\" to release the session. " +
                "Sessions opened for foreground commands are automatically closed after the command completes."

    override val defaultEnabled: Boolean = true

    override val inputSchemaJson: String? get() = TERMINAL_SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        return BuiltinToolResult.failure(
            code = "RAW_JSON_ONLY",
            message = "terminal accepts raw JSON requests only.",
            hint = """Example: {"command":"ls -la"} or {"command":"ls","backend":"ssh","background":true,"host":"1.2.3.4","username":"root","password":"..."}""",
        )
    }

    override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
        return try {
            val args = parseArguments(request.argumentsJson)
            when {
                // Action mode takes priority: if the user explicitly passes an action,
                // route to the action handler even when command is also present.
                args.action != null -> handleAction(args)
                args.command != null -> handleCommand(args)
                else -> TerminalToolResponse.invalidRequest(
                    "Either 'command' or 'action' is required."
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            TerminalToolResponse.invalidRequest(error.message ?: "Invalid terminal request.")
        } catch (error: Throwable) {
            TerminalToolResponse.internalError(error)
        }
    }

    // ── Command-first mode (Hermes-aligned) ──────────────────────────────────

    private suspend fun handleCommand(args: TerminalArgs): String {
        val command = args.requireCommand()
        val timeoutSec = args.resolveTimeout()
        val decision = safetyPolicy.evaluate(command)
        if (!decision.allowed) {
            return TerminalToolResponse.policyBlocked(decision)
        }

        return when (args.backend) {
            Backend.LOCAL -> handleLocalCommand(args, command, timeoutSec)
            Backend.SSH -> {
                if (!args.background) {
                    return TerminalToolResponse.invalidRequest(
                        "SSH backend requires background=true. SSH sessions cannot " +
                            "reliably detect command completion in foreground mode. " +
                            "Use background=true and action=\"read\" to check results."
                    )
                }
                handleSshCommand(args, command, timeoutSec)
            }
        }
    }

    private suspend fun handleLocalCommand(
        args: TerminalArgs,
        command: String,
        timeoutSec: Long,
    ): String {
        val identity = args.identity ?: DEFAULT_LOCAL_IDENTITY
        val workdir = args.workdir
        val timeoutMs = timeoutSec * 1000L

        return if (args.background) {
            startBackgroundLocal(identity, workdir, command, timeoutMs, args.notifyOnComplete)
        } else {
            executeForegroundLocal(identity, workdir, command, timeoutMs, args.mergeStderr)
        }
    }

    private suspend fun executeForegroundLocal(
        identity: String,
        workdir: String?,
        command: String,
        timeoutMs: Long,
        mergeStderr: Boolean,
    ): String {
        val outcome = TerminalSessionPool.openAndExecute(
            identity = identity,
            cwd = workdir,
            command = command,
            timeoutMs = timeoutMs,
        )
        return try {
            when (outcome) {
                is TerminalCommandOutcome.Success -> {
                    val result = outcome.result
                    TerminalToolResponse.commandSuccessFlat(
                        stdout = mergedStdout(result, mergeStderr),
                        stderr = if (mergeStderr) "" else result.stderrText(),
                        exitCode = result.exitCode ?: UNKNOWN_EXIT_CODE,
                    )
                }

                is TerminalCommandOutcome.Timeout -> {
                    val result = outcome.result
                    TerminalToolResponse.commandTimeoutFlat(
                        stdout = mergedStdout(result, mergeStderr),
                        stderr = if (mergeStderr) "" else result.stderrText(),
                        timeoutSec = timeoutMs / 1000L,
                    )
                }

                is TerminalCommandOutcome.Failure -> TerminalToolResponse.commandError(
                    code = TerminalToolResponse.failureCode(outcome.failure),
                    message = outcome.failure.message ?: "Command execution failed.",
                )

                is TerminalCommandOutcome.SessionNotFound -> TerminalToolResponse.sessionNotFound(
                    outcome.session
                )

                is TerminalCommandOutcome.Busy -> TerminalToolResponse.sessionBusy(
                    outcome.session,
                    outcome.asyncId
                )

                is TerminalCommandOutcome.UnexpectedError -> TerminalToolResponse.internalError(
                    outcome.throwable,
                    outcome.elapsedSeconds,
                )
            }
        } finally {
            // Critical: the session opened for this one-shot command must be
            // closed on every outcome branch, including Failure.
            closeForegroundSession(outcome)
        }
    }

    private suspend fun startBackgroundLocal(
        identity: String,
        workdir: String?,
        command: String,
        timeoutMs: Long,
        notifyOnComplete: Boolean,
    ): String {
        // Open a session first, then start async
        return when (val openOutcome = TerminalSessionPool.open(identity = identity, cwd = workdir)) {
            is TerminalOpenOutcome.Success -> {
                when (val asyncOutcome = TerminalSessionPool.startAsync(
                    session = openOutcome.session,
                    command = command,
                    timeoutMs = timeoutMs,
                    notifyOnComplete = notifyOnComplete,
                )) {
                    is TerminalAsyncStartOutcome.Accepted -> TerminalToolResponse.backgroundAccepted(
                        openOutcome.session
                    )

                    is TerminalAsyncStartOutcome.SessionNotFound -> TerminalToolResponse.sessionNotFound(
                        asyncOutcome.session
                    )

                    is TerminalAsyncStartOutcome.Busy -> TerminalToolResponse.sessionBusy(
                        asyncOutcome.session,
                        asyncOutcome.asyncId
                    )

                    is TerminalAsyncStartOutcome.InvalidRequest -> TerminalToolResponse.invalidRequest(
                        asyncOutcome.message
                    )
                }
            }

            is TerminalOpenOutcome.Failure -> TerminalToolResponse.commandError(
                code = TerminalToolResponse.failureCode(openOutcome.failure),
                message = openOutcome.failure.message ?: "Failed to open terminal session.",
            )

            is TerminalOpenOutcome.InvalidRequest -> TerminalToolResponse.invalidRequest(
                openOutcome.message
            )
        }
    }

    private suspend fun handleSshCommand(
        args: TerminalArgs,
        command: String,
        timeoutSec: Long,
    ): String {
        // Foreground SSH is rejected in handleCommand(); only background reaches
        // this point, so the foreground wrapper below is unreachable defense only.
        val sshOptions = args.requireSshOpenOptions()
        val workdir = args.workdir
        val timeoutMs = timeoutSec * 1000L
        return startBackgroundSsh(sshOptions, workdir, command, timeoutMs, args.notifyOnComplete)
    }

    /**
     * Foreground SSH wrapper. No longer reachable from the main path
     * (handleCommand() rejects backend=ssh without background=true), but kept
     * as a defensive layer: if it is ever invoked, the try-finally still
     * guarantees the opened session is closed on every outcome branch.
     */
    private suspend fun executeForegroundSsh(
        sshOptions: SshOpenOptions,
        workdir: String?,
        command: String,
        timeoutMs: Long,
        mergeStderr: Boolean,
    ): String {
        val outcome = TerminalSessionPool.openAndExecuteSsh(
            options = sshOptions,
            cwd = workdir,
            command = command,
            timeoutMs = timeoutMs,
        )
        return try {
            when (outcome) {
                is TerminalCommandOutcome.Success -> {
                    val result = outcome.result
                    TerminalToolResponse.commandSuccessFlat(
                        stdout = mergedStdout(result, mergeStderr),
                        stderr = if (mergeStderr) "" else result.stderrText(),
                        exitCode = result.exitCode ?: UNKNOWN_EXIT_CODE,
                    )
                }

                is TerminalCommandOutcome.Timeout -> {
                    val result = outcome.result
                    TerminalToolResponse.commandTimeoutFlat(
                        stdout = mergedStdout(result, mergeStderr),
                        stderr = if (mergeStderr) "" else result.stderrText(),
                        timeoutSec = timeoutMs / 1000L,
                    )
                }

                is TerminalCommandOutcome.Failure -> TerminalToolResponse.commandError(
                    code = TerminalToolResponse.failureCode(outcome.failure),
                    message = outcome.failure.message ?: "SSH command execution failed.",
                )

                is TerminalCommandOutcome.SessionNotFound -> TerminalToolResponse.sessionNotFound(
                    outcome.session
                )

                is TerminalCommandOutcome.Busy -> TerminalToolResponse.sessionBusy(
                    outcome.session,
                    outcome.asyncId
                )

                is TerminalCommandOutcome.UnexpectedError -> TerminalToolResponse.internalError(
                    outcome.throwable,
                    outcome.elapsedSeconds,
                )
            }
        } finally {
            closeForegroundSession(outcome)
        }
    }

    /**
     * SSH sessions are always interactive. Instead of running a one-shot exec
     * (which would store a completedResult and block subsequent reads from the
     * interactive collector), the initial command is sent as stdin input via
     * writeInteractive. Subsequent read/write/submit/close operations all work
     * against the same persistent interactive channel.
     */
    private suspend fun startBackgroundSsh(
        sshOptions: SshOpenOptions,
        workdir: String?,
        command: String,
        timeoutMs: Long,
        @Suppress("UNUSED_PARAMETER") notifyOnComplete: Boolean,
    ): String {
        return when (val openOutcome = TerminalSessionPool.openSsh(
            options = sshOptions,
            cwd = workdir
        )) {
            is TerminalOpenOutcome.Success -> {
                // Send the initial command via the interactive channel.
                // writeInteractive appends a newline; command is the raw input.
                when (val writeOutcome = TerminalSessionPool.writeInteractive(
                    session = openOutcome.session,
                    text = command + "\n",
                )) {
                    is TerminalInteractiveWriteOutcome.Accepted -> TerminalToolResponse.backgroundAccepted(
                        openOutcome.session
                    )

                    is TerminalInteractiveWriteOutcome.SessionNotFound -> {
                        TerminalSessionPool.close(openOutcome.session)
                        TerminalToolResponse.sessionNotFound(writeOutcome.session)
                    }

                    is TerminalInteractiveWriteOutcome.Busy -> {
                        TerminalSessionPool.close(openOutcome.session)
                        TerminalToolResponse.sessionBusy(
                            writeOutcome.session,
                            asyncId = null,
                        )
                    }

                    is TerminalInteractiveWriteOutcome.NotInteractive -> {
                        TerminalSessionPool.close(openOutcome.session)
                        TerminalToolResponse.invalidRequest(
                            "SSH session is not interactive."
                        )
                    }

                    is TerminalInteractiveWriteOutcome.UnexpectedError -> {
                        TerminalSessionPool.close(openOutcome.session)
                        TerminalToolResponse.internalError(writeOutcome.throwable)
                    }
                }
            }

            is TerminalOpenOutcome.Failure -> TerminalToolResponse.commandError(
                code = TerminalToolResponse.failureCode(openOutcome.failure),
                message = openOutcome.failure.message ?: "Failed to open SSH session.",
            )

            is TerminalOpenOutcome.InvalidRequest -> TerminalToolResponse.invalidRequest(
                openOutcome.message
            )
        }
    }

    /**
     * Best-effort close of the session opened for a one-shot foreground command.
     * Guarantees the session is released on every outcome branch, including
     * Failure (whose session is nullable).
     */
    private suspend fun closeForegroundSession(outcome: TerminalCommandOutcome) {
        when (outcome) {
            is TerminalCommandOutcome.Success -> TerminalSessionPool.close(outcome.session)
            is TerminalCommandOutcome.Timeout -> TerminalSessionPool.close(outcome.session)

            is TerminalCommandOutcome.Failure -> {
                outcome.session?.let { TerminalSessionPool.close(it) }
            }

            is TerminalCommandOutcome.SessionNotFound,
            is TerminalCommandOutcome.Busy -> Unit

            is TerminalCommandOutcome.UnexpectedError -> {
                outcome.session?.let { TerminalSessionPool.close(it) }
            }
        }
    }

    // ── Action mode ─────────────────────────────────────────────────────────

    private suspend fun handleAction(args: TerminalArgs): String {
        return when (args.action) {
            Action.READ -> handleRead(args)
            Action.WRITE -> handleWrite(args)
            Action.SUBMIT -> handleSubmit(args)
            Action.CLOSE -> handleClose(args)
            null -> TerminalToolResponse.invalidRequest("Field 'action' is required.")
        }
    }

    private suspend fun handleWrite(args: TerminalArgs): String {
        val sessionId = args.requireSessionId()
        val text = args.requireText()
        return writeInteractivePayload(sessionId, text, args.requestId)
    }

    private suspend fun handleSubmit(args: TerminalArgs): String {
        val sessionId = args.requireSessionId()
        val text = args.requireText()
        return writeInteractivePayload(sessionId, "$text\n", args.requestId)
    }

    private suspend fun writeInteractivePayload(
        sessionId: String,
        payload: String,
        requestId: String?,
    ): String {
        return when (val outcome = TerminalSessionPool.writeInteractive(
            session = sessionId,
            text = payload,
            requestId = requestId,
        )) {
            is TerminalInteractiveWriteOutcome.Accepted -> TerminalToolResponse.writeResult(
                sessionId = sessionId,
                bytesWritten = outcome.bytesWritten,
            )

            is TerminalInteractiveWriteOutcome.SessionNotFound -> TerminalToolResponse.sessionNotFound(
                outcome.session
            )

            is TerminalInteractiveWriteOutcome.NotInteractive -> TerminalToolResponse.invalidRequest(
                "Session '${outcome.session}' is not an interactive SSH terminal."
            )

            is TerminalInteractiveWriteOutcome.Busy -> TerminalToolResponse.sessionBusy(
                outcome.session,
                asyncId = null
            )

            is TerminalInteractiveWriteOutcome.UnexpectedError -> TerminalToolResponse.internalError(
                outcome.throwable
            )
        }
    }

    private suspend fun handleRead(args: TerminalArgs): String {
        val sessionId = args.requireSessionId()
        val mode = args.mode ?: TerminalReadMode.DELTA
        val maxBytes = args.maxBytes ?: DEFAULT_MAX_BYTES

        // Interactive sessions (SSH): read from the persistent collector.
        // The interactive collector is started by openSsh() and runs for the
        // lifetime of the session, accumulating all output.  Unlike background
        // local commands there is no "completion" boundary — status is always
        // "running".
        val interactiveOutcome = TerminalSessionPool.readInteractive(sessionId, mode, maxBytes)
        return when (interactiveOutcome) {
            is TerminalInteractiveReadOutcome.Success -> TerminalToolResponse.readResult(
                sessionId, "running",
                interactiveOutcome.stdout + interactiveOutcome.stderr,
                null,
                elapsedSeconds = 0L,
            )

            is TerminalInteractiveReadOutcome.SessionNotFound -> TerminalToolResponse.sessionNotFound(
                interactiveOutcome.session
            )

            is TerminalInteractiveReadOutcome.NotInteractive -> {
                // Not an SSH session — fall through to readSession for local
                // background tasks.
                when (val outcome = TerminalSessionPool.readSession(
                    session = sessionId,
                    mode = mode,
                    maxBytes = maxBytes,
                )) {
                    is TerminalReadOutcome.Running -> TerminalToolResponse.readResult(
                        sessionId, "running", outcome.output, null, outcome.elapsedSeconds
                    )

                    is TerminalReadOutcome.Exited -> TerminalToolResponse.readResult(
                        sessionId, "exited", outcome.output, outcome.exitCode, outcome.elapsedSeconds
                    )

                    is TerminalReadOutcome.TimedOut -> TerminalToolResponse.readResult(
                        sessionId, "timed_out", outcome.output, null, outcome.elapsedSeconds
                    )

                    is TerminalReadOutcome.Crashed -> TerminalToolResponse.readResult(
                        sessionId, "failed", outcome.errorMessage, null, outcome.elapsedSeconds
                    )

                    is TerminalReadOutcome.SessionNotFound -> TerminalToolResponse.sessionNotFound(
                        outcome.session
                    )

                    is TerminalReadOutcome.NotBackground -> TerminalToolResponse.invalidRequest(
                        "Session '$sessionId' is not a background task."
                    )
                }
            }
        }
    }

    private suspend fun handleClose(args: TerminalArgs): String {
        val sessionId = args.requireSessionId()
        return when (val outcome = TerminalSessionPool.close(session = sessionId)) {
            TerminalCloseOutcome.Closed -> TerminalToolResponse.closeSuccess()
            is TerminalCloseOutcome.UnexpectedError -> TerminalToolResponse.internalError(outcome.throwable)
        }
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    private fun parseArguments(argumentsJson: String): TerminalArgs {
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", error)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.", error)
        }
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("argumentsJson must be a JSON object.")
        obj.requireKnownKeys()

        // Parse action first: its presence selects action mode over command mode
        val action = obj.optionalString("action")?.let { resolveAction(it) }

        return TerminalArgs(
            // Hermes-aligned fields
            command = obj.optionalString("command"),
            background = obj.optionalBoolean("background") ?: false,
            timeout = obj.optionalLong("timeout"),
            workdir = obj.optionalString("workdir"),
            notifyOnComplete = obj.optionalBoolean("notify_on_complete") ?: false,
            // Nexus extensions
            backend = obj.optionalString("backend")?.let { resolveBackend(it) } ?: Backend.LOCAL,
            identity = obj.optionalString("identity")?.trim(),
            host = obj.optionalString("host")?.trim(),
            port = obj.optionalLong("port")?.toInt(),
            username = obj.optionalString("username")?.trim(),
            password = obj.optionalString("password"),
            hostKeyPolicy = obj.optionalString("host_key_policy")?.trim(),
            knownHostsPath = obj.optionalString("known_hosts_path")?.trim(),
            strictHostKeyChecking = obj.optionalBoolean("strict_host_key_checking"),
            connectTimeout = obj.optionalLong("connect_timeout")?.toInt(),
            serverAliveInterval = obj.optionalLong("server_alive_interval")?.toInt(),
            // Action mode
            action = action,
            sessionId = obj.optionalString("session_id")?.trim(),
            text = obj.optionalString("text"),
            requestId = obj.optionalString("request_id")?.trim(),
            mode = obj.optionalString("mode")?.let { parseReadMode(it) },
            maxBytes = obj.optionalLong("max_bytes")?.toInt(),
            mergeStderr = obj.optionalBoolean("merge_stderr") ?: false,
        )
    }

    private fun resolveAction(raw: String): Action {
        return when (raw.trim().lowercase()) {
            "read" -> Action.READ
            "write" -> Action.WRITE
            "submit" -> Action.SUBMIT
            "close" -> Action.CLOSE
            else -> throw IllegalArgumentException(
                "Field 'action' must be one of read, write, submit, close."
            )
        }
    }

    private fun resolveBackend(raw: String): Backend {
        return when (raw.trim().lowercase()) {
            "local" -> Backend.LOCAL
            "ssh" -> Backend.SSH
            else -> throw IllegalArgumentException("Field 'backend' must be 'local' or 'ssh'.")
        }
    }

    private fun parseReadMode(raw: String): TerminalReadMode {
        return when (raw.trim().lowercase()) {
            TerminalReadMode.DELTA.wireName -> TerminalReadMode.DELTA
            TerminalReadMode.SNAPSHOT.wireName -> TerminalReadMode.SNAPSHOT
            else -> throw IllegalArgumentException("Field 'mode' must be one of delta, snapshot.")
        }
    }

    // ── Arg helpers ──────────────────────────────────────────────────────────

    private fun TerminalArgs.requireCommand(): String {
        return command?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'command' must not be blank.")
    }

    private fun TerminalArgs.requireSessionId(): String {
        val value = sessionId?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'session_id' is required.")
        return value
    }

    private fun TerminalArgs.requireText(): String {
        return text
            ?: throw IllegalArgumentException("Field 'text' is required for write/submit.")
    }

    private fun TerminalArgs.requireSshOpenOptions(): SshOpenOptions {
        val host = host?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'host' must not be blank for SSH backend.")
        val username = username?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'username' must not be blank for SSH backend.")
        val password = password?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'password' must not be blank for SSH backend.")
        return SshOpenOptions(
            host = host,
            port = port ?: SshOpenOptions.DEFAULT_PORT,
            username = username,
            auth = SshAuth.Password(password),
            hostKeyPolicy = resolveHostKeyPolicy(),
            connectTimeoutMillis = (connectTimeout ?: SshOpenOptions.DEFAULT_CONNECT_TIMEOUT_MILLIS / 1000) * 1000,
            serverAliveIntervalMillis = (serverAliveInterval ?: SshOpenOptions.DEFAULT_SERVER_ALIVE_INTERVAL_MILLIS / 1000) * 1000,
        )
    }

    private fun TerminalArgs.resolveHostKeyPolicy(): SshHostKeyPolicy {
        return when (hostKeyPolicy?.lowercase() ?: HOST_KEY_POLICY_ACCEPT_ANY) {
            HOST_KEY_POLICY_ACCEPT_ANY -> SshHostKeyPolicy.AcceptAny
            HOST_KEY_POLICY_KNOWN_HOSTS_FILE -> {
                val path = knownHostsPath?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException(
                        "Field 'known_hosts_path' is required when host_key_policy is 'known_hosts_file'."
                    )
                SshHostKeyPolicy.KnownHostsFile(
                    path = path,
                    strict = strictHostKeyChecking ?: true,
                )
            }

            else -> throw IllegalArgumentException(
                "Field 'host_key_policy' must be one of accept_any, known_hosts_file."
            )
        }
    }

    /** Resolve timeout in seconds. Defaults to 180s. */
    private fun TerminalArgs.resolveTimeout(): Long {
        timeout?.let { timeoutSec ->
            require(timeoutSec > 0) { "Field 'timeout' must be greater than 0." }
            return timeoutSec
        }
        return DEFAULT_TIMEOUT_SEC
    }

    private fun JsonObject.requireKnownKeys() {
        val unknownKeys = keys - KNOWN_KEYS
        if (unknownKeys.isNotEmpty()) {
            throw IllegalArgumentException(
                "Unknown terminal request field(s): ${
                    unknownKeys.sorted().joinToString()
                }."
            )
        }
    }

    private fun JsonObject.optionalString(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) {
            return null
        }
        val primitive = element.asPrimitive(key)
        return try {
            Json.decodeFromJsonElement<String>(primitive)
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Field '$key' must be a string.")
        }
    }

    private fun JsonObject.optionalBoolean(key: String): Boolean? {
        val element = this[key] ?: return null
        if (element is JsonNull) {
            return null
        }
        return element.asPrimitive(key).booleanOrNull
            ?: throw IllegalArgumentException("Field '$key' must be a boolean.")
    }

    private fun JsonObject.optionalLong(key: String): Long? {
        val element = this[key] ?: return null
        if (element is JsonNull) {
            return null
        }
        return element.asPrimitive(key).longOrNull
            ?: throw IllegalArgumentException("Field '$key' must be an integer.")
    }

    private fun JsonElement.asPrimitive(key: String): JsonPrimitive {
        return runCatching { jsonPrimitive }.getOrElse {
            throw IllegalArgumentException("Field '$key' must be a primitive value.")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun mergedStdout(result: CommandResult, mergeStderr: Boolean): String {
        val stdout = result.stdoutText()
        return if (mergeStderr) stdout + result.stderrText() else stdout
    }

    // ── Data types ───────────────────────────────────────────────────────────

    private data class TerminalArgs(
        // Hermes-aligned
        val command: String?,
        val background: Boolean,
        val timeout: Long?,
        val workdir: String?,
        val notifyOnComplete: Boolean,
        // Nexus extensions
        val backend: Backend,
        val identity: String?,
        val host: String?,
        val port: Int?,
        val username: String?,
        val password: String?,
        val hostKeyPolicy: String?,
        val knownHostsPath: String?,
        val strictHostKeyChecking: Boolean?,
        val connectTimeout: Int?,
        val serverAliveInterval: Int?,
        // Action mode
        val action: Action?,
        val sessionId: String?,
        val text: String?,
        val requestId: String?,
        val mode: TerminalReadMode?,
        val maxBytes: Int?,
        val mergeStderr: Boolean,
    )

    private enum class Action { READ, WRITE, SUBMIT, CLOSE }

    private enum class Backend { LOCAL, SSH }

    // ── Companion ────────────────────────────────────────────────────────────

    companion object {
        private const val DEFAULT_TIMEOUT_SEC = 180L
        private const val DEFAULT_LOCAL_IDENTITY = "user"
        private const val DEFAULT_MAX_BYTES = 8192
        private const val UNKNOWN_EXIT_CODE = -1
        private const val HOST_KEY_POLICY_ACCEPT_ANY = "accept_any"
        private const val HOST_KEY_POLICY_KNOWN_HOSTS_FILE = "known_hosts_file"

        private val KNOWN_KEYS = setOf(
            // Hermes-aligned
            "command", "background", "timeout", "workdir", "notify_on_complete",
            // Nexus extensions
            "backend", "identity",
            "host", "port", "username", "password",
            "host_key_policy", "known_hosts_path", "strict_host_key_checking",
            "connect_timeout", "server_alive_interval",
            // Action mode
            "action", "session_id", "text", "request_id", "mode", "max_bytes",
            // Internal
            "merge_stderr",
        )

        private val TERMINAL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "The shell command to execute. For one-shot commands, just pass command and the tool handles session open/exec/close automatically."
                },
                "background": {
                  "type": "boolean",
                  "description": "Run the command in the background. Almost always pair with notify_on_complete=true — without it, the process runs silently and you'll have no way to learn it finished short of checking yourself. Two legitimate patterns: (1) Long-lived processes that never exit (servers, watchers, daemons) — these stay silent because there's no exit to notify on. (2) Long-running bounded tasks (tests, builds, deploys, batch jobs) — these MUST set notify_on_complete=true. For short commands, prefer foreground with a generous timeout instead.",
                  "default": false
                },
                "timeout": {
                  "type": "integer",
                  "minimum": 1,
                  "description": "Max seconds to wait (default: 180). Returns INSTANTLY when command finishes — set high for long tasks, you won't wait unnecessarily."
                },
                "workdir": {
                  "type": "string",
                  "description": "Working directory for this command (absolute path)."
                },
                "notify_on_complete": {
                  "type": "boolean",
                  "description": "When true (and background=true), you'll be automatically notified when the process finishes. Use this for long-running tasks — tests, builds, deployments, batch jobs. MUTUALLY EXCLUSIVE with long-lived servers/daemons that never exit.",
                  "default": false
                },
                "backend": {
                  "type": "string",
                  "enum": ["local", "ssh"],
                  "description": "Terminal backend. 'local' (default) uses the Android device shell. 'ssh' connects to a remote host and requires background=true.",
                  "default": "local"
                },
                "identity": {
                  "type": "string",
                  "enum": ["user", "root", "shizuku"],
                  "description": "Execution identity for local backend. 'user' (default, unprivileged), 'root' (via su), or 'shizuku' (requires device support, a running service, and granted authorization)."
                },
                "host": {
                  "type": "string",
                  "description": "SSH hostname or IP address. Required for backend=ssh."
                },
                "port": {
                  "type": "integer",
                  "minimum": 1,
                  "maximum": 65535,
                  "description": "SSH port. Defaults to 22."
                },
                "username": {
                  "type": "string",
                  "description": "SSH username. Required for backend=ssh."
                },
                "password": {
                  "type": "string",
                  "description": "SSH password. Credentials are not stored by this tool."
                },
                "host_key_policy": {
                  "type": "string",
                  "enum": ["accept_any", "known_hosts_file"],
                  "description": "SSH host key verification policy. Defaults to 'accept_any'."
                },
                "known_hosts_path": {
                  "type": "string",
                  "description": "Path to known_hosts file. Required when host_key_policy is 'known_hosts_file'."
                },
                "strict_host_key_checking": {
                  "type": "boolean",
                  "description": "Enforce strict host key checking when using known_hosts_file. Defaults to true."
                },
                "connect_timeout": {
                  "type": "integer",
                  "minimum": 1,
                  "description": "SSH connection timeout in seconds."
                },
                "server_alive_interval": {
                  "type": "integer",
                  "minimum": 0,
                  "description": "SSH server alive interval in seconds."
                },
                "action": {
                  "type": "string",
                  "enum": ["read", "write", "submit", "close"],
                  "description": "Session management actions for background tasks: read (poll output and status), write (send stdin input without appending a newline), submit (send stdin input plus a newline), close (release the session)."
                },
                "session_id": {
                  "type": "string",
                  "description": "Session handle returned when a background command is started. Required for action-based operations."
                },
                "text": {
                  "type": "string",
                  "description": "Input text for the write/submit actions. write does NOT append a newline — add \\n when you want to submit a line."
                },
                "mode": {
                  "type": "string",
                  "enum": ["delta", "snapshot"],
                  "description": "Read mode for action=read. 'delta' (default) returns only new output since the last read. 'snapshot' returns all accumulated output."
                },
                "max_bytes": {
                  "type": "integer",
                  "minimum": 1,
                  "description": "Maximum bytes to return for action=read. Defaults to 8192."
                }
              }
            }
        """
    }
}
