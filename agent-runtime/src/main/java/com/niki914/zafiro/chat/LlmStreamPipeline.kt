package com.niki914.zafiro.chat

import com.niki914.logging.Logger
import com.niki914.okia.Okia
import com.niki914.okia.TurnOptions
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.xposed.api.util.LockState
import com.niki914.zafiro.chat.agentic.accessibility.AccessibilityController
import com.niki914.zafiro.chat.agentic.stream.LlmStreamEventMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal fun Throwable.toUserErrorCode(): LlmErrorCode? {
    return when (this) {
        is LlmConfigRequiredException -> LlmErrorCode.ConfigRequired
        is IllegalStateException -> LlmErrorCode.TurnConflict
        else -> null
    }
}

internal fun Throwable.eventTypeName(): String = this::class.simpleName ?: "Throwable"

/**
 * LLM 回合流式执行管道。
 *
 * 包装 [Okia.send] 交互，负责流事件映射、首帧统计、异常映射、终态兜底与守卫。
 */
internal class LlmStreamPipeline {
    private companion object {
        const val LOG_TAG = "niki914_zafiro_LlmStreamPipeline"

        /** 每回合的 miss 在此以内算 breakpoint 粒度噪声，与 pi 同值。 */
        const val CACHE_NOISE_FLOOR_TOKENS = 1024L
    }

    private val turnActiveFlow = MutableStateFlow(false)
    val turnActive: StateFlow<Boolean> get() = turnActiveFlow

    suspend fun execute(
        session: Okia,
        query: String,
        images: List<ContentBlock.Image>,
        systemPrompt: String,
        channel: SendChannel<LlmStreamEvent>,
    ) {
        turnActiveFlow.value = true
        val startedAtMs = System.currentTimeMillis()
        var streamErrorReported = false
        var streamTerminated = false
        var firstFrameLogged = false

        suspend fun emit(event: LlmStreamEvent) {
            if (event is LlmStreamEvent.Error) streamErrorReported = true
            if (event is LlmStreamEvent.Error || event is LlmStreamEvent.Completed) {
                streamTerminated = true
            }
            channel.send(event)
        }

        try {
            Logger.i(
                LOG_TAG,
                "round started queryLength=${query.length} isUnlocked=${LockState.isUnlocked()}"
            )
            val result = try {
                session.send(
                    text = query,
                    images = images,
                    options = TurnOptions(systemPrompt = systemPrompt),
                ) { event ->
                    val mapped = LlmStreamEventMapper.map(event, startedAtMs)
                    if (event is TurnEvent.TurnCompleted) {
                        logCacheAccounting(event.message)
                    }
                    mapped?.let {
                        if (!firstFrameLogged && it is LlmStreamEvent.TextDelta) {
                            firstFrameLogged = true
                            Logger.i(
                                LOG_TAG,
                                "first frame elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                                        "charsPerSecond=${it.charsPerSecond}"
                            )
                        }
                        if (it is LlmStreamEvent.Error && !streamErrorReported) {
                            Logger.e(
                                LOG_TAG,
                                "stream error stage=session_event code=${it.code} " +
                                        "errorType=${it.throwable?.eventTypeName() ?: "OkiaEvent"} " +
                                        "message=${it.message} " +
                                        "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                            )
                        }
                        emit(it)
                    }
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    throw throwable
                }
                if (!streamErrorReported) {
                    Logger.e(
                        LOG_TAG,
                        "stream error stage=send code=${throwable.toUserErrorCode()} " +
                                "errorType=${throwable.eventTypeName()} " +
                                "message=${throwable.message} " +
                                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                    )
                    emit(
                        LlmStreamEvent.Error(
                            message = throwable.message?.trim()?.ifEmpty { null },
                            throwable = throwable,
                            code = throwable.toUserErrorCode(),
                        )
                    )
                }
                null
            }

            if (result is TurnResult.Failed && !streamErrorReported) {
                val error = result.error
                Logger.e(
                    LOG_TAG,
                    "stream failed by TurnResult code=${error.code} " +
                            "message=${error.message} " +
                            "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                )
                emit(
                    LlmStreamEvent.Error(
                        message = error.message.trim().ifEmpty { null },
                        throwable = error.cause,
                        code = RetryableErrorClassifier.classify(error),
                    )
                )
            }

            if (!streamTerminated && result !is TurnResult.Aborted) {
                Logger.w(
                    LOG_TAG,
                    "stream ended without terminal event, emitting guard error " +
                            "resultType=${result?.let { it::class.simpleName } ?: "null"} " +
                            "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                )
                emit(LlmStreamEvent.Error(message = null, code = null))
            }
            if (!streamErrorReported) {
                Logger.i(
                    LOG_TAG,
                    "round completed elapsedMs=${System.currentTimeMillis() - startedAtMs}"
                )
            }
        } finally {
            turnActiveFlow.value = false
            AccessibilityController.onTurnEnd()
        }
    }

    // ── prompt cache 记账（只读观测，不影响请求） ────────────────────────

    // 上一次上报过记账的请求：prompt 总量 + 时刻。per-engine（即 per-conversation），
    // 回合间不被其他会话污染。everReportedCache：该 provider 是否上报过缓存活动；
    // 从未上报过的（不支持缓存的 provider）永远不报 miss，否则每回合都是「全 miss」。
    private var lastPromptTokens: Long? = null
    private var lastRequestAtMs = 0L
    private var everReportedCache = false

    /**
     * 回合结束时上报一次缓存记账，并按 pi 口径判定本条 assistant 消息是否
     * 交了「本可命中却重算」的冤枉钱（cf. pi dist/core/cache-stats.js）。
     *
     * - promptTokens = input + cacheRead + cacheWrite（与 Usage.contextTokens 同口径）；
     * - missedTokens = min(上次 promptTokens, 本次 promptTokens) − cacheRead；
     * - 不计：首次请求、promptTokens ≤ 0、从未上报过缓存的 provider、≤1024 token 的噪声。
     *
     * fork / regenerate 后本会话是新引擎实例，上次值清空——首次请求按「无基准」
     * 处理，不误报 miss；想看前缀是否复用，要看 cacheRead 非零。
     */
    private fun logCacheAccounting(message: AssistantMessage) {
        val usage = message.usage ?: return
        val promptTokens = usage.inputTokens + usage.cacheReadTokens + usage.cacheWriteTokens
        if (promptTokens <= 0) return

        val nowMs = System.currentTimeMillis()
        val cacheActive = usage.cacheReadTokens + usage.cacheWriteTokens > 0
        val prevPrompt = lastPromptTokens
        // 三态：null = 本会话首请求（无基准）；0 = 有基准但未交冤枉钱（含负值与噪声）；
        // >0 = 本应在缓存里却被重算的 token。
        val missed = if (prevPrompt != null && (cacheActive || everReportedCache)) {
            (minOf(prevPrompt, promptTokens) - usage.cacheReadTokens)
                .coerceAtLeast(0L)
                .takeIf { it > CACHE_NOISE_FLOOR_TOKENS }
                ?: 0L
        } else {
            null
        }
        Logger.i(
            LOG_TAG,
            "cache prompt=$promptTokens nonCached=${usage.inputTokens} " +
                    "read=${usage.cacheReadTokens} write=${usage.cacheWriteTokens} " +
                    "output=${usage.outputTokens} " +
                    "missed=${missed?.toString() ?: "-"} " +
                    "idleMs=${if (prevPrompt != null) nowMs - lastRequestAtMs else 0}"
        )
        everReportedCache = everReportedCache || cacheActive
        lastPromptTokens = promptTokens
        lastRequestAtMs = nowMs
    }
}
