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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.time.Clock

const val DEFAULT_TELEMETRY_BASE_URL = "https://zafiro-telemetry.niki27687.workers.dev"
private const val CONFIG_FILE_NAME = "config.json"
private const val PERIODIC_CHECK_INTERVAL_MS = 60 * 60 * 1000L
private const val INITIAL_REPORT_DELAY_MS = 3_000L

/**
 * [Telemetry] 的完整实现。
 *
 * 全仓仅在组合根装配，保留单主构造。重 I/O（APK 解析、安装事实落盘、远程配置）全在后台 IO 协程完成。
 */
class TelemetryImpl(
    context: Context,
    private val http: () -> OkHttpClient,
    baseUrl: String = DEFAULT_TELEMETRY_BASE_URL,
    clock: Clock = Clock.systemUTC(),
) : Telemetry {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        val versionCode = packageInfo.versionCode
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
        scope.launch {
            if (initialDelayMs > 0L) {
                delay(initialDelayMs)
            }
            sendNow()
        }
    }

    private fun startPeriodicLoop() {
        if (loopStarted) return
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
    ) = Unit

    override fun setEnvelopeCustom(key: CustomKeyDef, value: CustomValue?) = Unit

    override fun setDayCustom(key: CustomKeyDef, value: CustomValue?) = Unit

    private companion object {
        const val LOG_TAG = "niki914_zafiro_TelemetryImpl"
    }
}
