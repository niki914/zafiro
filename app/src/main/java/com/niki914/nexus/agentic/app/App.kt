package com.niki914.nexus.agentic.app

import android.app.ActivityManager
import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.niki914.logging.Logger
import com.niki914.nexus.agentic.chat.agentic.python.PyRuntime
import com.niki914.nexus.agentic.app.conversation.ConversationPersister
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
        // 日志 debug 门控：release 构建 DEBUG/VERBOSE 全停，仅 INFO+ 输出
        Logger.setDebugProvider { BuildConfig.DEBUG }
        // `:python` worker 进程只需 PythonWorkerService，跳过主进程全部初始化
        //（否则 ContextProvider 从未 provide，PyRuntime.warmUp 会永远挂起）
        if (isPythonWorkerProcess()) return
        ContextProvider.provide(applicationContext)
        XRepo.init(this.applicationContext)
        ConversationRepo.init(this.applicationContext)
        // T3：消息级增量持久化器（观察 LLMController 当前会话快照流，
        // 独立于 UI 生命周期——回合可能在宿主后台跑，ViewModel 已销毁时仍落盘）
        ConversationPersister.start(applicationScope)
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
        // getMyMemoryState 是官方静态 API（API 23+，无权限），比 runningAppProcesses
        // （官方标注仅用于调试/进程管理 UI）更适合作为核心分支判断。
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.processName == "$packageName:python"
    }
}
