package com.niki914.okia.tooling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolWireName 线缆名派生测试：本地 / MCP 规范化、长度预算、哈希消歧确定性。
 * 覆盖 D1 B（mcp__server__tool 双层）、D2 B（保留 -、_）、D4 A（哈希后缀）。
 */
class ToolWireNameTest {

    // ── 本地工具 ───────────────────────────────────────────────────────────

    @Test
    fun forLocalKeepsCleanName() {
        assertEquals("web_search", ToolWireName.forLocal("web_search"))
        assertEquals("noop", ToolWireName.forLocal("noop"))
    }

    @Test
    fun forLocalKeepsDashAndUnderscoreAndReplacesOthers() {
        // D2 B：- 与 _ 保留；点 / 空格 / 非 ASCII 替换为 _
        assertEquals("admin_tools_list", ToolWireName.forLocal("admin.tools.list"))
        assertEquals("list-files", ToolWireName.forLocal("list-files"))
        assertEquals("get_user_info", ToolWireName.forLocal("get user info"))
        assertEquals("__", ToolWireName.forLocal("!!")) // 段内全部替换 → 全下划线
    }

    @Test
    fun forLocalTruncatesTo64() {
        val long = "t".repeat(200)
        val name = ToolWireName.forLocal(long)
        assertEquals(64, name.length)
        assertEquals("t".repeat(64), name)
    }

    // ── MCP 工具（D1 B：mcp__server__tool） ────────────────────────────────

    @Test
    fun forMcpNamespacesServerAndTool() {
        assertEquals("mcp__docs__search", ToolWireName.forMcp("docs", "search"))
        assertEquals("mcp__a__t", ToolWireName.forMcp("a", "t"))
    }

    @Test
    fun forMcpSanitizesServerAndToolSegments() {
        assertEquals("mcp__my_server__admin_tools_list", ToolWireName.forMcp("my server", "admin.tools.list"))
        assertEquals("mcp__srv-a__get-sum", ToolWireName.forMcp("srv-a", "get-sum"))
    }

    @Test
    fun forMcpTotalLengthAtMost64ForLongInputs() {
        val server = "s".repeat(80)
        val tool = "t".repeat(80)
        val name = ToolWireName.forMcp(server, tool)
        assertTrue("应 ≤ 64：$name", name.length <= 64)
        // 保留前缀与分隔符，域截断在工具名部分
        assertTrue(name.startsWith("mcp__"))
        assertTrue(name.contains("__"))
    }

    @Test
    fun forMcpIsDeterministic() {
        assertEquals(ToolWireName.forMcp("docs", "search"), ToolWireName.forMcp("docs", "search"))
    }

    // ── 消歧（D4 A：哈希后缀） ─────────────────────────────────────────────

    @Test
    fun disambiguateReturnsBaseWhenUnused() {
        val used = setOf("mcp__docs__other")
        assertEquals("mcp__docs__search", ToolWireName.disambiguate("mcp__docs__search", "docs\u0000search", used))
    }

    @Test
    fun disambiguateAppendsHashSuffixOnCollisionAndStaysDeterministic() {
        // a.b 与 a_b sanitize 后都变 mcp__docs__a_b → 第一个占 base，第二个哈希后缀
        val used = mutableSetOf<String>()
        val first = ToolWireName.disambiguate("mcp__docs__a_b", "docs\u0000a.b", used)
        used += first
        val second = ToolWireName.disambiguate("mcp__docs__a_b", "docs\u0000a_b", used)
        assertTrue(second != first)
        assertTrue("应带哈希后缀：$second", second.startsWith("mcp__docs__a_b_"))
        assertTrue("线缆名 ≤ 64：$second", second.length <= 64)
        // 同输入重复派生稳定
        val again = ToolWireName.disambiguate("mcp__docs__a_b", "docs\u0000a_b", used)
        assertEquals(second, again)
    }

    @Test
    fun disambiguateIteratesAttemptsUntilUnique() {
        val used = mutableSetOf<String>()
        // 构造多条碰撞：base 相同，但 rawIdentity 不同 → 每次追加不同哈希后缀
        val names = mutableListOf<String>()
        repeat(5) { i ->
            val n = ToolWireName.disambiguate("mcp__s__x", "s\u0000tool$i", used)
            assertTrue("应唯一：$n 未重复", names.none { it == n })
            names += n
            used += n
            assertTrue(n.length <= 64)
        }
        assertEquals(5, names.toSet().size)
    }

    @Test
    fun disambiguateStaysWithinLengthBudgetAfterCollision() {
        val base = ToolWireName.forMcp("s".repeat(32), "t".repeat(30)) // 已接近 64
        assertTrue(base.length <= 64)
        val used = mutableSetOf(base)
        val collided = ToolWireName.disambiguate(base, "s\u0000different", used)
        assertTrue("碰撞后仍 ≤ 64：$collided", collided.length <= 64)
        assertTrue(collided != base)
    }
}
