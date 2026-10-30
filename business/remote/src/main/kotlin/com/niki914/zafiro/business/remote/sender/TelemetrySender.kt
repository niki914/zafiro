package com.niki914.zafiro.business.remote.sender

import com.niki914.logging.Logger
import com.niki914.zafiro.api.telemetry.TelemetryContract
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.api.util.tryOrNull
import com.niki914.zafiro.business.remote.channel.ApkChannelReader
import com.niki914.zafiro.business.remote.config.RemoteConfig
import com.niki914.zafiro.business.remote.config.RemoteConfigClient
import com.niki914.zafiro.business.remote.ledger.InstallFacts
import com.niki914.zafiro.business.remote.ledger.Ledger
import com.niki914.zafiro.business.remote.ledger.SendSnapshot
import com.niki914.zafiro.business.remote.profile.DeviceProfile
import com.niki914.zafiro.business.remote.profile.DeviceProfileReader
import com.niki914.zafiro.business.remote.profile.DeviceSignals
import com.niki914.zafiro.business.remote.profile.ReportedAppLanguage
import kotlinx.coroutines.sync.Mutex
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

internal fun computeBackoffMillis(failures: Int): Long {
    val exponent = (failures - 1).coerceIn(0, 7)
    return (15L * 60 * 1000L * (1L shl exponent)).coerceAtMost(24L * 3600 * 1000L)
}

internal object TelemetryPayload {
    fun build(
        snapshot: SendSnapshot,
        today: LocalDate,
        configVersion: Int?,
        versionCode: Int,
        currentChannel: String,
        buildType: String,
        appLang: String?,
        installFacts: InstallFacts,
        installer: String?,
        profile: DeviceProfile,
    ): String {
        val root = JSONObject()
        root.put("schema_version", TelemetryContract.SCHEMA_VERSION)
        root.put("contract_version", TelemetryContract.CONTRACT_VERSION)
        root.put("config_version", configVersion ?: JSONObject.NULL)
        root.put("client_day", today.toString())

        val app = JSONObject()
        app.put("version_code", versionCode)
        app.put("current_channel", currentChannel)
        app.put("build_type", buildType)
        app.put("app_lang", appLang ?: JSONObject.NULL)
        root.put("app", app)

        val install = JSONObject()
        install.put("first_channel", installFacts.firstChannel)
        install.put("first_version_code", installFacts.firstVersionCode ?: JSONObject.NULL)
        install.put("install_day", installFacts.installDay)
        install.put("installer", installer ?: JSONObject.NULL)
        root.put("install", install)

        val device = JSONObject()
        device.put("api_level", profile.apiLevel ?: JSONObject.NULL)
        device.put("lang", profile.lang ?: JSONObject.NULL)
        device.put("brand", profile.brand)
        device.put("model", profile.model)
        device.put("abi", profile.abi)
        device.put("ram_gb", profile.ramGb)
        device.put("screen_class", profile.screenClass)
        device.put("locale_region", profile.localeRegion ?: JSONObject.NULL)
        device.put("tz_id", profile.tzId)
        device.put("tz_offset_min", profile.tzOffsetMin)
        root.put("device", device)

        root.put("dropped_days", snapshot.droppedDays)
        root.put("custom", JSONObject())

        val daysArray = JSONArray()
        for (page in snapshot.days) {
            val dayObj = JSONObject()
            dayObj.put("day_key", page.dayKey)
            dayObj.put("activity_day", page.day.toString())
            dayObj.put("version_code", page.versionCode)
            dayObj.put("rev", page.rev)
            dayObj.put("prev_active_day", page.prevActiveDay?.toString() ?: JSONObject.NULL)
            dayObj.put("prev_version_code", page.prevVersionCode ?: JSONObject.NULL)
            dayObj.put("custom", JSONObject())
            dayObj.put("counts", JSONArray())
            dayObj.put("counts_truncated", page.countsTruncated)
            dayObj.put("firsts", JSONArray())
            daysArray.put(dayObj)
        }
        root.put("days", daysArray)

        return root.toString()
    }
}

