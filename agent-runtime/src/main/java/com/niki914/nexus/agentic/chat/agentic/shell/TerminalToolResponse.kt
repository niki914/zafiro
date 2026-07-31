package com.niki914.nexus.agentic.chat.agentic.shell

import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.CommandResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object TerminalToolResponse {
    fun openSuccess(session: String, identity: String): String {
        return JsonObject(
            mapOf(
                "session" to JsonPrimitive(session),
                "identity" to JsonPrimitive(identity),
            )
        ).toString()
    }

    fun commandSuccess(
        result: CommandResult,
        elapsedSeconds: Long,
        session: String? = null,
        identity: String? = null,
        mergeStderr: Boolean = false,
    ): String {
        val stdout = result.stdoutText()
        val stderr = result.stderrText()
        val payload = linkedMapOf<String, JsonElement>()
        session?.let { payload["session"] = JsonPrimitive(it) }
        identity?.let { payload["identity"] = JsonPrimitive(it) }
        payload["exit_code"] = JsonPrimitive(result.exitCode ?: UNKNOWN_EXIT_CODE)
        payload["stdout"] = JsonPrimitive(if (mergeStderr) stdout + stderr else stdout)
        payload["stderr"] = JsonPrimitive(if (mergeStderr) "" else stderr)
        payload["elapsed_seconds"] = JsonPrimitive(elapsedSeconds)
        return JsonObject(payload).toString()
    }

    /** Hermes-aligned flat success: {"stdout":"...","stderr":"","exit_code":0} */
    fun commandSuccessFlat(
        stdout: String,
        stderr: String,
        exitCode: Int,
    ): String {
        return JsonObject(
            mapOf(
                "stdout" to JsonPrimitive(stdout),
                "stderr" to JsonPrimitive(stderr),
                "exit_code" to JsonPrimitive(exitCode),
            )
        ).toString()
    }

    fun commandTimeout(
        result: CommandResult,
        elapsedSeconds: Long,
        timeoutMs: Long,
        session: String? = null,
        identity: String? = null,
        mergeStderr: Boolean = false,
    ): String {
        val stdout = result.stdoutText()
        val stderr = result.stderrText()
        val payload = linkedMapOf<String, JsonElement>()
        session?.let { payload["session"] = JsonPrimitive(it) }
        identity?.let { payload["identity"] = JsonPrimitive(it) }
        payload["stdout"] = JsonPrimitive(if (mergeStderr) stdout + stderr else stdout)
        payload["stderr"] = JsonPrimitive(if (mergeStderr) "" else stderr)
        payload["elapsed_seconds"] = JsonPrimitive(elapsedSeconds)
        payload["error"] = errorObject(
            code = "TIMEOUT",
            message = "Command timed out after ${timeoutMs}ms. Increase timeout_ms, run a smaller command, or use exec with is_async=true and poll read_async_result.",
        )
        return JsonObject(payload).toString()
    }

    /** Hermes-aligned flat timeout: {"stdout":"...(partial)...","stderr":"","error":{"code":"TIMEOUT","message":"..."}} */
    fun commandTimeoutFlat(
        stdout: String,
        stderr: String,
        timeoutSec: Long,
    ): String {
        return JsonObject(
            mapOf(
                "stdout" to JsonPrimitive(stdout),
                "stderr" to JsonPrimitive(stderr),
                "error" to errorObject(
                    code = "TIMEOUT",
                    message = "Command timed out after ${timeoutSec}s. Increase timeout, run a smaller command, or use background=true.",
                ),
            )
        ).toString()
    }

    fun asyncAccepted(asyncId: String, elapsedSeconds: Long): String {
        return JsonObject(
            mapOf(
                "async_id" to JsonPrimitive(asyncId),
                "accepted" to JsonPrimitive(true),
                "elapsed_seconds" to JsonPrimitive(elapsedSeconds),
            )
        ).toString()
    }

    /** Hermes-aligned background acceptance: {"background":true,"async_id":"abc123"} */
    fun backgroundAccepted(asyncId: String): String {
        return JsonObject(
            mapOf(
                "background" to JsonPrimitive(true),
                "async_id" to JsonPrimitive(asyncId),
            )
        ).toString()
    }

    /** Flat error for command-first failures: {"stdout":"","stderr":"","exit_code":-1,"error":{...}} */
    fun commandError(code: String, message: String): String {
        return JsonObject(
            mapOf(
                "stdout" to JsonPrimitive(""),
                "stderr" to JsonPrimitive(""),
                "exit_code" to JsonPrimitive(UNKNOWN_EXIT_CODE),
                "error" to errorObject(code = code, message = message),
            )
        ).toString()
    }

    fun asyncRunning(stdoutPartial: String, stderrPartial: String, elapsedSeconds: Long): String {
        return JsonObject(
            mapOf(
                "stdout_partial" to JsonPrimitive(stdoutPartial),
                "stderr_partial" to JsonPrimitive(stderrPartial),
                "elapsed_seconds" to JsonPrimitive(elapsedSeconds),
            )
        ).toString()
    }

    fun closeSuccess(): String {
        return JsonObject(mapOf("closed" to JsonPrimitive(true))).toString()
    }

    fun policyBlocked(decision: ShellCommandPolicyDecision, elapsedSeconds: Long = 0L): String {
        return error(
            code = "COMMAND_BLOCKED",
            message = decision.reason.ifBlank { "Command blocked by safety policy." },
            elapsedSeconds = elapsedSeconds,
            extra = mapOf(
                "policy_code" to JsonPrimitive(decision.code),
                "matched_rule_id" to JsonPrimitive(decision.matchedRuleId.orEmpty()),
                "matched_rule_name" to JsonPrimitive(decision.matchedRuleName.orEmpty()),
                "matched_pattern" to JsonPrimitive(decision.matchedPattern.orEmpty()),
            )
        )
    }

    fun sessionNotFound(session: String): String {
        return error(
            code = "SESSION_NOT_FOUND",
            message = "Session '$session' not found. Use the session handle returned by open or open_and_exec. Do not pass identity names such as user or root.",
        )
    }

    fun asyncNotFound(session: String, asyncId: String): String {
        return error(
            code = "ASYNC_NOT_FOUND",
            message = "Async '$asyncId' not found on session '$session'. The result may have already been read or the session was closed.",
        )
    }

    fun sessionBusy(session: String, asyncId: String?): String {
        val message = if (asyncId.isNullOrBlank()) {
            "Session '$session' is already running a command. Wait for the current command to finish before retrying."
        } else {
            "Session '$session' is already running async command '$asyncId'. Use read_async_result to check its status before running another command."
        }
        return error(
            code = "SESSION_BUSY",
            message = message,
            extra = asyncId?.let { mapOf("async_id" to JsonPrimitive(it)) }.orEmpty(),
        )
    }

    fun invalidRequest(message: String, fieldErrors: Map<String, String> = emptyMap()): String {
        return error(
            code = "INVALID_REQUEST",
            message = message,
            fieldErrors = fieldErrors,
        )
    }

    fun failure(
        failure: TerminalFailure,
        elapsedSeconds: Long,
        session: String? = null,
        identity: String? = null,
    ): String {
        val publicIdentity = identity ?: failure.identity.publicName()
        val payload = linkedMapOf<String, JsonElement>()
        session?.let { payload["session"] = JsonPrimitive(it) }
        publicIdentity?.let { payload["identity"] = JsonPrimitive(it) }
        payload["elapsed_seconds"] = JsonPrimitive(elapsedSeconds)
        payload["error"] = errorObject(
            code = failureCode(failure),
            message = failure.message ?: defaultFailureMessage(failure),
            failureType = failureType(failure),
            identity = publicIdentity,
        )
        return JsonObject(payload).toString()
    }

    fun internalError(throwable: Throwable, elapsedSeconds: Long = 0L): String {
        return error(
            code = "INTERNAL_ERROR",
            message = throwable.message ?: "Unexpected terminal error.",
            elapsedSeconds = elapsedSeconds,
        )
    }

    private fun error(
        code: String,
        message: String,
        elapsedSeconds: Long? = null,
        failureType: String? = null,
        identity: String? = null,
        fieldErrors: Map<String, String> = emptyMap(),
        extra: Map<String, JsonPrimitive> = emptyMap(),
    ): String {
        val payload = linkedMapOf<String, JsonElement>()
        elapsedSeconds?.let { payload["elapsed_seconds"] = JsonPrimitive(it) }
        payload["error"] = errorObject(
            code = code,
            message = message,
            failureType = failureType,
            identity = identity,
            fieldErrors = fieldErrors,
            extra = extra,
        )
        return JsonObject(payload).toString()
    }

    private fun errorObject(
        code: String,
        message: String,
        failureType: String? = null,
        identity: String? = null,
        fieldErrors: Map<String, String> = emptyMap(),
        extra: Map<String, JsonPrimitive> = emptyMap(),
    ): JsonObject {
        val error = linkedMapOf<String, JsonElement>(
            "code" to JsonPrimitive(code),
            "message" to JsonPrimitive(message),
        )
        failureType?.let { error["failure_type"] = JsonPrimitive(it) }
        identity?.let { error["identity"] = JsonPrimitive(it) }
        if (fieldErrors.isNotEmpty()) {
            error["field_errors"] = JsonObject(fieldErrors.mapValues { JsonPrimitive(it.value) })
        }
        error.putAll(extra)
        return JsonObject(error)
    }

    internal fun failureCode(failure: TerminalFailure): String {
        return when (failure) {
            is TerminalFailure.BackendUnavailable -> "BACKEND_UNAVAILABLE"
            is TerminalFailure.AuthorizationDenied -> "AUTHORIZATION_DENIED"
            is TerminalFailure.AuthorizationFailed -> "AUTHORIZATION_FAILED"
            is TerminalFailure.StartupFailed -> "STARTUP_FAILED"
            is TerminalFailure.InvalidOpenOptions -> "INVALID_REQUEST"
            is TerminalFailure.SshConnectionFailed -> "SSH_CONNECTION_FAILED"
            is TerminalFailure.SshAuthenticationFailed -> "SSH_AUTHENTICATION_FAILED"
            is TerminalFailure.SshHostKeyVerificationFailed -> "SSH_HOST_KEY_VERIFICATION_FAILED"
            is TerminalFailure.SshChannelFailed -> "SSH_CHANNEL_FAILED"
            is TerminalFailure.RuntimeTerminated -> "RUNTIME_TERMINATED"
            is TerminalFailure.AlreadyClosed -> "SESSION_CLOSED"
        }
    }

    private fun failureType(failure: TerminalFailure): String {
        return when (failure) {
            is TerminalFailure.BackendUnavailable -> "BackendUnavailable"
            is TerminalFailure.AuthorizationDenied -> "AuthorizationDenied"
            is TerminalFailure.AuthorizationFailed -> "AuthorizationFailed"
            is TerminalFailure.StartupFailed -> "StartupFailed"
            is TerminalFailure.InvalidOpenOptions -> "InvalidOpenOptions"
            is TerminalFailure.SshConnectionFailed -> "SshConnectionFailed"
            is TerminalFailure.SshAuthenticationFailed -> "SshAuthenticationFailed"
            is TerminalFailure.SshHostKeyVerificationFailed -> "SshHostKeyVerificationFailed"
            is TerminalFailure.SshChannelFailed -> "SshChannelFailed"
            is TerminalFailure.RuntimeTerminated -> "RuntimeTerminated"
            is TerminalFailure.AlreadyClosed -> "AlreadyClosed"
        }
    }

    private fun defaultFailureMessage(failure: TerminalFailure): String {
        return when (failure) {
            is TerminalFailure.BackendUnavailable -> "Terminal backend is unavailable."
            is TerminalFailure.AuthorizationDenied -> "Terminal authorization was denied."
            is TerminalFailure.AuthorizationFailed -> "Terminal authorization failed."
            is TerminalFailure.StartupFailed -> "Terminal startup failed."
            is TerminalFailure.InvalidOpenOptions -> "Terminal open options are invalid."
            is TerminalFailure.SshConnectionFailed -> "SSH connection failed."
            is TerminalFailure.SshAuthenticationFailed -> "SSH authentication failed."
            is TerminalFailure.SshHostKeyVerificationFailed -> "SSH host key verification failed."
            is TerminalFailure.SshChannelFailed -> "SSH channel failed."
            is TerminalFailure.RuntimeTerminated -> "Terminal runtime terminated."
            is TerminalFailure.AlreadyClosed -> "Terminal session is already closed."
        }
    }

    internal fun CommandResult.stdoutText(): String = stdout.toByteArray().decodeToString()

    internal fun CommandResult.stderrText(): String = stderr.toByteArray().decodeToString()

    private fun TerminalIdentity?.publicName(): String? {
        return when (this) {
            TerminalIdentity.User -> "user"
            TerminalIdentity.Su -> "root"
            TerminalIdentity.Shizuku -> "shizuku"
            TerminalIdentity.Ssh -> "ssh"
            null -> null
        }
    }

    private const val UNKNOWN_EXIT_CODE = -1
}
