package com.niki914.nexus.agentic.mod.feat.hyper.subhooks

import com.niki914.logging.Logger
import com.niki914.nexus.agentic.chat.ActiveTurnStore
import com.niki914.nexus.agentic.chat.TurnMode
import com.niki914.nexus.agentic.mod.feat.HookTarget
import com.niki914.nexus.agentic.mod.feat.SubHook
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiConfigProvider
import de.robv.android.xposed.XC_MethodHook

/** 在 InjectedLLM 模式下拦截原生 TTS 播放调用，阻止注入回复期间的原生语音播报。 */
class BlockNativeTtsPlaybackHook : SubHook() {

    private companion object {
        const val LOG_TAG = "niki914_nexus_BlockNativeTts"
    }

    override val hookTarget: HookTarget?
        get() = XiaoaiConfigProvider.BlockNativeTtsPlayback.hookTarget

    override fun beforeHook(param: XC_MethodHook.MethodHookParam) {
        val activeTurn = ActiveTurnStore.getCurrent()
        when (activeTurn?.mode) {
            TurnMode.InjectedLLM -> {
                param.result = true
                Logger.i(LOG_TAG, "native response blocked host=xiaoai source=$name kind=tts reason=tts_blocked")
            }

            TurnMode.NativeTakeover, null -> {
                Logger.d(LOG_TAG, "native tts pass host=xiaoai source=$name reason=takeover_${activeTurn?.mode}")
            }
        }
    }
}