internal class TelemetrySender(
    private val httpProvider: () -> OkHttpClient,
    private val baseUrl: String,
    private val ledger: Ledger,
    private val configClient: RemoteConfigClient,
    private val versionCode: Int,
    private val isDebug: Boolean,
    private val apkFile: File?,
    private val installFactsProvider: suspend () -> InstallFacts,
    private val installerProvider: () -> String? = { null },
    private val signalsProvider: () -> DeviceSignals,
    private val appLangProvider: () -> String? = { ReportedAppLanguage.read() },
    private val clock: Clock = Clock.systemUTC(),
) {
    constructor(
        http: OkHttpClient,
        baseUrl: String,
        ledger: Ledger,
        configClient: RemoteConfigClient,
        versionCode: Int,
        isDebug: Boolean,
        apkFile: File?,
        installFactsProvider: suspend () -> InstallFacts,
        installerProvider: () -> String? = { null },
        signalsProvider: () -> DeviceSignals,
        appLangProvider: () -> String? = { ReportedAppLanguage.read() },
        clock: Clock = Clock.systemUTC(),
    ) : this(
        httpProvider = { http },
        baseUrl = baseUrl,
        ledger = ledger,
        configClient = configClient,
        versionCode = versionCode,
        isDebug = isDebug,
        apkFile = apkFile,
        installFactsProvider = installFactsProvider,
        installerProvider = installerProvider,
        signalsProvider = signalsProvider,
        appLangProvider = appLangProvider,
        clock = clock,
    )

    constructor(
        http: OkHttpClient,
        baseUrl: String,
        ledger: Ledger,
        configClient: RemoteConfigClient,
        versionCode: Int,
        isDebug: Boolean,
        apkFile: File?,
        installFacts: InstallFacts,
        installer: String?,
        signalsProvider: () -> DeviceSignals,
        appLangProvider: () -> String? = { ReportedAppLanguage.read() },
        clock: Clock = Clock.systemUTC(),
    ) : this(
        httpProvider = { http },
        baseUrl = baseUrl,
        ledger = ledger,
        configClient = configClient,
        versionCode = versionCode,
        isDebug = isDebug,
        apkFile = apkFile,
        installFactsProvider = { installFacts },
        installerProvider = { installer },
        signalsProvider = signalsProvider,
        appLangProvider = appLangProvider,
        clock = clock,
    )

    private val sendMutex = Mutex()

    suspend fun maybeSend() {
        if (!sendMutex.tryLock()) return
        try {
            sendInternal()
        } finally {
            sendMutex.unlock()
        }
    }

    private suspend fun sendInternal() {
        val nowMs = clock.millis()
        val config = configClient.getOrFetch(versionCode, nowMs)
        if (!config.enabled) {
            ledger.clearForDisabled()
            return
        }

        val sendState = ledger.sendState() ?: return
        if (nowMs < sendState.nextRetryMs) {
            return
        }

        val today = clock.instant().atOffset(ZoneOffset.UTC).toLocalDate()
        val snapshot = ledger.snapshotForSend(today)
        if (snapshot.days.isEmpty()) {
            val nextRetry = nowMs + config.reportIntervalHours * 3600_000L
            ledger.recordSendSuccess(nowMs = sendState.lastOkMs ?: nowMs, nextRetryMs = nextRetry)
            return
        }

        val currentChannel = if (apkFile != null) {
            ApkChannelReader.currentChannel(isDebug, apkFile)
        } else {
            if (isDebug) TelemetryContract.CHANNEL_DEV_NAME else TelemetryContract.CHANNEL_UNKNOWN_NAME
        }
        val buildType = if (isDebug) "debug" else "release"
        val appLang = appLangProvider()
        val profile = DeviceProfileReader.read(signalsProvider(), nowMs)
        val installFacts = installFactsProvider()
        val installer = installerProvider()

        val payload = TelemetryPayload.build(
            snapshot = snapshot,
            today = today,
            configVersion = config.configVersion,
            versionCode = versionCode,
            currentChannel = currentChannel,
            buildType = buildType,
            appLang = appLang,
            installFacts = installFacts,
            installer = installer,
            profile = profile,
        )

        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        if (payloadBytes.size > TelemetryContract.LIMIT_BODY_BYTES) {
            Logger.w(LOG_TAG, "遥测负载超出上限: ${payloadBytes.size}")
            ledger.recordSendFailure(nowMs + 24 * 3600_000L)
            return
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/usage")
            .post(payloadBytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val outcome = runCatchingCancellable {
            httpProvider().newCall(request).execute()
        }

        if (outcome.isFailure) {
            val error = outcome.exceptionOrNull()
            if (error != null) {
                Logger.w(LOG_TAG, "遥测网络请求异常", error)
            } else {
                Logger.w(LOG_TAG, "遥测网络请求异常")
            }
            val backoff = computeBackoffMillis(sendState.failures + 1)
            ledger.recordSendFailure(nowMs + backoff)
            return
        }

        val response = outcome.getOrThrow()
        response.use { res ->
            when (res.code) {
                200 -> {
                    val bodyString = res.body?.string().orEmpty()
                    handleSuccess(bodyString, snapshot, today, nowMs, config)
                }
                400 -> {
                    Logger.w(LOG_TAG, "遥测请求被服务端拒收 (400): ${res.body?.string()}")
                    ledger.recordSendFailure(nowMs + 24 * 3600_000L)
                }
                else -> {
                    Logger.w(LOG_TAG, "遥测服务响应异常: ${res.code}")
                    val backoff = computeBackoffMillis(sendState.failures + 1)
                    ledger.recordSendFailure(nowMs + backoff)
                }
            }
        }
    }

    private suspend fun handleSuccess(
        bodyString: String,
        snapshot: SendSnapshot,
        today: LocalDate,
        nowMs: Long,
        config: RemoteConfig,
    ) {
        val root = tryOrNull { JSONObject(bodyString) }
        val ackArray = root?.optJSONArray("ack")
        val ackMap = mutableMapOf<String, Int>()
        if (ackArray != null) {
            for (i in 0 until ackArray.length()) {
                val key = ackArray.optString(i)
                snapshot.days.find { it.dayKey == key }?.let { page ->
                    ackMap[key] = page.rev
                }
            }
        }

        val nextRetryMs = nowMs + config.reportIntervalHours * 3600_000L
        ledger.onAck(ackMap, today, nowMs = nowMs, nextRetryMs = nextRetryMs)

        val rejectedArray = root?.optJSONArray("rejected")
        if (rejectedArray != null && rejectedArray.length() > 0) {
            val rejectedKeys = mutableListOf<String>()
            for (i in 0 until rejectedArray.length()) {
                val item = rejectedArray.optJSONObject(i)
                val key = item?.optString("day_key")
                if (!key.isNullOrEmpty()) {
                    rejectedKeys.add(key)
                }
            }
            if (rejectedKeys.isNotEmpty()) {
                ledger.onDropped(rejectedKeys)
            }
        }

        val serverConfigVersion = root?.optInt("config_version")?.takeIf { it >= 1 }
        if (serverConfigVersion != null && (config.configVersion == null || serverConfigVersion > config.configVersion)) {
            configClient.getOrFetch(versionCode, nowMs, force = true)
        }
    }

    private companion object {
        const val LOG_TAG = "niki914_zafiro_TelemetrySender"
    }
}
