// githooks:ignore-file file-token-limit
// 保护：SshBuiltin 的凭据建联、显式寻址防静默跑、同 alias 掉线重建、别名域隔离与认证失败错误映射
package com.niki914.zafiro.chat

import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalFailure
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.impl.BaseSessionBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.SshBuiltin
import com.niki914.zafiro.chat.agentic.shell.SessionRegistry
import com.niki914.zafiro.chat.agentic.shell.TerminalOpenOutcome
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SshBuiltinTest {

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

    private class TestSshBuiltin : SshBuiltin() {
        private val sessionCounter = AtomicInteger(1)
        val sessionBuffers = ConcurrentHashMap<String, StringBuilder>()
        val openedOptions = mutableListOf<SshOpenOptions>()

        /** 默认全部存活；掉线用例拨到 false 模拟连接已断。 */
        var sessionsAlive = true

        override fun isSessionAlive(sessionId: String): Boolean = sessionsAlive

        override suspend fun openSshSession(
            options: SshOpenOptions,
            cwd: String?
        ): TerminalOpenOutcome {
            openedOptions.add(options)
            val auth = options.auth
            if (auth is SshAuth.Password && auth.value == "bad_pass") {
                return TerminalOpenOutcome.Failure(
                    failure = TerminalFailure.SshAuthenticationFailed(message = "Authentication rejected for user ${options.username}"),
                    elapsedSeconds = 1L,
                )
            }
            val handle = "ssh_session_${sessionCounter.getAndIncrement()}"
            return TerminalOpenOutcome.Success(
                session = handle,
                identity = "ssh",
            )
        }

        override suspend fun sendInput(sessionId: String, text: String): Boolean {
            // token 位于 payload 的 "$__t" "<token>" 处
            val token = text.substringAfter("\"\$__t\" \"", "").substringBefore("\"")
            if (token.isNotEmpty()) {
                val output = "remote_output_of_$sessionId"
                appendOutput(
                    sessionId,
                    "$output\n${BaseSessionBuiltin.SessionSentinel.MARKER_PREFIX}$token:0${BaseSessionBuiltin.SessionSentinel.MARKER_SUFFIX}\n"
                )
            }
            return true
        }

        override suspend fun readRawOutput(sessionId: String): String? {
            return sessionBuffers[sessionId]?.toString()
        }

        fun appendOutput(sessionId: String, text: String) {
            sessionBuffers.getOrPut(sessionId) { StringBuilder() }.append(text)
        }
    }

    // ── 1. 凭据缺失拦截 ──────────────────────────────────────────────────────

    @Test
    fun missingCredentials_rejectedWhenConnectingNewSession() = runTest {
        val tool = TestSshBuiltin()

        val resp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"command":"ls -la"}"""
            )
        )
        val json = Json.parseToJsonElement(resp).jsonObject
        val error = json["error"]?.jsonObject
        assertNotNull(error)
        assertEquals("INVALID_REQUEST", error?.get("code")?.jsonPrimitive?.content)
    }

    // ── 2. 凭据建联成功与回执自证明 ──────────────────────────────────────────

    @Test
    fun connectSuccess_registersAliasAndAttestsReceipt() = runTest {
        val tool = TestSshBuiltin()

        val connectResp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{
                    "host":"192.168.1.100",
                    "username":"ubuntu",
                    "password":"secret",
                    "alias":"prod_server"
                }"""
            )
        )
        val connectJson = Json.parseToJsonElement(connectResp).jsonObject
        assertEquals("connected", connectJson["status"]?.jsonPrimitive?.content)
        assertEquals("prod_server", connectJson["alias"]?.jsonPrimitive?.content)
        assertEquals("ssh", connectJson["identity"]?.jsonPrimitive?.content)
        val sessionId = connectJson["session_id"]!!.jsonPrimitive.content

        // 建联后直接使用 alias 下发命令
        val cmdResp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"alias":"prod_server","command":"uname -a"}"""
            )
        )
        val cmdJson = Json.parseToJsonElement(cmdResp).jsonObject
        assertEquals("exited", cmdJson["status"]?.jsonPrimitive?.content)
        assertEquals(sessionId, cmdJson["session_id"]?.jsonPrimitive?.content)
        assertEquals("prod_server", cmdJson["alias"]?.jsonPrimitive?.content)
    }

    @Test
    fun actionConnect_establishesSessionWithCredentials() = runTest {
        val tool = TestSshBuiltin()

        val connectResp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{
                    "action":"connect",
                    "host":"192.168.1.100",
                    "username":"ubuntu",
                    "password":"secret",
                    "alias":"prod_node"
                }"""
            )
        )
        val connectJson = Json.parseToJsonElement(connectResp).jsonObject
        assertEquals("connected", connectJson["status"]?.jsonPrimitive?.content)
        assertEquals("prod_node", connectJson["alias"]?.jsonPrimitive?.content)
        assertEquals("ssh", connectJson["identity"]?.jsonPrimitive?.content)
        val sessionId = connectJson["session_id"]!!.jsonPrimitive.content

        // 建联后可正常下发命令
        val cmdResp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"alias":"prod_node","command":"whoami"}"""
            )
        )
        val cmdJson = Json.parseToJsonElement(cmdResp).jsonObject
        assertEquals("exited", cmdJson["status"]?.jsonPrimitive?.content)
        assertEquals(sessionId, cmdJson["session_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun actionConnect_withoutCredentials_returnsInvalidRequest() = runTest {
        val tool = TestSshBuiltin()

        val resp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"action":"connect"}"""
            )
        )
        val error = Json.parseToJsonElement(resp).jsonObject["error"]?.jsonObject
        assertNotNull(error)
        assertEquals("INVALID_REQUEST", error?.get("code")?.jsonPrimitive?.content)
        assertTrue(error?.get("message")?.jsonPrimitive?.content?.contains("requires 'host'") == true)
    }

    // ── 3. 已有会话禁止携带连接凭据 (防止 Issue #295 混淆静默跑) ────────────

    @Test
    fun existingSession_rejectsConflictingCredentials() = runTest {
        val tool = TestSshBuiltin()

        // 先建联
        val connectResp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"host":"10.0.0.1","username":"root","password":"pwd","alias":"my_node"}"""
            )
        )
        val sessId =
            Json.parseToJsonElement(connectResp).jsonObject["session_id"]!!.jsonPrimitive.content

        // 试图携带 session 句柄的同时，又传 host 凭据 -> 必须被立即拦截
        val conflictResp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{
                    "session":"$sessId",
                    "host":"10.0.0.1",
                    "command":"whoami"
                }"""
            )
        )
        val conflictJson = Json.parseToJsonElement(conflictResp).jsonObject
        val error = conflictJson["error"]?.jsonObject
        assertNotNull(error)
        assertEquals("INVALID_REQUEST", error?.get("code")?.jsonPrimitive?.content)
        assertTrue(error?.get("message")?.jsonPrimitive?.content?.contains("Cannot specify connection credentials") == true)
    }

    // ── 4. 多机器活跃列表查询 (action="list") ────────────────────────────────

    @Test
    fun actionList_returnsAllActiveSshSessions() = runTest {
        val tool = TestSshBuiltin()

        // 建立两个不同机器的连接
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"host":"10.0.0.1","username":"node1","password":"p1","alias":"n1"}"""
            )
        )
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"host":"10.0.0.2","username":"node2","password":"p2","alias":"n2"}"""
            )
        )

        val listResp = tool.invokeRawJson(
            BuiltinToolRequest(name = "ssh", argumentsJson = """{"action":"list"}""")
        )
        val listJson = Json.parseToJsonElement(listResp).jsonObject
        assertEquals("list", listJson["action"]?.jsonPrimitive?.content)
        assertEquals("2", listJson["total"]?.jsonPrimitive?.content)
        val sessionsArray = listJson["sessions"]?.jsonArray
        assertEquals(2, sessionsArray?.size)
    }

    @Test
    fun handleConnect_hidesLocalAndDeadSessionsFromActiveSessions() = runTest {
        val tool = TestSshBuiltin()
        // 注册一个本地域会话（模拟本地 shell）
        SessionRegistry.register(
            sessionId = "local_1",
            alias = "local_shell",
            scope = SessionRegistry.SCOPE_LOCAL,
            metadata = mapOf("identity" to "user"),
        )
        // 连第一个 SSH
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"host":"10.0.0.1","username":"u1","password":"p1","alias":"n1"}""",
            )
        )
        // 连第二个 SSH，检查返回的 active_sessions
        val resp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"host":"10.0.0.2","username":"u2","password":"p2","alias":"n2"}""",
            )
        )
        val json = Json.parseToJsonElement(resp).jsonObject
        val activeSessions = json["active_sessions"]?.jsonArray
        assertNotNull(activeSessions)
        // 只能包含 SSH 域的 n1 和 n2，不能泄漏 local_shell
        assertEquals(2, activeSessions!!.size)
        val aliases = activeSessions.map { it.jsonObject["alias"]?.jsonPrimitive?.content }
        assertTrue("n1" in aliases)
        assertTrue("n2" in aliases)
        assertTrue("local_shell" !in aliases)
    }

    // ── 5. 掉线会话：同 alias 带凭据可重建，不带凭据给可行动的错误 ─────────

    @Test
    fun droppedSession_reconnectsWithCredentialsOnSameAlias() = runTest {
        val tool = TestSshBuiltin()
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"host":"10.0.0.1","username":"root","password":"pwd","alias":"n1"}"""
            )
        )
        val oldSessionId = tool.sessionRegistry.resolveSessionId("n1", SessionRegistry.SCOPE_SSH)!!
        tool.sessionsAlive = false

        val resp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"host":"10.0.0.1","username":"root","password":"pwd","alias":"n1"}"""
            )
        )
        val json = Json.parseToJsonElement(resp).jsonObject

        // 真机复现过的死结：旧实现直接拒绝带凭据重连，alias 永久指向死会话
        assertEquals("connected", json["status"]?.jsonPrimitive?.content)
        val newSessionId = json["session_id"]!!.jsonPrimitive.content
        assertTrue(newSessionId != oldSessionId)
        assertEquals("n1", json["alias"]?.jsonPrimitive?.content)
        assertEquals(
            newSessionId,
            tool.sessionRegistry.resolveSessionId("n1", SessionRegistry.SCOPE_SSH)
        )
    }

    @Test
    fun droppedSession_commandWithoutCredentialsReportsGoneInsteadOfBusy() = runTest {
        val tool = TestSshBuiltin()
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"host":"10.0.0.2","username":"root","password":"pwd","alias":"n2"}"""
            )
        )
        tool.sessionsAlive = false

        val resp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"alias":"n2","command":"ls"}"""
            )
        )
        val error = Json.parseToJsonElement(resp).jsonObject["error"]?.jsonObject

        // 不是 SESSION_BUSY / SEND_FAILED / 空超时，而是明确的 gone + 重建指引
        assertNotNull(error)
        assertEquals("INVALID_REQUEST", error?.get("code")?.jsonPrimitive?.content)
        assertTrue(error?.get("message")?.jsonPrimitive?.content?.contains("gone") == true)
        assertNull(tool.sessionRegistry.resolveSessionId("n2", SessionRegistry.SCOPE_SSH))
    }

    // ── 6. 别名域隔离：本地 shell 的别名不得被 ssh 解析到 ────────────────

    @Test
    fun ssh_doesNotResolveAliasOwnedByLocalShell() = runTest {
        // 全局登记表下，别名漏掉域限定会让 ssh 把命令跑在本地会话上（真机复现过）
        SessionRegistry.register(
            sessionId = "local_1",
            alias = "shared",
            scope = SessionRegistry.SCOPE_LOCAL,
            metadata = mapOf("identity" to "user"),
        )
        val tool = TestSshBuiltin()

        val resp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"alias":"shared","command":"whoami"}"""
            )
        )
        val error = Json.parseToJsonElement(resp).jsonObject["error"]?.jsonObject

        // 不是去跑本地会话，而是停在“需要凭据才能新建 ssh 会话”
        assertNotNull(error)
        assertEquals("INVALID_REQUEST", error?.get("code")?.jsonPrimitive?.content)
        assertTrue(error?.get("message")?.jsonPrimitive?.content?.contains("requires 'host'") == true)
    }

    @Test
    fun localSessionId_isNotExecutableThroughSshTool() = runTest {
        // 句柄与别名一样按域隔离，池是进程共享的：不校验归属域，ssh 工具会把命令跑在本地会话上
        SessionRegistry.register(
            sessionId = "local_1",
            alias = "l",
            scope = SessionRegistry.SCOPE_LOCAL,
            metadata = mapOf("identity" to "user"),
        )
        val tool = TestSshBuiltin()

        val resp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"session":"local_1","command":"whoami"}"""
            )
        )
        val error = Json.parseToJsonElement(resp).jsonObject["error"]?.jsonObject

        assertNotNull(error)
        assertEquals("SESSION_NOT_FOUND", error?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun actionList_reportsOnlySshScopedSessions() = runTest {
        // list 把别的工具的会话一起报出来，等于给模型发一个注定用不了的句柄
        SessionRegistry.register(
            sessionId = "local_1",
            alias = "l",
            scope = SessionRegistry.SCOPE_LOCAL
        )
        val tool = TestSshBuiltin()
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"host":"10.0.0.1","username":"u","password":"p","alias":"n1"}""",
            )
        )

        val listJson = Json.parseToJsonElement(
            tool.invokeRawJson(
                BuiltinToolRequest(
                    name = "ssh",
                    argumentsJson = """{"action":"list"}"""
                )
            )
        ).jsonObject

        assertEquals("1", listJson["total"]?.jsonPrimitive?.content)
    }

    @Test
    fun actionList_hidesDroppedSessions() = runTest {
        // 死会话留在列表里就是谎报健康：模型会拿它去发命令，只能拿到 gone
        val tool = TestSshBuiltin()
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"host":"10.0.0.1","username":"u","password":"p","alias":"n1"}""",
            )
        )
        tool.sessionsAlive = false

        val listJson = Json.parseToJsonElement(
            tool.invokeRawJson(
                BuiltinToolRequest(
                    name = "ssh",
                    argumentsJson = """{"action":"list"}"""
                )
            )
        ).jsonObject

        assertEquals("0", listJson["total"]?.jsonPrimitive?.content)
    }

    @Test
    fun unknownSessionIdWithCredentials_reconnectsInsteadOfBlamingExistingSession() = runTest {
        // 旧行为：句柄不在登记里也报 “targeting an existing session”，把本来能用的重建路径堆死
        val tool = TestSshBuiltin()

        val resp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"session":"ghost","host":"10.0.0.1","username":"u","password":"p","alias":"n9"}""",
            )
        )
        val json = Json.parseToJsonElement(resp).jsonObject

        assertEquals("connected", json["status"]?.jsonPrimitive?.content)
        assertEquals("n9", json["alias"]?.jsonPrimitive?.content)
    }

    // ── 7. 认证失败错误码映射 ────────────────────────────────────────────────

    @Test
    fun authenticationFailure_mapsToSshAuthenticationFailedErrorCode() = runTest {
        val tool = TestSshBuiltin()

        val resp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"host":"10.0.0.3","username":"admin","password":"bad_pass"}"""
            )
        )
        val json = Json.parseToJsonElement(resp).jsonObject
        val error = json["error"]?.jsonObject
        assertNotNull(error)
        assertEquals("SSH_AUTHENTICATION_FAILED", error?.get("code")?.jsonPrimitive?.content)
    }

    // ── 6. 执行规则对 SSH 命令同样生效 (防止约束只留在本地 shell) ────────

    @Test
    fun executionRule_blocksRemoteCommand() = runTest {
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
        val tool = TestSshBuiltin()
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"host":"10.0.0.9","username":"ops","password":"p","alias":"edge"}""",
            )
        )

        val resp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "ssh",
                argumentsJson = """{"alias":"edge","command":"rm -rf /var/tmp/thing"}""",
            )
        )
        val error = Json.parseToJsonElement(resp).jsonObject["error"]?.jsonObject

        assertNotNull(error)
        assertEquals("COMMAND_BLOCKED", error?.get("code")?.jsonPrimitive?.content)
        assertTrue(error?.get("message")?.jsonPrimitive?.content?.contains("Block rm -rf") == true)
    }
}
