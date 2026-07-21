package com.niki914.nexus.agentic.mod.feat.hyper.subhooks

import com.niki914.nexus.agentic.chat.ActiveTurnStore
import com.niki914.nexus.agentic.chat.TurnMode
import com.niki914.nexus.agentic.mod.feat.HookTarget
import com.niki914.nexus.agentic.mod.feat.SubHook
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiConfigProvider
import com.niki914.nexus.xposed.api.xevent.XEvent
import com.niki914.nexus.xposed.runtime.util.call
import com.niki914.nexus.xposed.runtime.util.getTag
import de.robv.android.xposed.XC_MethodHook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 在 InjectedLLM 模式下按白名单放行必要原生 Instruction，其余原生样式默认拦截。 */
class BlockNativeInstructionByWhitelistHook(
    private val scope: CoroutineScope
) : SubHook() {

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
        val eventContext = XEvent.snapshotContext()
        scope.launch {
            XEvent.withContext(eventContext) {
                XEvent.nativeResponseBlocked(
                    fields = mapOf(
                        "host" to "xiaoai",
                        "source" to name,
                        "kind" to "instruction",
                        "reason" to "instruction_blocked"
                    )
                )
            }
        }
    }
}
