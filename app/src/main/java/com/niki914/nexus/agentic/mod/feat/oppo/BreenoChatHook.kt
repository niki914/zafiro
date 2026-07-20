package com.niki914.nexus.agentic.mod.feat.oppo

import com.niki914.nexus.agentic.chat.ActiveTurnStore
import com.niki914.nexus.agentic.chat.ConversationTurnState
import com.niki914.nexus.agentic.chat.TurnMode
import com.niki914.nexus.agentic.mod.feat.AbstractAssistantHook
import com.niki914.nexus.agentic.mod.feat.oppo.subhooks.BlockNativeCardHook
import com.niki914.nexus.agentic.mod.feat.oppo.subhooks.CaptureInputHook
import com.niki914.nexus.agentic.mod.feat.oppo.subhooks.ResetConversationSignalHook
import com.niki914.nexus.agentic.mod.feat.oppo.subhooks.SuppressCleanupHook
import com.niki914.nexus.h.util.call
import com.niki914.nexus.h.util.findClass
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Breeno 宿主主 Hook，走回答卡片层做注入：复用同一张卡片并持续用累计文本全量刷新内容。 */
class BreenoChatHook(scope: CoroutineScope) : AbstractAssistantHook(scope) {

    override val name: String = "BreenoChatHook"

    private var dataCenterInstance: Any? = null
    private var viewBeanClass: Class<*>? = null
    private val renderSessionMutex = Mutex()
    private var currentRenderSession: BreenoRenderSession? = null

    private data class BreenoRenderSession(
        val turnId: Long,
        val recordId: String,
        var bean: Any? = null
    )

