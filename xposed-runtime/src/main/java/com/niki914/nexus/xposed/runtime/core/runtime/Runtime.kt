package com.niki914.nexus.xposed.runtime.core.runtime

import com.niki914.nexus.xposed.api.util.xTry
import com.niki914.nexus.xposed.api.util.xtlog
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import kotlin.system.measureTimeMillis

/**
 * Orchestrates dual-track hook execution.
 * 编排双轨 hook 执行。
 *
 * Execution model:
 * - Executes synchronous hooks immediately on the main thread.
 * - Spawns a background coroutine to initialize DexKit and executes async hooks.
 * 执行模型：
 * - 在主线程上立即执行同步 hooks。
 * - 启动后台协程初始化 DexKit 并执行异步 hooks。
 */
class Runtime(
    private val scope: CoroutineScope,
    private val hooks: List<Hook>
) {
    companion object {
        private const val TAG = "XRuntime"
    }

    fun attach(params: XC_LoadPackage.LoadPackageParam) {
        val (dexkitHooks, syncHooks) = hooks.partition { it.useDexkit }

        // 1. Execute synchronous hooks immediately on the current (main) thread
        syncHooks.forEach { hook ->
            xTry { hook.onHook(params) }
        }

        // 2. Execute asynchronous hooks requiring DexKit scanning in a background coroutine
        if (dexkitHooks.isNotEmpty()) {
            scope.launch {
                xTry {
                    val ms = measureTimeMillis {
                        System.loadLibrary("dexkit")
                        DexKitBridge.create(params.appInfo.sourceDir).use { bridge ->
                            dexkitHooks.forEach { hook ->
                                xTry("Runtime#attaching: ${hook.name}") {
                                    hook.onHookWithDexkit(
                                        params,
                                        bridge
                                    )
                                }
                            }
                        }
                    }
                    xtlog(TAG, "DexKit scanner finished in ${ms}ms")
                }
            }
        }
    }
}
