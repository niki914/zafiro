package com.niki914.okia.tooling

import com.niki914.okia.fake.RecordingToolExecutor
import com.niki914.okia.fake.localTool
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 保护：registry.snapshot() 的输出顺序与注册时序无关。
 *
 * 快照顺序决定请求体 tools 数组的顺序，而工具描述排在 prompt 最前——顺序抖动
 * 会让整段 prompt 前缀失去缓存命中（静默的延迟 / 成本回归，不报错）。
 * Host 每次 refresh 做 syncLocalTools（本地工具整体重注册）、MCP 每次发现做
 * registerAll（该服务器工具整体重注册），两者都会把各自挪到 LinkedHashMap
 * 尾部，所以「注册先后」本身就是不稳定的——快照必须自己定序。
 */
class DefaultToolRegistryTest {

    private val executor = RecordingToolExecutor()

    private fun names(registry: ToolRegistry): List<String> =
        registry.snapshot().map { it.descriptor.wireName }

    @Test
    fun snapshotOrderIsIndependentOfRegistrationOrder() {
        val forward = DefaultToolRegistry().apply {
            listOf("b_tool", "a_tool", "c_tool").forEach { register(localTool(it), executor) }
        }
        val reverse = DefaultToolRegistry().apply {
            listOf("c_tool", "a_tool", "b_tool").forEach { register(localTool(it), executor) }
        }

        assertEquals(listOf("a_tool", "b_tool", "c_tool"), names(forward))
        assertEquals(names(forward), names(reverse))
    }

    @Test
    fun reRegistrationKeepsSnapshotOrder() {
        // 复现 syncLocalTools / registerAll 的行为：移除后重新注册。
        // 无排序时这会把重注册的工具挪到末尾，快照顺序随之改变。
        val registry = DefaultToolRegistry().apply {
            register(localTool("local_tool"), executor)
            register(localTool("mcp__srv__remote"), executor)
        }
        val before = names(registry)

        registry.remove("local_tool")
        registry.register(localTool("local_tool"), executor)

        assertEquals(before, names(registry))
    }
}
