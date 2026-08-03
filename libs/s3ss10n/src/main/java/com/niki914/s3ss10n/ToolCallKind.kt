package com.niki914.s3ss10n

sealed interface ToolCallKind {
    data object Local : ToolCallKind
    data class Mcp(val serverName: String) : ToolCallKind
}
