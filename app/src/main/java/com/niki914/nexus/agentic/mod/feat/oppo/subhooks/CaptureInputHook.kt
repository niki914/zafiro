package com.niki914.nexus.agentic.mod.feat.oppo.subhooks

import com.niki914.nexus.agentic.mod.feat.HookTarget
import com.niki914.nexus.agentic.mod.feat.SubHook
import com.niki914.nexus.agentic.mod.feat.oppo.BreenoConfigProvider
import com.niki914.nexus.xposed.runtime.util.call
import de.robv.android.xposed.XC_MethodHook

/** 从宿主输入链路捕获用户 query 与 roomId，含去重逻辑，回调至 handleCapturedQuery。 */
class CaptureInputHook(
    private val onDataCenterInstanceResolved: (Any) -> Unit,
    private val onInput: (roomId: String, query: String) -> Unit
) : SubHook() {

    private val duplicateLock = Any()
    private var lastDeliveredInput: CapturedInput? = null

    private data class CapturedInput(
        val roomId: String,
        val query: String
    )

    override val hookTarget: HookTarget?
        get() = BreenoConfigProvider.CaptureInput.hookTarget

    override fun beforeHook(param: XC_MethodHook.MethodHookParam) {
        val queryArgIndex = BreenoConfigProvider.CaptureInput.queryArgIndex
        val chatTypeQuery = BreenoConfigProvider.CaptureInput.chatTypeQuery
        val beanGetChatTypeMethod = BreenoConfigProvider.CaptureInput.beanGetChatTypeMethod
        val beanGetRoomIdMethod = BreenoConfigProvider.CaptureInput.beanGetRoomIdMethod
        val beanGetContentMethod = BreenoConfigProvider.CaptureInput.beanGetContentMethod

        val bean = param.args.getOrNull(queryArgIndex) ?: return
        onDataCenterInstanceResolved(param.thisObject)

        val chatType = bean.call<Int>(beanGetChatTypeMethod) ?: return
        val roomId = bean.call<String>(beanGetRoomIdMethod) ?: return
        val query = bean.call<String>(beanGetContentMethod)

        if (chatType != chatTypeQuery) {
            return
        }

        if (query.isNullOrBlank() || shouldSuppress(roomId, query)) return

        onInput(roomId, query)
    }

    private fun shouldSuppress(roomId: String, query: String): Boolean =
        synchronized(duplicateLock) {
            val currentInput = CapturedInput(roomId = roomId, query = query)
            if (lastDeliveredInput == currentInput) {
                true
            } else {
                lastDeliveredInput = currentInput
                false
            }
        }
}
