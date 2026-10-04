package com.niki914.zafiro.chat

import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.chat.agentic.ToolExecutionPreflight
import com.niki914.zafiro.chat.agentic.ToolExecutionPreflight.Companion.WATCHED_PREFIXES
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionResult
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.settings.RuntimeEnvironment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import com.niki914.zafiro.chat.util.SilentLoggerRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.niki914.zafiro.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode

class ToolExecutionPreflightTest {
    @get:Rule
    val silentLogger = SilentLoggerRule()

    @After
    fun tearDown() {
        RuntimeEnvironment.clearForTest()
    }

    @Test
    fun evaluate_allowsBenignCommand() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(executionRules = listOf(dangerousRule()))
        )

        assertTrue(
            ToolExecutionPreflight().evaluate(
                command = "getprop ro.product.model",
                toolName = "terminal"
            ).allowed
        )
    }

    @Test
    fun evaluate_blocksCommandWithRuleDetails() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(executionRules = listOf(dangerousRule()))
        )

        val decision = ToolExecutionPreflight().evaluate(
            command = "rm -rf /data/local/tmp/cache",
            toolName = "terminal"
        )

        assertFalse(decision.allowed)
        assertEquals("RULE_BLOCKED", decision.code)
        assertEquals("dangerous-delete", decision.matchedRuleId)
        assertEquals("危险删改", decision.matchedRuleName)
        assertEquals("\\brm\\s+-rf\\b", decision.matchedPattern)
        assertTrue(decision.reason.contains("危险删改"))
    }

    @Test
    fun evaluate_blocksRmLongFlagExecutionRulePattern() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(executionRules = listOf(dangerousRule()))
        )

        listOf(
            "rm --recursive --force /data/local/tmp/cache",
            "rm --force --recursive /data/local/tmp/cache",
            "rm -f -r /data/local/tmp/cache",
            "rm -r --force /data/local/tmp/cache",
            "rm --recursive -f /data/local/tmp/cache",
        ).forEach { command ->
            val decision =
                ToolExecutionPreflight().evaluate(command = command, toolName = "terminal")

            assertFalse(decision.allowed)
            assertEquals("RULE_BLOCKED", decision.code)
            assertTrue(decision.matchedPattern.orEmpty().contains("\\brm\\s+"))
        }
    }

    @Test
    fun evaluate_blocksNestedShellPayloads() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(executionRules = listOf(uninstallRule()))
        )

        assertFalse(
            ToolExecutionPreflight().evaluate(
                command = "sh -c 'pm uninstall com.example.app'",
                toolName = "terminal"
            ).allowed
        )
        assertFalse(
            ToolExecutionPreflight().evaluate(
                command = "eval 'cmd package uninstall com.example.app'",
                toolName = "terminal"
            ).allowed
        )
    }

    @Test
    fun evaluate_lockedOnlyRuleOnlyBlocksWhenDeviceLocked() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                executionRules = listOf(
                    dangerousRule(enabledMode = ExecutionRuleEnabledMode.LOCKED_ONLY)
                )
            )
        )

        assertTrue(
            ToolExecutionPreflight(isUnlocked = { true })
                .evaluate("rm -rf /data/local/tmp/cache", toolName = "terminal")
                .allowed
        )
        assertFalse(
            ToolExecutionPreflight(isUnlocked = { false })
                .evaluate("rm -rf /data/local/tmp/cache", toolName = "terminal")
                .allowed
        )
    }

    @Test
    fun evaluate_invalidRegexFallsBackToKeywordContains() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                executionRules = listOf(
                    ExecutionRule(
                        id = "keyword",
                        name = "关键字",
                        enabledMode = ExecutionRuleEnabledMode.ALWAYS,
                        patterns = listOf("[broken"),
                    )
                )
            )
        )

        assertFalse(
            ToolExecutionPreflight().evaluate(
                command = "echo [broken",
                toolName = "terminal"
            ).allowed
        )
    }

    @Test
    fun evaluate_confirmRuleDeniedWithoutUiChannel() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                executionRules = listOf(dangerousRule(enabledMode = ExecutionRuleEnabledMode.CONFIRM))
            )
        )
        val agentControl = FakeAgentControl(decision = ApprovalDecision.Abstain)

        val decision = ToolExecutionPreflight(agentControlProvider = { agentControl })
            .evaluate("rm -rf /data/local/tmp/cache", toolName = "terminal")

        assertFalse(decision.allowed)
        assertEquals("CONFIRM_UNAVAILABLE", decision.code)
    }

    @Test
    fun evaluate_confirmRuleDeniedByUser() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                executionRules = listOf(dangerousRule(enabledMode = ExecutionRuleEnabledMode.CONFIRM))
            )
        )
        var receivedRequest: ApprovalRequest? = null
        val agentControl = FakeAgentControl(
            decision = ApprovalDecision.Deny,
            onRequest = { receivedRequest = it }
        )

        val decision = ToolExecutionPreflight(agentControlProvider = { agentControl })
            .evaluate(
                "rm -rf /data/local/tmp/cache",
                toolName = "terminal"
            )

        assertFalse(decision.allowed)
        assertEquals("CONFIRM_DENIED", decision.code)
        assertEquals("危险删改", decision.matchedRuleName)
        assertTrue(receivedRequest is ApprovalRequest.ToolExecution)
        val toolReq = receivedRequest as ApprovalRequest.ToolExecution
        assertEquals("terminal", toolReq.toolName)
        assertEquals("rm -rf /data/local/tmp/cache", toolReq.command)
    }

    @Test
    fun evaluate_confirmRuleAllowedStillBlockedByLaterRule() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                executionRules = listOf(
                    dangerousRule(enabledMode = ExecutionRuleEnabledMode.CONFIRM),
                    uninstallRule(),
                )
            )
        )
        val agentControl = FakeAgentControl(decision = ApprovalDecision.Allow)

        val decision = ToolExecutionPreflight(agentControlProvider = { agentControl })
            .evaluate(
                command = "rm -rf /data/local/tmp/cache; pm uninstall com.example.app",
                toolName = "terminal",
            )

        assertFalse(decision.allowed)
        assertEquals("RULE_BLOCKED", decision.code)
        assertEquals("uninstall", decision.matchedRuleId)
    }

    @Test
    fun evaluate_confirmRuleAllowedWhenApproverAllows() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                executionRules = listOf(dangerousRule(enabledMode = ExecutionRuleEnabledMode.CONFIRM))
            )
        )
        var receivedRequest: ApprovalRequest? = null
        val agentControl = FakeAgentControl(
            decision = ApprovalDecision.Allow,
            onRequest = { receivedRequest = it }
        )

        val decision = ToolExecutionPreflight(agentControlProvider = { agentControl })
            .evaluate(
                "rm -rf /data/local/tmp/cache",
                toolName = "terminal"
            )

        assertTrue(decision.allowed)
        assertEquals("OK", decision.code)
        assertTrue(receivedRequest is ApprovalRequest.ToolExecution)
    }

    // ── ensurePathAccess：提取 + 申请，拒绝照常放行 ────────────────────────

    @Test
    fun extractExternalPathIntents_findsWatchedPaths() = runTest {
        val sandbox = setOf("/data/data/com.niki914.zafiro/files", "/data/user/0/com.niki914.zafiro")
        val preflight = ToolExecutionPreflight()

        assertEquals(
            setOf("/sdcard/DCIM/photo.jpg"),
            preflight.extractExternalPathIntents(
                "cat /sdcard/DCIM/photo.jpg > out.txt", sandbox
            )
        )
        assertEquals(
            setOf("/storage/emulated/0/Download/a.pdf"),
            preflight.extractExternalPathIntents(
                "open('/storage/emulated/0/Download/a.pdf')", sandbox
            )
        )
        // 沙箱两种别名都排除；沙箱外的 /data 路径仍命中（勿缺勿滥）
        assertEquals(
            setOf("/data/local/tmp/x"),
            preflight.extractExternalPathIntents(
                "cat /data/data/com.niki914.zafiro/files/log.txt /data/user/0/com.niki914.zafiro/c/x /data/local/tmp/x",
                sandbox
            )
        )
        // 相对路径、URL、分段名（src/main）不算绝对路径
        assertEquals(
            emptySet<String>(),
            preflight.extractExternalPathIntents(
                "https://example.com/a/b and src/main/kotlin and sdcard/DCIM/x", sandbox
            )
        )
        // 前缀落在段边界上：/storageX 不命中 /storage
        assertEquals(
            emptySet<String>(),
            preflight.extractExternalPathIntents("cat /storageX/y", sandbox)
        )
    }

    @Test
    fun ensurePathAccess_noIntent_skipsPermissionEntirely() = runTest {
        val permissions = FakePermissionManager()
        val preflight = ToolExecutionPreflight(
            permissionsProvider = { permissions },
            sandboxRootsProvider = { setOf("/data/data/pkg/files") },
        )

        preflight.ensurePathAccess("ls -la")
        preflight.ensurePathAccess("cat /data/data/pkg/files/tool_output/a.log")

        assertEquals(0, permissions.statusCalls)
        assertEquals(0, permissions.requestCalls)
    }

    @Test
    fun ensurePathAccess_intentAndGranted_skipsRequest() = runTest {
        val permissions = FakePermissionManager(status = PermissionState.GRANTED)
        val preflight = ToolExecutionPreflight(
            permissionsProvider = { permissions },
            sandboxRootsProvider = { emptySet() },
        )

        preflight.ensurePathAccess("cat /sdcard/x")

        assertEquals(1, permissions.statusCalls)
        assertEquals(0, permissions.requestCalls)
    }

    @Test
    fun ensurePathAccess_notGranted_requestsOnce_andProceedsOnDenial() = runTest {
        val permissions = FakePermissionManager(status = PermissionState.DENIED_BY_USER)
        val preflight = ToolExecutionPreflight(
            permissionsProvider = { permissions },
            sandboxRootsProvider = { emptySet() },
        )

        // 被拒绝也不抛、不返回失败：照常放行
        preflight.ensurePathAccess("cat /sdcard/x")

        assertEquals(1, permissions.requestCalls)
    }

    @Test
    fun ensurePathAccess_requestThrows_swallowsAndProceeds() = runTest {
        val permissions = FakePermissionManager(status = PermissionState.DENIED_BY_USER, throwOnRequest = true)
        val preflight = ToolExecutionPreflight(
            permissionsProvider = { permissions },
            sandboxRootsProvider = { emptySet() },
        )

        preflight.ensurePathAccess("cat /sdcard/x")
    }

    @Test
    fun ensurePathAccess_noPermissionManager_noop() = runTest {
        val preflight = ToolExecutionPreflight(
            permissionsProvider = { null },
            sandboxRootsProvider = { emptySet() },
        )

        preflight.ensurePathAccess("cat /sdcard/x")
    }

    private fun dangerousRule(
        enabledMode: ExecutionRuleEnabledMode = ExecutionRuleEnabledMode.ALWAYS,
    ): ExecutionRule {
        return ExecutionRule(
            id = "dangerous-delete",
            name = "危险删改",
            enabledMode = enabledMode,
            patterns = listOf(
                "\\brm\\s+-rf\\b",
                "\\brm\\s+-(?=[^\\s]*r)(?=[^\\s]*f)[^\\s]*\\b",
                "\\brm\\s+-r\\s+-f\\b",
                "\\brm\\s+(?=[^\\n]*--recursive\\b)(?=[^\\n]*--force\\b)[^\\n]*",
                "\\brm\\s+(?=[^\\n]*-(?:[^\\s-]*r[^\\s-]*|-[^-\\s]*recursive)\\b)(?=[^\\n]*-(?:[^\\s-]*f[^\\s-]*|-[^-\\s]*force)\\b)[^\\n]*",
            ),
        )
    }

    private fun uninstallRule(): ExecutionRule {
        return ExecutionRule(
            id = "uninstall",
            name = "卸载相关",
            enabledMode = ExecutionRuleEnabledMode.ALWAYS,
            patterns = listOf("\\bpm\\s+uninstall\\b", "\\bcmd\\s+package\\s+uninstall\\b"),
        )
    }
}

