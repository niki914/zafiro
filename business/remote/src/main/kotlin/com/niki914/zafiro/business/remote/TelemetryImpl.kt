package com.niki914.zafiro.business.remote

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.annotation.MainThread
import com.niki914.logging.Logger
import com.niki914.zafiro.api.telemetry.CustomKeyDef
import com.niki914.zafiro.api.telemetry.CustomValue
import com.niki914.zafiro.api.telemetry.EventDef
import com.niki914.zafiro.api.telemetry.Telemetry
import com.niki914.zafiro.api.telemetry.TelemetryContract
import com.niki914.zafiro.api.telemetry.sanitizeLabel
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.business.remote.activity.ActivityTracker
import com.niki914.zafiro.business.remote.channel.ApkChannelReader
import com.niki914.zafiro.business.remote.config.RemoteConfigClient
import com.niki914.zafiro.business.remote.config.RemoteConfigStore
import com.niki914.zafiro.business.remote.ledger.FileLedgerStore
import com.niki914.zafiro.business.remote.ledger.InstallFacts
import com.niki914.zafiro.business.remote.ledger.InstallFactsStore
import com.niki914.zafiro.business.remote.ledger.Ledger
import com.niki914.zafiro.business.remote.ledger.TELEMETRY_DIRECTORY_NAME
import com.niki914.zafiro.business.remote.profile.DeviceProfileReader
import com.niki914.zafiro.business.remote.profile.DeviceSignals
import com.niki914.zafiro.business.remote.sender.TelemetrySender
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.time.Clock

private const val CONFIG_FILE_NAME = "config.json"
private const val PERIODIC_CHECK_INTERVAL_MS = 60 * 60 * 1000L
private const val INITIAL_REPORT_DELAY_MS = 3_000L
private const val TELEMETRY_PREFS_NAME = "telemetry_facts"
private const val KEY_TELEMETRY_VERSION = "telemetry_version"

/**
 * [Telemetry] 的完整实现。
 *
 * 全仓仅在组合根装配，保留单主构造。重 I/O（APK 解析、安装事实落盘、远程配置）全在后台 IO 协程完成。
 */
