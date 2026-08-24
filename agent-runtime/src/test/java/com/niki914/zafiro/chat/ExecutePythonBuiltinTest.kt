package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.impl.ExecutePythonBuiltin
import com.niki914.zafiro.chat.agentic.shell.ShellCommandPolicyDecision
import com.niki914.zafiro.chat.agentic.shell.ShellCommandSafetyPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutePythonBuiltinTest {

    private fun allowAllPolicy(): ShellCommandSafetyPolicy =
        ShellCommandSafetyPolicy(
            listExecutionRules = { emptyList() },
            isUnlocked = { true },
        )

    private suspend fun invoke(
        argumentsJson: String,
        executor: suspend (String, Long) -> String = { _, _ -> "" },
        safetyPolicy: ShellCommandSafetyPolicy = allowAllPolicy(),
    ): String {
        val tool = ExecutePythonBuiltin(executor = executor, safetyPolicy = safetyPolicy)
        return tool.invokeRaw(BuiltinToolRequest("execute_python", argumentsJson))
    }

    // ---- success ----

    @Test
    fun invoke_validCodeAndDefaultTimeout_executesAndReturnsSuccess() = runTest {
        val result = invoke(
            """{"code":"print('hello')"}""",
            executor = { code, timeoutMs ->
                assertEquals("print('hello')", code)
                assertEquals(30_000L, timeoutMs)
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
            """{"code":"x=1","timeout_ms":60000}""",
            executor = { _, timeoutMs ->
                assertEquals(60_000L, timeoutMs)
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

    @Test
    fun invoke_executorThrowsTimeoutMessage_returnsTimeoutCode() = runTest {
        val result = invoke(
            """{"code":"while True: pass"}""",
            executor = { _, _ ->
                throw RuntimeException("Execution timed out after 30s\n\nPartial output:\nsome output")
            },
        )
        assertTrue(result.contains("#!status: failure"))
        assertTrue(result.contains("#!code: TIMEOUT"))
        assertTrue(result.contains("timed out after"))
    }

    // ---- safety policy ----

    @Test
    fun invoke_policyBlocks_returnsFailure() = runTest {
        val blockingPolicy = ShellCommandSafetyPolicy(
            listExecutionRules = {
                listOf(
                    com.niki914.zafiro.settings.model.RuntimeExecutionRule(
                        id = "rule-1",
                        name = "Block su",
                        enabledMode = com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode.ALWAYS,
                        patterns = listOf("""os\.system"""),
                    )
                )
            },
            isUnlocked = { true },
        )
        val result = invoke(
            """{"code":"import os\nos.system('su')"}""",
            safetyPolicy = blockingPolicy,
        )
        assertTrue(result.contains("#!status: failure"))
        assertTrue(result.contains("#!code: COMMAND_BLOCKED"))
        assertTrue(result.contains("Block su"))
    }

    @Test
    fun invoke_policyAllows_continuesToExecute() = runTest {
        val result = invoke(
            """{"code":"print('safe')"}""",
            executor = { _, _ -> "safe output" },
        )
        assertTrue(result.contains("#!status: success"))
        assertTrue(result.contains("safe output"))
    }

    // ---- timeout clamping ----

    @Test
    fun invoke_timeoutBelowMin_clampsToOneSecond() = runTest {
        invoke(
            """{"code":"x","timeout_ms":-5}""",
            executor = { _, timeoutMs ->
                assertEquals(1_000L, timeoutMs)
                ""
            },
        )
    }

    @Test
    fun invoke_timeoutAboveMax_clampsTo120Seconds() = runTest {
        invoke(
            """{"code":"x","timeout_ms":999999}""",
            executor = { _, timeoutMs ->
                assertEquals(120_000L, timeoutMs)
                ""
            },
        )
    }
}
