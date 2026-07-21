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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode

class TerminalBuiltinTest {
    @After
    fun tearDown() {
        runTest {
            TerminalSessionPool.closeAll()
            RuntimeEnvironment.clearForTest()
        }
    }

    @Test
    fun invoke_returnsRawJsonOnlyHintWithOpenAndExecExample() = runTest {
        val result = TerminalBuiltin().invoke(
            BuiltinToolRequest(
                name = "terminal",
                argumentsJson = "{}",
            )
        )

        assertFalse(result.ok)
        assertEquals("RAW_JSON_ONLY", result.code)
        assertTrue(result.hint.contains("open_and_exec"))
        assertTrue(result.hint.contains("identity"))
        assertTrue(result.hint.contains("command"))
    }

    @Test
    fun invokeRawJson_rejectsInvalidJson() = runTest {
        val json = invoke("""{"action":""")

        assertErrorCode("INVALID_REQUEST", json)
        assertEquals(
            "argumentsJson is not valid JSON.",
            json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun invokeRawJson_rejectsUnknownAction() = runTest {
        val json = invoke("""{"action":"unknown"}""")

        assertErrorCode("INVALID_REQUEST", json)
        assertTrue(json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("open_and_exec"))
    }

    @Test
    fun invokeRawJson_rejectsBlankCommand() = runTest {
        val json = invoke("""{"action":"exec","session":"user","command":"   "}""")

        assertErrorCode("INVALID_REQUEST", json)
        assertEquals(
            "Field 'command' must not be blank.",
            json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun invokeRawJson_rejectsBlockedOpenAndExecBeforeOpeningSession() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(executionRules = dangerousRules())
        )

        val json = invoke(
            """{"action":"open_and_exec","identity":"user","command":"rm -rf /data/local/tmp/cache"}"""
        )

        assertErrorCode("COMMAND_BLOCKED", json)
        assertEquals("0", json["elapsed_seconds"]!!.jsonPrimitive.content)
        assertEquals(
            "dangerous-command",
            json["error"]!!.jsonObject["matched_rule_id"]!!.jsonPrimitive.content,
        )
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
    fun configure_sessionSchemaEnumeratesPublicIdentitiesIncludingShizuku() {
        val config = LocalToolConfig()

        TerminalBuiltin().configure(config)

        val schema = Json.parseToJsonElement(config.rawInputSchemaJson!!).jsonObject
        val properties = schema["properties"]!!.jsonObject
        val sessionSchema = properties["session"]!!.jsonObject
        val identitySchema = properties["identity"]!!.jsonObject
        assertFalse(sessionSchema.containsKey("enum"))
        assertEquals(
            listOf("user", "root", "shizuku"),
            identitySchema["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(
            sessionSchema["description"]!!.jsonPrimitive.content.contains("returned by open or open_and_exec")
        )
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

    @Test
    fun invokeRawJson_rejectsInvalidTimeoutOverride() = runTest {
        val json = invoke("""{"action":"exec","session":"user","command":"pwd","timeout_ms":0}""")

        assertErrorCode("INVALID_REQUEST", json)
        assertEquals(
            "Field 'timeout_ms' must be greater than 0.",
            json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content,
        )
    }

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
    ) : TerminalSessionPort {
        override val stream = emptyFlow<com.niki914.libterm.runtime.TerminalTextChunk>()
        val commands = mutableListOf<String>()

        override suspend fun exec(command: String, timeoutMillis: Long): TermResult<CommandResult> {
            commands.add(command)
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
