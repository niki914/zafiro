package com.niki914.zafiro.business.remote.config

import com.niki914.logging.Logger
import com.niki914.zafiro.api.telemetry.TelemetryContract
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.api.util.tryOrNull
import com.niki914.zafiro.business.remote.ledger.AtomicText
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

internal data class RemoteConfig(
    val configVersion: Int?,
    val enabled: Boolean,
    val reportIntervalHours: Int,
    val configTtlHours: Int,
    val fetchedAtMs: Long,
) {
    companion object {
        fun default(): RemoteConfig = RemoteConfig(
            configVersion = null,
            enabled = TelemetryContract.CONFIG_KEY_TELEMETRY_ENABLED_DEFAULT,
            reportIntervalHours = TelemetryContract.CONFIG_KEY_REPORT_INTERVAL_HOURS_DEFAULT,
            configTtlHours = TelemetryContract.CONFIG_KEY_CONFIG_TTL_HOURS_DEFAULT,
            fetchedAtMs = 0L,
        )
    }
}

internal fun clampRemoteConfig(
    configVersion: Int?,
    rawEnabled: Boolean?,
    rawInterval: Int?,
    rawTtl: Int?,
    fetchedAtMs: Long,
): RemoteConfig {
    val enabled = rawEnabled ?: TelemetryContract.CONFIG_KEY_TELEMETRY_ENABLED_DEFAULT
    val interval = (rawInterval ?: TelemetryContract.CONFIG_KEY_REPORT_INTERVAL_HOURS_DEFAULT)
        .coerceIn(
            TelemetryContract.CONFIG_KEY_REPORT_INTERVAL_HOURS_MIN,
            TelemetryContract.CONFIG_KEY_REPORT_INTERVAL_HOURS_MAX,
        )
    val ttl = (rawTtl ?: TelemetryContract.CONFIG_KEY_CONFIG_TTL_HOURS_DEFAULT)
        .coerceIn(
            TelemetryContract.CONFIG_KEY_CONFIG_TTL_HOURS_MIN,
            TelemetryContract.CONFIG_KEY_CONFIG_TTL_HOURS_MAX,
        )
    return RemoteConfig(
        configVersion = configVersion?.takeIf { it >= 1 },
        enabled = enabled,
        reportIntervalHours = interval,
        configTtlHours = ttl,
        fetchedAtMs = fetchedAtMs,
    )
}

internal class RemoteConfigStore(private val file: File) {

    fun load(): RemoteConfig {
        if (!file.exists()) return RemoteConfig.default()
        val text = try {
            AtomicText.read(file)
        } catch (_: FileNotFoundException) {
            return RemoteConfig.default()
        } catch (error: IOException) {
            Logger.w(LOG_TAG, "配置缓存读出失败", error)
            return RemoteConfig.default()
        }

        val json = tryOrNull { JSONObject(text) } ?: return RemoteConfig.default()
        val configVersion = if (json.has("config_version") && !json.isNull("config_version")) {
            json.optInt("config_version").takeIf { it >= 1 }
        } else {
            null
        }
        val enabled = if (json.has("telemetry_enabled")) json.optBoolean("telemetry_enabled") else null
        val interval = if (json.has("report_interval_hours")) json.optInt("report_interval_hours") else null
        val ttl = if (json.has("config_ttl_hours")) json.optInt("config_ttl_hours") else null
        val fetchedAt = json.optLong("fetched_at_ms", 0L)

        return clampRemoteConfig(configVersion, enabled, interval, ttl, fetchedAt)
    }

    fun save(config: RemoteConfig) {
        val json = JSONObject()
            .put("config_version", config.configVersion ?: JSONObject.NULL)
            .put("telemetry_enabled", config.enabled)
            .put("report_interval_hours", config.reportIntervalHours)
            .put("config_ttl_hours", config.configTtlHours)
            .put("fetched_at_ms", config.fetchedAtMs)
        try {
            AtomicText.write(file, json.toString())
        } catch (error: IOException) {
            Logger.w(LOG_TAG, "配置缓存写入失败", error)
        }
    }

    private companion object {
        const val LOG_TAG = "niki914_zafiro_RemoteConfigStore"
    }
}

internal class RemoteConfigClient(
    private val httpProvider: () -> OkHttpClient,
    private val baseUrl: String,
    private val store: RemoteConfigStore,
) {
    constructor(
        http: OkHttpClient,
        baseUrl: String,
        store: RemoteConfigStore,
    ) : this({ http }, baseUrl, store)
    @Volatile
    private var cached: RemoteConfig = RemoteConfig.default()
    @Volatile
    private var loaded = false

    fun current(): RemoteConfig {
        ensureLoaded()
        return cached
    }

    private fun ensureLoaded() {
        if (!loaded) {
            cached = store.load()
            loaded = true
        }
    }

    suspend fun getOrFetch(versionCode: Int, nowMs: Long, force: Boolean = false): RemoteConfig {
        ensureLoaded()
        if (baseUrl.isBlank()) return cached
        val ttlMs = cached.configTtlHours * 3600_000L
        if (!force && cached.fetchedAtMs > 0L && (nowMs - cached.fetchedAtMs) in 0L until ttlMs) {
            return cached
        }

        val url = "$baseUrl/v1/config?version_code=$versionCode"
        val requestBuilder = Request.Builder().url(url).get()
        cached.configVersion?.let { ver ->
            requestBuilder.header("If-None-Match", "\"c$ver\"")
        }

        val outcome = runCatchingCancellable {
            httpProvider().newCall(requestBuilder.build()).execute()
        }

        if (outcome.isFailure) {
            val error = outcome.exceptionOrNull()
            if (error != null) {
                Logger.w(LOG_TAG, "远程配置请求失败", error)
            } else {
                Logger.w(LOG_TAG, "远程配置请求失败")
            }
            return cached
        }

        val response = outcome.getOrThrow()
        response.use { res ->
            when (res.code) {
                304 -> {
                    val updated = cached.copy(fetchedAtMs = nowMs)
                    cached = updated
                    store.save(updated)
                    return updated
                }
                200 -> {
                    val bodyString = res.body?.string().orEmpty()
                    val json = tryOrNull { JSONObject(bodyString) }
                    if (json != null) {
                        val ver = if (json.has("config_version") && !json.isNull("config_version")) {
                            json.optInt("config_version").takeIf { it >= 1 }
                        } else {
                            null
                        }
                        val values = json.optJSONObject("values")
                        val enabled = if (values?.has("telemetry_enabled") == true) values.getBoolean("telemetry_enabled") else null
                        val interval = if (values?.has("report_interval_hours") == true) values.getInt("report_interval_hours") else null
                        val ttl = if (values?.has("config_ttl_hours") == true) values.getInt("config_ttl_hours") else null
                        val updated = clampRemoteConfig(ver, enabled, interval, ttl, nowMs)
                        cached = updated
                        store.save(updated)
                        return updated
                    }
                    return cached
                }
                else -> {
                    Logger.w(LOG_TAG, "远程配置服务端响应异常: ${res.code}")
                    return cached
                }
            }
        }
    }

    private companion object {
        const val LOG_TAG = "niki914_zafiro_RemoteConfigClient"
    }
}
