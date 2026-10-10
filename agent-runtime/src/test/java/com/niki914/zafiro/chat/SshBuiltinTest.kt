// 保护：SshBuiltin 的凭据建联、显式寻址防静默跑、已有会话禁止传连接凭据与认证失败错误映射
package com.niki914.zafiro.chat

import com.niki914.libterm.SshAuth
import com.niki914.libterm.SshOpenOptions
import com.niki914.libterm.TerminalFailure
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.impl.BaseSessionBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.SshBuiltin
import com.niki914.zafiro.chat.agentic.shell.TerminalOpenOutcome
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SshBuiltinTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    @After
    fun tearDown() {
        runBlocking {
            TerminalSessionPool.closeAll()
        }
    }

    private class TestSshBuiltin : SshBuiltin() {
        private val sessionCounter = AtomicInteger(1)
        val sessionBuffers = ConcurrentHashMap<String, StringBuilder>()
        val openedOptions = mutableListOf<SshOpenOptions>()

        override suspend fun openSshSession(options: SshOpenOptions, cwd: String?): TerminalOpenOutcome {
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
            val prefix = BaseSessionBuiltin.SessionSentinel.MARKER_PREFIX
            val markerIdx = text.indexOf(prefix)
            if (markerIdx >= 0) {
                val token = text.substring(markerIdx + prefix.length).substringBefore(":")
                val output = "remote_output_of_$sessionId"
                appendOutput(
                    sessionId,
                    "$output\n$prefix$token:0${BaseSessionBuiltin.SessionSentinel.MARKER_SUFFIX}\n"
                )
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
        val sessId = Json.parseToJsonElement(connectResp).jsonObject["session_id"]!!.jsonPrimitive.content

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

    // ── 5. 认证失败错误码映射 ────────────────────────────────────────────────

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
}
