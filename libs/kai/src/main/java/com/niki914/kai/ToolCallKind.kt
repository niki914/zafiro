package com.niki914.kai

sealed interface ToolCallKind {
    data object Local : ToolCallKind
    data class Mcp(val serverName: String) : ToolCallKind
}
