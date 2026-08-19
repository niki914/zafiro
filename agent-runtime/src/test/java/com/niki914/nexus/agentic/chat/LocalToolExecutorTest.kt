package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.LocalToolExecutor
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolExecutor
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResultCodec
import com.niki914.nexus.agentic.chat.agentic.custom.CustomCommandExecutionResult
import com.niki914.nexus.agentic.chat.agentic.custom.CustomToolExecutor
import com.niki914.nexus.agentic.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.nexus.agentic.chat.util.SilentLoggerRule
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.tooling.ToolCallContext
import com.niki914.okia.tooling.ToolDescriptor
import com.niki914.okia.tooling.ToolKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LocalToolExecutorTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    private fun descriptor(name: String) =
        ToolDescriptor(name, "desc ${name}", null, ToolKind.Local)

    private fun builtinResolved(vararg tools: BuiltinTool): ResolvedTools =
        ResolvedTools(builtinTools = tools.map { LocalTool.Builtin(it.name, it.name, it) })

    private fun customResolved(tools: List<LocalTool.Custom>): ResolvedTools =
        ResolvedTools(customTools = tools)

    @Test
    fun execute_builtinSuccess_mapsToSuccessOutcome() = runBlocking {
        val tool = OkBuiltinTool("alpha")
        val executor = LocalToolExecutor(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(tool))),
            currentTools = { builtinResolved(tool) },
        )

        val outcome = executor.execute(call("alpha", "{}"))

        assertTrue(outcome is ToolCallOutcome.Success)
        assertEquals(tool.resultJson(), outcome.contentOrNull())
    }

    @Test
    fun execute_builtinFailure_mapsToFailureWithMessageAndContent() = runBlocking {
        val tool = FailingBuiltinTool("alpha", "boom")
        val executor = LocalToolExecutor(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(tool))),
            currentTools = { builtinResolved(tool) },
        )

        val outcome = executor.execute(call("alpha", "{}"))

        assertTrue(outcome is ToolCallOutcome.Failure)
        outcome as ToolCallOutcome.Failure
        assertEquals("boom", outcome.message)
        assertEquals(tool.resultJson(), outcome.content)
    }

    @Test
    fun execute_customSuccess_mapsToSuccessOutcome() = runBlocking {
        val custom = LocalTool.Custom("custom_a", "desc", true, "echo hi")
        val customExec = fakeCustomExecutor { _, _ ->
            CustomCommandExecutionResult(ok = true, stdout = "hi")
        }
        val executor = LocalToolExecutor(
            customToolExecutor = customExec,
            currentTools = { customResolved(listOf(custom)) },
        )

        val outcome = executor.execute(call("custom_a", "{}"))

        assertTrue(outcome is ToolCallOutcome.Success)
    }

    @Test
    fun execute_customFailure_mapsToFailure() = runBlocking {
        val custom = LocalTool.Custom("custom_b", "desc", true, "false")
        val customExec = fakeCustomExecutor { _, _ ->
            CustomCommandExecutionResult(ok = false, message = "denied")
        }
        val executor = LocalToolExecutor(
            customToolExecutor = customExec,
            currentTools = { customResolved(listOf(custom)) },
        )

        val outcome = executor.execute(call("custom_b", "{}"))

        assertTrue(outcome is ToolCallOutcome.Failure)
        outcome as ToolCallOutcome.Failure
        assertEquals("denied", outcome.message)
    }

    @Test
    fun execute_unknownName_mapsToFailureStructuredError() = runBlocking {
        val executor = LocalToolExecutor(currentTools = { ResolvedTools() })

        val outcome = executor.execute(call("missing", "{}"))

        assertTrue(outcome is ToolCallOutcome.Failure)
        outcome as ToolCallOutcome.Failure
        assertEquals(true, outcome.content?.contains("LOCAL_TOOL_NOT_EXECUTABLE"))
    }

    @Test
    fun execute_textProtocolSuccess_mapsToSuccessOutcome() = runBlocking {
        val tool = TextProtocolBuiltinTool("texty", successPayload = "payload-ok")
        val executor = LocalToolExecutor(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(tool))),
            currentTools = { builtinResolved(tool) },
        )

        val outcome = executor.execute(call("texty", "{}"))

        assertTrue(outcome is ToolCallOutcome.Success)
        assertEquals(tool.rawResult(), outcome.contentOrNull())
    }

    @Test
    fun execute_textProtocolFailure_mapsToFailureOutcome() = runBlocking {
        val tool = TextProtocolBuiltinTool(
            "texty_bad",
            failure = TextToolResult.failure("E1", "bad"),
        )
        val executor = LocalToolExecutor(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(tool))),
            currentTools = { builtinResolved(tool) },
        )

        val outcome = executor.execute(call("texty_bad", "{}"))

        assertTrue(outcome is ToolCallOutcome.Failure)
        outcome as ToolCallOutcome.Failure
        assertEquals("bad", outcome.message)
    }

    @Test
    fun onInterrupt_returnsInterrupted() {
        val executor = LocalToolExecutor(currentTools = { ResolvedTools() })

        assertEquals(
            ToolCallOutcome.Interrupted(),
            executor.onInterrupt(call("a", "{}")),
        )
    }

    @Test
    fun createCustomTool_successAndEnabled_registersInlineAndInvokesCallback() = runBlocking {
        val createTool = OkBuiltinTool("create_custom_tool")
        var created: LocalTool.Custom? = null
        val inline = mutableMapOf<String, LocalTool.Custom>()
        val executor = LocalToolExecutor(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(createTool))),
            currentTools = { builtinResolved(createTool) },
            inlineCustomTools = inline,
            onCustomToolCreated = { created = it },
        )
        val args = """{"name":"my_tool","description":"d","command":"echo x","enabled":true}"""

        executor.execute(call("create_custom_tool", args))

        assertTrue(inline.containsKey("my_tool"))
        assertEquals("my_tool", created?.name)
        assertTrue(created?.enabled == true)
    }

    @Test
    fun createCustomTool_enabledFalse_doesNotRegister() = runBlocking {
        val createTool = OkBuiltinTool("create_custom_tool")
        var created: LocalTool.Custom? = null
        val inline = mutableMapOf<String, LocalTool.Custom>()
        val executor = LocalToolExecutor(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(createTool))),
            currentTools = { builtinResolved(createTool) },
            inlineCustomTools = inline,
            onCustomToolCreated = { created = it },
        )
        val args = """{"name":"off_tool","description":"d","command":"echo","enabled":false}"""

        executor.execute(call("create_custom_tool", args))

        assertNull(inline["off_tool"])
        assertNull(created)
    }

    @Test
    fun createCustomTool_inlineTool_executesViaInlineFallback() = runBlocking {
        val inline = mutableMapOf<String, LocalTool.Custom>()
        val customExec = fakeCustomExecutor { _, _ ->
            CustomCommandExecutionResult(ok = true, stdout = "ran")
        }
        val executor = LocalToolExecutor(
            customToolExecutor = customExec,
            currentTools = { ResolvedTools() }, // snapshot 里没有该工具
            inlineCustomTools = inline,
            onCustomToolCreated = {},
        )
        inline["my_tool"] = LocalTool.Custom("my_tool", "d", true, "echo x")

        val outcome = executor.execute(call("my_tool", "{}"))

        assertTrue(outcome is ToolCallOutcome.Success)
    }

    private fun fakeCustomExecutor(
        fake: suspend (String, Long) -> CustomCommandExecutionResult,
    ): CustomToolExecutor = CustomToolExecutor(
        commandExecutor = fake,
        safetyPolicy = ShellCommandSafetyPolicy(listExecutionRules = { emptyList() }),
    )

    private fun call(name: String, args: String): ToolCallContext =
        ToolCallContext("id-$name", name, descriptor(name), args)

    private fun ToolCallOutcome.contentOrNull(): String? = when (this) {
        is ToolCallOutcome.Success -> content
        is ToolCallOutcome.Failure -> content
        is ToolCallOutcome.Intercepted -> content
        is ToolCallOutcome.Interrupted -> content
        is ToolCallOutcome.Unknown -> content
    }

    private class OkBuiltinTool(
        override val name: String,
    ) : BuiltinTool() {
        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult =
            BuiltinToolResult.success(message = "ok")

        fun resultJson(): String = BuiltinToolResult.success(message = "ok").toJsonString()
    }

    private class FailingBuiltinTool(
        override val name: String,
        private val message: String,
    ) : BuiltinTool() {
        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult =
            BuiltinToolResult.failure(code = "E", message = message)

        fun resultJson(): String =
            BuiltinToolResult.failure(code = "E", message = message).toJsonString()
    }

    private class TextProtocolBuiltinTool(
        override val name: String,
        private val successPayload: String? = null,
        private val failure: TextToolResult? = null,
    ) : BuiltinTool(), RawJsonBuiltinTool {
        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult =
            BuiltinToolResult.failure(code = "RAW_OUTPUT_TOOL", message = "raw")

        override suspend fun invokeRawJson(request: BuiltinToolRequest): String =
            successPayload?.let { TextToolResultCodec.encode(TextToolResult.success(it)) }
                ?: TextToolResultCodec.encode(
                    TextToolResult.failure(failure!!.code!!, failure.message!!)
                )

        fun rawResult(): String =
            TextToolResultCodec.encode(TextToolResult.success(successPayload!!))
    }
}
