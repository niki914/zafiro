package com.niki914.nexus.agentic.chat.agentic

import com.niki914.logging.Logger
import com.niki914.nexus.agentic.chat.LocalTool
import com.niki914.nexus.agentic.chat.ResolvedTools
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolExecutor
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.custom.CustomToolExecutor

class ToolCallDispatcher(
    private val builtinToolExecutor: BuiltinToolExecutor = BuiltinToolExecutor(),
    private val customToolExecutor: CustomToolExecutor = CustomToolExecutor(),
    private val currentTools: () -> ResolvedTools?
) {
    private companion object {
        const val LOG_TAG = "niki914_nexus_ToolCallDispatcher"
    }

    fun findCustomTool(name: String): LocalTool.Custom? {
        return currentTools()
            ?.customTools
            .orEmpty()
            .filterIsInstance<LocalTool.Custom>()
            .firstOrNull { it.name == name }
    }

    suspend fun executeCustomTool(tool: LocalTool.Custom): String {
        val startedAtMs = System.currentTimeMillis()
        Logger.i(LOG_TAG, "custom tool start name=${tool.name}")
        return customToolExecutor.execute(tool).also { result ->
            Logger.i(
                LOG_TAG,
                "custom tool done name=${tool.name} resultLength=${result.length} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }
    }

    suspend fun executeLocalTool(
        name: String,
        argumentsJson: String,
    ): String {
        val startedAtMs = System.currentTimeMillis()
        Logger.i(
            LOG_TAG,
            "local tool start name=$name argsLength=${argumentsJson.length}"
        )
        val tools = currentTools()
        val builtinTool = tools
            ?.builtinTools
            .orEmpty()
            .filterIsInstance<LocalTool.Builtin>()
            .firstOrNull { it.name == name }
        if (builtinTool != null) {
            return builtinToolExecutor.execute(
                tool = builtinTool.tool,
                argumentsJson = argumentsJson,
            ).also { result ->
                Logger.i(
                    LOG_TAG,
                    "local tool done name=$name kind=builtin resultLength=${result.length} " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                )
            }
        }

        val customTool = tools
            ?.customTools
            .orEmpty()
            .filterIsInstance<LocalTool.Custom>()
            .firstOrNull { it.name == name }
        if (customTool != null) {
            return executeCustomTool(customTool)
        }

        Logger.w(
            LOG_TAG,
            "local tool not executable name=$name " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return BuiltinToolResult.failure(
            code = "LOCAL_TOOL_NOT_EXECUTABLE",
            message = "Local tool '$name' is not executable in current runtime.",
            hint = "Check builtin_tool_flags or custom_tools configuration.",
        ).toJsonString()
    }
}
