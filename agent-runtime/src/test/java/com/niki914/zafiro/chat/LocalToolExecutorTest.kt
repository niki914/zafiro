package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.LocalToolExecutor
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolExecutor
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.buildin.RawJsonBuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import com.niki914.zafiro.chat.agentic.buildin.TextToolResultCodec
import com.niki914.zafiro.chat.agentic.python.PyToolExecutor
import com.niki914.zafiro.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.zafiro.chat.util.SilentLoggerRule
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

    private fun pyResolved(tools: List<LocalTool.Py>): ResolvedTools =
        ResolvedTools(pyTools = tools)

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
    fun execute_pySuccess_mapsToSuccessOutcome() = runBlocking {
        val py = LocalTool.Py("py_a", "desc", "print('hi')", null)
        val pyExec = fakePyExecutor { _, _ -> "{\"ok\":true,\"stdout\":\"hi\"}" }
        val executor = LocalToolExecutor(
            pyToolExecutor = pyExec,
            currentTools = { pyResolved(listOf(py)) },
        )

        val outcome = executor.execute(call("py_a", "{}"))

        assertTrue(outcome is ToolCallOutcome.Success)
    }

    @Test
    fun execute_pyFailure_mapsToFailure() = runBlocking {
        val py = LocalTool.Py("py_b", "desc", "raise RuntimeError()", null)
        val pyExec = fakePyExecutor { _, _ ->
            throw RuntimeException("denied")
            @Suppress("UNREACHABLE_CODE")
            ""
        }
        val executor = LocalToolExecutor(
            pyToolExecutor = pyExec,
            currentTools = { pyResolved(listOf(py)) },
        )

        val outcome = executor.execute(call("py_b", "{}"))

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
    fun execute_hermesStructuredError_mapsToFailure() = runBlocking {
        val tool = RawJsonTool(
            "terminal",
            """{"stdout":"","stderr":"","exit_code":127,"error":{"code":"CMD_NOT_FOUND","message":"command not found: nope"}}""",
        )
        val executor = LocalToolExecutor(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(tool))),
            currentTools = { builtinResolved(tool) },
        )

        val outcome = executor.execute(call("terminal", "{}"))

        assertTrue(outcome is ToolCallOutcome.Failure)
        outcome as ToolCallOutcome.Failure
        assertEquals("command not found: nope", outcome.message)
        assertEquals(tool.raw, outcome.content)
    }

    @Test
    fun execute_hermesNonZeroExitCode_mapsToFailure() = runBlocking {
        val tool = RawJsonTool(
            "terminal",
            """{"stdout":"","stderr":"sh: nope: not found","exit_code":127}""",
        )
        val executor = LocalToolExecutor(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(tool))),
            currentTools = { builtinResolved(tool) },
        )

        val outcome = executor.execute(call("terminal", "{}"))

        assertTrue(outcome is ToolCallOutcome.Failure)
        outcome as ToolCallOutcome.Failure
        assertEquals("sh: nope: not found", outcome.message)
    }

    @Test
    fun execute_hermesSuccessJson_mapsToSuccess() = runBlocking {
        val tool = RawJsonTool(
            "terminal",
            """{"stdout":"hi","stderr":"","exit_code":0}""",
        )
        val executor = LocalToolExecutor(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(tool))),
            currentTools = { builtinResolved(tool) },
        )

        val outcome = executor.execute(call("terminal", "{}"))

        assertTrue(outcome is ToolCallOutcome.Success)
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
    fun pytoolsWrite_successAndEnabled_registersInlineAndInvokesCallback() = runBlocking {
        val writeResult = BuiltinToolResult.success(
            message = "ok",
            data = kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive("py_my_tool"))
                put("description", kotlinx.serialization.json.JsonPrimitive("d"))
                put("schema_json", kotlinx.serialization.json.JsonPrimitive("{\"type\":\"object\"}"))
                put("enabled", kotlinx.serialization.json.JsonPrimitive(true))
            },
        )
        val manageTool = StaticBuiltinTool("pytools", writeResult)
        var written: LocalTool.Py? = null
        val inline = mutableMapOf<String, LocalTool.Py>()
        val executor = LocalToolExecutor(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(manageTool))),
            currentTools = { builtinResolved(manageTool) },
            inlinePyTools = inline,
            onPyToolWritten = { written = it },
        )
        val args = """{"action":"write","name":"py_my_tool","code":"def main():\n    pass"}"""

        executor.execute(call("pytools", args))

        assertTrue(inline.containsKey("py_my_tool"))
        assertEquals("py_my_tool", written?.name)
        assertEquals("{\"type\":\"object\"}", written?.inputSchemaJson)
    }

    @Test
    fun pytoolsWrite_enabledFalse_doesNotRegister() = runBlocking {
        val writeResult = BuiltinToolResult.success(
            message = "ok",
            data = kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive("py_off_tool"))
                put("enabled", kotlinx.serialization.json.JsonPrimitive(false))
            },
        )
        val manageTool = StaticBuiltinTool("pytools", writeResult)
        var written: LocalTool.Py? = null
        val inline = mutableMapOf<String, LocalTool.Py>()
        val executor = LocalToolExecutor(
            builtinToolExecutor = BuiltinToolExecutor(BuiltinToolRegistry(listOf(manageTool))),
            currentTools = { builtinResolved(manageTool) },
            inlinePyTools = inline,
            onPyToolWritten = { written = it },
        )
        val args = """{"action":"write","name":"py_off_tool","code":"def main():\n    pass","enabled":false}"""

        executor.execute(call("pytools", args))

        assertNull(inline["py_off_tool"])
        assertNull(written)
    }

    @Test
    fun pytoolsWrite_inlineTool_executesViaInlineFallback() = runBlocking {
        val inline = mutableMapOf<String, LocalTool.Py>()
        val pyExec = fakePyExecutor { _, _ -> "{\"ok\":true,\"stdout\":\"ran\"}" }
        val executor = LocalToolExecutor(
            pyToolExecutor = pyExec,
            currentTools = { ResolvedTools() }, // snapshot 里没有该工具
            inlinePyTools = inline,
            onPyToolWritten = {},
        )
        inline["py_my_tool"] = LocalTool.Py("py_my_tool", "d", "print('ran')", null)

        val outcome = executor.execute(call("py_my_tool", "{}"))

        assertTrue(outcome is ToolCallOutcome.Success)
    }

    private fun fakePyExecutor(
        fake: suspend (String, Long) -> String,
    ): PyToolExecutor = PyToolExecutor(exec = fake)

    private class StaticBuiltinTool(
        override val name: String,
        private val result: BuiltinToolResult,
    ) : BuiltinTool() {
        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult = result
    }

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

    private class RawJsonTool(
        override val name: String,
        val raw: String,
    ) : BuiltinTool(), RawJsonBuiltinTool {
        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult =
            BuiltinToolResult.failure(code = "RAW_OUTPUT_TOOL", message = "raw")

        override suspend fun invokeRawJson(request: BuiltinToolRequest): String = raw
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
