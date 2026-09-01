package com.niki914.zafiro.mod.feat.hyper.subhooks

import com.niki914.logging.Logger
import com.niki914.xposed.runtime.util.call
import com.niki914.xposed.runtime.util.getTag
import com.niki914.zafiro.chat.ActiveTurnStore
import com.niki914.zafiro.chat.TurnMode
import com.niki914.zafiro.mod.feat.HookTarget
import com.niki914.zafiro.mod.feat.SubHook
import com.niki914.zafiro.mod.feat.hyper.XiaoaiConfigProvider
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
            Logger.d(
                LOG_TAG,
                "native instruction pass host=xiaoai source=$name reason=self_injected"
            )
            return
        }

        val activeTurn = ActiveTurnStore.getCurrent()
        when (activeTurn?.mode) {
            TurnMode.InjectedLLM -> Unit
            TurnMode.NativeTakeover, null -> {
                Logger.d(
                    LOG_TAG,
                    "native instruction pass host=xiaoai source=$name reason=takeover_${activeTurn?.mode}"
                )
                return
            }
        }

        val config = XiaoaiConfigProvider.BlockNativeInstructionWhitelist
        val fullName = instruction.call<String>(config.instructionFullNameGetter)
        val allowedFullNames = config.allowedInstructionFullNames
        if (fullName != null && fullName in allowedFullNames) {
            Logger.d(
                LOG_TAG,
                "native instruction pass host=xiaoai source=$name reason=whitelisted fullName=$fullName"
            )
            return
        }

        param.result = null
        Logger.i(
            LOG_TAG,
            "native response blocked host=xiaoai source=$name kind=instruction reason=instruction_blocked"
        )
    }
}
