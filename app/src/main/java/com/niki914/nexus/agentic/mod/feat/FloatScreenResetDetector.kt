package com.niki914.nexus.agentic.mod.feat

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.niki914.nexus.xposed.runtime.util.hookMethod
import com.niki914.nexus.xposed.runtime.util.resolveParamTypes
import de.robv.android.xposed.callbacks.XC_LoadPackage

class FloatScreenResetDetector(
    private val graceWindowMs: Long = 1500L,
    private val onReset: () -> Unit
) {
    private var lastFloatResumeObservedElapsed: Long = 0
    private val floatResetHandler = Handler(Looper.getMainLooper())
    private var pendingFloatResetCheck: Runnable? = null

    fun install(
        lpparam: XC_LoadPackage.LoadPackageParam,
        detachTarget: HookTarget?,
        resumeTarget: HookTarget?
    ) {
        if (detachTarget == null || resumeTarget == null) return

        installHookTargetObserver(
            lpparam = lpparam,
            target = detachTarget,
            onObserved = ::onFloatScreenDetachObserved
        )
        installHookTargetObserver(
            lpparam = lpparam,
            target = resumeTarget,
            onObserved = ::onFloatResumeObserved
        )
    }

    private fun onFloatScreenDetachObserved(target: HookTarget, thisObject: Any?) {
        val detachObservedElapsed = SystemClock.elapsedRealtime()
        val instanceInfo =
            thisObject?.let { "${it.javaClass.simpleName}@${Integer.toHexString(it.hashCode())}" }
                ?: "null"

        pendingFloatResetCheck?.let {
            floatResetHandler.removeCallbacks(it)
        }

        val check = Runnable {
            val resumeObservedElapsed = lastFloatResumeObservedElapsed
            val timeDiff = resumeObservedElapsed - detachObservedElapsed
            val absTimeDiff = kotlin.math.abs(timeDiff)
            val isResumedAroundDetach = absTimeDiff <= graceWindowMs

            if (!isResumedAroundDetach) {
                onReset()
            }
        }
        pendingFloatResetCheck = check
        floatResetHandler.postDelayed(check, graceWindowMs)
    }

    private fun onFloatResumeObserved(target: HookTarget, thisObject: Any?) {
        lastFloatResumeObservedElapsed = SystemClock.elapsedRealtime()
        val instanceInfo =
            thisObject?.let { "${it.javaClass.simpleName}@${Integer.toHexString(it.hashCode())}" }
                ?: "null"
    }

    private fun installHookTargetObserver(
        lpparam: XC_LoadPackage.LoadPackageParam,
        target: HookTarget,
        onObserved: (HookTarget, Any?) -> Unit
    ) {
        val paramTypes = resolveParamTypes(target.methodParams, lpparam) ?: return
        registerBeforeOrAfterObserver(
            lpparam = lpparam,
            target = target,
            paramTypes = paramTypes,
            onObserved = onObserved
        )
    }

    private fun registerBeforeOrAfterObserver(
        lpparam: XC_LoadPackage.LoadPackageParam,
        target: HookTarget,
        paramTypes: Array<Class<*>>,
        onObserved: (HookTarget, Any?) -> Unit
    ) {
        when (target.hookTiming?.lowercase()) {
            "before" -> lpparam.hookMethod(
                className = target.ownerClass,
                methodName = target.methodName,
                *paramTypes,
                before = { param -> onObserved(target, param.thisObject) }
            )

            "after" -> lpparam.hookMethod(
                className = target.ownerClass,
                methodName = target.methodName,
                *paramTypes,
                after = { param -> onObserved(target, param.thisObject) }
            )

            else -> Unit
        }
    }
}
