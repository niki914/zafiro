package com.niki914.nexus.agentic.mod.feat.oppo.subhooks

import com.niki914.logging.Logger
import com.niki914.nexus.agentic.chat.ActiveTurnStore
import com.niki914.nexus.agentic.chat.TurnMode
import com.niki914.nexus.agentic.mod.feat.HookTarget
import com.niki914.nexus.agentic.mod.feat.SubHook
import com.niki914.nexus.agentic.mod.feat.oppo.BreenoConfigProvider
import com.niki914.nexus.xposed.runtime.util.call
import de.robv.android.xposed.XC_MethodHook

/**
 * 在 InjectedLLM 模式下拦截原生回答卡片，避免 Breeno 侧基于回答卡片的全量刷新注入被原生回答覆盖。
 *
 * 逻辑：
 * - 检查 bean 的 chatType 是否为回答类型（answer），非回答类型直接放行
 * - 检查 bean 是否已包含自注入标记，若已标记则放行（不拦自己注入的卡片）
 * - 通过 ActiveTurnStore 读取当前轮次状态：
 *   - InjectedLLM → 拦截（param.result = null）
 *   - NativeTakeover → 放行
 *   - 无状态 → 保守放行
 */
class BlockNativeCardHook(
    private val selfInjectedFlagKey: String
) : SubHook() {

    private companion object {
        const val LOG_TAG = "niki914_nexus_BlockNativeCard"
    }

    override val hookTarget: HookTarget?
        get() = BreenoConfigProvider.CaptureResponseTarget.hookTarget

    override fun beforeHook(param: XC_MethodHook.MethodHookParam) {
        val bean = param.args[0] ?: return

        val chatType =
            bean.call<Int>(BreenoConfigProvider.CaptureResponseTarget.beanGetChatTypeMethod)
                ?: return
        val typeAnswer = BreenoConfigProvider.CaptureResponseTarget.chatTypeAnswer
        if (chatType != typeAnswer) {
            return
        }

        val isSelfInjected = bean.call<Any>(
            BreenoConfigProvider.CaptureResponseTarget.beanGetClientLocalDataMethod,
            selfInjectedFlagKey
        ) != null
        if (isSelfInjected) return

        val activeTurn = ActiveTurnStore.getCurrent()
        when (activeTurn?.mode) {
            TurnMode.InjectedLLM -> {
                param.result = null
                Logger.i(LOG_TAG, "native response blocked host=breeno source=$name reason=answer_card_blocked")
            }

            TurnMode.NativeTakeover, null -> Unit
        }
    }
}
