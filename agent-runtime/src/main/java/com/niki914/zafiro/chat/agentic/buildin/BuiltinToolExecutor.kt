package com.niki914.zafiro.chat.agentic.buildin

import kotlinx.coroutines.CancellationException

class BuiltinToolExecutor(
    private val registry: BuiltinToolRegistry = BuiltinToolRegistry.default(),
) {
    fun find(name: String): BuiltinTool? {
        return registry.find(name)
    }

    suspend fun execute(
        name: String,
        argumentsJson: String,
    ): String {
        val tool = find(name)
            ?: return BuiltinToolResult.failure(
                code = "LOCAL_TOOL_NOT_EXECUTABLE",
                message = "Local tool '$name' is not executable in current runtime.",
                hint = "Check builtin_tool_flags or py tools configuration.",
            ).toJsonString()

        return execute(tool = tool, argumentsJson = argumentsJson)
    }

    suspend fun execute(
        tool: BuiltinTool,
        argumentsJson: String,
    ): String {
        if (tool is RawJsonBuiltinTool) {
            return try {
                tool.invokeRawJson(
                    BuiltinToolRequest(
                        name = tool.name,
                        argumentsJson = argumentsJson,
                    )
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    throw throwable
                }
                BuiltinToolResult.failure(
                    code = "UNKNOWN_ERROR",
                    message = throwable.message ?: "Builtin tool '${tool.name}' failed.",
                    hint = "Inspect the builtin tool implementation and argumentsJson.",
                ).toJsonString()
            }
        }
        return try {
            tool.invoke(
                BuiltinToolRequest(
                    name = tool.name,
                    argumentsJson = argumentsJson,
                )
            ).toJsonString()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            BuiltinToolResult.failure(
                code = "UNKNOWN_ERROR",
                message = throwable.message ?: "Builtin tool '${tool.name}' failed.",
                hint = "Inspect the builtin tool implementation and argumentsJson.",
            ).toJsonString()
        }
    }
}
