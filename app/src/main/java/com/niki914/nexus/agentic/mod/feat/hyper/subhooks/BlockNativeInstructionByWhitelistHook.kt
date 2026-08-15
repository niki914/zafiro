package com.niki914.nexus.agentic.mod.feat.hyper.subhooks

import com.niki914.logging.Logger
import com.niki914.nexus.agentic.chat.ActiveTurnStore
import com.niki914.nexus.agentic.chat.TurnMode
import com.niki914.nexus.agentic.mod.feat.HookTarget
import com.niki914.nexus.agentic.mod.feat.SubHook
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiConfigProvider
import com.niki914.nexus.xposed.runtime.util.call
import com.niki914.nexus.xposed.runtime.util.getTag
import de.robv.android.xposed.XC_MethodHook

/** 在 InjectedLLM 模式下按白名单放行必要原生 Instruction，其余原生样式默认拦截。 */
class BlockNativeInstructionByWhitelistHook : SubHook() {

    private companion object {
        const val LOG_TAG = "niki914_nexus_BlockNativeInstruction"
    }

    override val hookTarget: HookTarget?
        get() = XiaoaiConfigProvider.BlockNativeInstructionWhitelist.hookTarget

    override fun beforeHook(param: XC_MethodHook.MethodHookParam) {
        val instruction = param.args.firstOrNull() ?: return
        if (instruction.getTag<Boolean>(injectedFlagKey()) == true) {
            return
        }

        val activeTurn = ActiveTurnStore.getCurrent()
        when (activeTurn?.mode) {
            TurnMode.InjectedLLM -> Unit
            TurnMode.NativeTakeover, null -> return
        }

        val config = XiaoaiConfigProvider.BlockNativeInstructionWhitelist
        val fullName = instruction.call<String>(config.instructionFullNameGetter)
        val allowedFullNames = config.allowedInstructionFullNames
        if (fullName != null && fullName in allowedFullNames) return

        param.result = null
        Logger.i(LOG_TAG, "native response blocked host=xiaoai source=$name kind=instruction reason=instruction_blocked")
    }
}
