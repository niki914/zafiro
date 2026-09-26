package com.niki914.zafiro.app.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.niki914.zafiro.api.model.AgentPhase
import com.niki914.zafiro.api.model.AgentStatus
import com.niki914.zafiro.app.R
import com.niki914.zafiro.business.notification.AppNotificationChannel
import com.niki914.zafiro.business.notification.NotificationChannelManagerImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        channelManager = NotificationChannelManagerImpl(
            context = context,
            isPermissionGrantedProvider = { true },
        )
    }

    @Test
    fun resolveTitleResId_mapsAllPhasesCorrectly() {
        assertEquals(R.string.agent_resident_title_idle, ResidentNotificationBuilder.resolveTitleResId(AgentPhase.Idle))
        assertEquals(R.string.agent_resident_title_thinking, ResidentNotificationBuilder.resolveTitleResId(AgentPhase.Thinking))
        assertEquals(R.string.agent_resident_title_generating, ResidentNotificationBuilder.resolveTitleResId(AgentPhase.Generating))
        assertEquals(R.string.agent_resident_title_tool_running, ResidentNotificationBuilder.resolveTitleResId(AgentPhase.ToolRunning))
        assertEquals(R.string.agent_resident_title_waiting_approval, ResidentNotificationBuilder.resolveTitleResId(AgentPhase.WaitingApproval))
        assertEquals(R.string.agent_resident_title_stopping, ResidentNotificationBuilder.resolveTitleResId(AgentPhase.Stopping))
    }

    @Test
    fun resolveBody_usesPreviewWhenPresent() {
        val status = AgentStatus(phase = AgentPhase.Generating, preview = "Streaming markdown response...")
        val body = ResidentNotificationBuilder.resolveBody(status)
        assertEquals("Streaming markdown response...", body)
    }

    @Test
    fun resolveBody_returnsNullWhenBlank() {
        val status = AgentStatus(phase = AgentPhase.Idle, preview = "")
        val body = ResidentNotificationBuilder.resolveBody(status)
        org.junit.Assert.assertNull(body)
    }

    @Test
    fun build_forGenerating_includesStopAction() {
        val dummyIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent("ACTION_STOP"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val status = AgentStatus(phase = AgentPhase.Generating, preview = "Generating response")
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
        val status = AgentStatus(phase = AgentPhase.Thinking, preview = "Reasoning...")
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
        val status = AgentStatus(phase = AgentPhase.ToolRunning, preview = "Executing bash command")
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
        val status = AgentStatus(phase = AgentPhase.WaitingApproval, preview = "Tool execute_command needs confirmation")
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
    fun build_forIdle_hasNoActions() {
        val status = AgentStatus(phase = AgentPhase.Idle, preview = "")
        val notification = ResidentNotificationBuilder.build(
            context = context,
            channelManager = channelManager,
            status = status,
        )

        assertNotNull(notification)
        org.junit.Assert.assertNull(notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
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
        val status = AgentStatus(phase = AgentPhase.Stopping, preview = "Cancelling turn...")
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
