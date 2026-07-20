package com.niki914.nexus.agentic.mod.feat

import com.niki914.nexus.agentic.chat.ActiveTurnStore
import com.niki914.nexus.agentic.chat.ConversationTurnState
import com.niki914.nexus.agentic.chat.TurnMode
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverTarget
import com.niki914.nexus.agentic.takeover.TakeoverDecision
import com.niki914.nexus.agentic.takeover.TakeoverResolver
import com.niki914.nexus.h.core.runtime.Hook
import com.niki914.nexus.agentic.runtime.client.AgentRuntimeClient
import com.niki914.nexus.agentic.runtime.ipc.AgentEvent
import com.niki914.nexus.h.xevent.XEvent
import com.niki914.nexus.h.xevent.XEventContext
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 抽象语音助手 Hook 基类，规范核心生命周期与功能职责
 *
 * TODO 统一所有业务下的 subhooks、云 config、AbstractAssistantHook 方法命名
 */
abstract class AbstractAssistantHook(protected val scope: CoroutineScope) : Hook {
    protected open val floatResumeGraceWindowMs: Long = 1500L
    var client: AgentRuntimeClient? = null

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
        onBeforeInstallHooks(lpparam)
        installSessionHooks(lpparam)
        installResponseHooks(lpparam)
        installInputHooks(lpparam) { roomId, query ->
            scope.launch {
                handleCapturedQuery(roomId, query)
            }
        }
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
        val takeoverFields = mapOf(
            "mode" to nextTurnState.mode.eventName(),
            "takeoverTarget" to takeoverDecision.target.name,
            "matchedRuleId" to takeoverDecision.matchedRuleId.orEmpty(),
            "matchedRuleName" to takeoverDecision.matchedRuleName.orEmpty(),
        )
        val eventContext = XEventContext(
            roomId = roomId,
            turnId = nextTurnState.turnId,
            fields = takeoverFields
        )
        XEvent.setContext(eventContext)
        XEvent.withContext(eventContext) {
            onTurnStateChanged(nextTurnState)
            XEvent.inputCaptured(
                fields = mapOf("queryLength" to query.length)
            )
            XEvent.turnDecided(
                fields = takeoverFields + mapOf(
                    "queryLength" to query.length
                )
            )

            if (nextTurnState.mode == TurnMode.NativeTakeover) {
                client?.cancel()
                return@withContext
            }

            dispatchQueryToLLM(
                turnId = nextTurnState.turnId,
                roomId = roomId,
                query = query
            )
        }
    }

    protected open suspend fun onTurnStateChanged(state: ConversationTurnState) = Unit

    protected open suspend fun resolveTakeover(query: String): TakeoverDecision {
        val rules = XRepo.takeoverRules.list()
        val defaultTarget = XRepo.takeoverRules.getDefaultTarget()
        return TakeoverResolver.resolve(query, rules, defaultTarget)
    }

    private fun TurnMode.eventName(): String = when (this) {
        TurnMode.InjectedLLM -> "InjectedLLM"
        TurnMode.NativeTakeover -> "NativeTakeover"
    }

    protected open suspend fun onSessionReset() {
        client?.resetConversation()
        ActiveTurnStore.clear()
        XEvent.clearContext()
    }

    protected open fun onToolRunning(turnId: Long, roomId: String, toolName: String, toolLabel: String) = Unit
    protected open fun onToolSucceeded(turnId: Long, roomId: String, toolName: String, outputText: String?) = Unit
    protected open fun onToolFailed(turnId: Long, roomId: String, toolName: String, message: String) = Unit
    protected open fun onTurnError(turnId: Long, roomId: String, message: String, errorCode: String?) = Unit
    protected open fun onTurnCancelled(turnId: Long, roomId: String) = Unit

    protected abstract fun installSessionHooks(lpparam: XC_LoadPackage.LoadPackageParam)

    protected abstract fun installResponseHooks(lpparam: XC_LoadPackage.LoadPackageParam)

    /**
     * 监听输入逻辑，当捕获到用户输入时，调用 [onInput] 回调
     */
    protected abstract fun installInputHooks(
        lpparam: XC_LoadPackage.LoadPackageParam,
        onInput: (roomId: String, query: String) -> Unit
    )

    /**
     * 将查询分发给 LLM SDK
     */
    protected open suspend fun dispatchQueryToLLM(turnId: Long, roomId: String, query: String) {
        val eventContext = XEvent.snapshotContext()
        XEvent.withContext(eventContext) {
            val cl = client
            if (cl == null) {
                onTurnError(turnId, roomId, "Runtime client not initialized", "SERVICE_UNAVAILABLE")
                return@withContext
            }
            cl.submit(query = query) { event ->
                when (event.eventType) {
                    "TextDelta" -> scope.launch {
                        renderStreamCard(turnId, roomId, event.text!!, event.isFirst, event.isFinal)
                    }
                    "ToolRunning" -> onToolRunning(turnId, roomId, event.toolName!!, event.toolLabel!!)
                    "ToolSucceeded" -> onToolSucceeded(turnId, roomId, event.toolName!!, event.toolOutput)
                    "ToolFailed" -> onToolFailed(turnId, roomId, event.toolName!!, event.toolError.orEmpty())
                    "Completed" -> scope.launch {
                        renderStreamCard(turnId, roomId, event.text!!, false, true)
                    }
                    "Error" -> onTurnError(turnId, roomId, event.errorMessage.orEmpty(), event.errorCode)
                    "Cancelled" -> onTurnCancelled(turnId, roomId)
                }
            }.onFailure { error ->
                onTurnError(turnId, roomId, error.message ?: "Service unavailable", "SERVICE_UNAVAILABLE")
            }
        }
    }

    /**
     * 渲染流式返回的大模型文本卡片
     * @param turnId 当前轮次 ID
     * @param roomId 当前会话 roomId
     * @param chunk 累加后的流式文本块
     * @param isFirst 是否为第一片
     * @param isFinal 是否为最后一片
     */
    protected abstract suspend fun renderStreamCard(
        turnId: Long,
        roomId: String,
        chunk: String,
        isFirst: Boolean,
        isFinal: Boolean
    )
}
