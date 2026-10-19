// 保护：常驻通知在各运行阶段（生成/思考/工具/审批/失败/待命）的文案、操作按钮、底色与整卡着色标记映射，避免错误配置导致用户无法操作或界面混乱。
package com.niki914.zafiro.app.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.TurnOutcome
import com.niki914.zafiro.app.R
import com.niki914.zafiro.business.notification.NotificationChannelManagerImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.niki914.zafiro.remoteview.R as RemoteViewR

@RunWith(RobolectricTestRunner::class)
class ResidentNotificationBuilderTest {

    private lateinit var context: Context
    private lateinit var channelManager: NotificationChannelManagerImpl

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        channelManager = NotificationChannelManagerImpl(context = context)
    }

    @Test
    fun resolveTitleResId_mapsAllStatesCorrectly() {
        assertEquals(
            R.string.agent_resident_title_idle,
            ResidentNotificationBuilder.resolveTitleResId(AgentState.Idle())
        )
        assertEquals(
            R.string.agent_resident_title_idle,
            ResidentNotificationBuilder.resolveTitleResId(
                AgentState.Idle(lastOutcome = TurnOutcome.Completed, lastText = "done"),
            ),
        )
        assertEquals(
            R.string.agent_resident_title_failed,
            ResidentNotificationBuilder.resolveTitleResId(
                AgentState.Idle(lastOutcome = TurnOutcome.Failed, lastText = "partial"),
            ),
        )
        assertEquals(
            R.string.agent_resident_title_interrupted,
            ResidentNotificationBuilder.resolveTitleResId(
                AgentState.Idle(lastOutcome = TurnOutcome.Interrupted, lastText = "half"),
            ),
        )
        assertEquals(
            R.string.agent_resident_title_thinking,
            ResidentNotificationBuilder.resolveTitleResId(AgentState.Thinking(text = null))
        )
        assertEquals(
            R.string.agent_resident_title_generating,
            ResidentNotificationBuilder.resolveTitleResId(AgentState.Generating(text = null))
        )
        assertEquals(
            R.string.agent_resident_title_tool_running,
            ResidentNotificationBuilder.resolveTitleResId(
                AgentState.ToolRunning(toolName = "bash", label = "bash", argumentsJson = null),
            ),
        )
        assertEquals(
            R.string.agent_resident_title_waiting_approval,
            ResidentNotificationBuilder.resolveTitleResId(
                AgentState.WaitingApproval(
                    ApprovalRequest.ToolExecution("terminal", "ls", "RULE", "ls")
                ),
            ),
        )
        assertEquals(
            R.string.agent_resident_title_stopping,
            ResidentNotificationBuilder.resolveTitleResId(AgentState.Stopping)
        )
    }

    @Test
    fun resolveBody_usesTextWhenPresent() {
        val body = ResidentNotificationBuilder.resolveBody(
            AgentState.Generating(text = "Streaming markdown response..."),
            context,
        )
        assertEquals("Streaming markdown response...", body)
    }

    @Test
    fun resolveBody_returnsNullWhenBlank() {
        val body =
            ResidentNotificationBuilder.resolveBody(AgentState.Idle(lastOutcome = null), context)
        assertNull(body)
    }

    @Test
    fun resolveBody_idleUsesLastText() {
        assertEquals(
            "最终回答",
            ResidentNotificationBuilder.resolveBody(
                AgentState.Idle(lastOutcome = TurnOutcome.Completed, lastText = "最终回答"),
                context,
            ),
        )
        assertEquals(
            "说到一半",
            ResidentNotificationBuilder.resolveBody(
                AgentState.Idle(lastOutcome = TurnOutcome.Interrupted, lastText = "说到一半"),
                context,
            ),
        )
        assertNull(
            ResidentNotificationBuilder.resolveBody(
                AgentState.Idle(lastOutcome = TurnOutcome.Completed, lastText = null),
                context,
            ),
        )
    }

    @Test
    fun resolveBody_truncatesLongTextWithEllipsis() {
        val long = "字".repeat(200)
        val body = ResidentNotificationBuilder.resolveBody(
            AgentState.Idle(lastOutcome = TurnOutcome.Completed, lastText = long),
            context,
        )
        assertEquals("字".repeat(119) + "…", body)
    }

    @Test
    fun resolveBody_waitingApprovalAppendsMatchedPattern() {
        assertEquals(
            "rm -rf /tmp · " + context.getString(R.string.agent_resident_body_matched_pattern, "rm -rf"),
            ResidentNotificationBuilder.resolveBody(
                AgentState.WaitingApproval(
                    ApprovalRequest.ToolExecution(
                        "terminal",
                        "rm -rf /tmp",
                        "RULE",
                        "rm -rf",
                    )
                ),
                context,
            ),
        )
        // 命令为空时回落 toolName，命中条目仍要带上
        assertEquals(
            "terminal · " + context.getString(R.string.agent_resident_body_matched_pattern, "su"),
            ResidentNotificationBuilder.resolveBody(
                AgentState.WaitingApproval(
                    ApprovalRequest.ToolExecution("terminal", "  ", "RULE", "su")
                ),
                context,
            ),
        )
    }

    @Test
    fun resolveBody_screenControlConsentUsesTitle() {
        assertEquals(
            context.getString(R.string.screen_control_consent_title),
            ResidentNotificationBuilder.resolveBody(
                AgentState.WaitingApproval(ApprovalRequest.ScreenControlConsent),
                context,
            ),
        )
    }

    @Test
    fun resolveBody_toolRunningUsesRunningTextFromFloatingBall() {
        assertEquals(
            context.getString(
                RemoteViewR.string.floating_ball_tool_running,
                context.getString(R.string.ui_tool_display_shell),
            ),
            ResidentNotificationBuilder.resolveBody(
                AgentState.ToolRunning(
                    toolName = "shell",
                    label = "shell",
                    argumentsJson = null
                ),
                context,
            ),
        )
    }

    @Test
    fun resolveBody_stoppingHasNoBody() {
        assertNull(ResidentNotificationBuilder.resolveBody(AgentState.Stopping, context))
    }

    @Test
    fun build_forGenerating_includesStopAction() {
        val dummyIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent("ACTION_STOP"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val status = AgentState.Generating(text = "Generating response")
        val notification = ResidentNotificationBuilder.build(
            context = context,
            channelManager = channelManager,
            status = status,
            stopIntent = dummyIntent,
        )

        assertNotNull(notification)
        assertEquals(1, notification.actions?.size)
        assertEquals(
            context.getString(R.string.agent_resident_action_stop),
            notification.actions[0].title.toString()
        )
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun build_forWaitingApproval_includesApproveAndDeclineActions() {
        val approveIntent = PendingIntent.getBroadcast(
            context,
            2,
            Intent("ACTION_APPROVE"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val declineIntent = PendingIntent.getBroadcast(
            context,
            3,
            Intent("ACTION_DECLINE"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val status = AgentState.WaitingApproval(
            ApprovalRequest.ToolExecution(
                toolName = "execute_command",
                command = "ls /tmp",
                ruleName = "RULE",
                matchedPattern = "ls",
            ),
        )
        val notification = ResidentNotificationBuilder.build(
            context = context,
            channelManager = channelManager,
            status = status,
            approveIntent = approveIntent,
            declineIntent = declineIntent,
        )

        assertNotNull(notification)
        assertEquals(2, notification.actions?.size)
        assertEquals(
            context.getString(R.string.agent_resident_action_approve),
            notification.actions[0].title.toString()
        )
        assertEquals(
            context.getString(R.string.agent_resident_action_decline),
            notification.actions[1].title.toString()
        )
    }

    @Test
    fun build_forIdle_showsLastTextWithNoActions() {
        val status = AgentState.Idle(lastOutcome = TurnOutcome.Completed, lastText = "最终回答")
        val notification = ResidentNotificationBuilder.build(
            context = context,
            channelManager = channelManager,
            status = status,
        )

        assertNotNull(notification)
        assertEquals(
            "最终回答",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        )
        assertTrue(notification.actions == null || notification.actions.isEmpty())
    }

    @Test
    fun build_forStopping_hasNoActions() {
        val dummyIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent("ACTION_STOP"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val status = AgentState.Stopping
        val notification = ResidentNotificationBuilder.build(
            context = context,
            channelManager = channelManager,
            status = status,
            stopIntent = dummyIntent,
        )

        assertNotNull(notification)
        assertTrue(notification.actions == null || notification.actions.isEmpty())
    }

    @Test
    fun resolveColor_mapsStatesToExpectedColors() {
        assertEquals(
            ResidentNotificationBuilder.COLOR_RUNNING,
            ResidentNotificationBuilder.resolveColor(AgentState.Generating(text = null)),
        )
        assertEquals(
            ResidentNotificationBuilder.COLOR_RUNNING,
            ResidentNotificationBuilder.resolveColor(AgentState.Thinking(text = null)),
        )
        assertEquals(
            ResidentNotificationBuilder.COLOR_RUNNING,
            ResidentNotificationBuilder.resolveColor(
                AgentState.ToolRunning(toolName = "bash", label = "bash", argumentsJson = null),
            ),
        )
        assertEquals(
            ResidentNotificationBuilder.COLOR_RUNNING,
            ResidentNotificationBuilder.resolveColor(AgentState.Stopping),
        )
        assertEquals(
            ResidentNotificationBuilder.COLOR_WAITING_APPROVAL,
            ResidentNotificationBuilder.resolveColor(
                AgentState.WaitingApproval(ApprovalRequest.ToolExecution("cmd", "echo 1", "RULE", "echo")),
            ),
        )
        assertEquals(
            ResidentNotificationBuilder.COLOR_FAILED,
            ResidentNotificationBuilder.resolveColor(AgentState.Idle(lastOutcome = TurnOutcome.Failed)),
        )
        assertEquals(
            ResidentNotificationBuilder.COLOR_IDLE,
            ResidentNotificationBuilder.resolveColor(AgentState.Idle(lastOutcome = TurnOutcome.Completed)),
        )
        assertEquals(
            ResidentNotificationBuilder.COLOR_IDLE,
            ResidentNotificationBuilder.resolveColor(AgentState.Idle(lastOutcome = null)),
        )
        assertEquals(
            ResidentNotificationBuilder.COLOR_IDLE,
            ResidentNotificationBuilder.resolveColor(AgentState.Idle(lastOutcome = TurnOutcome.Interrupted)),
        )
    }

    @Test
    fun build_appliesColorAndColorizedFlag() {
        val runningStatus = AgentState.Generating(text = "Generating response")
        val notification = ResidentNotificationBuilder.build(
            context = context,
            channelManager = channelManager,
            status = runningStatus,
        )

        assertEquals(ResidentNotificationBuilder.COLOR_RUNNING, notification.color)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_COLORIZED))
    }
}
