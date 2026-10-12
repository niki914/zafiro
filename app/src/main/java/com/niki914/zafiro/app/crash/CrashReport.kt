package com.niki914.zafiro.app.crash

import kotlinx.serialization.Serializable

@Serializable
data class CrashReport(
    val timestamp: Long,
    val timeFormatted: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val isDebug: Boolean,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidVersion: String,
    val threadName: String,
    val exceptionType: String,
    val message: String?,
    val stackTrace: String,
    val foregroundActivity: String? = null,
    /** 崩溃时距进程启动的毫秒数；负数 = 未能测量（旧报告或测量失败）。 */
    val timeSinceStartupMillis: Long = -1L,
) {
    /** 启动窗口内崩溃：首帧前就崩掉，弹窗路径取不到报告，改由启动早期直接送剪贴板。 */
    val isStartupCrash: Boolean
        get() = timeSinceStartupMillis in 0..STARTUP_CRASH_WINDOW_MILLIS

    fun formatAsMarkdown(): String = buildString {
        appendLine("### Crash Report")
        appendLine("- **Time**: $timeFormatted")
        appendLine("- **Uptime at Crash**: ${if (timeSinceStartupMillis < 0) "N/A" else "$timeSinceStartupMillis ms"}")
        appendLine("- **Version**: $appVersionName ($appVersionCode) [Debug: $isDebug]")
        appendLine("- **Device**: $deviceManufacturer $deviceModel ($androidVersion)")
        appendLine("- **Thread**: $threadName")
        appendLine("- **Foreground Activity**: ${foregroundActivity ?: "N/A"}")
        appendLine("- **Exception**: $exceptionType")
        if (!message.isNullOrBlank()) {
            appendLine("- **Message**: $message")
        }
        appendLine()
        appendLine("```stacktrace")
        appendLine(stackTrace.trim())
        appendLine("```")
    }

    companion object {
        const val STARTUP_CRASH_WINDOW_MILLIS = 3_000L
    }
}
