package com.niki914.nexus.agentic.chat.agentic.buildin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class TextResultBuiltinToolTest {

    @Test
    fun invokeTextReturnsSuccess_invokeRawReturnsEncodedToolResultString() = runTest {
        val tool = object : TextResultBuiltinTool() {
            override val name: String = "test_tool"
            override suspend fun invokeText(request: BuiltinToolRequest): TextToolResult =
                TextToolResult.success("test payload")
        }

        val result = tool.invokeRaw(BuiltinToolRequest("test_tool", "{}"))

        assertTrue(result.startsWith("#!tool-result"))
        assertTrue(result.contains("#!status: success"))
        assertTrue(result.contains("test payload"))
    }

    @Test
    fun invokeTextThrowsRuntimeException_invokeRawReturnsUnknownErrorFailure() = runTest {
        val tool = object : TextResultBuiltinTool() {
            override val name: String = "test_tool"
            override suspend fun invokeText(request: BuiltinToolRequest): TextToolResult =
                throw RuntimeException("something went wrong")
        }

        val result = tool.invokeRaw(BuiltinToolRequest("test_tool", "{}"))

        assertTrue(result.startsWith("#!tool-result"))
        assertTrue(result.contains("#!status: failure"))
        assertTrue(result.contains("#!code: UNKNOWN_ERROR"))
        assertTrue(result.contains("something went wrong"))
    }

    @Test(expected = CancellationException::class)
    fun invokeTextThrowsCancellationException_invokeRawRethrows() = runTest {
        val tool = object : TextResultBuiltinTool() {
            override val name: String = "test_tool"
            override suspend fun invokeText(request: BuiltinToolRequest): TextToolResult =
                throw CancellationException("cancelled")
        }

        tool.invokeRaw(BuiltinToolRequest("test_tool", "{}"))
    }
}
