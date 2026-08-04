package com.niki914.okai.tool

/**
 * Registry of available tools. Host registers descriptors with executors;
 * the loop resolves model tool calls through this registry.
 *
 * Design source: independent design; generalized from the local tool usage
 * validated in Nexus, per kai PRD section 3.
 */
interface ToolRegistry {

    fun register(descriptor: ToolDescriptor, executor: ToolExecutor)

    fun remove(name: String)

    fun find(name: String): RegisteredTool?

    fun snapshot(): List<RegisteredTool>
}

/** A tool bound to its executor. */
data class RegisteredTool(
    val descriptor: ToolDescriptor,
    val executor: ToolExecutor
)

/** Static description of a tool, serialized into the request body. */
data class ToolDescriptor(
    val name: String,
    val description: String,
    val inputSchemaJson: String? = null,
    val kind: ToolKind
)

/** Where the tool runs. */
sealed interface ToolKind {

    /** Executed by the host in-process. */
    data object Local : ToolKind

    /** Executed on a named MCP server. */
    data class Mcp(val serverName: String) : ToolKind
}
