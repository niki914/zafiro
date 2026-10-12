// 保护：BaseSessionBuiltin 的别名寻址映射、PTY回显环境下的Sentinel退出码提取、同步超时优雅降级、多会话生命周期解绑，
// 以及交互输入的规则前检与已完成命令的重复读契约；输入错误别名、超时或回显干扰会破坏它
package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.impl.BaseSessionArgs
import com.niki914.zafiro.chat.agentic.buildin.impl.BaseSessionBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.SessionAction
import com.niki914.zafiro.chat.agentic.shell.SessionRegistry
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.chat.util.SilentLoggerRule
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

class BaseSessionBuiltinTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    @Before
    fun setUp() {
        // base 现在统一跑执行规则前检，前检依赖 settings gateway
        installRuntimeSettingsGatewayForTest()
    }

    @After
    fun tearDown() {
        runBlocking {
            TerminalSessionPool.closeAll()
        }
        SessionRegistry.clear()
        RuntimeEnvironment.clearForTest()
    }

    private class TestSessionBuiltin(
        private val defaultSessionId: String? = null,
    ) : BaseSessionBuiltin() {
        override val name: String = "test_session"

        val sentPayloads = mutableListOf<Pair<String, String>>()
        val sessionBuffers = ConcurrentHashMap<String, StringBuilder>()
        var autoRespondExitCode: Int? = null
        var autoRespondOutput: String = "ok\n"

        /** 假死会话：默认全部存活；个别用例把句柄放进来模拟掉线/被回收。 */
        val deadSessions = mutableSetOf<String>()

        override fun isSessionAlive(sessionId: String): Boolean = sessionId !in deadSessions

        /** 默认按无 PTY 的本地会话说事；需要走 Ctrl-C 作废的用例把它拨到 true。 */
        var ctrlCAbort = false

        override val supportsCtrlCAbort: Boolean get() = ctrlCAbort

        override fun parseArguments(argumentsJson: String): BaseSessionArgs {
            val obj = Json.parseToJsonElement(argumentsJson).jsonObject
            val action =
                obj["action"]?.jsonPrimitive?.content?.let { SessionAction.fromWireName(it) }
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
                // token 位于 payload 的 "$__t" "<token>" 处
                val token = text.substringAfter("\"\$__t\" \"", "").substringBefore("\"")
                if (token.isNotEmpty()) {
                    appendOutput(
                        sessionId,
                        "$autoRespondOutput\n${BaseSessionBuiltin.SessionSentinel.MARKER_PREFIX}$token:$exitCode${BaseSessionBuiltin.SessionSentinel.MARKER_SUFFIX}\n"
                    )
                }
            }
            return true
        }

        override suspend fun readRawOutput(sessionId: String): String? {
            // 模拟采集管道有延迟：前 N 次读还看不到已经 append 的内容
            val visibleAfter = bufferVisibleAfterReads
            if (visibleAfter != null) {
                readCount += 1
                if (readCount < visibleAfter) return ""
            }
            return sessionBuffers[sessionId]?.toString()
        }

        /** 模拟尾部（含哨兵标记）还在采集管道里往外走。 */
        var bufferVisibleAfterReads: Int? = null
        private var readCount = 0

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
    fun sessionSentinel_echoedPayloadAloneCannotForgeMarker() {
        // 门禁：旧实现把标记字面写进 payload，tty 回显即可伪造真标记；shell 未就绪时真实
        // 输出被整段截掉（真机 3/3 必现首条命令丢输出）。新 payload 的回显拼不出完整标记：
        // 只有回显、没有真标记的流必须解析为 null。
        val token = "abc123xyz"
        val payload = BaseSessionBuiltin.SessionSentinel.buildPayload("echo hello", token)
        val echoStream = "~ $ echo hello\n${payload.lines()[1]}\nhello\n~ $ "

        assertNull(
            BaseSessionBuiltin.SessionSentinel.parseOutput(
                echoStream,
                token,
                echoCommand = "echo hello"
            )
        )
    }

    @Test
    fun sessionSentinel_extractsExitCodeAndStripsEchoFromRealMarker() {
        // 真实 PTY 流：提示符 + 命令回显 + payload 第二行回显 + 真输出 + 真标记；
        // 去噪只应拿走回显与残片，裸提示符行保留
        val token = "abc123xyz"
        val payload = BaseSessionBuiltin.SessionSentinel.buildPayload("echo hello", token)
        val ptyStream = buildString {
            append("~ $ echo hello\n")
            append(payload.lines()[1])
            append("\nhello\n~ $ \n")
            append("__SESSION_EXIT_abc123xyz:0__\n~ $ ")
        }

        val result = BaseSessionBuiltin.SessionSentinel.parseOutput(
            ptyStream,
            token,
            echoCommand = "echo hello"
        )

        assertNotNull(result)
        assertEquals(0, result!!.exitCode)
        assertTrue(result.cleanOutput.contains("hello"))
        assertFalse(result.cleanOutput.contains("printf"))
        assertFalse(result.cleanOutput.contains("\$__t"))
    }

    @Test
    fun sessionSentinel_returnsNullWhenMarkerNotFoundOrMalformed() {
        val token = "tok456"
        val outputWithoutMarker = "some output without marker\n"
        val outputWithMalformedCode = "\n__SESSION_EXIT_tok456:not_a_number__\n"

        assertNull(BaseSessionBuiltin.SessionSentinel.parseOutput(outputWithoutMarker, token))
        assertNull(BaseSessionBuiltin.SessionSentinel.parseOutput(outputWithMalformedCode, token))
    }

    @Test
    fun sessionSentinel_keepsFirstOutputLineThatMerelyEndsWithTheCommand() {
        // 本地 shell 没有回显，首行「以命令文本结尾」不代表是回显；按旧规则剥掉就是吃真实输出：
        // 命令 pwd 而输出 /data/.../pwd 会被整行删除
        val token = "abc123xyz"
        val stream = "/data/user/0/app/files/pwd\n~ $ \n__SESSION_EXIT_$token:0__\n~ $ "

        val result = BaseSessionBuiltin.SessionSentinel.parseOutput(
            rawOutput = stream,
            token = token,
            echoCommand = "pwd",
        )

        assertNotNull(result)
        assertTrue(result!!.cleanOutput.contains("/data/user/0/app/files/pwd"))
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
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"cmd1","session":"term"}"""
            )
        )
        val json1 = Json.parseToJsonElement(resp1).jsonObject
        assertEquals("first_output", json1["output"]!!.jsonPrimitive.content.trim())

        // 第二条命令在同一会话中执行，不应夹带第一条命令的输出或其 Sentinel
        tool.autoRespondOutput = "second_output\n"
        val resp2 = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"cmd2","session":"term"}"""
            )
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
        tool.sessionRegistry.register(
            sessionId = "s1",
            alias = "web",
            metadata = mapOf("host" to "1.1.1.1")
        )
        tool.sessionRegistry.register(
            sessionId = "s2",
            alias = "db",
            metadata = mapOf("host" to "2.2.2.2")
        )

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
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"ls","session":"repl"}"""
            )
        )
        assertEquals("exited", tool.sessionRegistry.getCommandState("s1")?.status)

        // 2. 发送 action="submit"，由于前一命令已退出，必须清除旧的 commandState
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"action":"submit","session":"repl","text":"q"}"""
            )
        )
        assertNull(tool.sessionRegistry.getCommandState("s1"))
    }

    // ── 回执卫生：NUL 填充与超长输出 ──────────────────────────────────────

    @Test
    fun handleRead_stripsNulPaddingFromTerminalOutput() = runTest {
        val tool = TestSessionBuiltin()
        tool.sessionRegistry.register(sessionId = "s1", alias = "noisy_pty")
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"job","session":"noisy_pty","background":true}""",
            )
        )
        // PTY 会在提示符后写入 NUL 填充
        tool.appendOutput("s1", "hello\u0000\u0000\u0000world")

        val readResponse = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"action":"read","session":"noisy_pty"}""",
            )
        )
        val output =
            Json.parseToJsonElement(readResponse).jsonObject["output"]!!.jsonPrimitive.content

        assertEquals("helloworld", output)
    }

    @Test
    fun handleCommand_truncatesOversizedOutputAndExportsFullText() = runTest {
        val exportDir = Files.createTempDirectory("zafiro-tool-output").toFile()
        try {
            val tool = TestSessionBuiltin().apply {
                exportDirOverride = exportDir
                autoRespondExitCode = 0
                autoRespondOutput = "line\n".repeat(3000)
            }
            tool.sessionRegistry.register(sessionId = "s1", alias = "loud")

            val rawResponse = tool.invokeRawJson(
                BuiltinToolRequest(
                    name = "test_session",
                    argumentsJson = """{"command":"noisy","session":"loud"}""",
                )
            )
            val output =
                Json.parseToJsonElement(rawResponse).jsonObject["output"]!!.jsonPrimitive.content

            assertTrue(output.contains("[Output truncated:"))
            assertTrue(output.contains(exportDir.absolutePath))
            assertTrue(exportDir.listFiles().orEmpty().isNotEmpty())
        } finally {
            exportDir.deleteRecursively()
        }
    }

    // ── action='read' 的差分语义 ────────────────────────────────────────────

    @Test
    fun handleRead_returnsOnlyOutputAppendedSincePreviousRead() = runTest {
        val tool = TestSessionBuiltin()
        tool.sessionRegistry.register(sessionId = "s1", alias = "stream")
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"job","session":"stream","background":true}""",
            )
        )

        tool.appendOutput("s1", "first_batch\n")
        val first = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"action":"read","session":"stream"}"""
            )
        )
        assertEquals(
            "first_batch",
            Json.parseToJsonElement(first).jsonObject["output"]!!.jsonPrimitive.content.trim(),
        )

        // 第二次读只能看到新增部分，不能把第一段重新发一遍
        tool.appendOutput("s1", "second_batch\n")
        val second = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"action":"read","session":"stream"}"""
            )
        )
        assertEquals(
            "second_batch",
            Json.parseToJsonElement(second).jsonObject["output"]!!.jsonPrimitive.content.trim(),
        )
    }

    // ── 忙会话拒绝新命令 ────────────────────────────────────────────────────

    @Test
    fun handleCommand_rejectsNewCommandWhilePreviousOneIsInFlight() = runTest {
        val tool = TestSessionBuiltin()
        tool.sessionRegistry.register(sessionId = "s1", alias = "busy")
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"slow_job","session":"busy","background":true}""",
            )
        )

        val response = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"second_job","session":"busy"}""",
            )
        )
        val error = Json.parseToJsonElement(response).jsonObject["error"]!!.jsonObject

        assertEquals("SESSION_BUSY", error["code"]!!.jsonPrimitive.content)
        // 第二条命令根本不该被下发到终端
        assertEquals(1, tool.sentPayloads.size)
    }

    @Test
    fun handleWrite_nonPtySessionRejectsCtrlCAndKeepsCommandInFlight() = runTest {
        // 非 PTY 会话里 \u0003 只是输入字节：杀不掉进程、会污染下一行命令，作废只能是假象
        val tool = TestSessionBuiltin()
        tool.sessionRegistry.register(sessionId = "s1", alias = "pipe")
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"slow_job","session":"pipe","background":true}""",
            )
        )

        val response = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = "{\"action\":\"write\",\"session\":\"pipe\",\"text\":\"\\u0003\"}",
            )
        )
        val error = Json.parseToJsonElement(response).jsonObject["error"]!!.jsonObject

        assertEquals("INVALID_REQUEST", error["code"]!!.jsonPrimitive.content)
        // 在途状态必须留着：放行下一条命令只会让它排在旧进程后面白等超时
        assertNotNull(tool.sessionRegistry.getCommandState("s1"))
    }

    @Test
    fun handleWrite_ptySessionCtrlCAbandonsPendingCommandAndFreesSession() = runTest {
        val tool = TestSessionBuiltin().apply { ctrlCAbort = true }
        tool.sessionRegistry.register(sessionId = "s1", alias = "stuck")
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"slow_job","session":"stuck","background":true}""",
            )
        )
        assertNotNull(tool.sessionRegistry.getCommandState("s1"))

        // PTY 上 Ctrl-C 会让 sentinel 标记不回来（tty 输入队列被 flush），只能靠显式作废放行
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = "{\"action\":\"write\",\"session\":\"stuck\",\"text\":\"\\u0003\"}",
            )
        )
        assertNull(tool.sessionRegistry.getCommandState("s1"))

        val response = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"next_job","session":"stuck"}""",
            )
        )

        assertNull(Json.parseToJsonElement(response).jsonObject["error"])
        assertEquals(2, tool.sentPayloads.size)
    }

    // ── 会话假死（掉线/被回收）时立刻给可行动错误 ───────────────────────────────

    @Test
    fun handleCommand_onDeadSessionReturnsActionableGoneInsteadOfTimeout() = runTest {
        val tool = TestSessionBuiltin()
        tool.sessionRegistry.register(sessionId = "s1", alias = "dropped")
        tool.deadSessions.add("s1")

        val rawResponse = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"ls","session":"s1","timeout":5}""",
            )
        )
        val json = Json.parseToJsonElement(rawResponse).jsonObject

        // 连接已断：不给 timed_out（那会让模型继续 read 空等），给可行动的 gone 错误
        assertNull(json["timed_out"])
        val error = json["error"]!!.jsonObject
        assertEquals("INVALID_REQUEST", error["code"]!!.jsonPrimitive.content)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("gone"))
        // 在途状态已被清掉，不会被 forever SESSION_BUSY 卡死
        assertNull(tool.sessionRegistry.getCommandState("s1"))
    }

    @Test
    fun handleCommand_keepsResultWhenSessionDiesBeforeTailIsCollected() = runTest {
        // 远端在打完标记之后、我们读到之前断连（尾部还在采集管道里往外走）。
        // 判死一拍定论会把已经成功的命令报成掉线，退出码一起丢。
        // 注：会话内的 exit / 自杀命令无法用这个场景测——shell 在那个哨兵行之前就没了。
        val tool = TestSessionBuiltin().apply {
            autoRespondExitCode = 0
            autoRespondOutput = "bye\n"
            bufferVisibleAfterReads = 3
        }
        tool.sessionRegistry.register(sessionId = "s1", alias = "dying")
        tool.deadSessions.add("s1")

        val rawResponse = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"last_breath","session":"dying"}""",
            )
        )
        val json = Json.parseToJsonElement(rawResponse).jsonObject

        assertNull(json["error"])
        assertEquals("exited", json["status"]!!.jsonPrimitive.content)
        assertEquals("0", json["exit_code"]!!.jsonPrimitive.content)
        assertTrue(json["output"]!!.jsonPrimitive.content.contains("bye"))
    }

    // ── 交互输入与重复读 ────────────────────────────────────────────────────

    @Test
    fun handleSubmit_obeysExecutionRuleInsteadOfBypassingIt() = runTest {
        // submit 会追换行立即执行，与 command 同等受约束；漏掉前检等于给执行规则开后门
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                executionRules = listOf(
                    RuntimeExecutionRule(
                        id = "rule-rm",
                        name = "Block rm -rf",
                        enabledMode = RuntimeExecutionRuleEnabledMode.ALWAYS,
                        patterns = listOf("""rm\s+-rf.*"""),
                    )
                )
            )
        )
        val tool = TestSessionBuiltin()
        tool.sessionRegistry.register(sessionId = "s1", alias = "repl2")

        val response = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"action":"submit","session":"repl2","text":"rm -rf /var/tmp/x"}""",
            )
        )
        val error = Json.parseToJsonElement(response).jsonObject["error"]!!.jsonObject

        assertEquals("COMMAND_BLOCKED", error["code"]!!.jsonPrimitive.content)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("Block rm -rf"))
    }

    @Test
    fun handleRead_repeatsAlreadyReadInsteadOfResendingFullOutput() = runTest {
        // 全量输出只交付一次：反复 read 同一段会把上下文无限撑大
        val tool = TestSessionBuiltin().apply {
            autoRespondExitCode = 0
            autoRespondOutput = "once_output\n"
        }
        tool.sessionRegistry.register(sessionId = "s1", alias = "once")
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"ls","session":"once"}"""
            )
        )

        val second = Json.parseToJsonElement(
            tool.invokeRawJson(
                BuiltinToolRequest(
                    name = "test_session",
                    argumentsJson = """{"action":"read","session":"once"}"""
                )
            )
        ).jsonObject

        assertEquals("", second["output"]!!.jsonPrimitive.content)
        assertEquals("true", second["already_read"]!!.jsonPrimitive.content)
    }

    @Test
    fun handleRead_dropsSentinelPlumbingFromRawInteractiveOutput() = runTest {
        // 交互式 read 走原始流、不经过 parseOutput，哨兵自己的 payload 回显与标记行只能在回执出口抹掉
        val tool = TestSessionBuiltin()
        tool.sessionRegistry.register(sessionId = "s1", alias = "raw")
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"job","session":"raw","background":true}""",
            )
        )
        tool.appendOutput(
            "s1",
            "real_line\n__sess_status=\$?; __t=\"__SESSION_EXIT_\"; printf '\\n%s%s:%s__\\n'\n" +
                    "__SESSION_EXIT_deadbeef:0__\n",
        )

        val output = Json.parseToJsonElement(
            tool.invokeRawJson(
                BuiltinToolRequest(
                    name = "test_session",
                    argumentsJson = """{"action":"read","session":"raw"}""",
                )
            )
        ).jsonObject["output"]!!.jsonPrimitive.content

        assertTrue(output.contains("real_line"))
        assertFalse(output.contains("__SESSION_EXIT_"))
        assertFalse(output.contains("__sess_status"))
    }

    // ── 会话缓冲消失时的读安全 ──────────────────────────────────────────────

    @Test
    fun handleRead_survivesSessionBufferDisappearingMidCommand() = runTest {
        val tool = TestSessionBuiltin()
        tool.sessionRegistry.register(sessionId = "s1", alias = "vanishing")
        // 先让缓冲里有内容，下一条命令的 initialOffset 必然 > 0
        tool.appendOutput("s1", "pre_existing_output\n")
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"command":"slow_job","session":"vanishing","background":true}""",
            )
        )

        // pool 侧的会话没了（closeAll / 远端断开 / UI 关掉终端），而 registry 还留着它
        tool.sessionBuffers.remove("s1")
        val response = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "test_session",
                argumentsJson = """{"action":"read","session":"vanishing"}""",
            )
        )
        val json = Json.parseToJsonElement(response).jsonObject

        // 差分窗口的区间不能倒置，否则 coerceIn 抛异常被兜成一条无意义的 "invalid request"
        assertNull(json["error"])
        assertEquals("running", json["status"]!!.jsonPrimitive.content)
    }
}
