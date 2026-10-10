package com.niki914.zafiro.mod.feat

import com.niki914.logging.Logger
import com.niki914.xposed.runtime.runtime.Hook
import com.niki914.zafiro.chat.ActiveTurnStore
import com.niki914.zafiro.chat.ConversationTurnState
import com.niki914.zafiro.chat.TurnMode
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.runtime.client.AssistantFrame
import com.niki914.zafiro.runtime.client.AssistantTextSource
import com.niki914.zafiro.runtime.ipc.ToolItem
import com.niki914.zafiro.settings.model.RuntimeTakeoverTarget
import com.niki914.zafiro.takeover.TakeoverDecision
import com.niki914.zafiro.takeover.TakeoverResolver
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

abstract class AbstractAssistantHook(
    protected val scope: CoroutineScope,
    protected val textSource: AssistantTextSource,
) : Hook {
    protected open val floatResumeGraceWindowMs: Long = 1500L

    private companion object {
        const val LOG_TAG = "niki914_zafiro_AbstractAssistantHook"
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
            RuntimeTakeoverTarget.ZAFIRO -> TurnMode.InjectedLLM
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
                    val len = when (frame) {
                        is AssistantFrame.Update -> frame.content.length
                        is AssistantFrame.Error -> frame.message.length
                    }
                    Logger.i(
                        LOG_TAG,
                        "dispatch final frame turnId=$turnId " +
                                "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                                "contentLength=$len"
                    )
                }
                renderStreamCard(turnId, roomId, frame)
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
                AssistantFrame.Error(e.message ?: "Service unavailable"),
            )
        }
    }

    /**
     * 表现层格式化：将纯思考内容格式化为 Markdown 引用块。
     * 仅供纯文本宿主（如小爱同学）使用，子类可覆盖自定义。
     */
    open fun formatThinking(thinking: String): String =
        thinking.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }

    /**
     * 表现层格式化：将工具调用项格式化为 Markdown 状态标签。
     * 仅供纯文本宿主（如小爱同学）使用，子类可覆盖自定义。
     */
    open fun formatTool(tool: ToolItem): String =
        "`[${tool.name}] ${tool.status.name.lowercase()}`"

    /**
     * 供旧版/纯文本宿主（如小爱同学）使用的默认降级文本组装器。
     */
    protected open fun buildLegacyPlainText(frame: AssistantFrame): String = when (frame) {
        is AssistantFrame.Update -> {
            val sb = StringBuilder()
            if (frame.thinking != null && frame.thinking.text.isNotBlank()) {
                sb.append(formatThinking(frame.thinking.text)).append("\n\n")
            }
            for (tool in frame.tools) {
                val toolLine = formatTool(tool)
                if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                sb.append(toolLine)
            }
            if (frame.content.isNotEmpty()) {
                if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                sb.append(frame.content)
            }
            sb.toString().trimEnd()
        }
        is AssistantFrame.Error -> frame.message
    }

    /**
     * 流式渲染完整语义帧数据 [AssistantFrame]。
     * 新版 BreenoHook 可直接重写此方法，接收纯粹的 content、thinking 与 tools；
     * 默认实现将通过 [buildLegacyPlainText] 降级拼装纯文本，以 100% 兼容小爱同学等纯文本宿主。
     */
    protected open suspend fun renderStreamCard(
        turnId: Long,
        roomId: String,
        frame: AssistantFrame,
    ) {
        val legacyText = buildLegacyPlainText(frame)
        renderStreamCard(turnId, roomId, legacyText, frame.isFirst, frame.isFinal)
    }

    /** 将流式文本帧渲染到宿主 UI。XiaoAi 流式注入文本节点。 */
    protected open suspend fun renderStreamCard(
        turnId: Long,
        roomId: String,
        chunk: String,
        isFirst: Boolean,
        isFinal: Boolean
    ) {
        // 默认空实现，供重写 renderStreamCard(turnId, roomId, frame) 的子类兜底
    }
}
