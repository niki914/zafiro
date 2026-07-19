package com.niki914.nexus.agentic.chat.agentic.buildin

abstract class RawBuiltinTool : BuiltinTool(), RawJsonBuiltinTool {

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        return BuiltinToolResult.failure(
            code = "RAW_OUTPUT_TOOL",
            message = "'${name}' returns raw output. Use the raw execution path.",
        )
    }

    override suspend fun invokeRawJson(request: BuiltinToolRequest): String {
        return invokeRaw(request)
    }

    abstract suspend fun invokeRaw(request: BuiltinToolRequest): String
}
