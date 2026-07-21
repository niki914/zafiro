package com.niki914.nexus.xposed.runtime.core.runtime

import com.niki914.nexus.xposed.api.util.xtlog
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 负责当前进程内 Runtime 的一次性安装。
 * 不尝试跨进程共享实例；每个宿主进程都会拥有自己独立的一份状态。
 */
object RuntimeBootstrap {
    private const val TAG = "RuntimeBootstrap"

    @Volatile
    private var runtime: Runtime? = null

    fun installIfNeeded(
        params: XC_LoadPackage.LoadPackageParam,
        create: () -> Runtime
    ): Runtime {
        runtime?.let {
            xtlog(TAG, "runtime already installed in process=${params.processName}")
            return it
        }

        return synchronized(this) {
            runtime?.let {
                xtlog(TAG, "runtime already installed in process=${params.processName}")
                return@synchronized it
            }

            create().also { created ->
                created.attach(params)
                runtime = created
                xtlog(TAG, "runtime installed in process=${params.processName}")
            }
        }
    }
}