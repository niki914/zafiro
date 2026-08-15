package com.niki914.nexus.agentic.mod.feat

import com.niki914.logging.Logger
import com.niki914.nexus.xposed.runtime.core.runtime.Hook
import com.niki914.nexus.xposed.runtime.util.hookMethod
import com.niki914.nexus.xposed.runtime.util.resolveParamTypes
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

abstract class SubHook : Hook {

    private companion object {
        const val LOG_TAG = "niki914_nexus_SubHook"
    }

    override val name: String = this::class.java.simpleName

    open val hookTarget: HookTarget? = null

    open fun beforeHook(param: XC_MethodHook.MethodHookParam) {}

    open fun afterHook(param: XC_MethodHook.MethodHookParam) {}

    override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        val target = hookTarget
        if (target == null) {
            Logger.w(LOG_TAG, "onHook skipped, hookTarget not configured name=$name")
            return
        }

        val paramTypes = resolveParamTypes(target.methodParams, lpparam)
        if (paramTypes == null) {
            Logger.w(
                LOG_TAG,
                "onHook paramTypes resolve failed name=$name " +
                    "target=${target.ownerClass}#${target.methodName} " +
                    "params=${target.methodParams}"
            )
            return
        }

        when (target.hookTiming) {
            "after", "before" -> {
                Logger.d(
                    LOG_TAG,
                    "onHook install name=$name " +
                        "target=${target.ownerClass}#${target.methodName} " +
                        "timing=${target.hookTiming}"
                )
                lpparam.hookMethod(
                    className = target.ownerClass,
                    methodName = target.methodName,
                    *paramTypes,
                    before = { param -> beforeHook(param) },
                    after = { param -> afterHook(param) }
                )
            }

            else -> Unit
        }
    }
}
