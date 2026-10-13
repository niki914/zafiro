package com.niki914.zafiro.app.wakelock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 保护：需求归并的两个分支。MCP 服务单独持锁时只能防 CPU 休眠，不能强制屏幕常亮——
// 这个差异只体现在 WakeDemand.needsScreenOn 一行上，改错后不看代码无法从现象中发现。
class WakeDemandTest {

    @Test
    fun mcpServingAloneHoldsCpuWithoutScreenOn() {
        val demands = AgentWakeLockController.demandsOf(
            agentKeepScreenOn = true,
            anyAgentRunning = false,
            mcpKeepScreenOn = true,
            mcpRunning = true,
        )

        assertEquals(setOf(WakeDemand.McpServing), demands)
        assertTrue("任一需求都应持有 CPU 锁", demands.isNotEmpty())
        assertFalse("MCP 调用不应强制屏幕常亮", demands.any { it.needsScreenOn })
    }

    @Test
    fun agentRunningNeedsScreenOnAndNoDemandReleasesEverything() {
        val agentOnly = AgentWakeLockController.demandsOf(
            agentKeepScreenOn = true,
            anyAgentRunning = true,
            mcpKeepScreenOn = true,
            mcpRunning = false,
        )
        assertEquals(setOf(WakeDemand.AgentRunning), agentOnly)
        assertTrue(agentOnly.any { it.needsScreenOn })

        val none = AgentWakeLockController.demandsOf(
            agentKeepScreenOn = true,
            anyAgentRunning = false,
            mcpKeepScreenOn = true,
            mcpRunning = false,
        )
        assertTrue("无需求时必须释放锁", none.isEmpty())

        val bothOff = AgentWakeLockController.demandsOf(
            agentKeepScreenOn = false,
            anyAgentRunning = true,
            mcpKeepScreenOn = false,
            mcpRunning = true,
        )
        assertTrue("开关关闭时不产生任何需求", bothOff.isEmpty())
    }
}
