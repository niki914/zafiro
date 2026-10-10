// 保护：BaseSessionBuiltin 的别名寻址映射、PTY回显环境下的Sentinel退出码提取、同步超时优雅降级与多会话生命周期解绑，输入错误别名、超时或回显干扰会破坏它
package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.impl.BaseSessionArgs
import com.niki914.zafiro.chat.agentic.buildin.impl.BaseSessionBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.SessionAction
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.chat.util.SilentLoggerRule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class BaseSessionBuiltinTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    @After
    fun tearDown() {
        runBlocking {
            TerminalSessionPool.closeAll()
        }
    }

    private class TestSessionBuiltin(
        private val defaultSessionId: String? = null,
    ) : BaseSessionBuiltin() {
        override val name: String = "test_session"

        val sentPayloads = mutableListOf<Pair<String, String>>()
        val sessionBuffers = ConcurrentHashMap<String, StringBuilder>()
        var autoRespondExitCode: Int? = null
        var autoRespondOutput: String = "ok\n"

        override fun parseArguments(argumentsJson: String): BaseSessionArgs {
            val obj = Json.parseToJsonElement(argumentsJson).jsonObject
            val action = obj["action"]?.jsonPrimitive?.content?.let { SessionAction.fromWireName(it) }
            val session = obj["session"]?.jsonPrimitive?.content
            val alias = obj["alias"]?.jsonPrimitive?.content
            val command = obj["command"]?.jsonPrimitive?.content
            val text = obj["text"]?.jsonPrimitive?.content
            val background = obj["background"]?.jsonPrimitive?.content?.toBoolean() ?: false
            val timeout = obj["timeout"]?.jsonPrimitive?.content?.toLongOrNull()
            return BaseSessionArgs(
                command = command,
                session = session,
                alias = alias,
                action = action,
                text = text,
                background = background,
                timeout = timeout,
            )
        }

        override suspend fun onDefaultSession(): String? = defaultSessionId

        override suspend fun sendInput(sessionId: String, text: String): Boolean {
            sentPayloads.add(sessionId to text)
            val exitCode = autoRespondExitCode
            if (exitCode != null) {
                val prefix = BaseSessionBuiltin.SessionSentinel.MARKER_PREFIX
                val markerIdx = text.indexOf(prefix)
                if (markerIdx >= 0) {
                    val token = text.substring(markerIdx + prefix.length).substringBefore(":")
                    appendOutput(
                        sessionId,
                        "$autoRespondOutput\n$prefix$token:$exitCode${BaseSessionBuiltin.SessionSentinel.MARKER_SUFFIX}\n"
                    )
                }
            }
            return true
        }

        override fun readRawOutput(sessionId: String): String? {
            return sessionBuffers[sessionId]?.toString()
        }

        fun appendOutput(sessionId: String, text: String) {
            sessionBuffers.getOrPut(sessionId) { StringBuilder() }.append(text)
        }
    }

    // ── SessionRegistry & 别名寻址 ──────────────────────────────────────────

    @Test
    fun sessionRegistry_resolvesBothIdAndAlias() {
        val tool = TestSessionBuiltin()
        tool.sessionRegistry.register(sessionId = "sess_01", alias = "my-host")

        assertEquals("sess_01", tool.sessionRegistry.resolveSessionId("my-host"))
        assertEquals("sess_01", tool.sessionRegistry.resolveSessionId("sess_01"))
        assertNull(tool.sessionRegistry.resolveSessionId("non-existent"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun sessionRegistry_rejectsDuplicateAliasForDifferentSessions() {
        val tool = TestSessionBuiltin()
        tool.sessionRegistry.register(sessionId = "sess_01", alias = "shared-alias")
        tool.sessionRegistry.register(sessionId = "sess_02", alias = "shared-alias")
    }

    @Test
    fun dispatchRequest_resolvesSessionViaAliasField() = runTest {
        val tool = TestSessionBuiltin().apply {
            autoRespondExitCode = 0
            autoRespondOutput = "alias_ok\n"
        }
        tool.sessionRegistry.register(sessionId = "sess_target", alias = "my_alias")

        // 仅传 alias，不传 session 字段
        val rawResponse = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"test","alias":"my_alias"}""",
            )
        )
        val json = Json.parseToJsonElement(rawResponse).jsonObject

        assertEquals("sess_target", json["session_id"]!!.jsonPrimitive.content)
        assertEquals("my_alias", json["alias"]!!.jsonPrimitive.content)
        assertEquals("exited", json["status"]!!.jsonPrimitive.content)
    }

    // ── onDefaultSession 路由 ───────────────────────────────────────────────

    @Test
    fun dispatchRequest_fallsBackToDefaultSessionWhenSessionOmitted() = runTest {
        val tool = TestSessionBuiltin(defaultSessionId = "default_sess")
        tool.sessionRegistry.register(sessionId = "default_sess", alias = "default")
        tool.appendOutput("default_sess", "hello\n__SESSION_EXIT_anytoken:0__\n")

        val rawResponse = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"whoami","background":true}""",
            )
        )
        val json = Json.parseToJsonElement(rawResponse).jsonObject

        assertEquals("default_sess", json["session_id"]!!.jsonPrimitive.content)
        assertEquals("default", json["alias"]!!.jsonPrimitive.content)
    }

    // ── SessionSentinel 退出码解析 (防御 PTY 回显干扰) ─────────────────────

    @Test
    fun sessionSentinel_correctlyExtractsExitCodeDespitePtyEcho() {
        val token = "abc123xyz"
        val ptyStreamOutput = buildString {
            append("echo hello\nhello\n")
            append("__sess_status=$?; printf '\\n__SESSION_EXIT_abc123xyz:%s__\\n' \"\$__sess_status\"\n")
            append("\n__SESSION_EXIT_abc123xyz:0__\n")
        }

        val result = BaseSessionBuiltin.SessionSentinel.parseOutput(ptyStreamOutput, token)

        assertNotNull(result)
        assertEquals(0, result!!.exitCode)
        assertFalse(result.cleanOutput.contains("__SESSION_EXIT_"))
    }

    @Test
    fun sessionSentinel_returnsNullWhenMarkerNotFoundOrMalformed() {
        val token = "tok456"
        val outputWithoutMarker = "some output without marker\n"
        val outputWithMalformedCode = "\n__SESSION_EXIT_tok456:not_a_number__\n"

        assertNull(BaseSessionBuiltin.SessionSentinel.parseOutput(outputWithoutMarker, token))
        assertNull(BaseSessionBuiltin.SessionSentinel.parseOutput(outputWithMalformedCode, token))
    }

    // ── 命令执行：同步成功 vs 超时优雅降级 vs 显式后台 ──────────────────────

    @Test
    fun handleCommand_executesSynchronouslyWhenSentinelCompletes() = runTest {
        val tool = TestSessionBuiltin().apply {
            autoRespondExitCode = 0
            autoRespondOutput = "hello world\n"
        }
        tool.sessionRegistry.register(sessionId = "s1", alias = "fast")

        val requestJson = """{"command":"echo hello world","session":"fast","timeout":5}"""
        val rawResponse = tool.invokeRawJson(
            BuiltinToolRequest(name = "test_session", argumentsJson = requestJson)
        )
        val json = Json.parseToJsonElement(rawResponse).jsonObject

        assertEquals("exited", json["status"]!!.jsonPrimitive.content)
        assertEquals("0", json["exit_code"]!!.jsonPrimitive.content)
        assertEquals("hello world", json["output"]!!.jsonPrimitive.content.trim())
        assertEquals("s1", json["session_id"]!!.jsonPrimitive.content)
        assertEquals("fast", json["alias"]!!.jsonPrimitive.content)
    }

    @Test
    fun handleCommand_timesOutAndDegradesToBackground() = runTest {
        val tool = TestSessionBuiltin()
        tool.sessionRegistry.register(sessionId = "s1", alias = "slow")

        val requestJson = """{"command":"sleep 100","session":"slow","timeout":1}"""
        val rawResponse = tool.invokeRawJson(
            BuiltinToolRequest(name = "test_session", argumentsJson = requestJson)
        )
        val json = Json.parseToJsonElement(rawResponse).jsonObject

        assertEquals("running", json["status"]!!.jsonPrimitive.content)
        assertTrue(json["timed_out"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("s1", json["session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun handleCommand_consecutiveCommandsDoNotLeakPreviousOutputOrSentinel() = runTest {
        val tool = TestSessionBuiltin().apply {
            autoRespondExitCode = 0
        }
        tool.sessionRegistry.register(sessionId = "s1", alias = "term")

        // 第一条命令
        tool.autoRespondOutput = "first_output\n"
        val resp1 = tool.invokeRawJson(
            BuiltinToolRequest(name = "test_session", argumentsJson = """{"command":"cmd1","session":"term"}""")
        )
        val json1 = Json.parseToJsonElement(resp1).jsonObject
        assertEquals("first_output", json1["output"]!!.jsonPrimitive.content.trim())

        // 第二条命令在同一会话中执行，不应夹带第一条命令的输出或其 Sentinel
        tool.autoRespondOutput = "second_output\n"
        val resp2 = tool.invokeRawJson(
            BuiltinToolRequest(name = "test_session", argumentsJson = """{"command":"cmd2","session":"term"}""")
        )
        val json2 = Json.parseToJsonElement(resp2).jsonObject
        val output2 = json2["output"]!!.jsonPrimitive.content.trim()

        assertEquals("second_output", output2)
        assertFalse(output2.contains("first_output"))
        assertFalse(output2.contains(BaseSessionBuiltin.SessionSentinel.MARKER_PREFIX))
    }

    @Test
    fun handleCommand_explicitBackgroundReturnsImmediately() = runTest {
        val tool = TestSessionBuiltin()
        tool.sessionRegistry.register(sessionId = "s1", alias = "bg")

        val rawResponse = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"sleep 10","session":"bg","background":true}""",
            )
        )
        val json = Json.parseToJsonElement(rawResponse).jsonObject

        assertEquals("running", json["status"]!!.jsonPrimitive.content)
        assertTrue(json["background"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("s1", json["session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun handleRead_detectsSentinelAndTransitionsToExited() = runTest {
        val tool = TestSessionBuiltin()
        tool.sessionRegistry.register(sessionId = "s1", alias = "reader")

        // 显式起后台任务
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"long_job","session":"reader","background":true}""",
            )
        )

        // 此时取 token 并写入完成输出
        val commandState = tool.sessionRegistry.getCommandState("s1")
        assertNotNull(commandState)
        tool.appendOutput("s1", "done!\n\n__SESSION_EXIT_${commandState!!.token}:0__\n")

        // 调用 action: read
        val readResponse = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"action":"read","session":"reader"}""",
            )
        )
        val readJson = Json.parseToJsonElement(readResponse).jsonObject

        assertEquals("exited", readJson["status"]!!.jsonPrimitive.content)
        assertEquals("0", readJson["exit_code"]!!.jsonPrimitive.content)
        assertTrue(readJson["output"]!!.jsonPrimitive.content.contains("done!"))
    }

    // ── action: list ────────────────────────────────────────────────────────

    @Test
    fun handleList_returnsAllRegisteredSessions() = runTest {
        val tool = TestSessionBuiltin()
        tool.sessionRegistry.register(sessionId = "s1", alias = "web", metadata = mapOf("host" to "1.1.1.1"))
        tool.sessionRegistry.register(sessionId = "s2", alias = "db", metadata = mapOf("host" to "2.2.2.2"))

        val rawResponse = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"action":"list"}""",
            )
        )
        val json = Json.parseToJsonElement(rawResponse).jsonObject

        assertEquals(2, json["total"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, json["sessions"]!!.jsonArray.size)
    }

    // ── action: close ───────────────────────────────────────────────────────

    @Test
    fun handleClose_unregistersSessionAndFreesAlias() = runTest {
        val tool = TestSessionBuiltin()
        tool.sessionRegistry.register(sessionId = "close_me", alias = "temp")

        val rawResponse = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"action":"close","session":"temp"}""",
            )
        )
        val json = Json.parseToJsonElement(rawResponse).jsonObject

        assertEquals("close_me", json["session_id"]!!.jsonPrimitive.content)
        assertTrue(json["closed"]!!.jsonPrimitive.content.toBoolean())
        assertNull(tool.sessionRegistry.resolveSessionId("temp"))
        assertNull(tool.sessionRegistry.findById("close_me"))
    }

    @Test
    fun handleSubmit_clearsExitedCommandStateToAllowSubsequentInteractiveReads() = runTest {
        val tool = TestSessionBuiltin().apply {
            autoRespondExitCode = 0
            autoRespondOutput = "initial_output\n"
        }
        tool.sessionRegistry.register(sessionId = "s1", alias = "repl")

        // 1. 发送普通命令并执行完毕 (status="exited")
        tool.invokeRawJson(
            BuiltinToolRequest(name = "test_session", argumentsJson = """{"command":"ls","session":"repl"}""")
        )
        assertEquals("exited", tool.sessionRegistry.getCommandState("s1")?.status)

        // 2. 发送 action="submit"，由于前一命令已退出，必须清除旧的 commandState
        tool.invokeRawJson(
            BuiltinToolRequest(name = "test_session", argumentsJson = """{"action":"submit","session":"repl","text":"q"}""")
        )
        assertNull(tool.sessionRegistry.getCommandState("s1"))
    }
}
