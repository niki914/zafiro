package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.impl.ExecutePythonBuiltin
import com.niki914.zafiro.chat.agentic.python.PyExecOutput
import com.niki914.zafiro.chat.agentic.python.PyExecResult
import com.niki914.zafiro.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutePythonBuiltinTest {

    @get:Rule
    val silentLogger = com.niki914.zafiro.chat.util.SilentLoggerRule()

    private fun allowAllPolicy(): ShellCommandSafetyPolicy =
        ShellCommandSafetyPolicy(
            listExecutionRules = { emptyList() },
            isUnlocked = { true },
        )

    private suspend fun invoke(
        argumentsJson: String,
        executor: suspend (String, Long) -> PyExecOutput = { _, _ -> inlineOutput("") },
        safetyPolicy: ShellCommandSafetyPolicy = allowAllPolicy(),
        exportDir: java.io.File? = null,
    ): String {
        val tool = ExecutePythonBuiltin(
            executor = executor,
            safetyPolicy = safetyPolicy,
            exportDir = exportDir,
        )
        return tool.invokeRaw(BuiltinToolRequest("execute_python", argumentsJson))
    }

    private fun inlineOutput(text: String): PyExecOutput =
        PyExecOutput(text, null, timedOut = false)

    private fun fileOutput(text: String, dir: java.io.File): PyExecOutput {
        val f = java.io.File.createTempFile("pyout", ".log", dir)
        f.writeText(text)
        return PyExecOutput(text, f, timedOut = false)
    }

    // ---- success ----

    @Test
    fun invoke_validCodeAndDefaultTimeout_executesAndReturnsSuccess() = runTest {
        val result = invoke(
            """{"code":"print('hello')"}""",
            executor = { code, timeoutMs ->
                assertEquals("print('hello')", code)
                assertEquals(30_000L, timeoutMs)
                inlineOutput("hello\n")
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
                inlineOutput("ok")
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
    fun invoke_executorThrowsAnyMessage_returnsPythonError() = runTest {
        // 超时已走结构化路径（PyExecOutput.timedOut），异常不再嗅探字符串：
        // executor 抛什么都是 PYTHON_ERROR
        val result = invoke(
            """{"code":"raise"}""",
            executor = { _, _ ->
                throw RuntimeException("Execution timed out after 30s\n\nPartial output:\nsome output")
            },
        )
        assertTrue(result.contains("#!status: failure"))
        assertTrue(result.contains("#!code: PYTHON_ERROR"))
    }

    @Test
    fun invoke_structuredTimeout_appendsPartialNote() = runTest {
        val result = invoke(
            """{"code":"while True: pass"}""",
            executor = { _, _ ->
                PyExecOutput("partial output\n", null, timedOut = true)
            },
        )
        assertTrue(result.contains("partial output"))
        assertTrue(result.contains("timed out"))
    }

    @Test
    fun invoke_fileOutput_small_notExported() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("pyexport").toFile()
        val exportDir = java.nio.file.Files.createTempDirectory("pyexport").toFile()
        try {
            val result = invoke(
                """{"code":"print('ok')"}""",
                executor = { _, _ -> fileOutput("ok\n", dir) },
                exportDir = exportDir,
            )
            assertTrue(result.contains("ok"))
            // 未截断：传输文件已被消费删除，导出目录无文件
            assertFalse(java.io.File(dir, "x").exists())
            assertEquals(0, exportDir.listFiles()?.size)
        } finally {
            dir.deleteRecursively()
            exportDir.deleteRecursively()
        }
    }

    @Test
    fun invoke_truncatedOutput_exportsFullFile() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("pyexport").toFile()
        val exportDir = java.nio.file.Files.createTempDirectory("pyexport").toFile()
        try {
            val big = "x\n".repeat(30000) // 60KB > 50KB
            val result = invoke(
                """{"code":"print('big')"}""",
                executor = { _, _ -> fileOutput(big, dir) },
                exportDir = exportDir,
            )
            assertTrue(result.contains("[Output truncated"))
            assertTrue(result.contains("[Full output: "))
            // 传输文件被 move 到导出目录
            val exported = exportDir.listFiles()!!.single()
            assertEquals(big, exported.readText())
        } finally {
            dir.deleteRecursively()
            exportDir.deleteRecursively()
        }
    }

    // ---- safety policy ----

    @Test
    fun invoke_policyBlocks_returnsFailure() = runTest {
        val blockingPolicy = ShellCommandSafetyPolicy(
            listExecutionRules = {
                listOf(
                    RuntimeExecutionRule(
                        id = "rule-1",
                        name = "Block su",
                        enabledMode = RuntimeExecutionRuleEnabledMode.ALWAYS,
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
            executor = { _, _ -> inlineOutput("safe output") },
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
                inlineOutput("")
            },
        )
    }

    @Test
    fun invoke_timeoutAboveMax_clampsTo120Seconds() = runTest {
        invoke(
            """{"code":"x","timeout_ms":999999}""",
            executor = { _, timeoutMs ->
                assertEquals(120_000L, timeoutMs)
                inlineOutput("")
            },
        )
    }
}
