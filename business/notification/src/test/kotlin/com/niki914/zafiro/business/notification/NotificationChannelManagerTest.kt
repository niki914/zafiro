package com.niki914.zafiro.business.notification

import android.app.NotificationManager
import android.content.Context
import com.niki914.zafiro.business.permission.Channel
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionResult
import com.niki914.zafiro.business.permission.PermissionScope
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.service.ServiceRegistry
import com.niki914.zafiro.service.installService
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationChannelManagerTest {

    @After
    fun tearDown() {
        ServiceRegistry.clearForTest()
    }

    @Test
    fun appNotificationChannel_definesCorrectChannels() {
        val alerts = AppNotificationChannel.Alerts
        assertEquals("zafiro_alerts", alerts.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, alerts.importance)
        assertEquals(R.string.notification_channel_alerts, alerts.channelNameResId)

        val resident = AppNotificationChannel.Resident
        assertEquals("zafiro_resident", resident.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, resident.importance)
        assertEquals(R.string.notification_channel_resident, resident.channelNameResId)
    }

    @Test
    fun isNotificationPermissionGranted_readsPermissionService() {
        installService<PermissionManager>(FakePermissionManager(PermissionState.GRANTED))
        val manager = NotificationChannelManagerImpl(context = FakeContext())
        assertTrue(manager.isNotificationPermissionGranted())

        ServiceRegistry.clearForTest()
        installService<PermissionManager>(FakePermissionManager(PermissionState.DENIED_BY_USER))
        val denied = NotificationChannelManagerImpl(context = FakeContext())
        assertFalse(denied.isNotificationPermissionGranted())
    }

    @Test
    fun post_shortCircuitsWhenPermissionDenied() = runTest {
        installService<PermissionManager>(FakePermissionManager(PermissionState.DENIED_BY_USER))
        var builderBlockExecuted = false
        val deniedManager = NotificationChannelManagerImpl(context = FakeContext())

        val result = deniedManager.post(AppNotificationChannel.Alerts, 1001) {
            builderBlockExecuted = true
        }

        assertFalse("When permission is denied, post must return false", result)
        assertFalse("Builder block must not be executed when permission is denied", builderBlockExecuted)
    }

    private class FakePermissionManager(
        private val state: PermissionState,
    ) : PermissionManager {
        override fun status(permission: Permission): PermissionState = state
        override suspend fun request(permission: Permission): PermissionResult =
            PermissionResult(permission, state, emptyList())
        override suspend fun request(permission: Permission, vararg channels: Channel): PermissionResult =
            PermissionResult(permission, state, emptyList())
        override fun applyScope(vararg channels: Channel): PermissionScope =
            throw UnsupportedOperationException("not needed in test")
    }

    private class FakeContext : android.content.ContextWrapper(null)
}
