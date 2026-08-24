package com.niki914.nexus.agentic.mod.feat

import com.niki914.logging.Logger
import com.niki914.nexus.agentic.chat.ActiveTurnStore
import com.niki914.nexus.agentic.chat.ConversationTurnState
import com.niki914.nexus.agentic.chat.TurnMode
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.agentic.runtime.client.AssistantTextSource
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverTarget
import com.niki914.nexus.agentic.takeover.TakeoverDecision
import com.niki914.nexus.agentic.takeover.TakeoverResolver
import com.niki914.nexus.xposed.runtime.core.runtime.Hook
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

abstract class AbstractAssistantHook(
    protected val scope: CoroutineScope,
    protected val textSource: AssistantTextSource,
) : Hook {
    protected open val floatResumeGraceWindowMs: Long = 1500L

    private companion object {
        const val LOG_TAG = "niki914_nexus_AbstractAssistantHook"
    }

    protected fun installFloatScreenDetachHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        detachTarget: HookTarget?,
        resumeTarget: HookTarget?
    ) {
        FloatScreenResetDetector(
            graceWindowMs = floatResumeGraceWindowMs,
            onReset = { scope.launch { onSessionReset() } }
        ).install(
            lpparam = lpparam,
            detachTarget = detachTarget,
            resumeTarget = resumeTarget
        )
    }

    final override fun onHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        Logger.i(LOG_TAG, "onHook start package=${lpparam.packageName}")
        onBeforeInstallHooks(lpparam)
        Logger.i(LOG_TAG, "onHook onBeforeInstallHooks done")
        installSessionHooks(lpparam)
        Logger.i(LOG_TAG, "onHook installSessionHooks done")
        installResponseHooks(lpparam)
        Logger.i(LOG_TAG, "onHook installResponseHooks done")
        installInputHooks(lpparam) { roomId, query ->
            scope.launch {
                handleCapturedQuery(roomId, query)
            }
        }
        Logger.i(LOG_TAG, "onHook installInputHooks done")
    }

    protected open fun onBeforeInstallHooks(lpparam: XC_LoadPackage.LoadPackageParam) = Unit

    private suspend fun handleCapturedQuery(roomId: String, query: String) {
        val takeoverDecision = resolveTakeover(query)
        val turnMode = when (takeoverDecision.target) {
            RuntimeTakeoverTarget.NATIVE_ASSISTANT -> TurnMode.NativeTakeover
            RuntimeTakeoverTarget.NEXUS -> TurnMode.InjectedLLM
        }
        val nextTurnState = ConversationTurnState().nextTurn(
            query = query,
            mode = turnMode
        )
        ActiveTurnStore.setCurrent(nextTurnState)
        Logger.i(LOG_TAG, "input captured roomId=$roomId queryLength=${query.length}")
        Logger.i(
            LOG_TAG,
            "turn decided mode=${nextTurnState.mode.eventName()} " +
                "takeoverTarget=${takeoverDecision.target.name} " +
                "matchedRuleId=${takeoverDecision.matchedRuleId.orEmpty()} " +
                "matchedRuleName=${takeoverDecision.matchedRuleName.orEmpty()}"
        )
        onTurnStateChanged(nextTurnState)

        if (nextTurnState.mode == TurnMode.NativeTakeover) {
            textSource.cancel()
            return
        }

        dispatchQueryToLLM(
            turnId = nextTurnState.turnId,
            roomId = roomId,
            query = query
        )
    }

    protected open suspend fun onTurnStateChanged(state: ConversationTurnState) = Unit

    protected open suspend fun resolveTakeover(query: String): TakeoverDecision {
        val rules = XRepo.takeoverRules.list()
        val defaultTarget = XRepo.takeoverRules.getDefaultTarget()
        Logger.d(
            LOG_TAG,
            "resolve takeover rules=${rules.size} defaultTarget=$defaultTarget " +
                "queryLength=${query.length}"
        )
        return TakeoverResolver.resolve(query, rules, defaultTarget)
    }

    private fun TurnMode.eventName(): String = when (this) {
        TurnMode.InjectedLLM -> "InjectedLLM"
        TurnMode.NativeTakeover -> "NativeTakeover"
    }

    protected open suspend fun onSessionReset() {
        textSource.resetConversation()
        ActiveTurnStore.clear()
    }

    protected abstract fun installSessionHooks(lpparam: XC_LoadPackage.LoadPackageParam)

    protected abstract fun installResponseHooks(lpparam: XC_LoadPackage.LoadPackageParam)

    protected abstract fun installInputHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        onInput: (roomId: String, query: String) -> Unit
    )

    // 默认通过 textSource 提交查询并渲染；子类可覆盖以插入宿主特定的等待逻辑
    protected open suspend fun dispatchQueryToLLM(turnId: Long, roomId: String, query: String) {
        val startedAtMs = System.currentTimeMillis()
        var firstFrameLogged = false
        try {
            textSource.submit(query).collect { frame ->
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    Logger.i(
                        LOG_TAG,
                        "dispatch first frame turnId=$turnId " +
                            "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                    )
                }
                if (frame.isFinal) {
                    Logger.i(
                        LOG_TAG,
                        "dispatch final frame turnId=$turnId " +
                            "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                            "textLength=${frame.text.length}"
                    )
                }
                renderStreamCard(turnId, roomId, frame.text, frame.isFirst, frame.isFinal)
            }
            Logger.i(
                LOG_TAG,
                "dispatch completed turnId=$turnId elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        } catch (e: Exception) {
            Logger.e(
                LOG_TAG,
                "dispatch failed turnId=$turnId errorType=${e::class.simpleName} " +
                    "message=${e.message} elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            renderStreamCard(
                turnId, roomId,
                // Intentionally hardcoded: runs inside host process; must not reference app resources across IPC/Xposed boundary.
                e.message ?: "Service unavailable",
                true, true,
            )
        }
    }

    /** 将流式文本帧渲染到宿主 UI。Breeno 全量刷新单卡片，XiaoAi 流式注入文本节点。 */
    protected abstract suspend fun renderStreamCard(
        turnId: Long,
        roomId: String,
        chunk: String,
        isFirst: Boolean,
        isFinal: Boolean
    )
}
