package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshHostKeyPolicy
import com.niki914.libterm.SshOpenOptions
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalAsyncReadOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalAsyncStartOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalCloseOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalInteractiveReadMode
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalInteractiveReadOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalInteractiveWriteOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.runtime.CommandResult
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalToolResponse
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalToolResponse.stdoutText
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalToolResponse.stderrText
import com.niki914.s3ss10n.LocalToolConfig
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
                "Prefer foreground for short commands.\n" +
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
                "Working directory: Use 'workdir' for per-command cwd. " +
                "The session remembers the last used workdir as default.\n" +
                "\n" +
                "PTY mode: Set pty=true for interactive CLI tools. " +
                "On Android this primarily works with SSH backend; local PTY support is limited.\n" +
                "\n" +
                "Do NOT use vim/nano/interactive tools without pty=true — they hang without a pseudo-terminal. " +
                "Pipe git output to cat if it might page.\n" +
                "\n" +
                "Backend: Set backend to \"local\" (default) for the Android device shell, " +
                "or \"ssh\" for a remote host.\n" +
                "- backend=\"local\": Use identity to pick the execution user — \"user\" (default, unprivileged), " +
                "\"root\" (via su), or \"shizuku\". Shizuku requires device support, a running service, " +
                "and granted authorization.\n" +
                "- backend=\"ssh\": Connect to a remote host. Provide host, username, and password. " +
                "host_key_policy defaults to \"accept_any\"; use \"known_hosts_file\" with known_hosts_path " +
                "for host verification.\n" +
                "\n" +
                "For interactive SSH sessions, use action=\"pty_write\" to send input " +
                "(text field, newline is NOT appended automatically — add \\n when needed) " +
                "and action=\"pty_read\" to read output. Use action=\"close\" to close a session. " +
                "Sessions opened for foreground commands are automatically closed after the command completes."

    override val defaultEnabled: Boolean = true

    // Tracks the last workdir used in command-first mode so subsequent calls without
    // an explicit workdir default to it.
    private var lastWorkdir: String? = null

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.rawJsonSchema(TERMINAL_SCHEMA)
    }

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        return BuiltinToolResult.failure(
            code = "RAW_JSON_ONLY",
            message = "terminal accepts raw JSON requests only.",
            hint = """Example: {"command":"ls -la"} or {"command":"ls","backend":"ssh","host":"1.2.3.4","username":"root","password":"..."}""",
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
        // Remember this workdir for subsequent calls
        if (args.workdir != null) {
            lastWorkdir = args.workdir
        }

        return when (args.backend) {
            Backend.LOCAL -> handleLocalCommand(args, command, timeoutSec)
            Backend.SSH -> handleSshCommand(args, command, timeoutSec)
        }
    }

    private suspend fun handleLocalCommand(
        args: TerminalArgs,
        command: String,
        timeoutSec: Long,
    ): String {
        val identity = args.identity ?: DEFAULT_LOCAL_IDENTITY
        val workdir = args.workdir ?: lastWorkdir
        val timeoutMs = timeoutSec * 1000L

        return if (args.background) {
            startBackgroundLocal(identity, workdir, command, timeoutMs)
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
        return when (val outcome = TerminalSessionPool.openAndExecute(
            identity = identity,
            cwd = workdir,
            command = command,
            timeoutMs = timeoutMs,
        )) {
            is TerminalCommandOutcome.Success -> {
                TerminalSessionPool.close(outcome.session)
                val result = outcome.result
                TerminalToolResponse.commandSuccessFlat(
                    stdout = mergedStdout(result, mergeStderr),
                    stderr = if (mergeStderr) "" else result.stderrText(),
                    exitCode = result.exitCode ?: UNKNOWN_EXIT_CODE,
                )
            }

            is TerminalCommandOutcome.Timeout -> {
                TerminalSessionPool.close(outcome.session)
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
    }

    private suspend fun startBackgroundLocal(
        identity: String,
        workdir: String?,
        command: String,
        timeoutMs: Long,
    ): String {
        // Open a session first, then start async
        return when (val openOutcome = TerminalSessionPool.open(identity = identity, cwd = workdir)) {
            is TerminalOpenOutcome.Success -> {
                when (val asyncOutcome = TerminalSessionPool.startAsync(
                    session = openOutcome.session,
                    command = command,
                    timeoutMs = timeoutMs,
                )) {
                    is TerminalAsyncStartOutcome.Accepted -> TerminalToolResponse.backgroundAccepted(
                        asyncOutcome.asyncId
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
        val sshOptions = args.requireSshOpenOptions()
        val workdir = args.workdir ?: lastWorkdir
        val timeoutMs = timeoutSec * 1000L

        return if (args.background) {
            startBackgroundSsh(sshOptions, workdir, command, timeoutMs)
        } else {
            executeForegroundSsh(sshOptions, workdir, command, timeoutMs, args.mergeStderr)
        }
    }

    private suspend fun executeForegroundSsh(
        sshOptions: SshOpenOptions,
        workdir: String?,
        command: String,
        timeoutMs: Long,
        mergeStderr: Boolean,
    ): String {
        return when (val outcome = TerminalSessionPool.openAndExecuteSsh(
            options = sshOptions,
            cwd = workdir,
            command = command,
            timeoutMs = timeoutMs,
        )) {
            is TerminalCommandOutcome.Success -> {
                TerminalSessionPool.close(outcome.session)
                val result = outcome.result
                TerminalToolResponse.commandSuccessFlat(
                    stdout = mergedStdout(result, mergeStderr),
                    stderr = if (mergeStderr) "" else result.stderrText(),
                    exitCode = result.exitCode ?: UNKNOWN_EXIT_CODE,
                )
            }

            is TerminalCommandOutcome.Timeout -> {
                TerminalSessionPool.close(outcome.session)
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
    }

    private suspend fun startBackgroundSsh(
        sshOptions: SshOpenOptions,
        workdir: String?,
        command: String,
        timeoutMs: Long,
    ): String {
        return when (val openOutcome = TerminalSessionPool.openSsh(
            options = sshOptions,
            cwd = workdir
        )) {
            is TerminalOpenOutcome.Success -> {
                when (val asyncOutcome = TerminalSessionPool.startAsync(
                    session = openOutcome.session,
                    command = command,
                    timeoutMs = timeoutMs,
                )) {
                    is TerminalAsyncStartOutcome.Accepted -> TerminalToolResponse.backgroundAccepted(
                        asyncOutcome.asyncId
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
                message = openOutcome.failure.message ?: "Failed to open SSH session.",
            )

            is TerminalOpenOutcome.InvalidRequest -> TerminalToolResponse.invalidRequest(
                openOutcome.message
            )
        }
    }

    // ── Action mode (interactive SSH + backward compat) ─────────────────────

    private suspend fun handleAction(args: TerminalArgs): String {
        return when (args.action) {
            Action.PTY_WRITE -> handlePtyWrite(args)
            Action.PTY_READ -> handlePtyRead(args)
            Action.CLOSE -> handleClose(args)
            // Backward compat: old action names
            Action.OPEN -> handleLegacyOpen(args)
            Action.OPEN_AND_EXEC -> handleLegacyOpenAndExec(args)
            Action.EXEC -> handleLegacyExec(args)
            Action.READ_ASYNC_RESULT -> handleLegacyReadAsyncResult(args)
            null -> TerminalToolResponse.invalidRequest("Field 'action' is required.")
        }
    }

    private suspend fun handleLegacyOpen(args: TerminalArgs): String {
        val identity = args.requireIdentity()
        return when (val outcome = TerminalSessionPool.open(
            identity = identity,
            cwd = args.workdir
        )) {
            is TerminalOpenOutcome.Success -> TerminalToolResponse.openSuccess(
                session = outcome.session,
                identity = outcome.identity,
            )

            is TerminalOpenOutcome.Failure -> TerminalToolResponse.failure(
                failure = outcome.failure,
                elapsedSeconds = outcome.elapsedSeconds,
                identity = identity,
            )

            is TerminalOpenOutcome.InvalidRequest -> TerminalToolResponse.invalidRequest(outcome.message)
        }
    }

    private suspend fun handleLegacyOpenAndExec(args: TerminalArgs): String {
        val identity = args.requireIdentity()
        val command = args.requireCommand()
        val timeoutMs = args.resolveLegacyTimeoutMs()
        val decision = safetyPolicy.evaluate(command)
        if (!decision.allowed) {
            return TerminalToolResponse.policyBlocked(decision)
        }

        return when (val outcome = TerminalSessionPool.openAndExecute(
            identity = identity,
            cwd = args.workdir,
            command = command,
            timeoutMs = timeoutMs,
        )) {
            is TerminalCommandOutcome.Success -> TerminalToolResponse.commandSuccess(
                result = outcome.result,
                elapsedSeconds = outcome.elapsedSeconds,
                session = outcome.session,
                identity = outcome.identity,
                mergeStderr = args.mergeStderr,
            )

            is TerminalCommandOutcome.Timeout -> TerminalToolResponse.commandTimeout(
                result = outcome.result,
                elapsedSeconds = outcome.elapsedSeconds,
                timeoutMs = timeoutMs,
                session = outcome.session,
                identity = outcome.identity,
                mergeStderr = args.mergeStderr,
            )

            is TerminalCommandOutcome.Failure -> TerminalToolResponse.failure(
                failure = outcome.failure,
                elapsedSeconds = outcome.elapsedSeconds,
                session = outcome.session,
                identity = outcome.identity,
            )

            is TerminalCommandOutcome.SessionNotFound -> TerminalToolResponse.sessionNotFound(
                outcome.session
            )

            is TerminalCommandOutcome.Busy -> TerminalToolResponse.sessionBusy(
                outcome.session,
                outcome.asyncId
            )

            is TerminalCommandOutcome.UnexpectedError -> TerminalToolResponse.internalError(
                throwable = outcome.throwable,
                elapsedSeconds = outcome.elapsedSeconds,
            )
        }
    }

    private suspend fun handleLegacyExec(args: TerminalArgs): String {
        val session = args.requireSession()
        val command = args.requireCommand()
        val timeoutMs = args.resolveLegacyTimeoutMs()
        val decision = safetyPolicy.evaluate(command)
        if (!decision.allowed) {
            return TerminalToolResponse.policyBlocked(decision)
        }

        return if (args.legacyIsAsync) {
            when (val outcome = TerminalSessionPool.startAsync(
                session = session,
                command = command,
                timeoutMs = timeoutMs,
            )) {
                is TerminalAsyncStartOutcome.Accepted -> TerminalToolResponse.asyncAccepted(
                    asyncId = outcome.asyncId,
                    elapsedSeconds = outcome.elapsedSeconds,
                )

                is TerminalAsyncStartOutcome.SessionNotFound -> TerminalToolResponse.sessionNotFound(
                    outcome.session
                )

                is TerminalAsyncStartOutcome.Busy -> TerminalToolResponse.sessionBusy(
                    outcome.session,
                    outcome.asyncId
                )

                is TerminalAsyncStartOutcome.InvalidRequest -> TerminalToolResponse.invalidRequest(
                    outcome.message
                )
            }
        } else {
            when (val outcome = TerminalSessionPool.executeBlocking(
                session = session,
                command = command,
                timeoutMs = timeoutMs,
            )) {
                is TerminalCommandOutcome.Success -> TerminalToolResponse.commandSuccess(
                    result = outcome.result,
                    elapsedSeconds = outcome.elapsedSeconds,
                    session = outcome.session,
                    identity = outcome.identity,
                    mergeStderr = args.mergeStderr,
                )

                is TerminalCommandOutcome.Timeout -> TerminalToolResponse.commandTimeout(
                    result = outcome.result,
                    elapsedSeconds = outcome.elapsedSeconds,
                    timeoutMs = timeoutMs,
                    session = outcome.session,
                    identity = outcome.identity,
                    mergeStderr = args.mergeStderr,
                )

                is TerminalCommandOutcome.Failure -> TerminalToolResponse.failure(
                    failure = outcome.failure,
                    elapsedSeconds = outcome.elapsedSeconds,
                    session = outcome.session,
                    identity = outcome.identity,
                )

                is TerminalCommandOutcome.SessionNotFound -> TerminalToolResponse.sessionNotFound(
                    outcome.session
                )

                is TerminalCommandOutcome.Busy -> TerminalToolResponse.sessionBusy(
                    outcome.session,
                    outcome.asyncId
                )

                is TerminalCommandOutcome.UnexpectedError -> TerminalToolResponse.internalError(
                    throwable = outcome.throwable,
                    elapsedSeconds = outcome.elapsedSeconds,
                )
            }
        }
    }

    private suspend fun handleLegacyReadAsyncResult(args: TerminalArgs): String {
        val session = args.requireSession()
        val asyncId = args.requireAsyncId()
        return when (val outcome =
            TerminalSessionPool.readAsyncResult(session = session, asyncId = asyncId)) {
            is TerminalAsyncReadOutcome.Running -> TerminalToolResponse.asyncRunning(
                stdoutPartial = outcome.stdoutPartial,
                stderrPartial = outcome.stderrPartial,
                elapsedSeconds = outcome.elapsedSeconds,
            )

            is TerminalAsyncReadOutcome.Completed -> TerminalToolResponse.commandSuccess(
                result = outcome.result,
                elapsedSeconds = outcome.elapsedSeconds,
                mergeStderr = args.mergeStderr,
            )

            is TerminalAsyncReadOutcome.TimedOut -> TerminalToolResponse.commandTimeout(
                result = outcome.result,
                elapsedSeconds = outcome.elapsedSeconds,
                timeoutMs = args.resolveLegacyTimeoutMs(),
                mergeStderr = args.mergeStderr,
            )

            is TerminalAsyncReadOutcome.Failure -> TerminalToolResponse.failure(
                failure = outcome.failure,
                elapsedSeconds = outcome.elapsedSeconds,
                session = session,
            )

            is TerminalAsyncReadOutcome.AsyncNotFound -> TerminalToolResponse.asyncNotFound(
                session = outcome.session,
                asyncId = outcome.asyncId,
            )

            is TerminalAsyncReadOutcome.SessionNotFound -> TerminalToolResponse.sessionNotFound(
                outcome.session
            )

            is TerminalAsyncReadOutcome.UnexpectedError -> TerminalToolResponse.internalError(
                throwable = outcome.throwable,
                elapsedSeconds = outcome.elapsedSeconds,
            )
        }
    }

    // ── New interactive SSH actions ──────────────────────────────────────────

    private suspend fun handlePtyWrite(args: TerminalArgs): String {
        val session = args.requireSession()
        val text = args.requireText()

        // Determine whether to append newline based on the sub-action hint
        val appendNewline = when (args.legacySubAction) {
            LegacySubAction.SEND_LINE -> true
            LegacySubAction.INTERRUPT -> {
                return writeInteractivePayload(session, CTRL_C, args.requestId)
            }

            else -> false
        }
        val payload = if (appendNewline) "$text\n" else text
        return writeInteractivePayload(session, payload, args.requestId)
    }

    private suspend fun writeInteractivePayload(
        session: String,
        payload: String,
        requestId: String?,
    ): String {
        return when (val outcome = TerminalSessionPool.writeInteractive(
            session = session,
            text = payload,
            requestId = requestId,
        )) {
            is TerminalInteractiveWriteOutcome.Accepted -> JsonObject(
                mapOf(
                    "accepted" to JsonPrimitive(true),
                    "bytes_written" to JsonPrimitive(outcome.bytesWritten),
                    "sequence" to JsonPrimitive(outcome.sequence),
                    "replayed" to JsonPrimitive(outcome.replayed),
                )
            ).toString()

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

    private fun handlePtyRead(args: TerminalArgs): String {
        val session = args.requireSession()
        val mode = args.mode ?: TerminalInteractiveReadMode.DELTA
        val maxBytes = args.maxBytes ?: DEFAULT_MAX_BYTES
        return when (val outcome = TerminalSessionPool.readInteractive(
            session = session,
            mode = mode,
            maxBytes = maxBytes,
        )) {
            is TerminalInteractiveReadOutcome.Success -> JsonObject(
                mapOf(
                    "stdout" to JsonPrimitive(outcome.stdout),
                    "stderr" to JsonPrimitive(outcome.stderr),
                    "mode" to JsonPrimitive(outcome.mode.wireName),
                    "sequence" to JsonPrimitive(outcome.sequence),
                    "truncated" to JsonPrimitive(outcome.truncated),
                )
            ).toString()

            is TerminalInteractiveReadOutcome.SessionNotFound -> TerminalToolResponse.sessionNotFound(
                outcome.session
            )

            is TerminalInteractiveReadOutcome.NotInteractive -> TerminalToolResponse.invalidRequest(
                "Session '${outcome.session}' is not an interactive SSH terminal."
            )
        }
    }

    private suspend fun handleClose(args: TerminalArgs): String {
        val session = args.requireSession()
        return when (val outcome = TerminalSessionPool.close(session = session)) {
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

        // Parse action first to determine backward compat mode
        val actionRaw = obj.optionalString("action")
        val action = actionRaw?.let { resolveAction(it) }

        return TerminalArgs(
            // Hermes-aligned fields
            command = obj.optionalString("command"),
            background = obj.optionalBoolean("background")
                ?: obj.optionalBoolean("is_async") ?: false,
            timeout = obj.optionalLong("timeout"),
            workdir = obj.optionalString("workdir") ?: obj.optionalString("cwd"),
            pty = obj.optionalBoolean("pty") ?: false,
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
            session = obj.optionalString("session")?.trim(),
            text = obj.optionalString("text"),
            requestId = obj.optionalString("request_id")?.trim(),
            mode = obj.optionalString("mode")?.let { parseReadMode(it) },
            maxBytes = obj.optionalLong("max_bytes")?.toInt(),
            // Backward compat
            legacyIsAsync = obj.optionalBoolean("is_async") ?: false,
            legacyTimeoutMs = obj.optionalLong("timeout_ms"),
            legacyAsyncId = obj.optionalString("async_id")?.trim(),
            mergeStderr = obj.optionalBoolean("merge_stderr") ?: false,
            legacySubAction = actionRaw?.let { resolveLegacySubAction(it) },
        )
    }

    private fun resolveAction(raw: String): Action {
        return when (raw.trim().lowercase()) {
            // New actions
            "pty_write" -> Action.PTY_WRITE
            "pty_read" -> Action.PTY_READ
            "close" -> Action.CLOSE
            // Backward compat old actions
            "open" -> Action.OPEN
            "open_and_exec" -> Action.OPEN_AND_EXEC
            "exec" -> Action.EXEC
            "read_async_result" -> Action.READ_ASYNC_RESULT
            // SSH old actions mapped to new
            "send_line" -> Action.PTY_WRITE
            "write" -> Action.PTY_WRITE
            "interrupt" -> Action.PTY_WRITE
            "read" -> Action.PTY_READ
            else -> throw IllegalArgumentException(
                "Field 'action' must be one of pty_write, pty_read, close."
            )
        }
    }

    private fun resolveLegacySubAction(raw: String): LegacySubAction {
        return when (raw.trim().lowercase()) {
            "send_line" -> LegacySubAction.SEND_LINE
            "write" -> LegacySubAction.WRITE
            "interrupt" -> LegacySubAction.INTERRUPT
            else -> LegacySubAction.NONE
        }
    }

    private fun resolveBackend(raw: String): Backend {
        return when (raw.trim().lowercase()) {
            "local" -> Backend.LOCAL
            "ssh" -> Backend.SSH
            else -> throw IllegalArgumentException("Field 'backend' must be 'local' or 'ssh'.")
        }
    }

    private fun parseReadMode(raw: String): TerminalInteractiveReadMode {
        return when (raw.trim().lowercase()) {
            TerminalInteractiveReadMode.DELTA.wireName -> TerminalInteractiveReadMode.DELTA
            TerminalInteractiveReadMode.SNAPSHOT.wireName -> TerminalInteractiveReadMode.SNAPSHOT
            else -> throw IllegalArgumentException("Field 'mode' must be one of delta, snapshot.")
        }
    }

    // ── Arg helpers ──────────────────────────────────────────────────────────

    private fun TerminalArgs.requireCommand(): String {
        return command?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'command' must not be blank.")
    }

    private fun TerminalArgs.requireIdentity(): String {
        val value = identity?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'identity' must be one of user, root, shizuku.")
        if (value !in PUBLIC_IDENTITIES) {
            throw IllegalArgumentException("Field 'identity' must be one of user, root, shizuku.")
        }
        return value
    }

    private fun TerminalArgs.requireSession(): String {
        val value = session?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'session' is required.")
        return value
    }

    private fun TerminalArgs.requireText(): String {
        return text
            ?: throw IllegalArgumentException("Field 'text' is required for pty_write.")
    }

    private fun TerminalArgs.requireAsyncId(): String {
        return legacyAsyncId?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Field 'async_id' is required for read_async_result.")
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

    /** Resolve timeout: prefer `timeout` (seconds). Falls back to `timeout_ms` (ms → s). Defaults to 180s. */
    private fun TerminalArgs.resolveTimeout(): Long {
        timeout?.let { timeoutSec ->
            require(timeoutSec > 0) { "Field 'timeout' must be greater than 0." }
            return timeoutSec
        }
        // Backward compat: timeout_ms
        legacyTimeoutMs?.let { timeoutMs ->
            require(timeoutMs > 0) { "Field 'timeout_ms' must be greater than 0." }
            return timeoutMs / 1000L
        }
        return DEFAULT_TIMEOUT_SEC
    }

    /** Resolve timeout for legacy action mode (returns milliseconds). */
    private fun TerminalArgs.resolveLegacyTimeoutMs(): Long {
        timeout?.let { timeoutSec ->
            require(timeoutSec > 0) { "Field 'timeout' must be greater than 0." }
            return timeoutSec * 1000L
        }
        legacyTimeoutMs?.let { timeoutMs ->
            require(timeoutMs > 0) { "Field 'timeout_ms' must be greater than 0." }
            return timeoutMs
        }
        return DEFAULT_TIMEOUT_MS
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
        val pty: Boolean,
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
        val session: String?,
        val text: String?,
        val requestId: String?,
        val mode: TerminalInteractiveReadMode?,
        val maxBytes: Int?,
        // Backward compat
        val legacyIsAsync: Boolean,
        val legacyTimeoutMs: Long?,
        val legacyAsyncId: String?,
        val mergeStderr: Boolean,
        val legacySubAction: LegacySubAction?,
    )

    private enum class Action { PTY_WRITE, PTY_READ, CLOSE, OPEN, OPEN_AND_EXEC, EXEC, READ_ASYNC_RESULT }

    private enum class Backend { LOCAL, SSH }

    private enum class LegacySubAction { NONE, SEND_LINE, WRITE, INTERRUPT }

    // ── Companion ────────────────────────────────────────────────────────────

    companion object {
        private const val DEFAULT_TIMEOUT_SEC = 180L
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val DEFAULT_LOCAL_IDENTITY = "user"
        private const val DEFAULT_MAX_BYTES = 8192
        private const val CTRL_C = ""
        private const val UNKNOWN_EXIT_CODE = -1
        private const val HOST_KEY_POLICY_ACCEPT_ANY = "accept_any"
        private const val HOST_KEY_POLICY_KNOWN_HOSTS_FILE = "known_hosts_file"
        private val PUBLIC_IDENTITIES = setOf("user", "root", "shizuku")

        private val KNOWN_KEYS = setOf(
            // Hermes-aligned
            "command", "background", "timeout", "workdir", "pty", "notify_on_complete",
            // Nexus extensions
            "backend", "identity",
            "host", "port", "username", "password",
            "host_key_policy", "known_hosts_path", "strict_host_key_checking",
            "connect_timeout", "server_alive_interval",
            // Action mode
            "action", "session", "text", "request_id", "mode", "max_bytes",
            // Backward compat
            "cwd", "is_async", "async_id", "merge_stderr", "timeout_ms",
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
                  "description": "Working directory for this command (absolute path). Defaults to the session's last used working directory."
                },
                "pty": {
                  "type": "boolean",
                  "description": "Run in pseudo-terminal (PTY) mode for interactive CLI tools. Primarily works with SSH backend on Android. Default: false.",
                  "default": false
                },
                "notify_on_complete": {
                  "type": "boolean",
                  "description": "When true (and background=true), you'll be automatically notified when the process finishes. Use this for long-running tasks — tests, builds, deployments, batch jobs. MUTUALLY EXCLUSIVE with long-lived servers/daemons that never exit.",
                  "default": false
                },
                "backend": {
                  "type": "string",
                  "enum": ["local", "ssh"],
                  "description": "Terminal backend. 'local' (default) uses the Android device shell. 'ssh' connects to a remote host.",
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
                  "enum": ["pty_write", "pty_read", "close"],
                  "description": "Explicit session management for interactive SSH. pty_write sends input, pty_read reads output, close closes the session. Use these instead of command when you need interactive control over an SSH terminal."
                },
                "session": {
                  "type": "string",
                  "description": "Opaque session handle. Required for action=pty_write, pty_read, and close."
                },
                "text": {
                  "type": "string",
                  "description": "Input text for action=pty_write. A newline is NOT appended automatically — add \\n when you want to submit a line."
                },
                "mode": {
                  "type": "string",
                  "enum": ["delta", "snapshot"],
                  "description": "Read mode for action=pty_read. 'delta' (default) returns only new output since the last read. 'snapshot' returns all accumulated output."
                },
                "max_bytes": {
                  "type": "integer",
                  "minimum": 1,
                  "description": "Maximum bytes to return for action=pty_read. Defaults to 8192."
                }
              }
            }
        """
    }
}
