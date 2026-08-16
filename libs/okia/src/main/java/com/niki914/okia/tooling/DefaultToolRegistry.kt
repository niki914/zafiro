package com.niki914.okia.tooling

/**
 * 库默认工具注册表：host 直接注册工具用（无需自实现 ToolRegistry）。
 * 无锁实现：register / remove 只在活跃回合外调用（host 契约，§8.4 #10），
 * 回合中只读 find / snapshot；写先于读，由 host 的装配顺序保证。
 * snapshot 返回复制，外部持有不影响内部存储。
 * Design source: kai LocalToolRegistry；okia 骨架 ToolRegistry 对照基线。
 */
class DefaultToolRegistry : ToolRegistry {

    private val tools = LinkedHashMap<String, RegisteredTool>()

    override fun register(descriptor: ToolDescriptor, executor: ToolExecutor) {
        tools[descriptor.name] = RegisteredTool(descriptor, executor)
    }

    override fun remove(name: String) {
        tools.remove(name)
    }

    override fun find(name: String): RegisteredTool? = tools[name]

    override fun snapshot(): List<RegisteredTool> = tools.values.toList()
}
