package com.niki914.nexus.agentic.chat

import com.niki914.libterm.OpenResult
import com.niki914.libterm.OutputStream
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.runtime.CommandResult
import com.niki914.libterm.runtime.TermResult
import com.niki914.libterm.runtime.TerminalTextChunk
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.SshTerminalBuiltin
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalRuntimePort
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPool
import com.niki914.nexus.agentic.chat.agentic.shell.TerminalSessionPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshTerminalBuiltinTest {
    @After
    fun tearDown() {
        runTest {
            TerminalSessionPool.closeAll()
        }
    }

    @Test
    fun invoke_returnsRawJsonOnlyHintWithSshExample() = runTest {
        val result = SshTerminalBuiltin().invoke(
            BuiltinToolRequest(
                name = "ssh_terminal",
                argumentsJson = "{}",
            )
        )

        assertFalse(result.ok)
        assertEquals("RAW_JSON_ONLY", result.code)
        assertTrue(result.hint.contains("ssh_terminal"))
        assertTrue(result.hint.contains("send_line"))
        assertTrue(result.hint.contains("host"))
        assertTrue(result.hint.contains("password"))
    }

    @Test
    fun configure_schemaContainsSshFields() {
        val tool = SshTerminalBuiltin()
        val config = com.niki914.s3ss10n.LocalToolConfig()

        tool.configure(config)

        val schema = Json.parseToJsonElement(config.rawInputSchemaJson!!).jsonObject
        val properties = schema["properties"]!!.jsonObject
        assertEquals(
            listOf("accept_any", "known_hosts_file"),
            properties["host_key_policy"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("string", properties["password"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(properties["session"]!!.jsonObject["enum"])
        assertEquals(
            listOf("open", "send_line", "write", "interrupt", "read", "close"),
            properties["action"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun invokeRawJson_rejectsUnknownFields() = runTest {
        val json = invoke(
            """
            {
              "action":"open",
              "host":"example.com",
              "username":"alice",
              "password":"secret",
              "unexpected":true
            }
            """.trimIndent()
        )

        assertErrorCode("INVALID_REQUEST", json)
        assertTrue(json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("Unknown ssh_terminal request field"))
    }

    @Test
    fun invokeRawJson_rejectsPrivateKeyFieldsForV1() = runTest {
        val json = invoke(
            """
            {
              "action":"open",
              "host":"example.com",
              "username":"alice",
              "private_key_pem":"-----BEGIN PRIVATE KEY-----",
              "password":"secret"
            }
            """.trimIndent()
        )

        assertErrorCode("INVALID_REQUEST", json)
        assertEquals(
            "Private key authentication is not supported yet.",
            json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun invokeRawJson_rejectsCommandExecutionActions() = runTest {
        val json = invoke("""{"action":"exec","session":"a3f9","command":"pwd"}""")

        assertErrorCode("INVALID_REQUEST", json)
        assertTrue(json["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("send_line"))
    }

    @Test
    fun invokeRawJson_openSendLineAndDeltaReadModelInteractiveTerminal() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a3f9").use {
                val opened = invoke(
                    """
                    {
                      "action":"open",
                      "host":"example.com",
                      "port":2222,
                      "username":"alice",
                      "password":"secret",
                      "host_key_policy":"known_hosts_file",
                      "known_hosts_path":"/data/local/tmp/known_hosts",
                      "strict_host_key_checking":false,
                      "connect_timeout_ms":1234,
                      "server_alive_interval_ms":5678
                    }
                    """.trimIndent()
                )
                fakeRuntime.openedSessions.single().emitStdout("~→ ")
                val prompt = invokeUntilStdout("""{"action":"read","session":"a3f9"}""", "~→ ")
                val sent =
                    invoke("""{"action":"send_line","session":"a3f9","text":"pwd","request_id":"r1"}""")
                fakeRuntime.openedSessions.single().emitStdout("pwd\r\n/home/alice\r\n~→ ")
                val output = invokeUntilStdout(
                    """{"action":"read","session":"a3f9","mode":"delta"}""",
                    "pwd\r\n/home/alice\r\n~→ ",
                )
                val empty = invoke("""{"action":"read","session":"a3f9"}""")

                assertEquals("a3f9", opened["session"]!!.jsonPrimitive.content)
                assertEquals("ssh", opened["identity"]!!.jsonPrimitive.content)
                assertEquals(listOf(TerminalIdentity.Ssh), fakeRuntime.openedIdentities)
                assertEquals("example.com", fakeRuntime.openedSshOptions.single()!!.host)
                assertEquals(2222, fakeRuntime.openedSshOptions.single()!!.port)
                assertEquals("alice", fakeRuntime.openedSshOptions.single()!!.username)
                assertTrue(opened.toString().contains("secret").not())
                assertEquals("~→ ", prompt["stdout"]!!.jsonPrimitive.content)
                assertEquals("", prompt["stderr"]!!.jsonPrimitive.content)
                assertEquals("delta", prompt["mode"]!!.jsonPrimitive.content)
                assertEquals("true", sent["accepted"]!!.jsonPrimitive.content)
                assertEquals("4", sent["bytes_written"]!!.jsonPrimitive.content)
                assertEquals(listOf("pwd\n"), fakeRuntime.openedSessions.single().writes)
                assertEquals("pwd\r\n/home/alice\r\n~→ ", output["stdout"]!!.jsonPrimitive.content)
                assertEquals("", empty["stdout"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun invokeRawJson_writeIsIdempotentWhenRequestIdRepeats() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a3f9").use {
                invoke("""{"action":"open","host":"example.com","username":"alice","password":"secret"}""")
                val first =
                    invoke("""{"action":"write","session":"a3f9","text":"abc","request_id":"same"}""")
                val second =
                    invoke("""{"action":"write","session":"a3f9","text":"abc","request_id":"same"}""")

                assertEquals(listOf("abc"), fakeRuntime.openedSessions.single().writes)
                assertEquals("false", first["replayed"]!!.jsonPrimitive.content)
                assertEquals("true", second["replayed"]!!.jsonPrimitive.content)
                assertEquals(
                    first["sequence"]!!.jsonPrimitive.content,
                    second["sequence"]!!.jsonPrimitive.content
                )
            }
        }
    }

    @Test
    fun invokeRawJson_interruptWritesCtrlC() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a3f9").use {
                invoke("""{"action":"open","host":"example.com","username":"alice","password":"secret"}""")
                val interrupted =
                    invoke("""{"action":"interrupt","session":"a3f9","request_id":"ctrl-c"}""")

                assertEquals(listOf("\u0003"), fakeRuntime.openedSessions.single().writes)
                assertEquals("true", interrupted["accepted"]!!.jsonPrimitive.content)
                assertEquals("1", interrupted["bytes_written"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test
    fun invokeRawJson_snapshotReadDoesNotConsumeDelta() = runTest {
        installRuntimeSettingsGatewayForTest()
        val fakeRuntime = FakeTerminalRuntime()
        installFakeRuntime(fakeRuntime).use {
            installHandles("a3f9").use {
                invoke("""{"action":"open","host":"example.com","username":"alice","password":"secret"}""")
                fakeRuntime.openedSessions.single().emitStdout("hello")

                val snapshot = invokeUntilStdout(
                    """{"action":"read","session":"a3f9","mode":"snapshot"}""",
                    "hello"
                )
                val delta = invokeUntilStdout("""{"action":"read","session":"a3f9"}""", "hello")

                assertEquals("hello", snapshot["stdout"]!!.jsonPrimitive.content)
                assertEquals("snapshot", snapshot["mode"]!!.jsonPrimitive.content)
                assertEquals("hello", delta["stdout"]!!.jsonPrimitive.content)
                assertEquals("delta", delta["mode"]!!.jsonPrimitive.content)
            }
        }
    }

    private suspend fun invoke(argumentsJson: String) = Json.parseToJsonElement(
        SshTerminalBuiltin().invokeRawJson(
            BuiltinToolRequest(
                name = "ssh_terminal",
                argumentsJson = argumentsJson,
            )
        )
    ).jsonObject

    private suspend fun invokeUntilStdout(
        argumentsJson: String,
        expectedStdout: String,
    ): kotlinx.serialization.json.JsonObject {
        var last = invoke(argumentsJson)
        repeat(20) {
            if (last["stdout"]?.jsonPrimitive?.content == expectedStdout) {
                return last
            }
            delay(10)
            last = invoke(argumentsJson)
        }
        return last
    }

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

    private class FakeTerminalRuntime : TerminalRuntimePort {
        val openedSessions = mutableListOf<FakeTerminalSession>()
        val openedIdentities = mutableListOf<TerminalIdentity>()
        val openedSshOptions = mutableListOf<SshOpenOptions?>()

        override suspend fun open(
            identity: TerminalIdentity,
            cwd: String?,
            sshOptions: SshOpenOptions?,
        ): OpenResult<TerminalSessionPort> {
            openedIdentities.add(identity)
            openedSshOptions.add(sshOptions)
            val session = FakeTerminalSession(id = "runtime-${openedSessions.size + 1}")
            openedSessions.add(session)
            return OpenResult.Success(session)
        }

        override suspend fun close(sessionId: String) = Unit

        override suspend fun closeAll(): Int = openedSessions.size
    }

    private class FakeTerminalSession(
        override val id: String,
    ) : TerminalSessionPort {
        override val stream =
            MutableSharedFlow<TerminalTextChunk>(replay = 16, extraBufferCapacity = 16)
        val writes = mutableListOf<String>()

        suspend fun emitStdout(text: String) {
            stream.subscriptionCount.first { it > 0 }
            stream.emit(
                TerminalTextChunk(
                    stream = OutputStream.STDOUT,
                    text = text,
                    timestampMillis = 1L,
                )
            )
            delay(10)
        }

        override suspend fun exec(command: String, timeoutMillis: Long): TermResult<CommandResult> {
            error("SSH terminal tests should not use exec.")
        }

        override suspend fun write(text: String) {
            writes.add(text)
        }

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
}
