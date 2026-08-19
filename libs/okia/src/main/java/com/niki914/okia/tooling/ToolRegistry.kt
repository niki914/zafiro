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

/**
 * 工具的静态描述，序列化进请求体。
 * [name] 为原始名（MCP 工具原始名 / host 配置的本地工具名），仅用于 MCP
 * 调用与展示；[wireName] 为 provider 可见线缆名（请求体序列化 + registry
 * 键 + 模型回传匹配）。两者分离的原因：MCP 工具名是用户可控字符串，可含
 * Provider 不允许的字符（`.` 等）或超长，线缆名必须单独派生（见
 * ToolWireName），且不能因规范化破坏到原始 MCP 工具名的可逆关系。
 *
 * 默认 wireName = ToolWireName.forLocal(name)，适用于本地工具与 host 直接
 * 注册的干净名字；MCP 工具由 McpDiscovery 显式传入 `mcp__<server>__<tool>`
 * 形态的线缆名。
 */
data class ToolDescriptor(
    val name: String,
    val description: String,
    val inputSchemaJson: String? = null,
    val kind: ToolKind,
    val wireName: String = ToolWireName.forLocal(name)
)

/** 工具在哪里运行。 */
sealed interface ToolKind {

    /** host 进程内执行。 */
    data object Local : ToolKind

    /** 在命名 MCP 服务器上执行。 */
    data class Mcp(val serverName: String) : ToolKind
}
