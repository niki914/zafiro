package com.niki914.zafiro.chat.agentic.device

import android.content.Context
import android.content.Intent
import com.niki914.logging.Logger
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.api.screen.AppHit
import com.niki914.zafiro.api.screen.ShowResult
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.api.util.tryIgnore
import com.niki914.zafiro.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool

object AppLauncher {
    private const val LOG_TAG = "niki914_zafiro_AppLauncher"
    private const val LAUNCH_TIMEOUT_MS = 10_000L

    suspend fun launch(packageName: String?, appName: String?): ShowResult {
        val targetPackage = packageName?.takeIf { it.isNotBlank() }
        if (targetPackage != null) {
            return launchPackage(targetPackage)
        }
        val targetName = appName?.takeIf { it.isNotBlank() }
        if (targetName != null) {
            return launchByAppName(targetName)
        }
        return ShowResult.Failed("MISSING_REQUIRED_FIELD")
    }

    private suspend fun launchByAppName(appName: String): ShowResult {
        return when (val result = AppInfoProvider.cache().findByAppName(appName)) {
            is AppMatchResult.Found -> launchPackage(result.app.packageName)
            is AppMatchResult.Candidates -> ShowResult.Ambiguous(
                appName,
                result.apps.map { AppHit(it.packageName, it.appName) },
            )
            AppMatchResult.NotFound -> ShowResult.Failed("APP_NOT_FOUND")
        }
    }

    private suspend fun launchPackage(packageName: String): ShowResult {
        val context = ContextProvider.await().applicationContext
        val event = context.startApp(packageName)
        return when (event) {
            LaunchEvent.Launched -> ShowResult.Shown("App launched.")
            is LaunchEvent.Failed -> ShowResult.Failed(event.code)
        }
    }

    private suspend fun Context.startApp(packageName: String): LaunchEvent {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return LaunchEvent.Failed("NO_LAUNCHER_ACTIVITY")

        val componentName = launchIntent.component?.flattenToString()
        val command = if (componentName != null) {
            "am start -n '$componentName'"
        } else {
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p '$packageName'"
        }

        for (identity in listOf("root", "shizuku")) {
            val outcome = runCatchingCancellable {
                TerminalSessionPool.openAndExecute(
                    identity = identity,
                    cwd = null,
                    command = command,
                    timeoutMs = LAUNCH_TIMEOUT_MS,
                )
            }.getOrElse { e ->
                Logger.w(LOG_TAG, "am start exception identity=$identity: ${e.message}")
                null
            }
            val session = (outcome as? TerminalCommandOutcome.Success)?.session
                ?: (outcome as? TerminalCommandOutcome.Timeout)?.session
            if (session != null) {
                tryIgnore { TerminalSessionPool.close(session) }
            }
            if (outcome is TerminalCommandOutcome.Success && outcome.result.exitCode == 0) {
                val stdout = outcome.result.stdout.toByteArray().decodeToString()
                val stderr = outcome.result.stderr.toByteArray().decodeToString()
                val output = "$stdout\n$stderr"
                val hasError = output.contains("Error:", ignoreCase = true) ||
                        output.contains("Permission Denial", ignoreCase = true) ||
                        output.contains("SecurityException", ignoreCase = true)
                if (!hasError) {
                    Logger.d(LOG_TAG, "startApp succeeded via identity=$identity")
                    return LaunchEvent.Launched
                }
                Logger.w(LOG_TAG, "am start identity=$identity reported error: $output")
            } else {
                Logger.w(
                    LOG_TAG,
                    "am start failed identity=$identity outcome=${outcome?.let { it::class.simpleName }}"
                )
            }
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatchingCancellable {
            startActivity(launchIntent)
            LaunchEvent.Launched
        }.getOrElse { throwable ->
            Logger.w(LOG_TAG, "startActivity failed: ${throwable.message}")
            LaunchEvent.Failed("APP_LAUNCH_FAILED")
        }
    }

    private sealed interface LaunchEvent {
        data object Launched : LaunchEvent
        data class Failed(val code: String) : LaunchEvent
    }
}
