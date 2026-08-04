package com.niki914.nexus.agentic.chat

import android.content.Context
import android.content.ContextWrapper
import com.niki914.nexus.agentic.chat.agentic.ToolCallDispatcher
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolExecutor
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.custom.CustomCommandExecutionResult
import com.niki914.nexus.agentic.chat.agentic.custom.CustomToolExecutor
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.kai.LocalToolConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode

class ToolCallDispatcherTest {
    private val context: Context = ContextWrapper(null)

    @After
    fun tearDown() {
        RuntimeEnvironment.clearForTest()
    }

    @Test
    fun executeLocalTool_dispatchesEnabledBuiltinBeforeCustomToolFallback() = runTest {
        val builtin = RecordingBuiltinTool("create_custom_tool")
        val dispatcher = ToolCallDispatcher(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(builtin))),
            customToolExecutor = CustomToolExecutor(),
            currentTools = {
                ResolvedTools(
                    builtinTools = listOf(
                        LocalTool.Builtin(
                            name = "create_custom_tool",
                            description = "Create custom tool",
                            tool = builtin,
                        )
                    ),
                    customTools = listOf(
                        LocalTool.Custom(
                            name = "create_custom_tool",
                            description = "Conflicting command",
                            enabled = true,
                            command = "date",
                        )
                    ),
                )
            },
        )

        val resultJson = dispatcher.executeLocalTool(
            name = "create_custom_tool",
            argumentsJson = """{"name":"battery_status"}""",
        )

        assertEquals("""{"name":"battery_status"}""", builtin.lastRequest?.argumentsJson)
        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertEquals("OK", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun executeLocalTool_usesResolvedBuiltinInstanceInsteadOfExecutorRegistryLookup() = runTest {
        val builtin = RecordingBuiltinTool("create_custom_tool")
        val dispatcher = ToolCallDispatcher(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(emptyList())),
            customToolExecutor = CustomToolExecutor(),
            currentTools = {
                ResolvedTools(
                    builtinTools = listOf(
                        LocalTool.Builtin(
                            name = "create_custom_tool",
                            description = "Create custom tool",
                            tool = builtin,
                        )
                    ),
                )
            },
        )

        val resultJson = dispatcher.executeLocalTool(
            name = "create_custom_tool",
            argumentsJson = """{"name":"battery_status"}""",
        )

        assertEquals("""{"name":"battery_status"}""", builtin.lastRequest?.argumentsJson)
        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertEquals("OK", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun executeLocalTool_returnsStructuredErrorForUnknownName() = runTest {
        val dispatcher = ToolCallDispatcher(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(emptyList())),
            customToolExecutor = CustomToolExecutor(),
            currentTools = { ResolvedTools() },
        )

        val resultJson = dispatcher.executeLocalTool(
            name = "missing",
            argumentsJson = "{}",
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertEquals("LOCAL_TOOL_NOT_EXECUTABLE", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun executeLocalTool_fallsBackToCustomToolWhenBuiltinIsNotEnabled() = runTest {
        installRuntimeSettingsGatewayForTest()
        val dispatcher = ToolCallDispatcher(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(emptyList())),
            customToolExecutor = CustomToolExecutor(
                timeoutMs = 1_000,
                commandExecutor = { _, _ ->
                    CustomCommandExecutionResult(ok = true, stdout = "sample_model")
                },
            ),
            currentTools = {
                ResolvedTools(
                    customTools = listOf(
                        LocalTool.Custom(
                            name = "device_model",
                            description = "Read device model",
                            enabled = true,
                            command = "printf sample_model",
                        )
                    )
                )
            },
        )

        val resultJson = dispatcher.executeLocalTool(
            name = "device_model",
            argumentsJson = "{}",
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertEquals("printf sample_model", json["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun executeLocalTool_returnsStructuredErrorForBlockedCustomCommand() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(executionRules = dangerousRules())
        )
        val dispatcher = ToolCallDispatcher(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(emptyList())),
            customToolExecutor = CustomToolExecutor(),
            currentTools = {
                ResolvedTools(
                    customTools = listOf(
                        LocalTool.Custom(
                            name = "wipe_data",
                            description = "Blocked command",
                            enabled = true,
                            command = "rm -rf /data/local/tmp/cache",
                        )
                    )
                )
            },
        )

        val resultJson = dispatcher.executeLocalTool(
            name = "wipe_data",
            argumentsJson = "{}",
        )

        val json = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("rm -rf /data/local/tmp/cache", json["command"]!!.jsonPrimitive.content)
        assertEquals(
            "Command blocked by execution rule '危险命令' with pattern '\\brm\\s+-rf\\b'.",
            json["message"]!!.jsonPrimitive.content
        )
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

    private class RecordingBuiltinTool(
        override val name: String,
    ) : BuiltinTool() {
        var lastRequest: BuiltinToolRequest? = null

        override fun configure(config: LocalToolConfig) = Unit

        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
            lastRequest = request
            return BuiltinToolResult.success(message = "ok")
        }
    }
}
