package com.niki914.zafiro.app

import android.app.ActivityManager
import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.niki914.logging.Logger
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.app.crash.CrashRecorder
import com.niki914.zafiro.app.debug.DebugLogFile
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.runtime.createAppRuntimeBridge
import com.niki914.zafiro.settings.RuntimeEnvironment
import java.io.File

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        CrashRecorder.install(this)
        Logger.setDebugProvider { BuildConfig.DEBUG }
        DebugLogFile.install(this)

        if (!isMainProcess()) return
        // A staged FULL backup restore must be applied before any settings file or database is opened.
        com.niki914.zafiro.backup.BackupManager.applyPendingRestore(this)
        ContextProvider.provide(applicationContext)
        XRepo.init(this.applicationContext)
        ConversationRepo.init(this.applicationContext)
        RuntimeEnvironment.install(createAppRuntimeBridge())

        AppServices.install(this)
        AppStartup.start(this)

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    /**
     * 是否主进程。进程名优先读 `/proc/self/cmdline`（内核直接给出命令行，
     * 不依赖框架侧的内存状态），`getMyMemoryState` 仅作兜底；
     * 两者都取不到进程名时按主进程处理——宁可多初始化，不能让主进程缺初始化。
     */
    private fun isMainProcess(): Boolean {
        val fromProc = runCatching {
            File("/proc/self/cmdline").readBytes()
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .decodeToString()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        val name = fromProc ?: ActivityManager.RunningAppProcessInfo().also {
            ActivityManager.getMyMemoryState(it)
        }.processName?.takeIf { it.isNotEmpty() }
        return name == null || name == packageName
    }

}
