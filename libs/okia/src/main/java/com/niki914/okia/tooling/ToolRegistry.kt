package com.niki914.okia.tooling

/**
 * 可用工具注册表。host 注册 descriptor + executor；
 * loop 通过它解析模型工具调用。
 * host 契约：活跃回合期间不得直接变更 registry（remove / register），
 * 变更须经 Okia.update；直接改注入对象会绕过门面的活跃回合检查，
 * 导致请求里的工具描述与执行器不再一致。
 * Design source: okia 骨架 ToolRegistry。
 */
interface ToolRegistry {

    // 注册工具
    fun register(descriptor: ToolDescriptor, executor: ToolExecutor): Unit

    // 按名移除
    fun remove(name: String): Unit

    // 按名查找
    fun find(name: String): RegisteredTool?

    // 快照
    fun snapshot(): List<RegisteredTool>
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
