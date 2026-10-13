package com.niki914.zafiro.app.wakelock

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import com.niki914.logging.Logger
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.AgentManager
import com.niki914.zafiro.api.McpService
import com.niki914.zafiro.api.McpServiceStatus
import com.niki914.zafiro.api.model.isRunning
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.findService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference

/**
 * 后台 Agent 运行时的唤醒与常亮统一控制器。
 *
 * 统领两层保活机制：
 * 1. 系统级 CPU 唤醒锁：持有系统 [PowerManager.PARTIAL_WAKE_LOCK]，确保灭屏或切后台时
 *    CPU 不休眠，LLM 流式网络连接与工具执行持续进行（带 15 分钟超时上限兜底）；
 * 2. 窗口级屏幕常亮锁：接管 [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]，经 [Application.ActivityLifecycleCallbacks]
 *    自动跟踪前台 Activity，在前台且回答/执行中时保持屏幕常亮，页面退后台/暂停时自动解除。
 *
 * 需求方经 [WakeDemand] 收敛：Agent 会话（前台 [AgentControl] 与后台 [AgentManager.activeStates]）
 * 与 MCP 服务（[McpService.status]，缺失时降级为无需求）各自声明诉求，本控制器按诉求并集持有锁。
 * CPU 锁只要存在任一需求即持有；屏幕常亮仅在 [WakeDemand.needsScreenOn] 的需求存在时生效。
 */
object AgentWakeLockController {

    private const val LOG_TAG = "niki914_zafiro_AgentWakeLockController"
    private const val WAKE_LOCK_TAG = "zafiro:agent_execution_wakelock"
    private const val MAX_WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1000L

    private val mutex = Mutex()
    private var wakeLock: PowerManager.WakeLock? = null
    private var observeJob: Job? = null

    @Volatile
    private var isKeepAliveActive = false

    @Volatile
    private var currentForegroundActivity: WeakReference<Activity>? = null

    private val _shouldKeepScreenOn = MutableStateFlow(false)
    val shouldKeepScreenOn: StateFlow<Boolean> = _shouldKeepScreenOn.asStateFlow()

    /** 从各来源的当前状态算出需求集合。抽成纯函数以便单测直接覆盖分支。 */
    internal fun demandsOf(
        agentKeepScreenOn: Boolean,
        anyAgentRunning: Boolean,
        mcpKeepScreenOn: Boolean,
        mcpRunning: Boolean,
    ): Set<WakeDemand> = buildSet {
        if (agentKeepScreenOn && anyAgentRunning) {
            add(WakeDemand.AgentRunning)
        }
        if (mcpKeepScreenOn && mcpRunning) {
            add(WakeDemand.McpServing)
        }
    }

    fun start(context: Context, scope: CoroutineScope) {
        val application = (context.applicationContext as? Application) ?: (context as? Application)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG,
        )?.apply {
            setReferenceCounted(false)
        }

        application?.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentForegroundActivity = WeakReference(activity)
                applyWindowKeepScreenOn(activity, isKeepAliveActive)
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentForegroundActivity?.get() === activity) {
                    applyWindowKeepScreenOn(activity, false)
                    currentForegroundActivity = null
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (currentForegroundActivity?.get() === activity) {
                    currentForegroundActivity = null
                }
            }
        })

        observeJob?.cancel()
        observeJob = scope.launch {
            runCatching { XRepo.keepScreenOn() }
            val agentControl = findService<AgentControl>() ?: return@launch
            val agentManager = findService<AgentManager>() ?: return@launch
            // MCP 为可选需求方：缺失时只降级 MCP 防休眠，不影响 Agent 自身的保活。
            val mcpService = findService<McpService>()

            combine(
                XRepo.keepScreenOnSetting,
                agentControl.status,
                agentManager.activeStates,
                XRepo.mcpService.configFlow,
                mcpService?.status ?: MutableStateFlow(McpServiceStatus.Stopped),
            ) { agentKeepOn, mainStatus, activeStates, mcpConfig, mcpStatus ->
                demandsOf(
                    agentKeepScreenOn = agentKeepOn,
                    anyAgentRunning = mainStatus.isRunning ||
                            activeStates.values.any { it.isRunning },
                    mcpKeepScreenOn = mcpService != null && mcpConfig.keepScreenOn,
                    mcpRunning = mcpStatus is McpServiceStatus.Running,
                )
            }
                .distinctUntilChanged()
                .collect { demands ->
                    val holdCpu = demands.isNotEmpty()
                    val keepScreenOn = demands.any { it.needsScreenOn }
                    isKeepAliveActive = holdCpu
                    _shouldKeepScreenOn.value = keepScreenOn
                    applyWindowKeepScreenOn(currentForegroundActivity?.get(), keepScreenOn)
                    if (holdCpu) {
                        acquireCpuWakeLock()
                    } else {
                        releaseCpuWakeLock()
                    }
                    Logger.i(
                        LOG_TAG,
                        "wake lock updated: demands=$demands cpu=$holdCpu screenOn=$keepScreenOn"
                    )
                }
        }
    }

    private fun applyWindowKeepScreenOn(activity: Activity?, keepOn: Boolean) {
        val target = activity ?: return
        target.runOnUiThread {
            if (target.isFinishing || target.isDestroyed) return@runOnUiThread
            if (keepOn) {
                target.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                target.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private suspend fun acquireCpuWakeLock() {
        runCatching {
            mutex.withLock {
                val lock = wakeLock ?: return
                if (!lock.isHeld) {
                    Logger.i(
                        LOG_TAG,
                        "acquiring partial wake lock (timeout=${MAX_WAKE_LOCK_TIMEOUT_MS}ms)"
                    )
                    lock.acquire(MAX_WAKE_LOCK_TIMEOUT_MS)
                }
            }
        }.onFailure { e ->
            Logger.e(LOG_TAG, "failed to acquire wake lock: ${e.message}")
        }
    }

    private suspend fun releaseCpuWakeLock() {
        runCatching {
            mutex.withLock {
                val lock = wakeLock ?: return
                if (lock.isHeld) {
                    Logger.i(LOG_TAG, "releasing partial wake lock")
                    lock.release()
                }
            }
        }.onFailure { e ->
            Logger.e(LOG_TAG, "failed to release wake lock: ${e.message}")
        }
    }
}
