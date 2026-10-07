package com.niki914.zafiro.mod.feat.oppo

import com.niki914.logging.Logger
import com.niki914.zafiro.chat.ConversationTurnState
import com.niki914.zafiro.chat.TurnMode
import com.niki914.zafiro.mod.feat.AbstractAssistantHook
import com.niki914.zafiro.mod.feat.oppo.subhooks.BlockNativeCardHook
import com.niki914.zafiro.mod.feat.oppo.subhooks.CaptureInputHook
import com.niki914.zafiro.mod.feat.oppo.subhooks.FilterNativeDirectivesHook
import com.niki914.zafiro.mod.feat.oppo.subhooks.ResetConversationSignalHook
import com.niki914.zafiro.mod.feat.oppo.subhooks.SuppressCleanupHook
import com.niki914.zafiro.runtime.client.AssistantFrame
import com.niki914.zafiro.runtime.client.AssistantTextSource
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Breeno（小布助手）高层调度胶水层。
 *
 * 自身不包含任何具体类名反射或脏卡片装配逻辑，
 * 仅负责将 Xposed 输入/拦截/生命周期 SubHooks 与底层驱动 [BreenoCardRenderer] 串联。
 */
class BreenoHook(
    scope: CoroutineScope,
    textSource: AssistantTextSource,
) : AbstractAssistantHook(scope, textSource) {
    override val name: String = "BreenoHook"

    private companion object {
        const val LOG_TAG = "niki914_zafiro_BreenoHook"
    }

    private val cardRenderer = BreenoCardRenderer()

    override fun onBeforeInstallHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        Logger.i(LOG_TAG, "Initializing BreenoCardRenderer on package=${lpparam.packageName}")
        cardRenderer.init(lpparam.classLoader)
    }

    override suspend fun onTurnStateChanged(state: ConversationTurnState) {
        if (state.mode == TurnMode.NativeTakeover) {
            Logger.i(LOG_TAG, "Turn switched to NativeTakeover, clearing render session turnId=${state.turnId}")
            cardRenderer.clearSession(state.turnId)
        }
    }

    override suspend fun onSessionReset() {
        super.onSessionReset()
        Logger.i(LOG_TAG, "Session reset signal triggered, clearing render session")
        cardRenderer.clearSession()
    }

    override fun installSessionHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        ResetConversationSignalHook(
            onSessionReset = {
                scope.launch { onSessionReset() }
            }
        ).onHook(lpparam)

        installFloatScreenDetachHooks(
            lpparam = lpparam,
            detachTarget = BreenoConfigProvider.FloatScreenDetach.detachTarget,
            resumeTarget = BreenoConfigProvider.FloatScreenDetach.resumeTarget
        )
    }

    override fun installResponseHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        FilterNativeDirectivesHook(
            selfInjectedFlagKey = BreenoConfigProvider.CaptureResponseTarget.selfInjectedFlagKey
        ).onHook(lpparam)

        BlockNativeCardHook(
            selfInjectedFlagKey = BreenoConfigProvider.CaptureResponseTarget.selfInjectedFlagKey
        ).onHook(lpparam)

        SuppressCleanupHook().onHook(lpparam)
    }

    override fun installInputHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        onInput: (roomId: String, query: String) -> Unit
    ) {
        CaptureInputHook(
            onDataCenterInstanceResolved = { instance ->
                cardRenderer.setDataCenterInstance(instance)
            },
            onInput = onInput
        ).onHook(lpparam)
    }

    override suspend fun renderStreamCard(
        turnId: Long,
        roomId: String,
        frame: AssistantFrame,
    ) {
        when (frame) {
            is AssistantFrame.Update -> cardRenderer.renderUpdate(turnId, roomId, frame)
            is AssistantFrame.Error -> cardRenderer.renderError(turnId, roomId, frame.message)
        }
    }
}
