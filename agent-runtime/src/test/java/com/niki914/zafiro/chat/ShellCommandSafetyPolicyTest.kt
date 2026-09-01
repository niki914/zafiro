package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.shell.ShellCommandSafetyPolicy
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator
import com.niki914.zafiro.settings.RuntimeEnvironment
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.niki914.zafiro.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode

class ShellCommandSafetyPolicyTest {
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
            ShellCommandSafetyPolicy().evaluate(
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

        val decision = ShellCommandSafetyPolicy().evaluate(
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
                ShellCommandSafetyPolicy().evaluate(command = command, toolName = "terminal")

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
            ShellCommandSafetyPolicy().evaluate(
                command = "sh -c 'pm uninstall com.example.app'",
                toolName = "terminal"
            ).allowed
        )
        assertFalse(
            ShellCommandSafetyPolicy().evaluate(
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
            ShellCommandSafetyPolicy(isUnlocked = { true })
                .evaluate("rm -rf /data/local/tmp/cache", toolName = "terminal")
                .allowed
        )
        assertFalse(
            ShellCommandSafetyPolicy(isUnlocked = { false })
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
            ShellCommandSafetyPolicy().evaluate(
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
        ToolPermissionCoordinator.canRequestUserConfirmation = false

        val decision = ShellCommandSafetyPolicy()
            .evaluate("rm -rf /data/local/tmp/cache", toolName = "terminal")

        assertFalse(decision.allowed)
        assertEquals("CONFIRM_UNAVAILABLE", decision.code)
        assertTrue(decision.reason.contains("cannot request permission"))
    }

    @Test
    fun evaluate_confirmRuleDeniedByUser() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                executionRules = listOf(dangerousRule(enabledMode = ExecutionRuleEnabledMode.CONFIRM))
            )
        )
        ToolPermissionCoordinator.canRequestUserConfirmation = true

        val evaluation = async {
            ShellCommandSafetyPolicy().evaluate(
                "rm -rf /data/local/tmp/cache",
                toolName = "terminal"
            )
        }
        withTimeout(5_000) {
            ToolPermissionCoordinator.pendingConfirmation.first { it != null }
        }
        ToolPermissionCoordinator.respond(
            ToolPermissionCoordinator.pendingConfirmation.value?.id.orEmpty(),
            allowed = false,
        )

        val decision = evaluation.await()
        assertFalse(decision.allowed)
        assertEquals("CONFIRM_DENIED", decision.code)
        assertEquals("危险删改", decision.matchedRuleName)
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
        ToolPermissionCoordinator.canRequestUserConfirmation = true

        val evaluation = async {
            ShellCommandSafetyPolicy().evaluate(
                command = "rm -rf /data/local/tmp/cache; pm uninstall com.example.app",
                toolName = "terminal",
            )
        }
        withTimeout(5_000) {
            ToolPermissionCoordinator.pendingConfirmation.first { it != null }
        }
        ToolPermissionCoordinator.respond(
            ToolPermissionCoordinator.pendingConfirmation.value?.id.orEmpty(),
            allowed = true,
        )

        val decision = evaluation.await()
        assertFalse(decision.allowed)
        assertEquals("RULE_BLOCKED", decision.code)
        assertEquals("uninstall", decision.matchedRuleId)
    }

    @After
    fun resetToolPermissionCoordinator() {
        ToolPermissionCoordinator.canRequestUserConfirmation = false
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
