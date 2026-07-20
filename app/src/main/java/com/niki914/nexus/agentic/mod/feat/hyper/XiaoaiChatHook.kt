package com.niki914.nexus.agentic.mod.feat.hyper

import com.niki914.nexus.agentic.chat.ActiveTurnStore
import com.niki914.nexus.agentic.mod.feat.AbstractAssistantHook
import com.niki914.nexus.agentic.mod.feat.hyper.subhooks.BlockNativeInstructionByWhitelistHook
import com.niki914.nexus.agentic.mod.feat.hyper.subhooks.BlockNativeTtsPlaybackHook
import com.niki914.nexus.agentic.mod.feat.hyper.subhooks.CaptureInputHook
import com.niki914.nexus.agentic.mod.feat.hyper.subhooks.CaptureResponseTargetHook
import com.niki914.nexus.agentic.mod.feat.hyper.subhooks.RenderTextStreamCardHook
import com.niki914.nexus.h.xevent.XEvent
import com.niki914.nexus.h.xevent.XEventContext
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** XiaoAi 宿主主 Hook，编排全部子 Hook 安装、会话生命周期、关键词接管判定及 LLM 流式分片注入管线。 */
class XiaoaiChatHook( // TODO P2(由于标记 Beta 所以放缓) NewRoom / 卡片采用白名单模式避免放行不正确的卡片
    scope: CoroutineScope
) : AbstractAssistantHook(scope) {
    override val name: String = "XiaoaiChatHook"

    private var renderTextStreamCardHook: RenderTextStreamCardHook? = null

    @Volatile
    private var capturedResponseTarget: Any? = null
    private var targetReady = CompletableDeferred<Unit>()

    override suspend fun onSessionReset() {
        super.onSessionReset()
        client?.resetConversation()
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

    override suspend fun dispatchQueryToLLM(turnId: Long, roomId: String, query: String) {
        targetReady.cancel()
        targetReady = CompletableDeferred()

        val eventContext = XEvent.snapshotContext()
        XEvent.withContext(eventContext) {
            client!!.submit(query = query) { text, isFirst, isFinal ->
                scope.launch {
                    targetReady.await()
                    renderStreamCard(turnId, roomId, text, isFirst, isFinal)
                }
            }.onFailure { error ->
                scope.launch {
                    targetReady.await()
                    renderStreamCard(
                        turnId, roomId,
                        error.message ?: "Service unavailable",
                        true, true,
                    )
                }
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
