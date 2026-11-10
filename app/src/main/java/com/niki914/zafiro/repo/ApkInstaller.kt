package com.niki914.zafiro.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * In-app update: downloads the release APK and hands it to Android's installer (the system asks the owner to confirm).
 * It can only replace the installed app when the new APK is signed with the same key, which release builds are.
 */
object ApkInstaller {
    /** @return null when the installer was opened, otherwise a message to show the owner. */
    suspend fun downloadAndInstall(context: Context, apkUrl: String): String? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return@withContext "Allow Zafiro to install apps in the screen that just opened, then tap Update again."
        }
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "zafiro-update.apk")
        val failure = runCatching {
            SharedHttp.client.newCall(Request.Builder().url(apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@use "Download failed (HTTP ${response.code})."
                val body = response.body ?: return@use "Download failed (empty response)."
                file.outputStream().use { out -> body.byteStream().copyTo(out) }
                null
            }
        }.getOrElse { "Download failed: ${it.message}" }
        if (failure != null) return@withContext failure
        if (file.length() < MIN_APK_BYTES) return@withContext "The downloaded file is too small to be the app; try again."
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }.exceptionOrNull()?.let { return@withContext "Could not open the installer: ${it.message}" }
        null
    }

    private const val MIN_APK_BYTES = 1_000_000L
}