class TelemetryImpl(
    context: Context,
    private val http: () -> OkHttpClient,
    private val baseUrl: String,
    clock: Clock = Clock.systemUTC(),
    private val onboardingCompletedProvider: suspend () -> Boolean = { false },
    private val telemetryEnabledProvider: suspend () -> Boolean = { true },
) : Telemetry {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Logger.e(LOG_TAG, "遥测后台任务未捕获异常", throwable)
        }
    )
    private val versionCode: Int
    @Volatile
    private var cachedInstallFacts: InstallFacts? = null
    private val ledger: Ledger
    private val configClient: RemoteConfigClient
    private val sender: TelemetrySender
    private val tracker: ActivityTracker
    private var loopStarted = false

    init {
        val directory = File(context.filesDir, TELEMETRY_DIRECTORY_NAME)
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        versionCode = packageInfo.versionCode
        val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val apkFile = File(context.applicationInfo.sourceDir)
        val firstInstallTimeMs = packageInfo.firstInstallTime
        val lastUpdateTimeMs = packageInfo.lastUpdateTime

        val installFactsStore = InstallFactsStore(directory)
        ledger = Ledger(clock, FileLedgerStore(directory))
        val configStore = RemoteConfigStore(File(directory, CONFIG_FILE_NAME))
        configClient = RemoteConfigClient(http, baseUrl, configStore)

        sender = TelemetrySender(
            httpProvider = http,
            baseUrl = baseUrl,
            ledger = ledger,
            configClient = configClient,
            versionCode = versionCode,
            isDebug = isDebug,
            apkFile = apkFile,
            installFactsProvider = {
                cachedInstallFacts?.let { return@TelemetrySender it }
                val stampedChannel = ApkChannelReader.readStampedName(apkFile)
                val facts = installFactsStore.resolve(
                    firstInstallTimeMs = firstInstallTimeMs,
                    lastUpdateTimeMs = lastUpdateTimeMs,
                    versionCode = versionCode,
                    isDebug = isDebug,
                    stampedChannel = stampedChannel,
                )
                cachedInstallFacts = facts
                facts
            },
            installerProvider = {
                context.packageManager.getInstallerPackageName(context.packageName)
                    ?.takeIf { it.isNotEmpty() }
                    ?.take(TelemetryContract.LIMIT_INSTALLER_LENGTH)
            },
            signalsProvider = { DeviceProfileReader.signalsFrom(context) },
            clock = clock,
        )

        tracker = ActivityTracker(
            ledger = ledger,
            versionCode = versionCode,
            scope = scope,
            onActive = { triggerSend(initialDelayMs = INITIAL_REPORT_DELAY_MS) },
            onColdStart = {
                runCatchingCancellable {
                    val sp = context.getSharedPreferences(TELEMETRY_PREFS_NAME, Context.MODE_PRIVATE)
                    val lastVersion = sp.getInt(KEY_TELEMETRY_VERSION, -1)
                    val cohort = when {
                        lastVersion == -1 -> {
                            if (onboardingCompletedProvider()) "first_legacy_update" else "first_new"
                        }
                        lastVersion < versionCode -> "first_update"
                        else -> "launcher"
                    }
                    if (lastVersion < versionCode) {
                        sp.edit().putInt(KEY_TELEMETRY_VERSION, versionCode).apply()
                    }
                    ledger.setDayCustom(CustomKeyDef.UserCohort.wireName, CustomValue.Str(cohort), versionCode)
                    ledger.record(
                        event = EventDef.AppColdStart.wireName,
                        label = cohort,
                        n = 1,
                        custom = emptyMap(),
                        versionCode = versionCode,
                    )
                }.onFailure { error -> Logger.w(LOG_TAG, "冷启动状态标记失败", error) }
            },
            telemetryEnabledProvider = telemetryEnabledProvider,
        )
    }

    /**
     * 绑定活跃监听。需在主线程调用（内部注册 ProcessLifecycleOwner 依赖主线程）。
     */
    @MainThread
    fun attach() {
        tracker.attach()
        startPeriodicLoop()
    }

    private suspend fun sendNow() {
        runCatchingCancellable { sender.maybeSend() }
            .onFailure { error -> Logger.w(LOG_TAG, "遥测触发上报失败", error) }
    }

    private fun triggerSend(initialDelayMs: Long = 0L) {
        if (baseUrl.isBlank()) return
        scope.launch {
            if (initialDelayMs > 0L) {
                delay(initialDelayMs)
            }
            sendNow()
        }
    }

    private fun startPeriodicLoop() {
        if (baseUrl.isBlank() || loopStarted) return
        loopStarted = true
        scope.launch {
            while (isActive) {
                delay(PERIODIC_CHECK_INTERVAL_MS)
                triggerSend()
            }
        }
    }

    override fun record(
        event: EventDef,
        label: String?,
        n: Int,
        custom: Map<String, CustomValue>,
    ) {
        scope.launch {
            runCatchingCancellable {
                val isUsageEvent = event !is EventDef.AppColdStart &&
                    event !is EventDef.OnboardComplete &&
                    event !is EventDef.FirstOnboard
                if (isUsageEvent && !telemetryEnabledProvider()) {
                    return@launch
                }
                val cleanLabel = label?.let { sanitizeLabel(it) }
                ledger.record(
                    event = event.wireName,
                    label = cleanLabel,
                    n = n,
                    custom = custom,
                    versionCode = versionCode,
                )
            }.onFailure { error -> Logger.w(LOG_TAG, "遥测记账失败: ${event.wireName}", error) }
        }
    }

    override fun setEnvelopeCustom(key: CustomKeyDef, value: CustomValue?) {
        scope.launch {
            runCatchingCancellable {
                ledger.setEnvelopeCustom(key.wireName, value)
            }.onFailure { error -> Logger.w(LOG_TAG, "设置顶层扩展失败: ${key.wireName}", error) }
        }
    }

    override fun setDayCustom(key: CustomKeyDef, value: CustomValue?) {
        scope.launch {
            runCatchingCancellable {
                ledger.setDayCustom(key.wireName, value, versionCode)
            }.onFailure { error -> Logger.w(LOG_TAG, "设置日扩展失败: ${key.wireName}", error) }
        }
    }

    private companion object {
        const val LOG_TAG = "niki914_zafiro_TelemetryImpl"
    }
}
