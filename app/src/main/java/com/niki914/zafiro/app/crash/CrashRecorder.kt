package com.niki914.zafiro.app.crash

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import com.niki914.zafiro.app.BuildConfig
import com.niki914.zafiro.app.R
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

object CrashRecorder {
    const val CRASH_FILE_NAME = ".last_crash.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    @Volatile
    private var foregroundActivity: String? = null

    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                foregroundActivity = activity::class.java.simpleName
            }

            override fun onActivityPaused(activity: Activity) {
                if (foregroundActivity == activity::class.java.simpleName) {
                    foregroundActivity = null
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(application, thread, throwable)
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    fun handleUncaughtException(context: Context, thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val timeFormatted = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
        }.getOrDefault(now.toString())

        val report = CrashReport(
            timestamp = now,
            timeFormatted = timeFormatted,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            isDebug = BuildConfig.DEBUG,
            deviceManufacturer = Build.MANUFACTURER.orEmpty(),
            deviceModel = Build.MODEL.orEmpty(),
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            threadName = thread.name,
            exceptionType = throwable::class.java.name,
            message = throwable.message,
            stackTrace = throwable.stackTraceToString(),
            foregroundActivity = foregroundActivity,
            timeSinceStartupMillis = uptimeSinceStart(),
        )

        val file = File(context.filesDir, CRASH_FILE_NAME)
        runCatching {
            val content = json.encodeToString(report)
            file.writeText(content)
        }.onFailure {
            runCatching {
                file.writeText(
                    """
                    TIME: $timeFormatted
                    THREAD: ${thread.name}
                    EXCEPTION: ${throwable::class.java.name}
                    MESSAGE: ${throwable.message}
                    STACKTRACE:
                    ${throwable.stackTraceToString()}
                    """.trimIndent()
                )
            }
        }
    }

    fun peekPendingCrash(context: Context): CrashReport? {
        val report = readPendingCrash(context)
        deletePendingCrash(context)
        return report
    }

    /**
     * 秒崩报告不依赖 UI 取走：在 Compose 之前直接把 markdown 送进剪贴板并 Toast，
     * 文件随即消费掉，弹窗路径因此不会重复提示。
     * Toast 与剪贴板都由系统持有，进程随后再崩也不影响送达。
     */
    fun consumeStartupCrash(context: Context) {
        val report = readPendingCrash(context) ?: return
        if (!report.isStartupCrash) return

        deletePendingCrash(context)
        runCatching {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(null, report.formatAsMarkdown()))
            Toast.makeText(
                context.applicationContext,
                // 由调用方的 context 解析文案，系统按 resId 取资源会退回系统语言
                context.getString(R.string.crash_startup_copied_toast),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun readPendingCrash(context: Context): CrashReport? {
        val file = File(context.filesDir, CRASH_FILE_NAME)
        if (!file.exists()) return null

        val rawText = runCatching { file.readText() }.getOrNull()
        if (rawText.isNullOrBlank()) return null

        return runCatching {
            json.decodeFromString<CrashReport>(rawText)
        }.getOrElse {
            CrashReport(
                timestamp = System.currentTimeMillis(),
                timeFormatted = "Unknown",
                appVersionName = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE.toLong(),
                isDebug = BuildConfig.DEBUG,
                deviceManufacturer = Build.MANUFACTURER.orEmpty(),
                deviceModel = Build.MODEL.orEmpty(),
                androidVersion = "API ${Build.VERSION.SDK_INT}",
                threadName = "Unknown",
                exceptionType = "RawCrashLog",
                message = "Failed to parse structured JSON report",
                stackTrace = rawText,
                foregroundActivity = null,
            )
        }
    }

    private fun deletePendingCrash(context: Context) {
        File(context.filesDir, CRASH_FILE_NAME).delete()
    }

    /** 距进程启动的毫秒数；测量失败返回 -1：宁可退化成老的弹窗路径，也不能因为测量丢掉整份报告。 */
    private fun uptimeSinceStart(): Long = runCatching {
        SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
    }.getOrDefault(-1L)
}
