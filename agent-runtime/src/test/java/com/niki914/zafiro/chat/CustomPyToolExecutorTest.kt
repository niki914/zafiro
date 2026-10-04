package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.python.CustomPyToolExecutor
import com.niki914.zafiro.chat.agentic.python.PyExecOutput
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class CustomPyToolExecutorTest {

    @Before
    fun setUp() {
        installRuntimeSettingsGatewayForTest()
    }

    @After
    fun tearDown() {
        com.niki914.zafiro.settings.RuntimeEnvironment.clearForTest()
    }

    private val tool = com.niki914.zafiro.chat.LocalTool.Py(
        name = "py_echo",
        description = "echo",
        code = "def main(text):\\n    print(text)",
        inputSchemaJson = null,
    )

    @Test
    fun execute_wrapsStdoutInOkJson() = runTest {
        val executor = CustomPyToolExecutor(exec = { code, _ ->
            assertTrue(code.contains("main(**_args)"))
            PyExecOutput("hello", null, timedOut = false)
        })

        val json = Json.parseToJsonElement(executor.execute(tool, "{\"text\":\"x\"}")).jsonObject

        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("py_echo", json["tool"]!!.jsonPrimitive.content)
        assertEquals("hello", json["stdout"]!!.jsonPrimitive.content)
    }

    @Test
    fun execute_blankArgumentsTreatedAsEmptyObject() = runTest {
        val executor = CustomPyToolExecutor(exec = { _, _ -> PyExecOutput("ok", null, timedOut = false) })

        val json = Json.parseToJsonElement(executor.execute(tool, "")).jsonObject

        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun execute_runtimeError_mapsToPythonErrorFailure() = runTest {
        val executor = CustomPyToolExecutor(exec = { _, _ -> throw IllegalStateException("boom") })

        val json = Json.parseToJsonElement(executor.execute(tool, "{}")).jsonObject

        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("PYTHON_ERROR", json["code"]!!.jsonPrimitive.content)
        assertEquals("boom", json["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun execute_timeout_mapsToTimeoutFailure() = runTest {
        val executor = CustomPyToolExecutor(exec = { _, _ ->
            withTimeout(1) { kotlinx.coroutines.delay(5_000) }
            PyExecOutput("", null, timedOut = false)
        })

        val json = Json.parseToJsonElement(executor.execute(tool, "{}")).jsonObject

        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("TIMEOUT", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun execute_invalidArgumentsJsonFallsBackToEmptyObject() = runTest {
        var received = ""
        val spyExecutor = CustomPyToolExecutor(exec = { code, _ ->
            received = code
            PyExecOutput("ok", null, timedOut = false)
        })

        spyExecutor.execute(tool, "not-json{")
        // "{}" base64 = e30=
        assertTrue(received.contains("e30="))
    }

    @Test
    fun execute_ruleMatch_blocksBeforeRunning() = runTest {
        installRuntimeSettingsGatewayForTest(
            com.niki914.zafiro.chat.FakeRuntimeSettingsGateway(
                executionRules = listOf(
                    com.niki914.zafiro.settings.model.RuntimeExecutionRule(
                        id = "r",
                        name = "no-rm",
                        enabledMode = com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode.ALWAYS,
                        patterns = listOf("\\brm\\s+-rf\\b"),
                    )
                )
            )
        )
        var ran = false
        val executor = CustomPyToolExecutor(exec = { _, _ ->
            ran = true
            PyExecOutput("ok", null, timedOut = false)
        })

        val json = Json.parseToJsonElement(
            executor.execute(tool.copy(code = "import os\nos.system('rm -rf /data/x')"), "{\"text\":\"x\"}")
        ).jsonObject

        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("COMMAND_BLOCKED", json["code"]!!.jsonPrimitive.content)
        assertFalse(ran)
    }

    @Test
    fun execute_pathIntent_requestsStoragePermission() = runTest {
        var requested = false
        val preflight = com.niki914.zafiro.chat.agentic.ToolExecutionPreflight(
            permissionsProvider = {
                object : com.niki914.zafiro.business.permission.PermissionManager {
                    override fun status(permission: com.niki914.zafiro.business.permission.Permission) =
                        com.niki914.zafiro.business.permission.PermissionState.DENIED_BY_USER
                    override suspend fun request(permission: com.niki914.zafiro.business.permission.Permission): com.niki914.zafiro.business.permission.PermissionResult {
                        requested = true
                        return com.niki914.zafiro.business.permission.PermissionResult(
                            permission, com.niki914.zafiro.business.permission.PermissionState.DENIED_BY_USER, emptyList())
                    }
                    override suspend fun request(permission: com.niki914.zafiro.business.permission.Permission, vararg channels: com.niki914.zafiro.business.permission.Channel) = request(permission)
                    override fun applyScope(vararg channels: com.niki914.zafiro.business.permission.Channel): com.niki914.zafiro.business.permission.PermissionScope =
                        throw UnsupportedOperationException()
                }
            },
            sandboxRootsProvider = { emptySet() },
        )
        val executor = CustomPyToolExecutor(
            exec = { _, _ -> PyExecOutput("ok", null, timedOut = false) },
            preflight = preflight,
        )

        val json = Json.parseToJsonElement(
            executor.execute(tool, "{\"text\":\"/sdcard/DCIM/x.jpg\"}")
        ).jsonObject

        // 被拒绝也照常执行：ok=true，只是中间阻塞申请过一次
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(requested)
    }
}
