package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.ExecutePythonBuiltin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutePythonBuiltinTest {

    private suspend fun invoke(
        argumentsJson: String,
        executor: suspend (String, Long) -> String = { _, _ -> "" },
    ): String {
        val tool = ExecutePythonBuiltin(executor = executor)
        return tool.invokeRaw(BuiltinToolRequest("execute_python", argumentsJson))
    }

    // ---- success ----

    @Test
    fun invoke_validCodeAndDefaultTimeout_executesAndReturnsSuccess() = runTest {
        val result = invoke(
            """{"code":"print('hello')"}""",
            executor = { code, timeout ->
                assertEquals("print('hello')", code)
                assertEquals(30L, timeout)
                "hello\n"
            },
        )
        assertTrue(result.startsWith("#!tool-result"))
        assertTrue(result.contains("#!status: success"))
        assertTrue(result.contains("hello\n"))
    }

    @Test
    fun invoke_validCodeAndExplicitTimeout_passesTimeoutToExecutor() = runTest {
        val result = invoke(
            """{"code":"x=1","timeout":60}""",
            executor = { _, timeout ->
                assertEquals(60L, timeout)
                "ok"
            },
        )
        assertTrue(result.contains("ok"))
    }

    // ---- missing code ----

    @Test
    fun invoke_missingCode_returnsFailure() = runTest {
        val result = invoke("{}")
        assertTrue(result.contains("#!status: failure"))
        assertTrue(result.contains("#!code: MISSING_CODE"))
    }

    @Test
    fun invoke_blankCode_returnsFailure() = runTest {
        val result = invoke("""{"code":"  "}""")
        assertTrue(result.contains("#!status: failure"))
        assertTrue(result.contains("#!code: MISSING_CODE"))
    }

    // ---- invalid json ----

    @Test
    fun invoke_invalidJson_returnsFailure() = runTest {
        val result = invoke("not-json")
        assertTrue(result.contains("#!status: failure"))
        assertTrue(result.contains("#!code: INVALID_ARGUMENTS_JSON"))
    }

    @Test
    fun invoke_nonObjectJson_returnsFailure() = runTest {
        val result = invoke("[]")
        assertTrue(result.contains("#!status: failure"))
        assertTrue(result.contains("#!code: INVALID_ARGUMENTS_JSON"))
    }

    // ---- executor failure ----

    @Test
    fun invoke_executorThrows_returnsFailure() = runTest {
        val result = invoke(
            """{"code":"raise"}""",
            executor = { _, _ -> throw RuntimeException("something broke") },
        )
        assertTrue(result.contains("#!status: failure"))
        assertTrue(result.contains("#!code: PYTHON_ERROR"))
        assertTrue(result.contains("something broke"))
    }

    // ---- timeout clamping ----

    @Test
    fun invoke_timeoutBelowMin_clampsToOne() = runTest {
        invoke(
            """{"code":"x","timeout":-5}""",
            executor = { _, timeout ->
                assertEquals(1L, timeout)
                ""
            },
        )
    }

    @Test
    fun invoke_timeoutAboveMax_clampsTo120() = runTest {
        invoke(
            """{"code":"x","timeout":999}""",
            executor = { _, timeout ->
                assertEquals(120L, timeout)
                ""
            },
        )
    }
}