private class FakeAgentControl(
    var decision: ApprovalDecision = ApprovalDecision.Abstain,
    val onRequest: ((ApprovalRequest) -> Unit)? = null,
) : AgentControl {
    override val status = MutableStateFlow<AgentState>(AgentState.Idle())
    override fun stop() {}
    override fun addApprover(approver: Approver) {}
    override fun removeApprover(approver: Approver) {}
    override suspend fun decideApproval(request: ApprovalRequest): ApprovalDecision {
        onRequest?.invoke(request)
        return decision
    }
}

private class FakePermissionManager(
    private val status: PermissionState = PermissionState.GRANTED,
    private val throwOnRequest: Boolean = false,
) : PermissionManager {
    var statusCalls = 0
        private set
    var requestCalls = 0
        private set

    override fun status(permission: Permission): PermissionState {
        statusCalls++
        return status
    }

    override suspend fun request(permission: Permission): PermissionResult {
        requestCalls++
        if (throwOnRequest) throw IllegalStateException("boom")
        return PermissionResult(permission, PermissionState.DENIED_BY_USER, emptyList())
    }

    override suspend fun request(permission: Permission, vararg channels: com.niki914.zafiro.business.permission.Channel): PermissionResult =
        request(permission)

    override fun applyScope(vararg channels: com.niki914.zafiro.business.permission.Channel): com.niki914.zafiro.business.permission.PermissionScope =
        throw UnsupportedOperationException()
}

