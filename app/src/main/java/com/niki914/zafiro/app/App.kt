package com.niki914.zafiro.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.niki914.logging.Logger
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.permission.Permission
import com.niki914.permission.PermissionState
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.app.conversation.ConversationPersister
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.app.conversation.ConversationRuntimeLogger
import com.niki914.zafiro.app.overlay.ToolPermissionOverlay
import com.niki914.zafiro.chat.LLMController
import com.niki914.zafiro.chat.agentic.accessibility.AccessibilityController
import com.niki914.zafiro.chat.agentic.python.PyRuntime
import com.niki914.zafiro.chat.agentic.shell.AuthorizationContext
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionRequest
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionResponse
import com.niki914.zafiro.chat.runtime.ConversationOperation
import com.niki914.zafiro.chat.runtime.ConversationOperationBackend
import com.niki914.zafiro.chat.runtime.ConversationRuntime
import com.niki914.zafiro.chat.runtime.OperationReceipt
import com.niki914.zafiro.repo.UpdateCheckHolder
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.runtime.createAppRuntimeBridge
import com.niki914.zafiro.settings.RuntimeEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

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
        // 主进程唯一 ConversationRuntime：命令门面 + 唯一执行收集点，构造只订阅同源内容，
        // 不创建/重置/恢复任何会话。随后按同一实例启动独立日志消费者与持久化，
        // 并登记后台授权弹窗的原等待器响应接缝（Runtime 校验回合/请求身份后转发）。
        val conversationRuntime = ConversationRuntime(
            scope = applicationScope,
            operationBackend = AppConversationOperationBackend,
        )
        AppConversationRuntime.install(conversationRuntime)
        ConversationRuntimeLogger.start(conversationRuntime, applicationScope)
        // T3：消息级增量持久化器观察 runtime 同源内容流（底层即原 currentConversation），
        // 独立于 UI 生命周期——回合可能在宿主后台跑，ViewModel 已销毁时仍落盘
        ConversationPersister.start(applicationScope, conversationRuntime.conversation)
        conversationRuntime.registerBackgroundResponder(ToolPermissionOverlay::respond)
        RuntimeEnvironment.install(createAppRuntimeBridge())
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

        ToolPermissionCoordinator.backgroundConfirmationHandler = { request ->
            handleBackgroundConfirmation(this, request)
        }
        // 全部权限走 PermissionManager：ensureService 的门面注入（主 App 进程）。
        AccessibilityController.permissions = PermissionHolder.get(this)
    }

    private suspend fun handleBackgroundConfirmation(
        context: Context,
        request: ToolPermissionRequest,
    ): ToolPermissionResponse {
        // 挂起式等链路结果，不占线程；取消（Activity 销毁）时不吞，交由调用方协程处理
        // 系统权限链观察随捕获的执行身份下发，OVERLAY 渠道尝试/结果可归属当前回合
        val observation = coroutineContext[AuthorizationContext]?.permissionObservation
        val result = PermissionHolder.get(context).request(Permission.OVERLAY, observation)
        if (result.finalState != PermissionState.GRANTED) {
            return ToolPermissionResponse.DENIED_UNAVAILABLE
        }
        // 窗口加不上（权限被收回等）≠ 用户拒绝：失败走 DENIED_UNAVAILABLE
        val allowed = try {
            ToolPermissionOverlay.show(context, request)
        } catch (_: Throwable) {
            return ToolPermissionResponse.DENIED_UNAVAILABLE
        }
        return if (allowed) {
            ToolPermissionResponse.ALLOWED
        } else {
            ToolPermissionResponse.DENIED_BY_USER
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

/**
 * 主进程唯一 [ConversationRuntime] 的组装持有者（Group7/8 共享生产契约）。
 * [install] 只在 App.onCreate 调用一次；消费方用 [require] 取同一实例，不新建单例。
 */
internal object AppConversationRuntime {
    @Volatile
    private var instance: ConversationRuntime? = null

    fun install(runtime: ConversationRuntime) {
        instance = runtime
    }

    fun require(): ConversationRuntime = instance
        ?: error("ConversationRuntime is not installed yet")
}

/**
 * 会话操作后端：把原入口动作映射到既有会话层，Runtime 不吞并仓储/建档/reset 逻辑。
 * - Create：原 LLMController.ensureSession + ConversationRepo.createConversation 时序，
 *   回执为后端实际分配的持久化 ID。
 * - Restore/Switch：优先使用调用方已加载的 [SessionSnapshot]（不再二次读库）；
 *   仅 ID 调用方按 ID 查找，缺失时抛原异常。
 * - Reset：只调原 LLMController.resetConversation（偏好/草稿由 Home 自身处理）。
 */
private object AppConversationOperationBackend : ConversationOperationBackend {
    override suspend fun execute(operation: ConversationOperation): OperationReceipt =
        when (operation) {
            is ConversationOperation.Create -> {
                val id = LLMController.ensureSession()
                OperationReceipt(
                    persistedId = ConversationRepo.createConversation(id, operation.firstUserInput),
                )
            }

            is ConversationOperation.Restore -> {
                LLMController.openSession(operation.snapshot ?: requireSnapshot(operation.persistedId))
                OperationReceipt(persistedId = operation.persistedId)
            }

            is ConversationOperation.Switch -> {
                LLMController.openSession(operation.snapshot ?: requireSnapshot(operation.persistedId))
                OperationReceipt(persistedId = operation.persistedId)
            }

            ConversationOperation.Reset -> {
                LLMController.resetConversation()
                OperationReceipt()
            }
        }

    private suspend fun requireSnapshot(persistedId: String): SessionSnapshot =
        ConversationRepo.getConversation(persistedId)?.snapshot
            ?: error("conversation not found: $persistedId")
}