    override fun onBeforeInstallHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        viewBeanClass = lpparam.findClass(BreenoConfigProvider.RenderCard.viewBeanClass)
    }

    override suspend fun onTurnStateChanged(state: ConversationTurnState) {
        if (state.mode == TurnMode.NativeTakeover) {
            clearRenderSession(state.turnId)
        }
    }

    override suspend fun onSessionReset() {
        super.onSessionReset()
        client?.resetConversation()
        clearRenderSession()
    }

    override fun installSessionHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        ResetConversationSignalHook(
            onSessionReset = {
                scope.launch {
                    onSessionReset()
                }
            }
        ).onHook(lpparam)
        installFloatScreenDetachHooks(
            lpparam = lpparam,
            detachTarget = BreenoConfigProvider.FloatScreenDetach.detachTarget,
            resumeTarget = BreenoConfigProvider.FloatScreenDetach.resumeTarget
        )
    }

    override fun installResponseHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        BlockNativeCardHook(
            scope = scope,
            selfInjectedFlagKey = BreenoConfigProvider.CaptureResponseTarget.selfInjectedFlagKey
        ).onHook(lpparam)

        SuppressCleanupHook().onHook(lpparam)
    }

    override fun installInputHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        onInput: (roomId: String, query: String) -> Unit
    ) {
        CaptureInputHook(
            onDataCenterInstanceResolved = { instance ->
                if (dataCenterInstance == null) {
                    dataCenterInstance = instance
                }
            },
            onInput = onInput
        ).onHook(lpparam)
    }

    override suspend fun renderStreamCard(
        turnId: Long,
        roomId: String,
        chunk: String,
        isFirst: Boolean,
        isFinal: Boolean
    ) {
        if (!ActiveTurnStore.isActiveInjection(turnId)) {
            if (isFinal) {
                clearRenderSession(turnId)
            }
            return
        }

        val beanClass = viewBeanClass ?: return
        val dataCenterInsertMessageMethod =
            BreenoConfigProvider.RenderCard.dataCenterInsertMessageMethod
        val dataCenterUpdateMessageMethod =
            BreenoConfigProvider.RenderCard.dataCenterUpdateMessageMethod

        val mockBeanMethodsUnit = BreenoConfigProvider.RenderCard.mockBeanMethodsUnit
        val mockBeanMethodsReplayOnUpdate =
            BreenoConfigProvider.RenderCard.mockBeanMethodsReplayOnUpdate
        val mockBeanLocalDataUnit = BreenoConfigProvider.RenderCard.mockBeanLocalDataUnit
        val typeAnswer = BreenoConfigProvider.RenderCard.chatTypeAnswer
        val hideFeedbackViewLocalDataKey =
            BreenoConfigProvider.RenderCard.hideFeedbackViewLocalDataKey
        val setChatTypeMethod = BreenoConfigProvider.RenderCard.beanSetChatTypeMethod
        val setRoomIdMethod = BreenoConfigProvider.RenderCard.beanSetRoomIdMethod
        val setRecordIdMethod = BreenoConfigProvider.RenderCard.beanSetRecordIdMethod
        val setContentMethod = BreenoConfigProvider.RenderCard.beanSetContentMethod
        val setFinalMethod = BreenoConfigProvider.RenderCard.beanSetFinalMethod
        val setFirstSliceMethod = BreenoConfigProvider.RenderCard.beanSetFirstSliceMethod
        val addClientLocalDataMethod = BreenoConfigProvider.RenderCard.beanAddClientLocalDataMethod

        val renderSession = obtainRenderSession(turnId, roomId)
        if (isFirst || renderSession.bean == null) {
            val bean = beanClass.newInstance()
            bean.call<Unit>(setChatTypeMethod, typeAnswer)
            bean.call<Unit>(setRoomIdMethod, roomId)
            bean.call<Unit>(setRecordIdMethod, renderSession.recordId)
            mockBeanMethodsUnit.forEach { (methodName, value) ->
                bean.call<Unit>(methodName, value)
            }
            mockBeanLocalDataUnit.forEach { (key, value) ->
                bean.call<Unit>(addClientLocalDataMethod, key, value)
            }
            BreenoFeedbackAssembler.attachIfNeeded(bean)
            renderSession.bean = bean
        }

        val mockBean = renderSession.bean ?: return

        mockBean.call<Unit>(setContentMethod, chunk)
        mockBean.call<Unit>(setFinalMethod, isFinal)
        mockBean.call<Unit>(setFirstSliceMethod, isFirst)
        mockBeanMethodsUnit.filter { it.first in mockBeanMethodsReplayOnUpdate }
            .forEach { (methodName, value) ->
                mockBean.call<Unit>(methodName, value)
            }

        if (isFirst) {
            dataCenterInstance?.call<Unit>(dataCenterInsertMessageMethod, mockBean)
        } else {
            dataCenterInstance?.call<Unit>(dataCenterUpdateMessageMethod, mockBean, false)
        }

        if (isFinal) {
            mockBeanLocalDataUnit.firstOrNull { (key, _) -> key == hideFeedbackViewLocalDataKey }
                ?.let {
                    val bool = (it.second as? Boolean) ?: true
                    mockBean.call<Unit>(addClientLocalDataMethod, it.first, !bool)
                }
            dataCenterInstance?.call<Unit>(dataCenterUpdateMessageMethod, mockBean, false)
            clearRenderSession(turnId)
        }
    }

    private suspend fun obtainRenderSession(turnId: Long, roomId: String): BreenoRenderSession =
        renderSessionMutex.withLock {
            currentRenderSession?.takeIf { it.turnId == turnId } ?: BreenoRenderSession(
                turnId = turnId,
                recordId = "mock_record_${roomId}_${turnId}"
            ).also { currentRenderSession = it }
        }

    private suspend fun clearRenderSession(turnId: Long) {
        renderSessionMutex.withLock {
            if (currentRenderSession?.turnId == turnId) {
                currentRenderSession = null
            }
        }
    }

    private suspend fun clearRenderSession() {
        renderSessionMutex.withLock {
            currentRenderSession = null
        }
    }
}
