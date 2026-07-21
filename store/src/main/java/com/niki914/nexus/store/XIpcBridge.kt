package com.niki914.nexus.store

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject

object XIpcBridge {

    interface StoreClient {
        fun readStore(storeId: String): String?
        fun writeStore(storeId: String, json: String): Boolean
        fun mutateStore(storeId: String, path: String, valueJson: String): String?
        fun postNotification(title: String, content: String, uri: String?): Boolean
        fun postNetworkErrorNotification(): Boolean
        fun postUnsupportedVersionNotification(
            hostPackageName: String?,
            hostVersion: String?
        ): Boolean
    }

    suspend fun readWebSettingsJson(context: Context, client: StoreClient?): IpcReadResult {
        return readStoreJson(context, StoreDescriptorRegistry.WEB_SETTINGS_ID, client)
    }

    suspend fun writeWebSettingsJson(
        context: Context,
        json: String,
        client: StoreClient?
    ): IpcWriteResult {
        return writeStoreJsonFromOwner(
            context,
            StoreDescriptorRegistry.WEB_SETTINGS_ID,
            json,
            client
        )
    }

    suspend fun readLocalSettingsJson(context: Context, client: StoreClient?): IpcReadResult {
        return readStoreJson(context, StoreDescriptorRegistry.LOCAL_SETTINGS_ID, client)
    }

    suspend fun writeLocalSettingsJson(
        context: Context,
        json: String,
        client: StoreClient?
    ): IpcWriteResult {
        return writeStoreJsonFromOwner(
            context,
            StoreDescriptorRegistry.LOCAL_SETTINGS_ID,
            json,
            client
        )
    }

    suspend fun readStoreJson(
        context: Context,
        storeId: String,
        client: StoreClient?,
    ): IpcReadResult {
        StoreDescriptorRegistry.resolveDynamic(storeId) ?: return IpcReadResult.NotFound
        return when (resolveTransport(context, client)) {
            Transport.Binder -> {
                val json = client!!.readStore(storeId)
                if (json != null) IpcReadResult.Success(json) else IpcReadResult.Unreachable
            }

            Transport.Local -> IpcReadResult.Success(XIpcStoreRepository.readJson(context, storeId))
            Transport.Unreachable -> IpcReadResult.Unreachable
        }
    }

    suspend fun writeStoreJsonFromOwner(
        context: Context,
        storeId: String,
        json: String,
        client: StoreClient?,
    ): IpcWriteResult {
        StoreDescriptorRegistry.resolveDynamic(storeId) ?: return IpcWriteResult.Unreachable
        return when (resolveTransport(context, client)) {
            Transport.Binder -> {
                if (client!!.writeStore(
                        storeId,
                        json
                    )
                ) IpcWriteResult.Success else IpcWriteResult.Unreachable
            }

            Transport.Local -> {
                XIpcStoreRepository.writeJson(context, storeId, json)
                IpcWriteResult.Success
            }

            Transport.Unreachable -> IpcWriteResult.Unreachable
        }
    }

    suspend fun mutateStoreJson(
        context: Context,
        storeId: String,
        path: String,
        value: Any?,
        client: StoreClient?,
    ): IpcMutateResult {
        StoreDescriptorRegistry.resolveDynamic(storeId)
            ?: return IpcMutateResult(IpcWriteResult.Unreachable, null)
        val valueJson = serializeValue(value)
        return when (resolveTransport(context, client)) {
            Transport.Binder -> {
                val updatedJson = client!!.mutateStore(storeId, path, valueJson)
                if (updatedJson != null) {
                    IpcMutateResult(IpcWriteResult.Success, updatedJson)
                } else {
                    IpcMutateResult(IpcWriteResult.Unreachable, null)
                }
            }

            Transport.Local -> {
                val updatedJson = XIpcStoreRepository.mutateJson(
                    context = context,
                    storeId = storeId,
                    path = path,
                    valueJson = valueJson
                )
                IpcMutateResult(IpcWriteResult.Success, updatedJson)
            }

            Transport.Unreachable -> IpcMutateResult(IpcWriteResult.Unreachable, null)
        }
    }

    suspend fun postNotification(
        context: Context,
        title: String,
        content: String,
        uri: String?,
        client: StoreClient?,
    ): IpcWriteResult {
        return when (resolveTransport(context, client)) {
            Transport.Binder -> {
                if (client!!.postNotification(
                        title,
                        content,
                        uri
                    )
                ) IpcWriteResult.Success else IpcWriteResult.Unreachable
            }

            Transport.Local -> {
                postLocalNotification(context, title, content, uri)
                IpcWriteResult.Success
            }

            Transport.Unreachable -> IpcWriteResult.Unreachable
        }
    }

    private enum class Transport { Local, Binder, Unreachable }

    private fun resolveTransport(context: Context, client: StoreClient?): Transport {
        return when (XValues.getAppTypeOf(context)) {
            XValues.AppType.Host -> if (client != null) Transport.Binder else Transport.Unreachable
            XValues.AppType.Me -> Transport.Local
            XValues.AppType.Unknown -> throw IllegalStateException(
                "XIpcBridge does not support package=${context.packageName}"
            )
        }
    }

    private fun serializeValue(value: Any?): String {
        return when (val jsonValue = toJsonValue(value)) {
            JSONObject.NULL -> "null"
            is String -> JSONObject.quote(jsonValue)
            else -> jsonValue.toString()
        }
    }

    private fun toJsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is JSONObject -> value
            is JSONArray -> value
            is Boolean, is Number, is String -> value
            is Map<*, *> -> JSONObject().apply {
                value.forEach { (key, nestedValue) ->
                    if (key != null) {
                        put(key.toString(), toJsonValue(nestedValue))
                    }
                }
            }

            is Iterable<*> -> JSONArray().apply {
                value.forEach { put(toJsonValue(it)) }
            }

            is Array<*> -> JSONArray().apply {
                value.forEach { put(toJsonValue(it)) }
            }

            else -> value.toString()
        }
    }

    // --- Inlined notification posting (formerly XNotificationBridge) ---

    private const val CHANNEL_ID = "nexus_xservice_default_channel"
    private const val CHANNEL_NAME = "Nexus"

    private fun postLocalNotification(
        context: Context,
        title: String,
        content: String,
        uri: String?
    ) {
        fun hasPermission(): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermission()) return
        ensureNotificationChannel(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(resolveSmallIcon(context))
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)

        createContentIntent(context, uri)?.let { contentIntent ->
            builder.setContentIntent(contentIntent)
        }
        NotificationManagerCompat.from(context).notify(
            notificationId(title, content, uri),
            builder.build()
        )
    }

    private fun ensureNotificationChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }

    private fun createContentIntent(context: Context, uri: String?): PendingIntent? {
        if (uri.isNullOrBlank()) {
            return null
        }
        val intent = Intent(Intent.ACTION_VIEW, uri.toUri()).apply {
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

    private fun resolveSmallIcon(context: Context): Int {
        return context.applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.ic_dialog_info
    }

    private fun notificationId(title: String, content: String, uri: String?): Int {
        var result = title.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + (uri?.hashCode() ?: 0)
        return result
    }
}
