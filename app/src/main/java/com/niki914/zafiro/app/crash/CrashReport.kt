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
) {
    fun formatAsMarkdown(): String = buildString {
        appendLine("### Crash Report")
        appendLine("- **Time**: $timeFormatted")
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
}
