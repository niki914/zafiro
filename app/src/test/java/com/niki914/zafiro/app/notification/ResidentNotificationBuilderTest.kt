package com.niki914.zafiro.app.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.TurnOutcome
import com.niki914.zafiro.app.R
import com.niki914.zafiro.business.notification.AppNotificationChannel
import com.niki914.zafiro.business.notification.NotificationChannelManagerImpl
import com.niki914.zafiro.remoteview.R as RemoteViewR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
        assertEquals(R.string.agent_resident_title_idle, ResidentNotificationBuilder.resolveTitleResId(AgentState.Idle()))
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
        assertEquals(R.string.agent_resident_title_thinking, ResidentNotificationBuilder.resolveTitleResId(AgentState.Thinking(text = null)))
        assertEquals(R.string.agent_resident_title_generating, ResidentNotificationBuilder.resolveTitleResId(AgentState.Generating(text = null)))
        assertEquals(
            R.string.agent_resident_title_tool_running,
            ResidentNotificationBuilder.resolveTitleResId(
                AgentState.ToolRunning(toolName = "bash", label = "bash", argumentsJson = null),
            ),
        )
        assertEquals(
            R.string.agent_resident_title_waiting_approval,
            ResidentNotificationBuilder.resolveTitleResId(
                AgentState.WaitingApproval(ApprovalRequest.ToolExecution("terminal", "ls", "RULE")),
            ),
        )
        assertEquals(R.string.agent_resident_title_stopping, ResidentNotificationBuilder.resolveTitleResId(AgentState.Stopping))
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
        val body = ResidentNotificationBuilder.resolveBody(AgentState.Idle(lastOutcome = null), context)
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
    fun resolveBody_waitingApprovalUsesCommandFallback() {
        assertEquals(
            "rm -rf /tmp",
            ResidentNotificationBuilder.resolveBody(
                AgentState.WaitingApproval(ApprovalRequest.ToolExecution("terminal", "rm -rf /tmp", "RULE")),
                context,
            ),
        )
        assertEquals(
            "terminal",
            ResidentNotificationBuilder.resolveBody(
                AgentState.WaitingApproval(ApprovalRequest.ToolExecution("terminal", "  ", "RULE")),
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
                context.getString(R.string.ui_tool_display_terminal),
            ),
            ResidentNotificationBuilder.resolveBody(
                AgentState.ToolRunning(toolName = "terminal", label = "terminal", argumentsJson = null),
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
        assertEquals(context.getString(R.string.agent_resident_action_stop), notification.actions[0].title.toString())
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun build_forThinking_includesStopAction() {
        val dummyIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent("ACTION_STOP"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val status = AgentState.Thinking(text = "Reasoning...")
        val notification = ResidentNotificationBuilder.build(
            context = context,
            channelManager = channelManager,
            status = status,
            stopIntent = dummyIntent,
        )

        assertNotNull(notification)
        assertEquals(1, notification.actions?.size)
        assertEquals(context.getString(R.string.agent_resident_action_stop), notification.actions[0].title.toString())
    }

    @Test
    fun build_forToolRunning_includesStopAction() {
        val dummyIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent("ACTION_STOP"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val status = AgentState.ToolRunning(toolName = "bash", label = "bash", argumentsJson = null)
        val notification = ResidentNotificationBuilder.build(
            context = context,
            channelManager = channelManager,
            status = status,
            stopIntent = dummyIntent,
        )

        assertNotNull(notification)
        assertEquals(1, notification.actions?.size)
        assertEquals(context.getString(R.string.agent_resident_action_stop), notification.actions[0].title.toString())
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
            ApprovalRequest.ToolExecution(toolName = "execute_command", command = "ls /tmp", ruleName = "RULE"),
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
        assertEquals(context.getString(R.string.agent_resident_action_approve), notification.actions[0].title.toString())
        assertEquals(context.getString(R.string.agent_resident_action_decline), notification.actions[1].title.toString())
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
        assertEquals("最终回答", notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertTrue(notification.actions == null || notification.actions.isEmpty())
    }

    @Test
    fun build_forFreshIdle_hasNoBody() {
        val status = AgentState.Idle()
        val notification = ResidentNotificationBuilder.build(
            context = context,
            channelManager = channelManager,
            status = status,
        )

        assertNotNull(notification)
        assertNull(notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
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
}
