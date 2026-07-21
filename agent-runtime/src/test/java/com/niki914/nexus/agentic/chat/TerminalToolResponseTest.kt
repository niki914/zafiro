package com.niki914.nexus.agentic.chat

import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.CommandResult
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalToolResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalToolResponseTest {
    @Test
    fun commandSuccessIncludesExitCodeStreamsAndElapsedSeconds() {
        val json = parse(
            TerminalToolResponse.commandSuccess(
                result = commandResult(exitCode = 7, stdout = "out", stderr = "err"),
                elapsedSeconds = 3L,
                session = "user",
                identity = "user",
            )
        )

        assertEquals("user", json["session"]!!.jsonPrimitive.content)
        assertEquals("user", json["identity"]!!.jsonPrimitive.content)
        assertEquals("7", json["exit_code"]!!.jsonPrimitive.content)
        assertEquals("out", json["stdout"]!!.jsonPrimitive.content)
        assertEquals("err", json["stderr"]!!.jsonPrimitive.content)
        assertEquals("3", json["elapsed_seconds"]!!.jsonPrimitive.content)
    }

    @Test
    fun commandSuccessCanMergeStderrIntoStdout() {
        val json = parse(
            TerminalToolResponse.commandSuccess(
                result = commandResult(stdout = "out", stderr = "err"),
                elapsedSeconds = 1L,
                mergeStderr = true,
            )
        )

        assertEquals("outerr", json["stdout"]!!.jsonPrimitive.content)
        assertEquals("", json["stderr"]!!.jsonPrimitive.content)
    }

    @Test
    fun commandTimeoutKeepsPartialOutputAndOmitsExitCode() {
        val json = parse(
            TerminalToolResponse.commandTimeout(
                result = commandResult(
                    stdout = "partial",
                    stderr = "warn",
                    exitCode = null,
                    timedOut = true
                ),
                elapsedSeconds = 10L,
                timeoutMs = 10_000L,
                session = "user",
                identity = "user",
            )
        )

        assertFalse(json.containsKey("exit_code"))
        assertEquals("partial", json["stdout"]!!.jsonPrimitive.content)
        assertEquals("warn", json["stderr"]!!.jsonPrimitive.content)
        assertEquals("10", json["elapsed_seconds"]!!.jsonPrimitive.content)
        assertErrorCode("TIMEOUT", json)
    }

    @Test
    fun commandTimeoutIncludesActionableRecovery() {
        val json = parse(
            TerminalToolResponse.commandTimeout(
                result = commandResult(exitCode = null, timedOut = true),
                elapsedSeconds = 10L,
                timeoutMs = 10_000L,
            )
        )

        val message = json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertErrorCode("TIMEOUT", json)
        assertTrue(message.contains("timeout_ms"))
        assertTrue(message.contains("is_async"))
        assertTrue(message.contains("read_async_result"))
    }

    @Test
    fun asyncRunningUsesPartialFieldsWithoutExitCode() {
        val json = parse(TerminalToolResponse.asyncRunning("stdout so far", "stderr so far", 5L))

        assertFalse(json.containsKey("exit_code"))
        assertEquals("stdout so far", json["stdout_partial"]!!.jsonPrimitive.content)
        assertEquals("stderr so far", json["stderr_partial"]!!.jsonPrimitive.content)
        assertEquals("5", json["elapsed_seconds"]!!.jsonPrimitive.content)
    }

    @Test
    fun sessionNotFoundSuggestsOpenOrOpenAndExec() {
        val json = parse(TerminalToolResponse.sessionNotFound("user"))

        val message = json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertErrorCode("SESSION_NOT_FOUND", json)
        assertTrue(message.contains("open"))
        assertTrue(message.contains("open_and_exec"))
    }

    @Test
    fun sessionNotFoundTellsCallerToUseReturnedHandle() {
        val json = parse(TerminalToolResponse.sessionNotFound("user"))

        val message = json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertErrorCode("SESSION_NOT_FOUND", json)
        assertTrue(message.contains("handle returned by open or open_and_exec"))
        assertTrue(message.contains("Do not pass identity names"))
        assertTrue(message.contains("user or root"))
    }

    @Test
    fun sessionBusyIncludesAsyncIdWhenPresent() {
        val json = parse(TerminalToolResponse.sessionBusy(session = "user", asyncId = "a1"))

        assertErrorCode("SESSION_BUSY", json)
        assertEquals("a1", json["error"]!!.jsonObject["async_id"]!!.jsonPrimitive.content)
        assertTrue(json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("read_async_result"))
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
