package com.niki914.zafiro.chat.agentic.python

import com.niki914.zafiro.chat.LocalTool
import com.niki914.zafiro.chat.agentic.ToolExecutionPreflight
import com.niki914.zafiro.util.ToolOutputTruncator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * CustomPyTool 执行器：把 LLM 的参数 JSON 经 [CustomPyToolHarness.buildRunner] 拼接后
 * 交给 [PyRuntime.exec]（:python 进程）。输出即 stdout（截断+导出由
 * [ToolOutputTruncator.filterForAgent] 统一处理）。结果用 {"ok":...} JSON 约定，
 * 与 BuiltinToolResult 对齐，由 LocalToolResultClassifier 拆 Success/Failure。
 *
 * 执行前过 [ToolExecutionPreflight]（规则 + 路径权限）：保存期校验过的不算数，
 * 规则可能事后新增；参数里的路径更是只有执行期才看得到。
 */
class CustomPyToolExecutor(
    private val exec: suspend (code: String, timeoutMs: Long) -> PyExecOutput = PyRuntime::exec,
    private val preflight: ToolExecutionPreflight = ToolExecutionPreflight(),
    /** 截断导出目录，测试可注入临时目录；默认 filesDir/tool_output。 */
    private val exportDir: java.io.File? = ToolOutputTruncator.defaultExportDir(),
) {
    suspend fun execute(tool: LocalTool.Py, argumentsJson: String): String {
        val args = parseArguments(argumentsJson)
        val decision = preflight.evaluate(tool.code + "\n" + argumentsJson, toolName = tool.name)
        if (!decision.allowed) {
            return failureJson(
                tool.name,
                "COMMAND_BLOCKED",
                decision.reason.ifBlank { "Code blocked by execution rule." },
            )
        }
        preflight.ensurePathAccess(tool.code + "\n" + argumentsJson)
        return try {
            val result = exec(CustomPyToolHarness.buildRunner(tool.code, args), tool.timeoutMs)
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "tool" to JsonPrimitive(tool.name),
                    "stdout" to JsonPrimitive(
                        ToolOutputTruncator.filterForAgent(
                            fullContent = result.output,
                            existingFile = result.file,
                            exportDir = exportDir,
                        )
                    ),
                )
            ).toString()
        } catch (e: TimeoutCancellationException) {
            failureJson(
                tool.name,
                "TIMEOUT",
                "Execution timed out after ${tool.timeoutMs / 1000}s."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failureJson(tool.name, "PYTHON_ERROR", t.message ?: "Python execution failed.")
        }
    }

    private fun parseArguments(argumentsJson: String): String {
        if (argumentsJson.isBlank()) return "{}"
        val element = try {
            Json.parseToJsonElement(argumentsJson)
        } catch (_: Exception) {
            return "{}"
        }
        return if (element is JsonObject) argumentsJson else "{}"
    }

    private fun failureJson(name: String, code: String, message: String): String {
        return JsonObject(
            mapOf(
                "ok" to JsonPrimitive(false),
                "tool" to JsonPrimitive(name),
                "code" to JsonPrimitive(code),
                "message" to JsonPrimitive(message),
            )
        ).toString()
    }
}
