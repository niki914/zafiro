package com.niki914.zafiro.runtime

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.niki914.permission.Permission
import com.niki914.permission.PermissionState
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.app.PermissionHolder
import com.niki914.zafiro.business.notification.AppNotificationChannel
import com.niki914.zafiro.business.notification.NotificationChannelManager
import com.niki914.zafiro.service.requireService
import com.niki914.zafiro.settings.RuntimeHostGateway

class IpcRuntimeHostGateway : RuntimeHostGateway {
    override suspend fun postNotification(
        title: String,
        content: String,
        uri: String?,
    ): Boolean {
        val context = ContextProvider.await()
        val pm = PermissionHolder.get(context)
        if (pm.status(Permission.NOTIFICATION) != PermissionState.GRANTED) {
            if (pm.request(Permission.NOTIFICATION).finalState != PermissionState.GRANTED) {
                return false
            }
        }
        val notiManager = requireService<NotificationChannelManager>()
        val id = notificationId(title, content, uri)
        return notiManager.post(AppNotificationChannel.Alerts, id) {
            val icon = context.applicationInfo.icon.takeIf { it != 0 }
                ?: android.R.drawable.ic_dialog_info
            setSmallIcon(icon)
            setContentTitle(title)
            setContentText(content)
            setStyle(NotificationCompat.BigTextStyle().bigText(content))
            setPriority(NotificationCompat.PRIORITY_MAX)
            setDefaults(NotificationCompat.DEFAULT_ALL)
            setAutoCancel(true)
            createContentIntent(context, uri)?.let { setContentIntent(it) }
        }
    }

    private fun createContentIntent(context: Context, uri: String?): PendingIntent? {
        if (uri.isNullOrBlank()) {
            return null
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val resolved = context.packageManager.resolveActivity(intent, 0) ?: return null
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(
            context,
            resolved.activityInfo.packageName.hashCode(),
            intent,
            pendingIntentFlags
        )
    }

    private fun notificationId(title: String, content: String, uri: String?): Int {
        var result = title.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + (uri?.hashCode() ?: 0)
        return result
    }
}
