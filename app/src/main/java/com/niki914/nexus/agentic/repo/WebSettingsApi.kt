package com.niki914.nexus.agentic.repo

import android.content.Context
import com.niki914.nexus.agentic.app.getInstalledPackageVersion
import com.niki914.nexus.agentic.mod.WebSettings
import com.niki914.nexus.agentic.mod.parseJsonObject
import com.niki914.nexus.h.util.OsFamily
import com.niki914.nexus.h.util.OsUtils
import com.niki914.nexus.ipc.HostApp
import com.niki914.nexus.ipc.IpcWriteResult
import com.niki914.nexus.ipc.IpcReadResult

import com.niki914.nexus.ipc.XIpcBridge
import com.niki914.nexus.ipc.XValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class WebSettingsApi internal constructor(
    private val repo: XRepo,
) {
    @Volatile
    private var cachedSuccess: WebSettingsResult.Success? = null
    private val mutex = Mutex()

    suspend fun await(): WebSettingsResult {
        cachedSuccess?.let { return it }

        return mutex.withLock {
            cachedSuccess?.let { return@withLock it }

            val context = repo.context()
            val target = resolveTarget(context)
                ?: return@withLock WebSettingsResult.RequestFailed(WebSettingsFailureReason.UnsupportedVersion)
            
            readCachedSettings(context, target)?.let { 
                cachedSuccess = it
                return@withLock it 
            }
            
            val result = refreshFromNetwork(context, target)
            if (result is WebSettingsResult.Success) {
                cachedSuccess = result
            }
            result
        }
    }

    suspend fun retry(): WebSettingsResult {
        val maxRetries = 4
        var delayMs = 250L

        return mutex.withLock {
            val context = repo.context()
            val target = resolveTarget(context)
                ?: return@withLock WebSettingsResult.RequestFailed(WebSettingsFailureReason.UnsupportedVersion)

            var lastResult: WebSettingsResult? = null
            repeat(maxRetries) { attempt ->
                val result = refreshFromNetwork(context, target)
                if (result is WebSettingsResult.Success) {
                    cachedSuccess = result
                    return@withLock result
                }
                lastResult = result

                if (attempt < maxRetries - 1) {
                    delay(delayMs)
                    delayMs *= 2
                }
            }

            lastResult!!
        }
    }

    private suspend fun readCachedSettings(
        context: Context,
        target: WebSettingsTarget,
    ): WebSettingsResult.Success? {
        val json = when (val result = XIpcBridge.readWebSettingsJson(context, repo.storeClient)) {
            is IpcReadResult.Success -> result.json
            is IpcReadResult.Unreachable -> return null
            is IpcReadResult.NotFound -> return null
        }
        val settings = WebSettings(parseJsonObject(json))
        if (settings.config == null) {
            return null
        }
        if (!settings.matches(target)) {
            return null
        }
        return WebSettingsResult.Success(
            settings = settings,
            requestedVersionCode = settings.requestedVersionCode,
            resolvedVersionCode = settings.resolvedVersionCode,
            source = WebSettingsSource.Cache,
            isFallbackVersion = settings.requestedVersionCode != settings.resolvedVersionCode,
        )
    }

    private suspend fun refreshFromNetwork(
        context: Context,
        target: WebSettingsTarget,
    ): WebSettingsResult {
        val exactResult = fetchConfig(target.packageName, target.versionCode)
        return when (exactResult) {
            is ConfigFetchResult.Success -> persistSuccess(
                context = context,
                json = exactResult.json,
                requestedVersionCode = target.versionCode,
                resolvedVersionCode = target.versionCode,
                isFallbackVersion = false,
            )

            ConfigFetchResult.NotFound -> fetchNearestConfig(context, target)
            is ConfigFetchResult.Failed -> WebSettingsResult.RequestFailed(
                reason = exactResult.reason,
                cause = exactResult.cause,
            )
        }
    }

    private suspend fun fetchNearestConfig(
        context: Context,
        target: WebSettingsTarget,
    ): WebSettingsResult {
        val versionsResult = fetchSupportedVersions(target.packageName)
        val supportedVersions = when (versionsResult) {
            is VersionsFetchResult.Success -> versionsResult.versionCodes
            is VersionsFetchResult.Failed -> return WebSettingsResult.RequestFailed(
                reason = versionsResult.reason,
                cause = versionsResult.cause,
            )
        }
        val nearestVersionCode = WebSettingsVersionFallback.nearestVersionCode(
            requestedVersionCode = target.versionCode,
            supportedVersionCodes = supportedVersions,
        ) ?: return WebSettingsResult.RequestFailed(WebSettingsFailureReason.ServerError)
        val fallbackResult = fetchConfig(target.packageName, nearestVersionCode)
        return when (fallbackResult) {
            is ConfigFetchResult.Success -> persistSuccess(
                context = context,
                json = fallbackResult.json,
                requestedVersionCode = target.versionCode,
                resolvedVersionCode = nearestVersionCode,
                isFallbackVersion = true,
            )

            ConfigFetchResult.NotFound -> WebSettingsResult.RequestFailed(WebSettingsFailureReason.ServerError)

            is ConfigFetchResult.Failed -> WebSettingsResult.RequestFailed(
                reason = fallbackResult.reason,
                cause = fallbackResult.cause,
            )
        }
    }

    private suspend fun persistSuccess(
        context: Context,
        json: String,
        requestedVersionCode: Long,
        resolvedVersionCode: Long,
        isFallbackVersion: Boolean,
    ): WebSettingsResult {
        val rawSettings = WebSettings(parseJsonObject(json))
        val settings = rawSettings.withResolvedMetadata(
            requestedVersionCode = requestedVersionCode,
            resolvedVersionCode = resolvedVersionCode,
        )
        if (settings.config == null) {
            return WebSettingsResult.RequestFailed(WebSettingsFailureReason.InvalidConfig)
        }
        val writeResult = XIpcBridge.writeWebSettingsJson(context, settings.props.toString(), repo.storeClient)
        if (writeResult is IpcWriteResult.Unreachable) {
            return WebSettingsResult.RequestFailed(WebSettingsFailureReason.IpcUnreachable)
        }
        return WebSettingsResult.Success(
            settings = settings,
            requestedVersionCode = requestedVersionCode,
            resolvedVersionCode = resolvedVersionCode,
            source = WebSettingsSource.Network,
            isFallbackVersion = isFallbackVersion,
        )
    }

    private suspend fun fetchConfig(
        packageName: String,
        versionCode: Long,
    ): ConfigFetchResult {
        return fetchText(buildConfigUrl(packageName, versionCode)).toConfigFetchResult()
    }

    private suspend fun fetchSupportedVersions(packageName: String): VersionsFetchResult {
        return when (val response = fetchText(buildVersionsUrl(packageName))) {
            is HttpTextResult.Success -> {
                val versionCodes = runCatching {
                    Json.parseToJsonElement(response.body)
                        .jsonArray
                        .mapNotNull { it.jsonPrimitive.longOrNull }
                }.getOrNull()
                if (versionCodes == null) {
                    VersionsFetchResult.Failed(WebSettingsFailureReason.InvalidConfig)
                } else {
                    VersionsFetchResult.Success(versionCodes)
                }
            }

            is HttpTextResult.Failed -> VersionsFetchResult.Failed(response.reason, response.cause)
            HttpTextResult.NotFound -> VersionsFetchResult.Failed(WebSettingsFailureReason.ServerError)
        }
    }

    private suspend fun fetchText(url: String): HttpTextResult {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == HTTP_NOT_FOUND -> HttpTextResult.NotFound
                        !response.isSuccessful || response.body == null -> HttpTextResult.Failed(
                            WebSettingsFailureReason.ServerError
                        )

                        else -> HttpTextResult.Success(response.body!!.string())
                    }
                }
            } catch (e: IOException) {
                HttpTextResult.Failed(WebSettingsFailureReason.NetworkUnavailable, e)
            } catch (e: IllegalArgumentException) {
                HttpTextResult.Failed(WebSettingsFailureReason.InvalidConfig, e)
            }
        }
    }

    private fun HttpTextResult.toConfigFetchResult(): ConfigFetchResult {
        return when (this) {
            is HttpTextResult.Success -> ConfigFetchResult.Success(body)
            is HttpTextResult.Failed -> ConfigFetchResult.Failed(reason, cause)
            HttpTextResult.NotFound -> ConfigFetchResult.NotFound
        }
    }

    private fun resolveTarget(context: Context): WebSettingsTarget? {
        val appType = XValues.getAppTypeOf(context)
        val packageName = when (appType) {
            XValues.AppType.Host -> context.packageName
            XValues.AppType.Me -> resolveTargetHostPackage(context)
            XValues.AppType.Unknown -> null
        } ?: return null
        val version = context.getInstalledPackageVersion(packageName) ?: return null
        return WebSettingsTarget(packageName, version.versionCode)
    }

    private fun resolveTargetHostPackage(context: Context): String? {
        val preferredPackageName = when (OsUtils.getCurr()) {
            OsFamily.ColorOS -> HostApp.Breeno.packageName
            OsFamily.HyperOS -> HostApp.XiaoAi.packageName
            OsFamily.Unknown -> null
        }
        val candidatePackages = buildList {
            if (preferredPackageName != null) add(preferredPackageName)
            addAll(HostApp.packageNames.filter { it != preferredPackageName })
        }
        return candidatePackages.firstOrNull { packageName ->
            context.getInstalledPackageVersion(packageName) != null
        }
    }

    private fun buildConfigUrl(packageName: String, versionCode: Long): String {
        return "$REMOTE_BASE_URL$packageName/$versionCode/config.json"
    }

    private fun buildVersionsUrl(packageName: String): String {
        return "$REMOTE_BASE_URL$packageName/versions.json"
    }

    private fun WebSettings.matches(target: WebSettingsTarget): Boolean {
        return packageName == target.packageName && requestedVersionCode == target.versionCode
    }

    private fun WebSettings.withResolvedMetadata(
        requestedVersionCode: Long,
        resolvedVersionCode: Long,
    ): WebSettings {
        val updatedProps = JsonObject(
            props + mapOf(
                "requested_version_code" to JsonPrimitive(requestedVersionCode),
                "resolved_version_code" to JsonPrimitive(resolvedVersionCode),
            )
        )
        return WebSettings(updatedProps)
    }

    private data class WebSettingsTarget(
        val packageName: String,
        val versionCode: Long,
    )

    private sealed interface HttpTextResult {
        data class Success(val body: String) : HttpTextResult
        data class Failed(
            val reason: WebSettingsFailureReason,
            val cause: Throwable? = null,
        ) : HttpTextResult

        data object NotFound : HttpTextResult
    }

    private sealed interface ConfigFetchResult {
        data class Success(val json: String) : ConfigFetchResult
        data class Failed(
            val reason: WebSettingsFailureReason,
            val cause: Throwable? = null,
        ) : ConfigFetchResult

        data object NotFound : ConfigFetchResult
    }

    private sealed interface VersionsFetchResult {
        data class Success(val versionCodes: List<Long>) : VersionsFetchResult
        data class Failed(
            val reason: WebSettingsFailureReason,
            val cause: Throwable? = null,
        ) : VersionsFetchResult
    }

    private companion object {
        private const val REMOTE_BASE_URL = "https://gitee.com/niki914/nexus-res/raw/main/"
        private const val HTTP_NOT_FOUND = 404
        private val httpClient = OkHttpClient()
    }
}
