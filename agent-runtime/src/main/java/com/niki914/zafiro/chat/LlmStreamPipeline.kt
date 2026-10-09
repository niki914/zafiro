package com.niki914.zafiro.chat

import com.niki914.logging.Logger
import com.niki914.okia.Okia
import com.niki914.okia.TurnOptions
import com.niki914.okia.loop.TurnResult
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
}
