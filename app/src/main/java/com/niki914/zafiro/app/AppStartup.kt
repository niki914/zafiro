package com.niki914.zafiro.app

import android.app.Application
import com.niki914.logging.Logger
import com.niki914.zafiro.api.McpService
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.app.notification.ResidentNotificationManager
import com.niki914.zafiro.app.notification.TurnNotificationManager
import com.niki914.zafiro.app.overlay.FloatingBallOverlayManager
import com.niki914.zafiro.app.screen.PhonePointer
import com.niki914.zafiro.app.wakelock.AgentWakeLockController
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.chat.agentic.device.AppInfoProvider
import com.niki914.zafiro.chat.agentic.python.PyRuntime
import com.niki914.zafiro.repo.UpdateCheckHolder
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val APP_LOG_TAG = "niki914_zafiro_App"

/**
 * 主进程装配完成之后的后台活。只从 [App.onCreate] 调用一次。
 *
 * [AppServices] 只登记实现。这里分两段：跑完即走的预热，和挂到进程结束的开关监听。
 * 预热在调用时立刻开始。Python 和应用目录不推迟，第一次使用不承担冷启动。
 *
 * 下面每段都写了调度器。这些活是在等网络、磁盘或 Binder，没有纯计算，所以没有 CPU 池。
 * 界面操作由悬浮球、指针、常亮锁自己回到主线程。
 */
internal object AppStartup {

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(application: Application) {
        launchWarmups()
        watchProcess(application)
    }

    private fun launchWarmups() {
        ioScope.launch(Dispatchers.IO) {
            UpdateCheckHolder.runOnce(BuildConfig.VERSION_NAME)
        }
        // 默认设置、技能拷贝、Py 工具种子：跨进程读写或 assets
        StartupWrites.track(
            ioScope,
            { XRepo.tryPutDefaultSettings() },
            { XRepo.skills.seedDefaults() },
            { XRepo.seedPyTools() },
        )
        // OpenRouter 公共目录预热：免鉴权后台抓，30 分钟内不重拉；失败保留旧缓存
        ioScope.launch(Dispatchers.IO) {
            XRepo.cloudFacts.refreshOpenRouterIfStale()
        }
        ioScope.launch(Dispatchers.IO) {
            PyRuntime.warmUp()
        }
        // 应用目录：queryIntentActivities 打到 system_server，再按语言读资源。线程在等 Binder。
        ioScope.launch(Dispatchers.IO) {
            runCatchingCancellable {
                AppInfoProvider.warmUp()
            }.onFailure { error ->
                Logger.w(APP_LOG_TAG, "app info warmup failed: ${error.message}")
            }
        }
        ioScope.launch(Dispatchers.IO) {
            delay(2_000L.milliseconds)
            runCatchingCancellable {
                ConversationRepo.cleanAllOrphanImages()
            }
        }
    }

    private fun watchProcess(application: Application) {
        observeFloatingBall(application)
        observeResidentNotification(application)
        observeMcpService()
        AgentWakeLockController.start(application, ioScope)
        PhonePointer.watch(ioScope)
        TurnNotificationManager.init(application)
    }

    private fun observeMcpService() {
        ioScope.launch(Dispatchers.IO) {
            val service = requireService<McpService>()
            val initialConfig = XRepo.mcpService.get()
            if (initialConfig.enabled) {
                service.start()
            }
            var lastConfig = initialConfig
            XRepo.mcpService.configFlow.collect { config ->
                if (config != lastConfig) {
                    lastConfig = config
                    if (config.enabled) {
                        service.restart()
                    } else {
                        service.stop()
                    }
                }
            }
        }
    }

    private fun observeResidentNotification(application: Application) = launchFeatureFlagObserver(
        enabledFlow = XRepo.residentNotificationEnabledSetting,
        permission = Permission.NOTIFICATION,
        onPermissionMissing = { XRepo.setResidentNotificationEnabled(false) },
    ) { enabled ->
        if (enabled) ResidentNotificationManager.start(application)
        else ResidentNotificationManager.stop(application)
    }

    private fun observeFloatingBall(application: Application) = launchFeatureFlagObserver(
        enabledFlow = XRepo.floatingBallEnabledSetting,
        permission = Permission.OVERLAY,
        onPermissionMissing = { XRepo.setFloatingBallEnabled(false) },
    ) { enabled ->
        if (enabled) FloatingBallOverlayManager.show(application)
        else FloatingBallOverlayManager.dismiss()
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
        ioScope.launch(Dispatchers.IO) {
            enabledFlow.collect { enabled ->
                if (enabled && requireService<PermissionManager>().status(permission) != PermissionState.GRANTED) {
                    onPermissionMissing()
                } else {
                    onChange(enabled)
                }
            }
        }
    }
}
