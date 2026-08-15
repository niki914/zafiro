package com.niki914.nexus.xposed.runtime

import com.niki914.logging.Logger
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

abstract class IXposed : IXposedHookLoadPackage {

    private companion object {
        const val LOG_TAG = "niki914_nexus_IXposed"
    }

    sealed interface Target {
        data class All(val mainProcessOnly: Boolean = true) : Target

        data class Filter(
            val packages: List<String>,
            val processName: String? = null,
            val mainProcessOnly: Boolean = true
        ) : Target

        companion object {
            fun all(mainProcessOnly: Boolean = true) = All(mainProcessOnly)

            fun filter(vararg name: String, mainProcessOnly: Boolean = true) = Filter(
                packages = listOf(*name),
                mainProcessOnly = mainProcessOnly
            )

            fun filterProcess(vararg name: String, processName: String) = Filter(
                packages = listOf(*name),
                processName = processName,
                mainProcessOnly = false
            )
        }
    }

    abstract fun onLoad(params: XC_LoadPackage.LoadPackageParam)

    open fun getTarget(): Target = Target.All(mainProcessOnly = true)

    override fun handleLoadPackage(lpparams: XC_LoadPackage.LoadPackageParam?) {
        if (lpparams == null) {
            Logger.w(LOG_TAG, "params is null!")
            onXParamsNull()
            return
        }

        if (shouldHandle(lpparams)) {
            Logger.i(LOG_TAG, "awaken in process: ${lpparams.processName}")
            onLoad(lpparams)
        }
    }

    private fun shouldHandle(lpparams: XC_LoadPackage.LoadPackageParam): Boolean {
        return when (val target = getTarget()) {
            is Target.All -> {
                if (target.mainProcessOnly) lpparams.isMainProcess else true
            }

            is Target.Filter -> {
                val pkgMatch = lpparams.packageName in target.packages
                if (!pkgMatch) return false

                if (target.processName != null) {
                    lpparams.processName == target.processName
                } else if (target.mainProcessOnly) {
                    lpparams.isMainProcess
                } else {
                    true
                }
            }
        }.also {
            if (!it) {
                Logger.d(LOG_TAG, "process filtered: ${lpparams.processName}")
            }
        }
    }

    private val XC_LoadPackage.LoadPackageParam.isMainProcess: Boolean
        get() = this.processName == this.packageName

    open fun onXParamsNull() {}
}