package com.niki914.nexus.agentic.mod.feat.hyper

import com.niki914.nexus.agentic.chat.ActiveTurnStore
import com.niki914.nexus.agentic.mod.feat.AbstractAssistantHook
import com.niki914.nexus.agentic.mod.feat.hyper.subhooks.BlockNativeInstructionByWhitelistHook
import com.niki914.nexus.agentic.mod.feat.hyper.subhooks.BlockNativeTtsPlaybackHook
import com.niki914.nexus.agentic.mod.feat.hyper.subhooks.CaptureInputHook
import com.niki914.nexus.agentic.mod.feat.hyper.subhooks.CaptureResponseTargetHook
import com.niki914.nexus.agentic.mod.feat.hyper.subhooks.RenderTextStreamCardHook
import com.niki914.nexus.agentic.runtime.client.AssistantTextSource
import com.niki914.nexus.h.xevent.XEvent
import com.niki914.nexus.h.xevent.XEventContext
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope

class XiaoaiChatHook(
    scope: CoroutineScope,
    textSource: AssistantTextSource,
) : AbstractAssistantHook(scope, textSource) {
    override val name: String = "XiaoaiChatHook"

    private var renderTextStreamCardHook: RenderTextStreamCardHook? = null

    @Volatile
    private var capturedResponseTarget: Any? = null
    private var targetReady = CompletableDeferred<Unit>()

    override suspend fun onSessionReset() {
        super.onSessionReset()
        targetReady.cancel()
        targetReady = CompletableDeferred()
        capturedResponseTarget = null
        renderTextStreamCardHook?.reset()
    }

    override fun installSessionHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        installFloatScreenDetachHooks(
            lpparam = lpparam,
            detachTarget = XiaoaiConfigProvider.FloatScreenDetach.detachTarget,
            resumeTarget = XiaoaiConfigProvider.FloatScreenDetach.resumeTarget
        )
    }

    override fun installResponseHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        CaptureResponseTargetHook(
            onCaptured = { target ->
                capturedResponseTarget = target
                targetReady.complete(Unit)
            }
        ).onHook(lpparam)

        BlockNativeInstructionByWhitelistHook(scope).onHook(lpparam)

        BlockNativeTtsPlaybackHook(scope).onHook(lpparam)

        renderTextStreamCardHook = RenderTextStreamCardHook()
            .also { it.onHook(lpparam) }
    }

    override fun installInputHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        onInput: (roomId: String, query: String) -> Unit
    ) {
        CaptureInputHook(onInput = onInput).onHook(lpparam)
    }

    // 覆盖基类：渲染前需等待宿主 UI 卡片就绪（TODO 死等风险：若 Hook 永不触发则挂死）
    override suspend fun dispatchQueryToLLM(turnId: Long, roomId: String, query: String) {
        targetReady.cancel()
        targetReady = CompletableDeferred()

        val eventContext = XEvent.snapshotContext()
        XEvent.withContext(eventContext) {
            try {
                textSource.submit(query).collect { frame ->
                    targetReady.await()
                    renderStreamCard(turnId, roomId, frame.text, frame.isFirst, frame.isFinal)
                }
            } catch (e: Exception) {
                targetReady.await()
                renderStreamCard(
                    turnId, roomId,
                    e.message ?: "Service unavailable",
                    true, true,
                )
            }
        }
    }

    override suspend fun renderStreamCard(
        turnId: Long,
        roomId: String,
        chunk: String,
        isFirst: Boolean,
        isFinal: Boolean
    ) {
        if (!ActiveTurnStore.isActiveInjection(turnId)) {
            return
        }

        renderTextStreamCardHook?.render(
            turnId = turnId,
            dialogId = roomId,
            target = capturedResponseTarget,
            chunk = chunk,
            isFirst = isFirst,
            isFinal = isFinal
        )
    }
}
