// 保护：回合即时通知的分派分支（开关、前台可见性防骚扰、宿主隔离），以及通知撤销行为
package com.niki914.zafiro.app.notification

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.TurnOutcome
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.business.application.RuntimeDialogResult
import com.niki914.zafiro.business.notification.AppNotificationChannel
import com.niki914.zafiro.business.notification.NotificationChannelManager
import com.niki914.zafiro.repo.FakeDomainSettingsStore
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.ServiceRegistry
import com.niki914.zafiro.service.installService
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TurnNotificationManagerTest {

    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    private lateinit var context: Context
    private lateinit var fakeChannelManager: FakeNotificationChannelManager
    private lateinit var fakeAppService: FakeApplicationService
    private lateinit var mockActivity: Activity

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fakeChannelManager = FakeNotificationChannelManager(context)
        mockActivity = Robolectric.buildActivity(Activity::class.java).setup().get()
        fakeAppService =
            FakeApplicationService(context as Application, currentActivity = mockActivity)

        TurnNotificationManager.init(context as Application)
        XRepo.installStoreForTest(FakeDomainSettingsStore())
        installService<NotificationChannelManager>(fakeChannelManager)
        installService<ApplicationService>(fakeAppService)
        TurnNotificationManager.resetForTest()
    }

    @After
    fun tearDown() {
        TurnNotificationManager.resetForTest()
        ServiceRegistry.clearForTest()
    }

    @Test
    fun onTurnEvent_whenDisabled_doesNotPostNotification() = runTest {
        XRepo.setTurnNotificationEnabled(false)

        TurnNotificationManager.onTurnEvent(
            conversationId = "conv-1",
            state = AgentState.Idle(lastOutcome = TurnOutcome.Completed, lastText = "Finished"),
        )

        assertTrue(fakeChannelManager.postedNotifications.isEmpty())
    }

    @Test
    fun onTurnEvent_whenVisibleInForeground_skipsNotification() = runTest {
        XRepo.setTurnNotificationEnabled(true)
        fakeAppService.currentActivity = mockActivity
        TurnNotificationManager.visibleConversationId = "conv-1"

        TurnNotificationManager.onTurnEvent(
            conversationId = "conv-1",
            state = AgentState.Idle(lastOutcome = TurnOutcome.Completed, lastText = "Finished"),
        )

        assertTrue(fakeChannelManager.postedNotifications.isEmpty())
    }

    @Test
    fun onTurnEvent_whenVisibleConversationMatchesButAppInBackground_postsNotification() = runTest {
        XRepo.setTurnNotificationEnabled(true)
        fakeAppService.currentActivity = null
        TurnNotificationManager.visibleConversationId = "conv-1"

        TurnNotificationManager.onTurnEvent(
            conversationId = "conv-1",
            state = AgentState.Idle(lastOutcome = TurnOutcome.Completed, lastText = "Finished"),
        )

        assertEquals(1, fakeChannelManager.postedNotifications.size)
        assertEquals(
            AppNotificationChannel.Alerts,
            fakeChannelManager.postedNotifications.first().channel
        )
    }

    @Test
    fun onTurnEvent_whenOtherConversationActive_postsNotification() = runTest {
        XRepo.setTurnNotificationEnabled(true)
        fakeAppService.currentActivity = mockActivity
        TurnNotificationManager.visibleConversationId = "conv-2"

        TurnNotificationManager.onTurnEvent(
            conversationId = "conv-1",
            state = AgentState.Idle(lastOutcome = TurnOutcome.Completed, lastText = "Finished"),
        )

        assertEquals(1, fakeChannelManager.postedNotifications.size)
        val post = fakeChannelManager.postedNotifications.first()
        assertEquals("conv-1".hashCode(), post.notificationId)
        assertEquals(AppNotificationChannel.Alerts, post.channel)
    }

    @Test
    fun onTurnEvent_whenTriggeredByHost_skipsNotification() = runTest {
        XRepo.setTurnNotificationEnabled(true)
        fakeAppService.currentActivity = null
        TurnNotificationManager.markHostTurnStarted("conv-host")

        TurnNotificationManager.onTurnEvent(
            conversationId = "conv-host",
            state = AgentState.Idle(lastOutcome = TurnOutcome.Completed, lastText = "Finished"),
        )

        assertTrue(fakeChannelManager.postedNotifications.isEmpty())
    }

    @Test
    fun cancelNotification_callsChannelManagerCancel() {
        TurnNotificationManager.cancelNotification("conv-1")
        assertEquals(listOf("conv-1".hashCode()), fakeChannelManager.cancelledNotifications)
    }

    private class FakeApplicationService(
        private val app: Application,
        var currentActivity: Activity? = null,
    ) : ApplicationService {
        override fun getApplication(): Application = app
        override fun getActivity(): Activity? = currentActivity
        override suspend fun awaitForeground(timeoutMs: Long): Activity? = currentActivity
        override suspend fun awaitNextResume(timeoutMs: Long): Boolean = true
        override suspend fun requestRuntimePermission(permission: String): RuntimeDialogResult =
            RuntimeDialogResult.GRANTED

        override fun installPermissionLauncher(launcher: ActivityResultLauncher<String>) = Unit
        override fun onRuntimePermissionResult(granted: Boolean) = Unit
    }

    private class FakeNotificationChannelManager(
        private val appContext: Context,
    ) : NotificationChannelManager {
        data class PostCall(val channel: AppNotificationChannel, val notificationId: Int)

        val postedNotifications = mutableListOf<PostCall>()
        val cancelledNotifications = mutableListOf<Int>()

        override fun isNotificationPermissionGranted(): Boolean = true

        override suspend fun post(
            channel: AppNotificationChannel,
            notificationId: Int,
            block: NotificationCompat.Builder.() -> Unit,
        ): Boolean {
            postedNotifications.add(PostCall(channel, notificationId))
            return true
        }

        override fun buildNotification(
            channel: AppNotificationChannel,
            block: NotificationCompat.Builder.() -> Unit,
        ): Notification {
            val builder = NotificationCompat.Builder(appContext, channel.id)
            builder.block()
            return builder.build()
        }

        override fun cancel(notificationId: Int) {
            cancelledNotifications.add(notificationId)
        }
    }
}
