package com.niki914.nexus.agentic.chat

import com.niki914.libterm.OpenResult
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.CommandResult
import com.niki914.libterm.runtime.TermResult
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.TerminalBuiltin
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalRuntimePort
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPort
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode

class TerminalBuiltinTest {
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
        assertTrue(json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("pty_write"))
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
    fun invokeRawJson_commandFirstWithCwdBackwardCompat() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime(
            nextResult = commandResult(stdout = "/tmp\n"),
        )
        installFakeRuntime(fakeRuntime).use {
            installHandles("e001").use {
                val json = invoke("""{"command":"pwd","cwd":"/tmp"}""")

                assertEquals("0", json["exit_code"]!!.jsonPrimitive.content)
                assertEquals("/tmp", fakeRuntime.openedSessions.single().openedCwd)
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

    // ── Background (Hermes-aligned) ─────────────────────────────────────────

    @Test
    fun invokeRawJson_commandFirstBackground_returnsBackgroundAccepted() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime(
            nextResult = commandResult(stdout = "starting...\n"),
        )
        installFakeRuntime(fakeRuntime).use {
            installHandles("b0b1").use {
                val json = invoke(
                    """{"command":"npm run build","background":true,"timeout":300}"""
                )

                assertEquals("true", json["background"]!!.jsonPrimitive.content)
                assertTrue(json.containsKey("async_id"))
                assertTrue(json["async_id"]!!.jsonPrimitive.content.isNotBlank())
            }
        }
    }

    @Test
    fun invokeRawJson_backgroundBackwardCompat_isAsync() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime(
            nextResult = commandResult(stdout = "bg\n"),
        )
        installFakeRuntime(fakeRuntime).use {
            installHandles("c0c1").use {
                val json = invoke("""{"command":"long-task","is_async":true}""")

                assertEquals("true", json["background"]!!.jsonPrimitive.content)
            }
        }
    }

    // ── Action mode: backward compat (open_and_exec, exec, read_async_result) ─

    @Test
    fun invokeRawJson_openAndExecReturnsGeneratedHandle() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime(
            nextResult = commandResult(stdout = "ok\n"),
        )
        installFakeRuntime(fakeRuntime).use {
            installHandles("a3f9").use {
                val json =
                    invoke("""{"action":"open_and_exec","identity":"shizuku","command":"pwd"}""")

                assertEquals("a3f9", json["session"]!!.jsonPrimitive.content)
                assertEquals("shizuku", json["identity"]!!.jsonPrimitive.content)
                assertEquals("0", json["exit_code"]!!.jsonPrimitive.content)
                assertEquals("ok\n", json["stdout"]!!.jsonPrimitive.content)
                assertEquals(listOf(TerminalIdentity.Shizuku), fakeRuntime.openedIdentities)
                assertEquals(listOf("pwd"), fakeRuntime.openedSessions.single().commands)
            }
        }
    }

    @Test
    fun invokeRawJson_execReturnsSessionNotFoundWithoutOpening() = runTest {
        installRuntimeSettingsGatewayForTest()

        val json = invoke("""{"action":"exec","session":"user","command":"pwd"}""")

        assertErrorCode("SESSION_NOT_FOUND", json)
    }

    @Test
    fun invokeRawJson_execWithIdentityNameReturnsSessionNotFound() = runTest {
        installRuntimeSettingsGatewayForTest()

        val json = invoke("""{"action":"exec","session":"root","command":"pwd"}""")

        assertErrorCode("SESSION_NOT_FOUND", json)
        val message = json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertTrue(message.contains("handle returned by open or open_and_exec"))
        assertTrue(message.contains("Do not pass identity names"))
    }

    @Test
    fun invokeRawJson_asyncExecReturnsSessionNotFoundWithoutOpening() = runTest {
        installRuntimeSettingsGatewayForTest()

        val json =
            invoke("""{"action":"exec","session":"user","command":"sleep 10","is_async":true}""")

        assertErrorCode("SESSION_NOT_FOUND", json)
    }

    @Test
    fun invokeRawJson_readAsyncResultReturnsSessionNotFoundWithoutOpening() = runTest {
        val json = invoke("""{"action":"read_async_result","session":"user","async_id":"a1"}""")

        assertErrorCode("SESSION_NOT_FOUND", json)
    }

    @Test
    fun invokeRawJson_closeIsIdempotentForMissingSession() = runTest {
        val json = invoke("""{"action":"close","session":"user"}""")

        assertTrue(json["closed"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(json.containsKey("error"))
    }

    // ── Schema ───────────────────────────────────────────────────────────────

    @Test
    fun configure_schemaContainsHermesAlignedFields() {
        val config = LocalToolConfig()

        TerminalBuiltin().configure(config)

        val schema = Json.parseToJsonElement(config.rawInputSchemaJson!!).jsonObject
        val properties = schema["properties"]!!.jsonObject

        // Hermes-aligned fields
        assertTrue(properties.containsKey("command"))
        assertTrue(properties.containsKey("background"))
        assertTrue(properties.containsKey("timeout"))
        assertTrue(properties.containsKey("workdir"))
        assertTrue(properties.containsKey("pty"))
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

        // Action fields
        assertTrue(properties.containsKey("action"))
        assertTrue(
            properties["action"]!!.jsonObject["enum"]!!.jsonArray
                .map { it.jsonPrimitive.content }
                .containsAll(listOf("pty_write", "pty_read", "close"))
        )
    }

    @Test
    fun configure_schemaDoesNotRequireCommand() {
        val config = LocalToolConfig()
        TerminalBuiltin().configure(config)
        val schema = Json.parseToJsonElement(config.rawInputSchemaJson!!).jsonObject

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

    @Test
    fun invokeRawJson_rejectsInvalidTimeoutMsBackwardCompat() = runTest {
        val json = invoke("""{"action":"exec","session":"user","command":"pwd","timeout_ms":0}""")

        assertErrorCode("INVALID_REQUEST", json)
        assertEquals(
            "Field 'timeout_ms' must be greater than 0.",
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

    private fun assertErrorCode(expected: String, json: kotlinx.serialization.json.JsonObject) {
        assertEquals(expected, json["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
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
    ) : TerminalSessionPort {
        override val stream = emptyFlow<com.niki914.libterm.runtime.TerminalTextChunk>()
        val commands = mutableListOf<String>()
        var lastTimeoutMs: Long = 0L

        override suspend fun exec(command: String, timeoutMillis: Long): TermResult<CommandResult> {
            commands.add(command)
            lastTimeoutMs = timeoutMillis
            return TermResult.Success(nextResult)
        }

        override suspend fun write(text: String) = Unit

        override suspend fun close() = Unit
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
