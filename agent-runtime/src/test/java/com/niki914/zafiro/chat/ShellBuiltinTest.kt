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

        override suspend fun openLocalSession(
            identity: String,
            workdir: String?
        ): TerminalOpenOutcome {
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
            BuiltinToolRequest(
                name = "shell",
                argumentsJson = """{"command":"echo custom","alias":"worker_a"}"""
            )
        )
        val jsonCustom = Json.parseToJsonElement(respCustom).jsonObject
        val customId = jsonCustom["session_id"]!!.jsonPrimitive.content

        assertNotEquals(defaultId, customId)
        assertEquals("worker_a", jsonCustom["alias"]!!.jsonPrimitive.content)

        // 再次调用 alias="worker_a"，必须命中同一个 customId
        val respCustomAgain = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "shell",
                argumentsJson = """{"command":"echo custom2","alias":"worker_a"}"""
            )
        )
        val jsonCustomAgain = Json.parseToJsonElement(respCustomAgain).jsonObject
        assertEquals(customId, jsonCustomAgain["session_id"]!!.jsonPrimitive.content)
    }

    // ── 2b. identity 驱动默认会话：root 真的拿到 root ────────────────────────

    // 保护：identity 必须在「未指定 alias」时驱动会话选择。
    // 漏了它，identity=root 会静默落到 user 会话，用户以为提权了其实没有。
    @Test
    fun defaultSession_withRootIdentity_opensRootSession() = runTest {
        val tool = TestShellBuiltin()

        val resp = tool.invokeRawJson(
            BuiltinToolRequest(name = "shell", argumentsJson = """{"command":"whoami","identity":"root"}""")
        )
        val json = Json.parseToJsonElement(resp).jsonObject

        assertEquals("root", json["alias"]!!.jsonPrimitive.content)
        assertEquals("root", json["identity"]!!.jsonPrimitive.content)
        assertEquals(listOf("root"), tool.openedIdentities)
    }

    // 保护：不同身份的默认会话互不串味，不能共用一个 session_id。
    // 漏了它，后传的 root 会复用先建的 user 会话，提权依然不生效。
    @Test
    fun defaultSession_differentIdentitiesStaySeparate() = runTest {
        val tool = TestShellBuiltin()

        val userResp = tool.invokeRawJson(
            BuiltinToolRequest(name = "shell", argumentsJson = """{"command":"id"}""")
        )
        val rootResp = tool.invokeRawJson(
            BuiltinToolRequest(name = "shell", argumentsJson = """{"command":"id","identity":"root"}""")
        )
        val userJson = Json.parseToJsonElement(userResp).jsonObject
        val rootJson = Json.parseToJsonElement(rootResp).jsonObject

        assertNotEquals(userJson["session_id"], rootJson["session_id"])
        assertEquals("default", userJson["alias"]!!.jsonPrimitive.content)
        assertEquals("root", rootJson["alias"]!!.jsonPrimitive.content)
    }

    // 保护：显式 alias 上的身份与请求不一致时必须报错，不得静默复用。
    // 漏了它，用户显式要 root 却拿到一个 user 会话，且没有任何提示。
    @Test
    fun explicitAlias_withConflictingIdentity_fails() = runTest {
        val tool = TestShellBuiltin()

        // 先以默认身份建 alias="shared"
        tool.invokeRawJson(
            BuiltinToolRequest(name = "shell", argumentsJson = """{"command":"id","alias":"shared"}""")
        )
        // 再用同一 alias 声明 root：身份在建时已固定，不能提权
        val resp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "shell",
                argumentsJson = """{"command":"id","alias":"shared","identity":"root"}""",
            )
        )
        val json = Json.parseToJsonElement(resp).jsonObject
        val errorObj = json["error"]?.jsonObject ?: json

        assertEquals("INVALID_REQUEST", errorObj["code"]?.jsonPrimitive?.content)
        assertTrue(errorObj["message"]!!.jsonPrimitive.content.contains("runs as 'user'"))
        // 没有多开一个会话
        assertEquals(listOf("user"), tool.openedIdentities)
    }

    // 保护：按 session id 精确指定时同样要做身份校验，不能只在 alias 路径上报错。
    // 漏了它，同一个心智坑会从另一个入口复现：用户以为提权了，命令其实仍以旧身份执行。
    @Test
    fun explicitSessionId_withConflictingIdentity_fails() = runTest {
        val tool = TestShellBuiltin()

        val firstResp = tool.invokeRawJson(
            BuiltinToolRequest(name = "shell", argumentsJson = """{"command":"id","alias":"shared"}""")
        )
        val sessionId = Json.parseToJsonElement(firstResp).jsonObject["session_id"]!!.jsonPrimitive.content
        // 用 id 而不是 alias 指向同一会话，并声明 root
        val resp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "shell",
                argumentsJson = """{"command":"id","session":"$sessionId","identity":"root"}""",
            )
        )
        val json = Json.parseToJsonElement(resp).jsonObject
        val errorObj = json["error"]?.jsonObject ?: json

        assertEquals("INVALID_REQUEST", errorObj["code"]?.jsonPrimitive?.content)
        assertTrue(errorObj["message"]!!.jsonPrimitive.content.contains("runs as 'user'"))
        assertEquals(listOf("user"), tool.openedIdentities)
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
            BuiltinToolRequest(
                name = "shell",
                argumentsJson = """{"command":"rm -rf /data/local/tmp"}"""
            )
        )
        val json = Json.parseToJsonElement(resp).jsonObject
        val errorObj = json["error"]?.jsonObject ?: json

        assertEquals("COMMAND_BLOCKED", errorObj["code"]?.jsonPrimitive?.content)
        assertTrue(errorObj["message"]!!.jsonPrimitive.content.contains("Block rm -rf"))
    }

    // 保护：安全前检必须看会话的实际身份，而不是本次入参。
    // 漏了它，复用 root 会话时不传 identity 就能绕过 \bsu\b 规则。
    @Test
    fun preflight_usesSessionIdentityNotRequestParameter() = runTest {
        val suPreflight = ToolExecutionPreflight(
            listExecutionRules = {
                listOf(
                    RuntimeExecutionRule(
                        id = "su",
                        name = "提权",
                        enabledMode = RuntimeExecutionRuleEnabledMode.ALWAYS,
                        patterns = listOf("""\bsu\b"""),
                    )
                )
            },
            isUnlocked = { false },
            permissionsProvider = { null },
        )
        val tool = TestShellBuiltin(preflight = suPreflight)

        // 首次以 root 开辟会话（命令不含 su 字样）
        tool.invokeRawJson(
            BuiltinToolRequest(
                name = "shell",
                argumentsJson = """{"command":"id","alias":"roottest","identity":"root"}""",
            )
        )
        // 复用该会话时不传 identity：身份仍为 root，规则必须拦下
        val resp = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "shell",
                argumentsJson = """{"command":"cat /data/local/tmp/x","alias":"roottest"}""",
            )
        )
        val json = Json.parseToJsonElement(resp).jsonObject
        val errorObj = json["error"]?.jsonObject ?: json

        assertEquals("COMMAND_BLOCKED", errorObj["code"]?.jsonPrimitive?.content)
        assertEquals("su", errorObj["matched_rule_id"]?.jsonPrimitive?.content)
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
            BuiltinToolRequest(
                name = "shell",
                argumentsJson = """{"command":"echo 1","alias":"test_dead"}"""
            )
        )
        val json1 = Json.parseToJsonElement(resp1).jsonObject
        val sessionId = json1["session_id"]!!.jsonPrimitive.content
        assertNotNull(SessionRegistry.findById(sessionId))

        // 会话死掉
        tool.sessionsAlive = false

        // 显式传入 session 调用
        val resp2 = tool.invokeRawJson(
            BuiltinToolRequest(
                name = "shell",
                argumentsJson = """{"command":"echo 2","session":"$sessionId"}"""
            )
        )
        val json2 = Json.parseToJsonElement(resp2).jsonObject
        val errorObj = json2["error"]?.jsonObject ?: json2

        assertEquals("INVALID_REQUEST", errorObj["code"]?.jsonPrimitive?.content)
        assertTrue(errorObj["message"]!!.jsonPrimitive.content.contains("is gone"))
        // 验证僵尸会话已经被 unregister
        assertTrue(SessionRegistry.findById(sessionId) == null)
    }
}
