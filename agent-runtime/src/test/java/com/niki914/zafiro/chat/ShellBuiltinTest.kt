// 保护：ShellBuiltin 的默认持久会话复用、显式别名环境隔离、Preflight规则拦截与多身份回执自证
package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.ToolExecutionPreflight
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.impl.BaseSessionBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ShellBuiltin
import com.niki914.zafiro.chat.agentic.shell.SessionRegistry
import com.niki914.zafiro.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.chat.util.SilentLoggerRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ShellBuiltinTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    @After
    fun tearDown() {
        runBlocking {
            TerminalSessionPool.closeAll()
        }
        SessionRegistry.clear()
    }

    private class TestShellBuiltin(
        preflight: ToolExecutionPreflight = ToolExecutionPreflight(
            listExecutionRules = { emptyList() },
            permissionsProvider = { null },
        ),
    ) : ShellBuiltin(preflight) {

        private val sessionCounter = AtomicInteger(1)
        val sessionBuffers = ConcurrentHashMap<String, StringBuilder>()
        val openedIdentities = mutableListOf<String>()

        var sessionsAlive: Boolean = true

        override fun isSessionAlive(sessionId: String): Boolean = sessionsAlive

        override suspend fun openLocalSession(identity: String, workdir: String?): TerminalOpenOutcome {
            val handle = "shell_session_${sessionCounter.getAndIncrement()}"
            openedIdentities.add(identity)
            return TerminalOpenOutcome.Success(
                session = handle,
                identity = identity,
            )
        }

        override suspend fun sendInput(sessionId: String, text: String): Boolean {
            // token 位于 payload 的 "$__t" "<token>" 处
            val token = text.substringAfter("\"\$__t\" \"", "").substringBefore("\"")
            if (token.isNotEmpty()) {
                val output = "output_of_$sessionId"
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

    // ── 1. 默认持久会话复用 ──────────────────────────────────────────────────

    @Test
    fun defaultSession_persistsAcrossConsecutiveCommands() = runTest {
        val tool = TestShellBuiltin()

        // 第一次调用未传 session/alias，自动开辟 alias="default"
        val resp1 = tool.invokeRawJson(
            BuiltinToolRequest(name = "shell", argumentsJson = """{"command":"echo step1"}""")
        )
        val json1 = Json.parseToJsonElement(resp1).jsonObject
        val sessId1 = json1["session_id"]!!.jsonPrimitive.content
        assertEquals("default", json1["alias"]!!.jsonPrimitive.content)
        assertEquals("user", json1["identity"]!!.jsonPrimitive.content)

        // 第二次调用仍未传 session/alias，必须复用同一个 session_id
        val resp2 = tool.invokeRawJson(
            BuiltinToolRequest(name = "shell", argumentsJson = """{"command":"echo step2"}""")
        )
        val json2 = Json.parseToJsonElement(resp2).jsonObject
        val sessId2 = json2["session_id"]!!.jsonPrimitive.content

        assertEquals(sessId1, sessId2)
        assertEquals("default", json2["alias"]!!.jsonPrimitive.content)
    }

    // ── 2. 显式 alias 开辟独立隔离会话 ───────────────────────────────────────

    @Test
    fun customAlias_createsIsolatedSession() = runTest {
        val tool = TestShellBuiltin()

        // 默认会话
        val respDefault = tool.invokeRawJson(
            BuiltinToolRequest(name = "shell", argumentsJson = """{"command":"echo default"}""")
        )
        val jsonDefault = Json.parseToJsonElement(respDefault).jsonObject
        val defaultId = jsonDefault["session_id"]!!.jsonPrimitive.content

        // 显式指定 alias="worker_a"，开辟独立会话
        val respCustom = tool.invokeRawJson(
            BuiltinToolRequest(name = "shell", argumentsJson = """{"command":"echo custom","alias":"worker_a"}""")
        )
        val jsonCustom = Json.parseToJsonElement(respCustom).jsonObject
        val customId = jsonCustom["session_id"]!!.jsonPrimitive.content

        assertNotEquals(defaultId, customId)
        assertEquals("worker_a", jsonCustom["alias"]!!.jsonPrimitive.content)

        // 再次调用 alias="worker_a"，必须命中同一个 customId
        val respCustomAgain = tool.invokeRawJson(
            BuiltinToolRequest(name = "shell", argumentsJson = """{"command":"echo custom2","alias":"worker_a"}""")
        )
        val jsonCustomAgain = Json.parseToJsonElement(respCustomAgain).jsonObject
        assertEquals(customId, jsonCustomAgain["session_id"]!!.jsonPrimitive.content)
    }

    // ── 3. 权限与 Preflight 规则拦截 ─────────────────────────────────────────

    @Test
    fun preflight_blocksCommandWhenRuleDenies() = runTest {
        val blockingPreflight = ToolExecutionPreflight(
            listExecutionRules = {
                listOf(
                    RuntimeExecutionRule(
                        id = "rule-rm",
                        name = "Block rm -rf",
                        enabledMode = RuntimeExecutionRuleEnabledMode.ALWAYS,
                        patterns = listOf("""rm\s+-rf.*"""),
                    )
                )
            },
            isUnlocked = { false },
            permissionsProvider = { null },
        )
        val tool = TestShellBuiltin(preflight = blockingPreflight)

        val resp = tool.invokeRawJson(
            BuiltinToolRequest(name = "shell", argumentsJson = """{"command":"rm -rf /data/local/tmp"}""")
        )
        val json = Json.parseToJsonElement(resp).jsonObject
        val errorObj = json["error"]?.jsonObject ?: json

        assertEquals("COMMAND_BLOCKED", errorObj["code"]?.jsonPrimitive?.content)
        assertTrue(errorObj["message"]!!.jsonPrimitive.content.contains("Block rm -rf"))
    }

    // ── 4. 回执自证 (Session ID, Alias, Identity) ───────────────────────────

    @Test
    fun receipt_attestsSessionAliasAndIdentity() = runTest {
        val tool = TestShellBuiltin()

        val resp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "shell",
                argumentsJson = """{"command":"id","alias":"root_proc","identity":"root"}""",
            )
        )
        val json = Json.parseToJsonElement(resp).jsonObject

        assertNotNull(json["session_id"])
        assertEquals("root_proc", json["alias"]!!.jsonPrimitive.content)
        assertEquals("root", json["identity"]!!.jsonPrimitive.content)
        assertEquals("exited", json["status"]!!.jsonPrimitive.content)
        assertEquals("0", json["exit_code"]!!.jsonPrimitive.content)
    }

    @Test
    fun explicitSession_rejectsAndCleansDeadSession() = runTest {
        val tool = TestShellBuiltin()
        // 先开一个会话
        val resp1 = tool.invokeRawJson(
            BuiltinToolRequest(name = "shell", argumentsJson = """{"command":"echo 1","alias":"test_dead"}""")
        )
        val json1 = Json.parseToJsonElement(resp1).jsonObject
        val sessionId = json1["session_id"]!!.jsonPrimitive.content
        assertNotNull(SessionRegistry.findById(sessionId))

        // 会话死掉
        tool.sessionsAlive = false

        // 显式传入 session 调用
        val resp2 = tool.invokeRawJson(
            BuiltinToolRequest(name = "shell", argumentsJson = """{"command":"echo 2","session":"$sessionId"}""")
        )
        val json2 = Json.parseToJsonElement(resp2).jsonObject
        val errorObj = json2["error"]?.jsonObject ?: json2

        assertEquals("INVALID_REQUEST", errorObj["code"]?.jsonPrimitive?.content)
        assertTrue(errorObj["message"]!!.jsonPrimitive.content.contains("is gone"))
        // 验证僵尸会话已经被 unregister
        assertTrue(SessionRegistry.findById(sessionId) == null)
    }
}
