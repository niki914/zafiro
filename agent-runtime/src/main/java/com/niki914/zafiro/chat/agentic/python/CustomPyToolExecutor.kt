package com.niki914.zafiro.chat.agentic.python

import com.niki914.zafiro.chat.LocalTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * CustomPyTool 执行器：把 LLM 的参数 JSON 经 [CustomPyToolHarness.buildRunner] 拼接后
 * 交给 [PyRuntime.exec]（:python 进程）。输出即 stdout（runtime.py 已做
 * 50KB 截断）。结果用 {"ok":...} JSON 约定，与 BuiltinToolResult 对齐，
 * 由 LocalToolResultClassifier 拆 Success/Failure。
 */
class CustomPyToolExecutor(
    private val exec: suspend (code: String, timeoutMs: Long) -> String = PyRuntime::exec,
) {
    suspend fun execute(tool: LocalTool.Py, argumentsJson: String): String {
        val args = parseArguments(argumentsJson)
        return try {
            val output = exec(CustomPyToolHarness.buildRunner(tool.code, args), tool.timeoutMs)
            JsonObject(
                mapOf(
                    "ok" to JsonPrimitive(true),
                    "tool" to JsonPrimitive(tool.name),
                    "stdout" to JsonPrimitive(output),
                )
            ).toString()
        } catch (e: TimeoutCancellationException) {
            failureJson(tool.name, "TIMEOUT", "Execution timed out after ${tool.timeoutMs / 1000}s.")
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
