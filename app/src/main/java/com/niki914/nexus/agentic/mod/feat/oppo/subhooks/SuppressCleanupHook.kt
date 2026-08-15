package com.niki914.nexus.agentic.mod.feat.oppo.subhooks

import com.niki914.logging.Logger
import com.niki914.nexus.agentic.chat.ActiveTurnStore
import com.niki914.nexus.agentic.chat.TurnMode
import com.niki914.nexus.agentic.mod.feat.HookTarget
import com.niki914.nexus.agentic.mod.feat.SubHook
import com.niki914.nexus.agentic.mod.feat.oppo.BreenoConfigProvider
import de.robv.android.xposed.XC_MethodHook

class SuppressCleanupHook : SubHook() {

    private companion object {
        const val LOG_TAG = "niki914_nexus_SuppressCleanup"
    }

    override val hookTarget: HookTarget?
        get() = BreenoConfigProvider.SuppressCleanup.hookTarget

    override fun afterHook(param: XC_MethodHook.MethodHookParam) {
        val turnState = ActiveTurnStore.getCurrent() ?: return
        when (turnState.mode) {
            TurnMode.NativeTakeover -> {
                Logger.d(LOG_TAG, "cleanup pass host=breeno source=$name reason=native_takeover")
                return
            }

            TurnMode.InjectedLLM -> { /* proceed */
            }
        }

        val result = param.result ?: run {
            Logger.d(LOG_TAG, "cleanup pass host=breeno source=$name reason=null_result")
            return
        }
        val cleanOperationClass = BreenoConfigProvider.SuppressCleanup.cleanOperationClass
        val doNothingOperationClass = BreenoConfigProvider.SuppressCleanup.doNothingOperationClass

        val resultClass = result.javaClass
        val isCleanOperation = resultClass.name == cleanOperationClass ||
                resultClass.simpleName == cleanOperationClass
        if (!isCleanOperation) {
            Logger.d(LOG_TAG, "cleanup pass host=breeno source=$name reason=not_clean_operation")
            return
        }

        val classLoader = resultClass.classLoader ?: javaClass.classLoader
        val replacement = Class.forName(doNothingOperationClass, false, classLoader)
            .getDeclaredConstructor()
            .newInstance()
        param.result = replacement
        Logger.i(
            LOG_TAG,
            "cleanup suppressed host=breeno source=$name " +
                "cleanOperation=$cleanOperationClass"
        )
    }
}
