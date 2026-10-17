// 保护：MCP 状态快照注入文案契约；Loading 与 Failed 条件分支、错误行截断与兜底
package com.niki914.zafiro.chat

import com.niki914.okia.mcp.McpDiscoveryState
import com.niki914.okia.mcp.McpServerDiscoverySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpNoticeTest {

    private fun server(
        name: String,
        state: McpDiscoveryState,
        errorMessage: String? = null,
    ) = McpServerDiscoverySnapshot(
        serverName = name,
        enabled = true,
        fingerprint = null,
        state = state,
        errorMessage = errorMessage,
        lastSuccessAtMillis = null,
        discoveredToolCount = 0,
    )

    @Test
    fun loadingOnlyIncludesUnreadyNotice() {
        val notice = TurnPrefixComposer().buildMcpNotice(
            loading = listOf(
                server("weather", McpDiscoveryState.Discovering),
                server("database", McpDiscoveryState.Idle),
            ),
        )
        assertTrue(notice.startsWith("[Note: MCP status snapshot when this message was sent. Some tools may not be ready at this moment:"))
        assertTrue(notice.contains("- Loading: weather, database"))
        assertFalse(notice.contains("Failed"))
    }

    @Test
    fun failedOnlyExcludesUnreadyNotice() {
        val notice = TurnPrefixComposer().buildMcpNotice(
            failed = listOf(
                server("search", McpDiscoveryState.Failed, "timeout after 5000ms"),
            ),
        )
        assertTrue(notice.startsWith("[Note: MCP status snapshot when this message was sent:"))
        assertFalse(notice.contains("Some tools may not be ready at this moment"))
        assertTrue(notice.contains("- Failed: search (timeout after 5000ms)"))
    }

    @Test
    fun mixedIncludesBothSectionsAndUnreadyNotice() {
        val notice = TurnPrefixComposer().buildMcpNotice(
            loading = listOf(server("weather", McpDiscoveryState.Discovering)),
            failed = listOf(server("search", McpDiscoveryState.Failed, "connection refused")),
        )
        assertTrue(notice.contains("Some tools may not be ready at this moment"))
        assertTrue(notice.contains("- Loading: weather"))
        assertTrue(notice.contains("- Failed: search (connection refused)"))
    }

    @Test
    fun truncatesMultilineErrorToFirstLine() {
        val notice = TurnPrefixComposer().buildMcpNotice(
            failed = listOf(server("docs", McpDiscoveryState.Failed, "line1\nline2\nline3")),
        )
        assertTrue(notice.contains("- Failed: docs (line1)"))
        assertFalse(notice.contains("line2"))
    }

    @Test
    fun missingErrorFallsBackToUnknown() {
        val notice = TurnPrefixComposer().buildMcpNotice(
            failed = listOf(server("docs", McpDiscoveryState.Failed)),
        )
        assertTrue(notice.contains("- Failed: docs (unknown error)"))
    }

    @Test
    fun emptyReturnsEmpty() {
        val notice = TurnPrefixComposer().buildMcpNotice()
        assertEquals("", notice)
    }
}
