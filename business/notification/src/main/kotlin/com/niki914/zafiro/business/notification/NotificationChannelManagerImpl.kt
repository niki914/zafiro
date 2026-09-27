package com.niki914.zafiro.business.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.niki914.logging.Logger
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.service.requireService

class NotificationChannelManagerImpl(
    private val context: Context,
) : NotificationChannelManager {

    companion object {
        private const val TAG = "niki914_zafiro_NotificationChannelManager"
        private val OBSOLETE_CHANNELS = listOf(
            "zafiro_alerts_v1",
            "zafiro_alerts_v2",
            "zafiro_alerts_v3",
            "nexus_xservice_default_channel",
        )
    }

    private val notificationManager: NotificationManager? by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    }

    init {
        ensureChannels()
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager ?: return

        for (oldChannelId in OBSOLETE_CHANNELS) {
            try {
                manager.deleteNotificationChannel(oldChannelId)
            } catch (e: Throwable) {
                Logger.w(TAG, "Failed to delete obsolete channel $oldChannelId", e)
            }
        }

        for (channelDef in AppNotificationChannel.entries) {
            val name = context.getString(channelDef.channelNameResId)
            val channel = NotificationChannel(
                channelDef.id,
                name,
                channelDef.importance,
            ).apply {
                if (channelDef.importance >= NotificationManager.IMPORTANCE_DEFAULT) {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 200)
                    enableLights(true)
                    val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(soundUri, audioAttributes)
                }
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun isNotificationPermissionGranted(): Boolean {
        return requireService<PermissionManager>().status(Permission.NOTIFICATION) == PermissionState.GRANTED
    }

    override suspend fun post(
        channel: AppNotificationChannel,
        notificationId: Int,
        block: NotificationCompat.Builder.() -> Unit,
    ): Boolean {
        if (!isNotificationPermissionGranted()) {
            val requestResult = requireService<PermissionManager>().request(Permission.NOTIFICATION)
            if (requestResult.finalState != PermissionState.GRANTED) {
                Logger.w(TAG, "post skipped: notification permission is not granted after request")
                return false
            }
        }

        ensureChannels()

        val builder = NotificationCompat.Builder(context, channel.id)
        // 针对高优先级通知预设 IM 级别的提示音、震动与横幅优先级。
        // 注意：部分厂商系统（如 ColorOS/Oppo 等）有额外的应用级策略，默认可能压制横幅显示或静音。
        if (channel.importance >= NotificationManager.IMPORTANCE_DEFAULT) {
            builder.setPriority(NotificationCompat.PRIORITY_MAX)
            builder.setCategory(NotificationCompat.CATEGORY_MESSAGE)
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            builder.setVibrate(longArrayOf(0, 200))
            builder.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        }
        builder.block()

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        return true
    }

    override fun buildNotification(
        channel: AppNotificationChannel,
        block: NotificationCompat.Builder.() -> Unit,
    ): Notification {
        ensureChannels()
        val builder = NotificationCompat.Builder(context, channel.id)
        builder.block()
        return builder.build()
    }

    override fun cancel(notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }
}
