package com.niki914.zafiro.chat

import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.CommandResult
import com.niki914.zafiro.chat.agentic.shell.TerminalToolResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalToolResponseTest {
    @Test
    fun sessionNotFoundSuggestsBackgroundCommand() {
        val json = parse(TerminalToolResponse.sessionNotFound("user"))

        val message = json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertErrorCode("SESSION_NOT_FOUND", json)
        assertTrue(message.contains("session_id returned by a background command"))
    }

    @Test
    fun sessionNotFoundTellsCallerToUseReturnedHandle() {
        val json = parse(TerminalToolResponse.sessionNotFound("user"))

        val message = json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertErrorCode("SESSION_NOT_FOUND", json)
        assertTrue(message.contains("Do not pass identity names"))
        assertTrue(message.contains("user or root"))
    }

    @Test
    fun sessionBusyIncludesAsyncIdWhenPresent() {
        val json = parse(TerminalToolResponse.sessionBusy(session = "user", asyncId = "a1"))

        assertErrorCode("SESSION_BUSY", json)
        assertEquals("a1", json["error"]!!.jsonObject["async_id"]!!.jsonPrimitive.content)
        assertTrue(
            json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                .contains("action=\"read\"")
        )
    }

    @Test
    fun sessionBusyWithoutAsyncIdSuggestsWaiting() {
        val json = parse(TerminalToolResponse.sessionBusy(session = "user", asyncId = null))

        val message = json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertErrorCode("SESSION_BUSY", json)
        assertTrue(message.contains("Wait"))
        assertTrue(message.contains("current command"))
    }

    @Test
    fun failureMapsAuthorizationDeniedAndPublicIdentity() {
        val json = parse(
            TerminalToolResponse.failure(
                failure = TerminalFailure.AuthorizationDenied(TerminalIdentity.Su, "denied"),
                elapsedSeconds = 2L,
            )
        )

        assertEquals("root", json["identity"]!!.jsonPrimitive.content)
        assertErrorCode("AUTHORIZATION_DENIED", json)
        assertEquals(
            "AuthorizationDenied",
            json["error"]!!.jsonObject["failure_type"]!!.jsonPrimitive.content
        )
        assertEquals("root", json["error"]!!.jsonObject["identity"]!!.jsonPrimitive.content)
    }

    @Test
    fun failureMapsShizukuBackendUnavailableAndPublicIdentity() {
        val json = parse(
            TerminalToolResponse.failure(
                failure = TerminalFailure.BackendUnavailable(
                    TerminalIdentity.Shizuku,
                    "Shizuku is not installed or not running",
                ),
                elapsedSeconds = 1L,
            )
        )

        assertEquals("shizuku", json["identity"]!!.jsonPrimitive.content)
        assertErrorCode("BACKEND_UNAVAILABLE", json)
        assertEquals(
            "BackendUnavailable",
            json["error"]!!.jsonObject["failure_type"]!!.jsonPrimitive.content
        )
        assertEquals("shizuku", json["error"]!!.jsonObject["identity"]!!.jsonPrimitive.content)
    }

    @Test
    fun failureMapsRuntimeTerminated() {
        val json = parse(
            TerminalToolResponse.failure(
                failure = TerminalFailure.RuntimeTerminated(TerminalIdentity.User),
                elapsedSeconds = 4L,
                session = "user",
            )
        )

        assertEquals("user", json["session"]!!.jsonPrimitive.content)
        assertErrorCode("RUNTIME_TERMINATED", json)
        assertEquals(
            "RuntimeTerminated",
            json["error"]!!.jsonObject["failure_type"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun failureMapsSshAuthenticationFailureAndPublicIdentity() {
        val json = parse(
            TerminalToolResponse.failure(
                failure = TerminalFailure.SshAuthenticationFailed(
                    message = "Auth fail",
                    username = "alice",
                ),
                elapsedSeconds = 2L,
            )
        )

        assertEquals("ssh", json["identity"]!!.jsonPrimitive.content)
        assertErrorCode("SSH_AUTHENTICATION_FAILED", json)
        assertEquals(
            "SshAuthenticationFailed",
            json["error"]!!.jsonObject["failure_type"]!!.jsonPrimitive.content
        )
        assertEquals("ssh", json["error"]!!.jsonObject["identity"]!!.jsonPrimitive.content)
    }

    // ── Hermes-aligned flat responses ──────────────────────────────────────

    @Test
    fun commandSuccessFlat_onlyContainsStdoutStderrExitCode() {
        val json = parse(
            TerminalToolResponse.commandSuccessFlat(
                stdout = "hello\n",
                stderr = "",
                exitCode = 0,
            )
        )

        assertEquals("hello\n", json["stdout"]!!.jsonPrimitive.content)
        assertEquals("", json["stderr"]!!.jsonPrimitive.content)
        assertEquals("0", json["exit_code"]!!.jsonPrimitive.content)
        assertFalse(json.containsKey("session"))
        assertFalse(json.containsKey("identity"))
        assertFalse(json.containsKey("elapsed_seconds"))
        assertFalse(json.containsKey("error"))
    }

    @Test
    fun commandTimeoutFlat_includesPartialStdoutAndTimeoutError() {
        val json = parse(
            TerminalToolResponse.commandTimeoutFlat(
                stdout = "partial",
                stderr = "warn",
                timeoutSec = 5L,
            )
        )

        assertEquals("partial", json["stdout"]!!.jsonPrimitive.content)
        assertEquals("warn", json["stderr"]!!.jsonPrimitive.content)
        assertFalse(json.containsKey("exit_code"))
        assertErrorCode("TIMEOUT", json)
        val message = json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertTrue(message.contains("5s"))
    }

    @Test
    fun backgroundAccepted_includesSessionIdBackgroundAndOutput() {
        val json = parse(TerminalToolResponse.backgroundAccepted("abc123"))

        assertEquals("true", json["background"]!!.jsonPrimitive.content)
        assertEquals("abc123", json["session_id"]!!.jsonPrimitive.content)
        assertEquals("Background process started.", json["output"]!!.jsonPrimitive.content)
        assertFalse(json.containsKey("async_id"))
        assertFalse(json.containsKey("error"))
    }

    @Test
    fun readResult_includesStatusOutputAndOptionalExitCode() {
        val running = parse(
            TerminalToolResponse.readResult(
                sessionId = "a3f9",
                status = "running",
                output = "partial",
                exitCode = null,
                elapsedSeconds = 2L,
            )
        )

        assertEquals("a3f9", running["session_id"]!!.jsonPrimitive.content)
        assertEquals("running", running["status"]!!.jsonPrimitive.content)
        assertEquals("partial", running["output"]!!.jsonPrimitive.content)
        assertEquals("2", running["elapsed_seconds"]!!.jsonPrimitive.content)
        assertFalse(running.containsKey("exit_code"))

        val exited = parse(
            TerminalToolResponse.readResult(
                sessionId = "a3f9",
                status = "exited",
                output = "42 tests passed",
                exitCode = 0,
                elapsedSeconds = 30L,
            )
        )

        assertEquals("exited", exited["status"]!!.jsonPrimitive.content)
        assertEquals("42 tests passed", exited["output"]!!.jsonPrimitive.content)
        assertEquals("0", exited["exit_code"]!!.jsonPrimitive.content)
    }

    @Test
    fun writeResult_includesSessionIdAndBytesWritten() {
        val json = parse(TerminalToolResponse.writeResult(sessionId = "a3f9", bytesWritten = 5))

        assertEquals("a3f9", json["session_id"]!!.jsonPrimitive.content)
        assertEquals("5", json["bytes_written"]!!.jsonPrimitive.content)
        assertFalse(json.containsKey("error"))
    }

    @Test
    fun commandError_includesEmptyStreamsAndErrorObject() {
        val json = parse(
            TerminalToolResponse.commandError(
                code = "SESSION_NOT_FOUND",
                message = "Session 's1' not found.",
            )
        )

        assertEquals("", json["stdout"]!!.jsonPrimitive.content)
        assertEquals("", json["stderr"]!!.jsonPrimitive.content)
        assertEquals("-1", json["exit_code"]!!.jsonPrimitive.content)
        assertErrorCode("SESSION_NOT_FOUND", json)
        assertEquals(
            "Session 's1' not found.",
            json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun internalErrorUsesStructuredError() {
        val json = parse(
            TerminalToolResponse.internalError(
                IllegalStateException("boom"),
                elapsedSeconds = 6L
            )
        )

        assertErrorCode("INTERNAL_ERROR", json)
        assertEquals("boom", json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertEquals("6", json["elapsed_seconds"]!!.jsonPrimitive.content)
    }

    private fun commandResult(
        stdout: String = "",
        stderr: String = "",
        exitCode: Int? = 0,
        timedOut: Boolean = false,
    ): CommandResult {
        return CommandResult(
            command = "cmd",
            stdout = TerminalBytes.of(stdout.encodeToByteArray()),
            stderr = TerminalBytes.of(stderr.encodeToByteArray()),
            exitCode = exitCode,
            timedOut = timedOut,
        )
    }

    private fun parse(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun assertErrorCode(expected: String, json: kotlinx.serialization.json.JsonObject) {
        assertEquals(expected, json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }
}
