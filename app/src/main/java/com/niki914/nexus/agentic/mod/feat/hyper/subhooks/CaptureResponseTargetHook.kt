package com.niki914.nexus.agentic.mod.feat.hyper.subhooks

import com.niki914.logging.Logger
import com.niki914.nexus.agentic.mod.feat.HookTarget
import com.niki914.nexus.agentic.mod.feat.SubHook
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiConfigProvider
import de.robv.android.xposed.XC_MethodHook

/** 在宿主创建响应目标时捕获目标对象，供后续文字流分片注入。 */
class CaptureResponseTargetHook(
    private val onCaptured: (target: Any) -> Unit = {}
) : SubHook() {

    private companion object {
        const val LOG_TAG = "niki914_nexus_CaptureResponseTarget"
    }

    override val hookTarget: HookTarget?
        get() = XiaoaiConfigProvider.CaptureResponseTarget.hookTarget

    override fun beforeHook(param: XC_MethodHook.MethodHookParam) {
        val instruction = param.args.firstOrNull() ?: return
        val dialogId = resolveDialogId(instruction, param.thisObject)
        if (dialogId.isNullOrBlank()) {
            Logger.d(
                LOG_TAG,
                "capture skipped dialogId blank targetClass=${param.thisObject?.javaClass?.name}"
            )
            return
        }
        onCaptured(param.thisObject)
        Logger.i(
            LOG_TAG,
            "response target captured targetClass=${param.thisObject?.javaClass?.name} " +
                "dialogId=$dialogId"
        )
    }
}
