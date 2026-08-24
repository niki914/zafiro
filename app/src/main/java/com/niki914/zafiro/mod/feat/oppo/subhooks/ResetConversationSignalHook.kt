package com.niki914.zafiro.mod.feat.oppo.subhooks

import com.niki914.zafiro.mod.feat.HookTarget
import com.niki914.zafiro.mod.feat.SubHook
import com.niki914.zafiro.mod.feat.oppo.BreenoConfigProvider
import de.robv.android.xposed.XC_MethodHook

/** 检测宿主显式发出的会话重置信号 */
class ResetConversationSignalHook(
    private val onSessionReset: () -> Unit
) : SubHook() {

    override val hookTarget: HookTarget?
        get() = BreenoConfigProvider.ResetConversationSignal.hookTarget

    override fun afterHook(param: XC_MethodHook.MethodHookParam) {
        onSessionReset()
    }
}
