package com.niki914.zafiro.chat

import com.niki914.okia.mcp.McpDiscoveryState
import com.niki914.okia.mcp.McpServerDiscoverySnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MCP 失败注入文案（#switch-refresh 配套）：仅 Failed、格式、错误截断。 */
class McpFailureNoticeTest {

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
    fun formatsFailedServersWithReasons() {
        val notice = LLMController.buildMcpFailureNotice(
            listOf(
                server("docs", McpDiscoveryState.Failed, "connection refused"),
                server("search", McpDiscoveryState.Failed, "timeout after 5000ms"),
            )
        )
        assertTrue(notice.startsWith("[IMPORTANT: MCP discovery failed"))
        assertTrue(notice.contains("- docs: connection refused"))
        assertTrue(notice.contains("- search: timeout after 5000ms"))
        assertTrue(notice.contains("Do not attempt to call their tools"))
        assertTrue(notice.endsWith("]"))
    }

    @Test
    fun truncatesMultilineErrorToFirstLine() {
        val notice = LLMController.buildMcpFailureNotice(
            listOf(server("docs", McpDiscoveryState.Failed, "line1\nline2\nline3"))
        )
        assertTrue(notice.contains("- docs: line1"))
        assertFalse(notice.contains("line2"))
    }

    @Test
    fun missingErrorFallsBackToUnknown() {
        val notice = LLMController.buildMcpFailureNotice(
            listOf(server("docs", McpDiscoveryState.Failed))
        )
        assertTrue(notice.contains("- docs: unknown error"))
    }
}
