package com.niki914.zafiro.business.notification

import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationChannelManagerTest {

    @Test
    fun appNotificationChannel_definesCorrectChannels() {
        val alerts = AppNotificationChannel.Alerts
        assertEquals("zafiro_alerts_v2", alerts.id)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, alerts.importance)
        assertEquals(R.string.notification_channel_alerts, alerts.channelNameResId)

        val resident = AppNotificationChannel.Resident
        assertEquals("zafiro_resident", resident.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, resident.importance)
        assertEquals(R.string.notification_channel_resident, resident.channelNameResId)
    }

    @Test
    fun isNotificationPermissionGranted_reflectsProvider() {
        val deniedManager = NotificationChannelManagerImpl(
            context = FakeContext(),
            isPermissionGrantedProvider = { false },
        )
        assertFalse(deniedManager.isNotificationPermissionGranted())

        val grantedManager = NotificationChannelManagerImpl(
            context = FakeContext(),
            isPermissionGrantedProvider = { true },
        )
        assertTrue(grantedManager.isNotificationPermissionGranted())
    }

    @Test
    fun post_shortCircuitsWhenPermissionDenied() {
        var builderBlockExecuted = false
        val deniedManager = NotificationChannelManagerImpl(
            context = FakeContext(),
            isPermissionGrantedProvider = { false },
        )

        val result = deniedManager.post(AppNotificationChannel.Alerts, 1001) {
            builderBlockExecuted = true
        }

        assertFalse("When permission is denied, post must return false", result)
        assertFalse("Builder block must not be executed when permission is denied", builderBlockExecuted)
    }

    private class FakeContext : android.content.ContextWrapper(null)
}
