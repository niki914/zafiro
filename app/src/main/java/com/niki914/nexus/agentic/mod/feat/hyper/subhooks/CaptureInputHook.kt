package com.niki914.nexus.agentic.mod.feat.hyper.subhooks

import com.niki914.nexus.agentic.mod.feat.HookTarget
import com.niki914.nexus.agentic.mod.feat.SubHook
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiConfigProvider
import de.robv.android.xposed.XC_MethodHook

/** 从宿主输入链路捕获用户 query 与 dialogId，含去重逻辑，回调至 handleCapturedQuery。 */
class CaptureInputHook(
    private val onInput: (dialogId: String, query: String) -> Unit
) : SubHook() {

    private val duplicateLock = Any()
    private var lastDeliveredInput: CapturedInput? = null

    private data class CapturedInput(
        val dialogId: String,
        val query: String
    )

    override val hookTarget: HookTarget?
        get() = XiaoaiConfigProvider.CaptureInput.hookTarget

    override fun beforeHook(param: XC_MethodHook.MethodHookParam) {
        val dialogIdArgIndex = XiaoaiConfigProvider.CaptureInput.dialogIdArgIndex
        val queryArgIndex = XiaoaiConfigProvider.CaptureInput.queryArgIndex

        val dialogId = param.args.getOrNull(dialogIdArgIndex) as? String
        val query = param.args.getOrNull(queryArgIndex) as? String

        if (dialogId.isNullOrBlank() || query.isNullOrBlank() || shouldSuppress(
                dialogId,
                query
            )
        ) return

        onInput(dialogId, query)
    }

    private fun shouldSuppress(dialogId: String, query: String): Boolean =
        synchronized(duplicateLock) {
            val currentInput = CapturedInput(dialogId = dialogId, query = query)
            if (lastDeliveredInput == currentInput) {
                true
            } else {
                lastDeliveredInput = currentInput
                false
            }
        }
}
