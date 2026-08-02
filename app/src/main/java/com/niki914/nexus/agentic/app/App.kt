package com.niki914.nexus.agentic.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Process
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.niki914.nexus.agentic.chat.agentic.python.PyRuntime
import com.niki914.nexus.agentic.app.conversation.ConversationRepo
import com.niki914.nexus.agentic.repo.UpdateCheckHolder
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.agentic.runtime.createAppRuntimeBridge
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.nexus.xposed.api.util.ContextProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // `:python` worker 进程只需 PythonWorkerService，跳过主进程全部初始化
        //（否则 ContextProvider 从未 provide，PyRuntime.warmUp 会永远挂起）
        if (isPythonWorkerProcess()) return
        ContextProvider.provide(applicationContext)
        XRepo.init(this.applicationContext)
        ConversationRepo.init(this.applicationContext)
        RuntimeEnvironment.install(createAppRuntimeBridge())
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        DynamicColors.applyToActivitiesIfAvailable(this)
        applicationScope.launch {
            XRepo.web.await()
        }
        applicationScope.launch {
            UpdateCheckHolder.runOnce(BuildConfig.VERSION_NAME)
        }
        applicationScope.launch {
            XRepo.tryPutDefaultSettings()
        }
        applicationScope.launch {
            XRepo.skills.seedDefaults()
        }
        applicationScope.launch {
            PyRuntime.warmUp()
        }
    }

    private fun isPythonWorkerProcess(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return manager.runningAppProcesses?.any {
            it.pid == Process.myPid() && it.processName == "$packageName:python"
        } ?: false
    }
}
