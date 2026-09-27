package com.niki914.zafiro.chat

import android.app.Notification
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.core.app.NotificationCompat
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.business.notification.AppNotificationChannel
import com.niki914.zafiro.business.notification.NotificationChannelManager
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.impl.NotifyBuiltin
import com.niki914.zafiro.chat.util.SilentLoggerRule
import com.niki914.zafiro.service.ServiceRegistry
import com.niki914.zafiro.service.installService
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NotifyBuiltinTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    private lateinit var fakeNotiManager: FakeNotificationChannelManager

    private val tempDir = java.nio.file.Files.createTempDirectory("test_notify_builtin").toFile()

    private val testContext: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo().apply { icon = 0 }
        override fun getFilesDir(): java.io.File = tempDir
    }

    @Before
    fun setUp() {
        ContextProvider.provide(testContext)
        fakeNotiManager = FakeNotificationChannelManager()
        installService<NotificationChannelManager>(fakeNotiManager)
    }

    @After
    fun tearDown() {
        ServiceRegistry.clearForTest()
        ContextProvider.clearForTest()
    }

    @Test
    fun invoke_invalidJson_returnsFailure() = runTest {
        val builtin = NotifyBuiltin()
        val result = builtin.invoke(
            BuiltinToolRequest(
                name = "notify",
                argumentsJson = "not a valid json",
            )
        )

        val json = Json.parseToJsonElement(result.toJsonString()).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("INVALID_ARGUMENTS_JSON", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun invoke_missingRequiredFields_returnsFailure() = runTest {
        val builtin = NotifyBuiltin()
        val result = builtin.invoke(
            BuiltinToolRequest(
                name = "notify",
                argumentsJson = """{"title":"","content":""}""",
            )
        )

        val json = Json.parseToJsonElement(result.toJsonString()).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("MISSING_REQUIRED_FIELD", json["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun invoke_validArguments_postsToAlertsChannel() = runTest {
        val builtin = NotifyBuiltin()
        val result = builtin.invoke(
            BuiltinToolRequest(
                name = "notify",
                argumentsJson = """{"title":"Meeting Alert","content":"Sync at 3pm"}""",
            )
        )

        val json = Json.parseToJsonElement(result.toJsonString()).jsonObject
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("Notification posted.", json["message"]!!.jsonPrimitive.content)
        assertEquals(AppNotificationChannel.Alerts, fakeNotiManager.lastPostedChannel)
    }

    @Test
    fun invoke_postFailure_returnsNotificationPostFailed() = runTest {
        fakeNotiManager.shouldPostSucceed = false

        val builtin = NotifyBuiltin()
        val result = builtin.invoke(
            BuiltinToolRequest(
                name = "notify",
                argumentsJson = """{"title":"Alert","content":"Some content"}""",
            )
        )

        val json = Json.parseToJsonElement(result.toJsonString()).jsonObject
        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("NOTIFICATION_POST_FAILED", json["code"]!!.jsonPrimitive.content)
    }

    private class FakeNotificationChannelManager(
        var shouldPostSucceed: Boolean = true,
    ) : NotificationChannelManager {
        var lastPostedChannel: AppNotificationChannel? = null
        var lastPostedId: Int? = null

        override fun isNotificationPermissionGranted(): Boolean = shouldPostSucceed

        override suspend fun post(
            channel: AppNotificationChannel,
            notificationId: Int,
            block: NotificationCompat.Builder.() -> Unit,
        ): Boolean {
            if (!shouldPostSucceed) return false
            lastPostedChannel = channel
            lastPostedId = notificationId
            return true
        }

        override fun buildNotification(
            channel: AppNotificationChannel,
            block: NotificationCompat.Builder.() -> Unit,
        ): Notification {
            throw UnsupportedOperationException("Not needed in test")
        }

        override fun cancel(notificationId: Int) {}
    }
}
