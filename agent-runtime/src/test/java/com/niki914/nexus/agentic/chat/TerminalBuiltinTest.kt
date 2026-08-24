package com.niki914.nexus.agentic.chat

import com.niki914.libterm.OpenResult
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.CommandResult
import com.niki914.libterm.runtime.TermResult
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.TerminalBuiltin
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalRuntimePort
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPort
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.niki914.nexus.agentic.chat.util.SilentLoggerRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode

class TerminalBuiltinTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    @After
    fun tearDown() {
        runBlocking {
            TerminalSessionPool.closeAll()
            RuntimeEnvironment.clearForTest()
        }
    }

    // ── Basic invoke ────────────────────────────────────────────────────────

    @Test
    fun invoke_returnsRawJsonOnlyHintWithCommandExample() = runTest {
        val result = TerminalBuiltin().invoke(
            BuiltinToolRequest(
                name = "terminal",
                argumentsJson = "{}",
            )
        )

        assertFalse(result.ok)
        assertEquals("RAW_JSON_ONLY", result.code)
        assertTrue(result.hint.contains("command"))
    }

    @Test
    fun invokeRawJson_rejectsInvalidJson() = runTest {
        val json = invoke("""{"command":""")

        assertErrorCode("INVALID_REQUEST", json)
        assertEquals(
            "argumentsJson is not valid JSON.",
            json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun invokeRawJson_rejectsNeitherCommandNorAction() = runTest {
        val json = invoke("""{}""")

        assertErrorCode("INVALID_REQUEST", json)
        assertTrue(
            json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                .contains("command")
        )
    }

    @Test
    fun invokeRawJson_rejectsUnknownAction() = runTest {
        val json = invoke("""{"action":"unknown"}""")

        assertErrorCode("INVALID_REQUEST", json)
        assertTrue(
            json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                .contains("read, write, submit, close")
        )
    }

    @Test
    fun invokeRawJson_rejectsBlankCommand() = runTest {
        val json = invoke("""{"command":"   "}""")

        assertErrorCode("INVALID_REQUEST", json)
        assertEquals(
            "Field 'command' must not be blank.",
            json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun invokeRawJson_rejectsUnknownField() = runTest {
        val json = invoke("""{"command":"ls","unknown_key":"value"}""")

        assertErrorCode("INVALID_REQUEST", json)
        assertTrue(
            json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                .contains("Unknown terminal request field")
        )
    }

    // ── Command-first (Hermes-aligned) ──────────────────────────────────────

    @Test
    fun invokeRawJson_commandFirst_executesAndReturnsFlatResult() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime(
            nextResult = commandResult(stdout = "ok\n"),
        )
        installFakeRuntime(fakeRuntime).use {
            installHandles("a3f9").use {
                val json = invoke("""{"command":"pwd"}""")

                assertEquals("0", json["exit_code"]!!.jsonPrimitive.content)
                assertEquals("ok\n", json["stdout"]!!.jsonPrimitive.content)
                assertEquals("", json["stderr"]!!.jsonPrimitive.content)
                assertFalse(json.containsKey("session"))
            }
        }
    }

    @Test
    fun invokeRawJson_commandFirstWithLocalBackend_usesDefaultIdentity() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime(
            nextResult = commandResult(stdout = "done\n"),
        )
        installFakeRuntime(fakeRuntime).use {
            installHandles("b001").use {
                val json = invoke("""{"command":"whoami","backend":"local"}""")

                assertEquals("0", json["exit_code"]!!.jsonPrimitive.content)
                assertEquals("done\n", json["stdout"]!!.jsonPrimitive.content)
                assertEquals(listOf(TerminalIdentity.User), fakeRuntime.openedIdentities)
            }
        }
    }

    @Test
    fun invokeRawJson_commandFirstWithExplicitIdentity_usesGivenIdentity() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime(
            nextResult = commandResult(stdout = "root\n"),
        )
        installFakeRuntime(fakeRuntime).use {
            installHandles("c001").use {
                val json = invoke("""{"command":"whoami","identity":"root"}""")

                assertEquals("0", json["exit_code"]!!.jsonPrimitive.content)
                assertEquals(listOf(TerminalIdentity.Su), fakeRuntime.openedIdentities)
            }
        }
    }

    @Test
    fun invokeRawJson_commandFirstWithWorkdir_opensSessionWithCwd() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime(
            nextResult = commandResult(stdout = "/sdcard\n"),
        )
        installFakeRuntime(fakeRuntime).use {
            installHandles("d001").use {
                val json = invoke("""{"command":"pwd","workdir":"/sdcard"}""")

                assertEquals("0", json["exit_code"]!!.jsonPrimitive.content)
                assertEquals("/sdcard", fakeRuntime.openedSessions.single().openedCwd)
            }
        }
    }

    @Test
    fun invokeRawJson_commandFirstWithTimeout_usesSeconds() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime(
            nextResult = commandResult(stdout = "done\n"),
        )
        installFakeRuntime(fakeRuntime).use {
            installHandles("f001").use {
                val json = invoke("""{"command":"sleep 1","timeout":60}""")

                assertEquals("0", json["exit_code"]!!.jsonPrimitive.content)
                assertEquals(60000L, fakeRuntime.openedSessions.single().lastTimeoutMs)
            }
        }
    }

    @Test
    fun invokeRawJson_commandFirstTimesOut_returnsFlatTimeoutError() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime(
            nextResult = commandResult(stdout = "partial", timedOut = true),
        )
        installFakeRuntime(fakeRuntime).use {
            installHandles("a0a1").use {
                val rawResponse = TerminalBuiltin().invokeRawJson(
                    BuiltinToolRequest(name = "terminal", argumentsJson = """{"command":"sleep 999","timeout":1}""")
                )
                val json = Json.parseToJsonElement(rawResponse).jsonObject

                assertEquals("partial", json["stdout"]!!.jsonPrimitive.content)
                assertEquals("TIMEOUT", json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
                assertTrue(
                    json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                        .contains("1s")
                )
            }
        }
    }

    @Test
    fun invokeRawJson_commandFirstRejectsBlockedCommand() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(executionRules = dangerousRules())
        )

        val json = invoke(
            """{"command":"rm -rf /data/local/tmp/cache"}"""
        )

        assertErrorCode("COMMAND_BLOCKED", json)
        assertEquals(
            "dangerous-command",
            json["error"]!!.jsonObject["matched_rule_id"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun invokeRawJson_sshForegroundRejected_returnsInvalidRequest() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime(nextResult = commandResult())
        installFakeRuntime(fakeRuntime).use {
            val json = invoke(
                """{"command":"ls","backend":"ssh","host":"1.2.3.4","username":"root","password":"x"}"""
            )

            assertErrorCode("INVALID_REQUEST", json)
            assertTrue(
                json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                    .contains("background=true")
            )
            // Rejection happens before any SSH session is opened.
            assertTrue(fakeRuntime.openedSessions.isEmpty())
        }
    }

    @Test
    fun invokeRawJson_sshBackgroundWriteFailure_closesSession() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime(
            nextResult = commandResult(),
            failOnWrite = true,
        )
        installFakeRuntime(fakeRuntime).use {
            installHandles("b0b1").use {
                val json = invoke(
                    """{"command":"ls","backend":"ssh","background":true,"host":"1.2.3.4","username":"root","password":"x"}"""
                )

                // The write failed, so the response should be an internal error,
                // not a background-accepted response.
                assertErrorCode("INTERNAL_ERROR", json)
                // The session must have been closed (best-effort cleanup).
                assertEquals(1, fakeRuntime.openedSessions.single().closeCount)
                // The session must also have been removed from the pool.
                assertNull(TerminalSessionPool.get("b0b1"))
            }
        }
    }

    @Test
    fun invokeRawJson_commandFirstExecFailure_closesSessionInFinally() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime(failOnExec = true)
        installFakeRuntime(fakeRuntime).use {
            installHandles("f0f1").use {
                val json = invoke("""{"command":"pwd"}""")

                assertErrorCode("STARTUP_FAILED", json)
                assertEquals("-1", json["exit_code"]!!.jsonPrimitive.content)
                // The one-shot foreground session must be closed on the failure branch.
                assertEquals(1, fakeRuntime.openedSessions.single().closeCount)
                assertNull(TerminalSessionPool.get("f0f1"))
            }
        }
    }

    // ── Background (Hermes-aligned) ─────────────────────────────────────────

    @Test
    fun invokeRawJson_commandFirstBackground_returnsSessionId() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime(
            nextResult = commandResult(stdout = "starting...\n"),
        )
        installFakeRuntime(fakeRuntime).use {
            installHandles("b0b1").use {
                val json = invoke(
                    """{"command":"npm run build","background":true,"timeout":300}"""
                )

                assertEquals("b0b1", json["session_id"]!!.jsonPrimitive.content)
                assertEquals("true", json["background"]!!.jsonPrimitive.content)
                assertEquals(
                    "Background process started.",
                    json["output"]!!.jsonPrimitive.content,
                )
                assertFalse(json.containsKey("async_id"))
            }
        }
    }

    // ── Action mode (read/write/submit/close) ───────────────────────────────

    @Test
    fun invokeRawJson_readBackgroundSession_returnsRunningThenExited() = runTest {
        installRuntimeSettingsGatewayForTest()
        val gate = CompletableDeferred<Unit>()
        val fakeRuntime = FakeTerminalRuntime(
            nextResult = commandResult(stdout = "42 tests passed\n", exitCode = 0),
            execGate = gate,
        )
        installFakeRuntime(fakeRuntime).use {
            installHandles("a1b2").use {
                val started = invoke("""{"command":"npm test","background":true}""")
                assertEquals("a1b2", started["session_id"]!!.jsonPrimitive.content)

                // While the background job is still running, read reports "running"
                // with no exit_code. Both delta (default) and snapshot modes work.
                val running = invoke("""{"action":"read","session_id":"a1b2"}""")
                assertEquals("a1b2", running["session_id"]!!.jsonPrimitive.content)
                assertEquals("running", running["status"]!!.jsonPrimitive.content)
                assertFalse(running.containsKey("exit_code"))

                val snapshot = invoke("""{"action":"read","session_id":"a1b2","mode":"snapshot"}""")
                assertEquals("running", snapshot["status"]!!.jsonPrimitive.content)

                // Let the job finish: read then reports "exited" with the output
                // and exit_code stored when the background job completed.
                gate.complete(Unit)
                val exited = awaitBackgroundStatus("a1b2", "exited")
                assertEquals("a1b2", exited["session_id"]!!.jsonPrimitive.content)
                assertEquals("42 tests passed\n", exited["output"]!!.jsonPrimitive.content)
                assertEquals("0", exited["exit_code"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun invokeRawJson_writeAndSubmitToNonInteractiveSession_returnsInvalidRequest() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime(nextResult = commandResult())
        installFakeRuntime(fakeRuntime).use {
            installHandles("c3d4").use {
                val started = invoke("""{"command":"sleep 999","background":true}""")
                assertEquals("c3d4", started["session_id"]!!.jsonPrimitive.content)

                val write = invoke("""{"action":"write","session_id":"c3d4","text":"hello"}""")
                assertErrorCode("INVALID_REQUEST", write)
                assertTrue(
                    write["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                        .contains("not an interactive SSH terminal")
                )

                val submit = invoke("""{"action":"submit","session_id":"c3d4","text":"ls"}""")
                assertErrorCode("INVALID_REQUEST", submit)
                assertTrue(
                    submit["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                        .contains("not an interactive SSH terminal")
                )
            }
        }
    }

    @Test
    fun invokeRawJson_closeIsIdempotentForMissingSession() = runTest {
        val json = invoke("""{"action":"close","session_id":"user"}""")

        assertTrue(json["closed"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(json.containsKey("error"))
    }

    @Test
    fun invokeRawJson_ptyFieldReturnsInvalidRequest() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime(nextResult = commandResult())
        installFakeRuntime(fakeRuntime).use {
            installHandles("a3f9").use {
                val json = invoke("""{"command":"ls","pty":true}""")

                assertErrorCode("INVALID_REQUEST", json)
                assertTrue(
                    json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                        .contains("Unknown terminal request field")
                )
            }
        }
    }

    // ── Schema ───────────────────────────────────────────────────────────────

    @Test
    fun schema_containsHermesAlignedFields() {
        val schema = Json.parseToJsonElement(
            TerminalBuiltin().inputSchemaJson!!
        ).jsonObject
        val properties = schema["properties"]!!.jsonObject

        // Hermes-aligned fields
        assertTrue(properties.containsKey("command"))
        assertTrue(properties.containsKey("background"))
        assertTrue(properties.containsKey("timeout"))
        assertTrue(properties.containsKey("workdir"))
        assertTrue(properties.containsKey("notify_on_complete"))
        assertTrue(
            properties["command"]!!.jsonObject["description"]!!.jsonPrimitive.content
                .contains("automatically")
        )

        // Nexus extension fields
        assertTrue(properties.containsKey("backend"))
        assertTrue(properties.containsKey("identity"))
        assertTrue(properties.containsKey("host"))
        assertTrue(properties.containsKey("username"))
        assertTrue(properties.containsKey("password"))
        assertTrue(
            properties["backend"]!!.jsonObject["enum"]!!.jsonArray
                .map { it.jsonPrimitive.content }
                .containsAll(listOf("local", "ssh"))
        )
        assertTrue(
            properties["identity"]!!.jsonObject["enum"]!!.jsonArray
                .map { it.jsonPrimitive.content }
                .containsAll(listOf("user", "root", "shizuku"))
        )

        // Action fields: only the four Hermes-aligned session actions remain.
        assertTrue(properties.containsKey("action"))
        assertEquals(
            listOf("read", "write", "submit", "close"),
            properties["action"]!!.jsonObject["enum"]!!.jsonArray
                .map { it.jsonPrimitive.content },
        )
        assertTrue(properties.containsKey("session_id"))

        // pty must NOT be in the schema (not a Nexus protocol field)
        assertFalse(properties.containsKey("pty"))
    }

    @Test
    fun schema_doesNotRequireCommand() {
        val schema = Json.parseToJsonElement(
            TerminalBuiltin().inputSchemaJson!!
        ).jsonObject

        // Schema has no required fields (command or action is checked at runtime)
        val required = schema["required"]
        assertTrue(required == null || required.jsonArray.isEmpty())
    }

    // ── Timeout validation ──────────────────────────────────────────────────

    @Test
    fun invokeRawJson_rejectsInvalidTimeout() = runTest {
        val json = invoke("""{"command":"pwd","timeout":0}""")

        assertErrorCode("INVALID_REQUEST", json)
        assertEquals(
            "Field 'timeout' must be greater than 0.",
            json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content,
        )
    }

    // ── Test infrastructure ─────────────────────────────────────────────────

    private suspend fun invoke(argumentsJson: String) = Json.parseToJsonElement(
        TerminalBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "terminal",
                argumentsJson = argumentsJson,
            )
        )
    ).jsonObject

    private fun assertErrorCode(expected: String, json: JsonObject) {
        assertEquals(expected, json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    /**
     * Polls action=read until the background session reaches [expected] status.
     * The background exec job runs on the pool's IO dispatcher, so its completion
     * is observed asynchronously after the exec gate is released.
     */
    private suspend fun awaitBackgroundStatus(sessionId: String, expected: String): JsonObject {
        repeat(200) {
            val json = invoke("""{"action":"read","session_id":"$sessionId"}""")
            if (json["status"]?.jsonPrimitive?.content == expected) return json
            delay(5)
        }
        throw AssertionError("Background session '$sessionId' never reached status '$expected'")
    }

    private fun installFakeRuntime(fakeRuntime: FakeTerminalRuntime): AutoCloseable {
        return TerminalSessionPool.installRuntimePortFactoryForTest { fakeRuntime }
    }

    private fun installHandles(vararg handles: String): AutoCloseable {
        val iterator = handles.iterator()
        return TerminalSessionPool.installHandleGeneratorForTest {
            check(iterator.hasNext()) { "No fake terminal handles left." }
            iterator.next()
        }
    }

    private class FakeTerminalRuntime(
        private val nextResult: CommandResult = commandResult(),
        private val failOnExec: Boolean = false,
        private val execGate: CompletableDeferred<Unit>? = null,
        private val failOnWrite: Boolean = false,
    ) : TerminalRuntimePort {
        val openedSessions = mutableListOf<FakeTerminalSession>()
        val openedIdentities = mutableListOf<TerminalIdentity>()

        override suspend fun open(
            identity: TerminalIdentity,
            cwd: String?,
            sshOptions: SshOpenOptions?,
        ): OpenResult<TerminalSessionPort> {
            openedIdentities.add(identity)
            val session = FakeTerminalSession(
                id = "runtime-${openedSessions.size + 1}",
                nextResult = nextResult,
                openedCwd = cwd,
                failOnExec = failOnExec,
                execGate = execGate,
                failOnWrite = failOnWrite,
            )
            openedSessions.add(session)
            return OpenResult.Success(session)
        }

        override suspend fun close(sessionId: String) = Unit

        override suspend fun closeAll(): Int = openedSessions.size
    }

    private class FakeTerminalSession(
        override val id: String,
        private val nextResult: CommandResult,
        val openedCwd: String? = null,
        private val failOnExec: Boolean = false,
        private val execGate: CompletableDeferred<Unit>? = null,
        private val failOnWrite: Boolean = false,
    ) : TerminalSessionPort {
        override val stream = emptyFlow<com.niki914.libterm.runtime.TerminalTextChunk>()
        val commands = mutableListOf<String>()
        var lastTimeoutMs: Long = 0L
        var closeCount: Int = 0
            private set

        override suspend fun exec(command: String, timeoutMillis: Long): TermResult<CommandResult> {
            commands.add(command)
            lastTimeoutMs = timeoutMillis
            // When set, keep the exec suspended until the test releases it so the
            // background job's running/exited transitions can be observed.
            execGate?.await()
            return if (failOnExec) {
                TermResult.Failure(
                    TerminalFailure.StartupFailed(
                        identity = TerminalIdentity.User,
                        message = "fake exec failure",
                    )
                )
            } else {
                TermResult.Success(nextResult)
            }
        }

        override suspend fun write(text: String) {
            if (failOnWrite) throw RuntimeException("fake write failure")
        }

        override suspend fun close() {
            closeCount++
        }
    }

    private companion object {
        fun commandResult(
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
    }

    private fun dangerousRules(): List<ExecutionRule> {
        return listOf(
            ExecutionRule(
                id = "dangerous-command",
                name = "危险命令",
                enabledMode = ExecutionRuleEnabledMode.ALWAYS,
                patterns = listOf("\\brm\\s+-rf\\b"),
            )
        )
    }
}
