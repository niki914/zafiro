package com.niki914.nexus.xposed.runtime.util

import android.app.Application
import com.niki914.nexus.xposed.api.util.ContextProvider
import com.niki914.nexus.xposed.runtime.core.runtime.Hook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Especially for context provider
 */
class ContextHook : Hook {
    override val name: String = "ContextHook"

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        lpparam.hookMethod(
            "android.app.Application",
            "onCreate",
            after = { param ->
                val appContext = param.thisObject as Application
                val isFirstProvide = ContextProvider.provide(appContext)
                if (isFirstProvide) {
                    XposedBridge.log("[$name] Context successfully provided: ${appContext.packageName}")
                }
            }
        )
    }
}