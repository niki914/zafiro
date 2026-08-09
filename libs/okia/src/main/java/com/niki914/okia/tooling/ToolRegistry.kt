package com.niki914.okia.tooling

/**
 * 可用工具注册表。host 注册 descriptor + executor；
 * loop 通过它解析模型工具调用。
 * Design source: okia 骨架 ToolRegistry。
 */
interface ToolRegistry {

    // 注册工具
    fun register(descriptor: ToolDescriptor, executor: ToolExecutor): Unit = TODO()

    // 按名移除
    fun remove(name: String): Unit = TODO()

    // 按名查找
    fun find(name: String): RegisteredTool? = TODO()

    // 快照
    fun snapshot(): List<RegisteredTool> = TODO()
}

/** 绑定 executor 的工具。 */
data class RegisteredTool(
    val descriptor: ToolDescriptor,
    val executor: ToolExecutor
)

/** 工具的静态描述，序列化进请求体。 */
data class ToolDescriptor(
    val name: String,
    val description: String,
    val inputSchemaJson: String? = null,
    val kind: ToolKind
)

/** 工具在哪里运行。 */
sealed interface ToolKind {

    /** host 进程内执行。 */
    data object Local : ToolKind

    /** 在命名 MCP 服务器上执行。 */
    data class Mcp(val serverName: String) : ToolKind
}
