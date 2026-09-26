package com.niki914.zafiro.business.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.niki914.logging.Logger
import com.niki914.permission.Permission
import com.niki914.permission.PermissionManager
import com.niki914.permission.PermissionState

class NotificationChannelManagerImpl(
    private val context: Context,
    private val permissionManager: PermissionManager? = null,
    private val isPermissionGrantedProvider: (() -> Boolean)? = null,
) : NotificationChannelManager {

    constructor(
        context: Context,
        permissionManager: PermissionManager,
    ) : this(
        context = context,
        permissionManager = permissionManager,
        isPermissionGrantedProvider = null,
    )

    companion object {
        private const val TAG = "NotificationChannelManager"
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

        for (channelDef in AppNotificationChannel.entries) {
            val name = context.getString(channelDef.channelNameResId)
            val channel = NotificationChannel(
                channelDef.id,
                name,
                channelDef.importance,
            )
            manager.createNotificationChannel(channel)
        }
    }

    override fun isNotificationPermissionGranted(): Boolean {
        return isPermissionGrantedProvider?.invoke()
            ?: (permissionManager?.status(Permission.NOTIFICATION) == PermissionState.GRANTED)
    }

    override fun post(
        channel: AppNotificationChannel,
        notificationId: Int,
        block: NotificationCompat.Builder.() -> Unit,
    ): Boolean {
        if (!isNotificationPermissionGranted()) {
            Logger.w(TAG, "post skipped: notification permission is not granted")
            return false
        }

        ensureChannels()

        val builder = NotificationCompat.Builder(context, channel.id)
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
