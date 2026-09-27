package com.niki914.zafiro.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.niki914.logging.Logger
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.app.conversation.ConversationPersister
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.app.notification.ResidentNotificationManager
import com.niki914.zafiro.app.overlay.FloatingBallOverlayManager
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.chat.agentic.python.PyRuntime
import com.niki914.zafiro.repo.UpdateCheckHolder
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.runtime.createAppRuntimeBridge
import com.niki914.zafiro.service.requireService
import com.niki914.zafiro.settings.RuntimeEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File

class App : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 日志 debug 门控：release 构建 DEBUG/VERBOSE 全停，仅 INFO+ 输出
        Logger.setDebugProvider { BuildConfig.DEBUG }
        // 非主进程（目前只有 `:python`）不初始化主进程状态：上下文与持久化只属于主进程
        //（否则 ContextProvider 从未 provide，PyRuntime.warmUp 会永远挂起）
        if (!isMainProcess()) return
        ContextProvider.provide(applicationContext)
        XRepo.init(this.applicationContext)
        ConversationRepo.init(this.applicationContext)
        // T3：消息级增量持久化器（观察 LLMController 当前会话快照流，
        // 独立于 UI 生命周期——回合可能在宿主后台跑，ViewModel 已销毁时仍落盘）
        ConversationPersister.start(applicationScope)
        RuntimeEnvironment.install(createAppRuntimeBridge())
        // 依赖装配只发生在 AppServices（主进程组合根）
        AppServices.install(this)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        DynamicColors.applyToActivitiesIfAvailable(this)
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
            XRepo.seedPyTools()
        }
        applicationScope.launch {
            PyRuntime.warmUp()
        }

        observeFloatingBall()
        observeResidentNotification()
    }

    private fun observeResidentNotification() = launchFeatureFlagObserver(
        enabledFlow = XRepo.residentNotificationEnabledSetting,
        permission = Permission.NOTIFICATION,
        onPermissionMissing = { XRepo.setResidentNotificationEnabled(false) },
    ) { enabled ->
        if (enabled) ResidentNotificationManager.start(this) else ResidentNotificationManager.stop(this)
    }

    private fun observeFloatingBall() = launchFeatureFlagObserver(
        enabledFlow = XRepo.floatingBallEnabledSetting,
        permission = Permission.OVERLAY,
        onPermissionMissing = { XRepo.setFloatingBallEnabled(false) },
    ) { enabled ->
        if (enabled) FloatingBallOverlayManager.show(this) else FloatingBallOverlayManager.dismiss()
    }

    /**
     * 开关类功能的统一门禁：开关被打开但缺权限时回滚开关（不静默无效），
     * 其余情况交回 [onChange] 处理。
     */
    private fun launchFeatureFlagObserver(
        enabledFlow: Flow<Boolean>,
        permission: Permission,
        onPermissionMissing: suspend () -> Unit,
        onChange: (Boolean) -> Unit,
    ) {
        applicationScope.launch {
            enabledFlow.collect { enabled ->
                if (enabled && requireService<PermissionManager>().status(permission) != PermissionState.GRANTED) {
                    onPermissionMissing()
                } else {
                    onChange(enabled)
                }
            }
        }
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
