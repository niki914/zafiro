package com.niki914.s3ss10n

import com.niki914.s3ss10n.ext.protocol.ChatProtocol
import com.niki914.s3ss10n.ext.protocol.ProtocolEvent
import com.niki914.s3ss10n.net.HttpEngine
import com.niki914.s3ss10n.net.HttpFrame
import com.niki914.s3ss10n.util.HistoryKeeper
import com.niki914.s3ss10n.util.ToolCallWaiter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class RoundInput(
    val snapshot: SessionSnapshot,
    val initialInput: String,
    val onEvent: suspend (SessionEvent) -> Unit,
    val roundToken: Any = Any()
)

internal class RoundRunner(
    private val protocol: ChatProtocol,
    private val engine: HttpEngine,
    private val historyKeeper: HistoryKeeper,
    private val toolCallCoordinator: ToolCallCoordinator,
    private val scope: CoroutineScope
) {
    private val activeMutex = Mutex()
    private var activeContext: RoundContext? = null
    private var pendingStopRequest: PendingStopRequest? = null
    private val toolCallWaiter = ToolCallWaiter<RoundContext>(scope) { toolCall, ctx ->
        toolCallCoordinator.handle(toolCall, ctx.snapshot) { event ->
            emitCoordinatorEvent(ctx, event)
        }
    }

    internal suspend fun run(input: RoundInput) = coroutineScope {
        val ctx = RoundContext(
            snapshot = input.snapshot,
            onEvent = input.onEvent,
            initialInput = input.initialInput,
            roundToken = input.roundToken
        )
        val collector = async(start = CoroutineStart.LAZY) {
            doRound(ctx, input.initialInput)
        }
        ctx.collectorJob = collector
        activeMutex.withLock {
            pendingStopRequest?.takeIf { it.roundToken === input.roundToken }?.let {
                ctx.activeStopRequest = it.request
                pendingStopRequest = null
            }
            activeContext = ctx
        }
        if (ctx.activeStopRequest != null) {
            finishRound(ctx, SessionEvent.FinishReason.Stopped)
            return@coroutineScope
        }
        collector.start()
        resetIdleWatcher(ctx)
        try {
            collector.await()
        } catch (ce: CancellationException) {
            ctx.terminalFailure?.let { throw it }
            if (ctx.activeStopRequest == null) {
                throw ce
            }
        } finally {
            if (ctx.activeStopRequest?.reason != SessionEvent.FinishReason.IdleTimeout) {
                ctx.idleWatcher?.cancel()
            }
            activeMutex.withLock {
                if (activeContext === ctx) {
                    activeContext = null
                }
            }
        }
    }

    internal suspend fun requestStop(roundToken: Any, keepCurrentTurn: Boolean) {
        val request = StopRequest(
            keepCurrentTurn = keepCurrentTurn,
            reason = SessionEvent.FinishReason.Stopped
        )
        val ctx = activeMutex.withLock {
            val active = activeContext
            if (active == null) {
                pendingStopRequest = PendingStopRequest(roundToken, request)
                return
            }
            if (active.roundToken !== roundToken) {
                return
            }
            active
        }
        ctx.activeStopRequest = request
        ctx.collectorJob?.cancel(RoundStopException(request))
        toolCallWaiter.cancelAndClear(join = true)
        if (keepCurrentTurn) {
            rollbackOpenSegmentToCheckpoint(ctx)
        } else {
            rollbackCurrentTurn(ctx)
        }
        commitPartialTurnForStopIfNeeded(ctx, keepCurrentTurn)
        finishRound(ctx, request.reason)
    }

    internal suspend fun cancelAndClearTools(join: Boolean = false) {
        toolCallWaiter.cancelAndClear(join)
    }

    private suspend fun doRound(ctx: RoundContext, pendingUserInput: String?) {
        xTrySuspend("RoundRunner.doRound", onError = { t ->
            if (t is RoundEventCallbackException) {
                throw t.original
            }
            unwrapRoundEventCallbackException {
                emitRoundError(
                    ctx,
                    SessionEvent.Error(
                        stage = classifyStage(t),
                        message = t.message ?: "Round failed",
                        cause = t
                    )
                )
                finishRound(ctx, SessionEvent.FinishReason.Error)
            }
        }) {
            openSegment(ctx)
            throwIfStopRequested(ctx)
            ensureConfigValid(ctx.snapshot)

            val fullText = StringBuilder()
            val reasoningContent = StringBuilder()
            var reasoningSignature: String? = null
            val toolCalls = mutableListOf<ToolCallSpec>()
            val req = protocol.buildRequest(
                snapshot = ctx.snapshot,
                history = historyKeeper.snapshot(),
                pendingUserInput = pendingUserInput
            )

            val authHeaders = protocol.useApiKey(ctx.snapshot.apiKey)
            val mergedAuthAndCustom = mergeHeadersWithCustomOverride(
                authHeaders,
                ctx.snapshot.headers
            )
            val effectiveReq = req.copy(headers = req.headers + mergedAuthAndCustom)

            val payloadFlow = engine.frames(effectiveReq)
                .mapNotNull { it.payloadOrNull() }
                .takeWhile { it != "[DONE]" }
            protocol.parseStream(payloadFlow).collect { event ->
                throwIfStopRequested(ctx)
                startRoundIfNeeded(ctx)
                when (event) {
                    is ProtocolEvent.TextDelta -> {
                        fullText.append(event.text)
                        ctx.textAccumulator.append(event.text)
                        ctx.openSegmentTextAccumulator.append(event.text)
                        emitRoundEvent(
                            ctx,
                            SessionEvent.TextDelta(
                                delta = event.text,
                                fullText = ctx.textAccumulator.toString()
                            )
                        )
                    }

                    is ProtocolEvent.ReasoningDelta -> {
                        reasoningContent.append(event.text)
                    }

                    is ProtocolEvent.ReasoningSignature -> {
                        reasoningSignature = event.signature
                    }

                    is ProtocolEvent.ToolCallReady -> {
                        val toolCall = ToolCallSpec(
                            callId = event.callId,
                            toolName = event.toolName,
                            argumentsJson = event.argumentsJson
                        )
                        ctx.openSegmentHasToolCalls = true
                        toolCalls += toolCall
                        toolCallWaiter.enqueue(toolCall, ctx)
                    }

                    is ProtocolEvent.Error -> {
                        emitRoundError(
                            ctx,
                            SessionEvent.Error(
                                stage = event.stage,
                                message = event.cause.message ?: "Protocol error",
                                cause = event.cause
                            )
                        )
                    }

                    ProtocolEvent.Completed -> Unit
                }
            }
            throwIfStopRequested(ctx)

            val committed = commitRoundTurnsIfValid(
                ctx = ctx,
                userInput = pendingUserInput,
                assistantContent = fullText.toString(),
                toolCalls = toolCalls.toList(),
                reasoningContent = reasoningContent.toString().ifEmpty { null },
                reasoningSignature = reasoningSignature
            )
            if (!committed) {
                finishRound(ctx, SessionEvent.FinishReason.Error)
                return@xTrySuspend
            }
            throwIfStopRequested(ctx)

            if (!toolCallWaiter.isEmpty()) {
                responseToolCalls(ctx)
            } else {
                finishRound(ctx, finalReasonFor(ctx))
            }
        }
    }

    private suspend fun startRoundIfNeeded(ctx: RoundContext) {
        if (ctx.hasStarted) return
        emitRoundEvent(ctx, SessionEvent.RoundStarted(input = ctx.initialInput))
        ctx.hasStarted = true
    }

    private suspend fun finishRound(
        ctx: RoundContext,
        reason: SessionEvent.FinishReason
    ) {
        val event = ctx.eventMutex.withLock {
            if (ctx.hasCompleted) return
            ctx.hasCompleted = true
            SessionEvent.RoundCompleted(
                fullText = ctx.textAccumulator.toString(),
                finishReason = reason
            )
        }
        try {
            ctx.onEvent(event)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            throw RoundEventCallbackException(t)
        } finally {
            ctx.idleWatcher?.cancel()
        }
    }

    private suspend fun emitIdleTimeout(ctx: RoundContext, timeoutSeconds: Long) {
        val request = StopRequest(
            keepCurrentTurn = false,
            reason = SessionEvent.FinishReason.IdleTimeout
        )
        ctx.activeStopRequest = request
        try {
            rollbackCurrentTurn(ctx)
            emitRoundError(
                ctx,
                SessionEvent.Error(
                    stage = SessionEvent.Stage.Session,
                    message = "LLM idle timeout: no session event for $timeoutSeconds seconds"
                ),
                resetIdle = false
            )
            finishRound(ctx, request.reason)
        } catch (t: Throwable) {
            ctx.terminalFailure = if (t is RoundEventCallbackException) t.original else t
        } finally {
            ctx.collectorJob?.cancel(RoundStopException(request))
            toolCallWaiter.cancelAndClear(join = true)
        }
    }

    private fun resetIdleWatcher(ctx: RoundContext) {
        val timeoutSeconds = ctx.snapshot.llmIdleTimeoutSeconds
        if (timeoutSeconds == null || timeoutSeconds <= 0 || ctx.hasCompleted) return
        ctx.idleWatcher?.cancel()
        ctx.idleWatcher = scope.launch {
            delay(timeoutSeconds * 1000)
            emitIdleTimeout(ctx, timeoutSeconds)
        }
    }

    private suspend fun commitPartialTurnForStopIfNeeded(
        ctx: RoundContext,
        keepCurrentTurn: Boolean
    ) {
        ctx.historyMutex.withLock {
            if (
                !keepCurrentTurn ||
                ctx.openSegmentTextAccumulator.isBlank() ||
                ctx.hasCompleted ||
                ctx.stopPartialCommitted
            ) return
            if (!ctx.committedInitialUser) {
                historyKeeper.add(ChatTurn.User(ctx.initialInput))
                ctx.committedInitialUser = true
                ctx.committedTurnCount += 1
            }
            historyKeeper.add(
                ChatTurn.Assistant(
                    content = ctx.openSegmentTextAccumulator.toString()
                )
            )
            ctx.committedTurnCount += 1
            ctx.stopPartialCommitted = true
        }
    }

    private suspend fun rollbackOpenSegmentToCheckpoint(ctx: RoundContext) {
        ctx.historyMutex.withLock {
            val dropCount = ctx.committedTurnCount - ctx.safeCheckpointTurnCount
            if (dropCount <= 0) return
            historyKeeper.dropLast(dropCount)
            ctx.committedTurnCount = ctx.safeCheckpointTurnCount
            ctx.committedInitialUser = ctx.safeCheckpointCommittedInitialUser
        }
    }

    private suspend fun rollbackCurrentTurn(ctx: RoundContext) {
        ctx.historyMutex.withLock {
            if (ctx.committedTurnCount <= 0) return
            historyKeeper.dropLast(ctx.committedTurnCount)
            ctx.committedTurnCount = 0
            ctx.committedInitialUser = false
        }
    }

    private suspend fun emitRoundError(
        ctx: RoundContext,
        event: SessionEvent.Error,
        resetIdle: Boolean = true
    ) {
        ctx.hasError = true
        emitRoundEvent(ctx, event, resetIdle)
    }

    private suspend fun emitCoordinatorEvent(ctx: RoundContext, event: SessionEvent) {
        if (event is SessionEvent.Error) {
            emitRoundError(ctx, event)
        } else {
            emitRoundEvent(ctx, event)
        }
    }

    private suspend fun emitRoundEvent(
        ctx: RoundContext,
        event: SessionEvent,
        resetIdle: Boolean = true
    ) {
        val shouldEmit = ctx.eventMutex.withLock { !ctx.hasCompleted }
        if (!shouldEmit) return
        try {
            ctx.onEvent(event)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            throw RoundEventCallbackException(t)
        }
        if (resetIdle && event !is SessionEvent.RoundCompleted) {
            resetIdleWatcher(ctx)
        }
    }

    private suspend fun unwrapRoundEventCallbackException(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: RoundEventCallbackException) {
            throw e.original
        }
    }

    private suspend fun commitRoundTurnsIfValid(
        ctx: RoundContext,
        userInput: String?,
        assistantContent: String,
        toolCalls: List<ToolCallSpec>,
        reasoningContent: String?,
        reasoningSignature: String?
    ): Boolean {
        val hasAssistantPayload = assistantContent.isNotBlank() || toolCalls.isNotEmpty()
        if (!hasAssistantPayload) {
            if (!ctx.hasError) {
                emitRoundError(
                    ctx,
                    SessionEvent.Error(
                        stage = SessionEvent.Stage.Parse,
                        message = "Empty assistant response"
                    )
                )
            }
            return false
        }

        ctx.historyMutex.withLock {
            if (userInput != null) {
                historyKeeper.add(ChatTurn.User(userInput))
                ctx.committedInitialUser = true
                ctx.committedTurnCount += 1
            }
            historyKeeper.add(
                ChatTurn.Assistant(
                    content = assistantContent,
                    toolCalls = toolCalls,
                    reasoningContent = reasoningContent,
                    reasoningSignature = reasoningSignature
                )
            )
            ctx.committedTurnCount += 1
            if (toolCalls.isEmpty()) {
                markSafeCheckpoint(ctx)
                closeSegment(ctx)
            }
        }
        return true
    }

    private suspend fun responseToolCalls(ctx: RoundContext) {
        throwIfStopRequested(ctx)
        val results = toolCallWaiter.awaitAll()
        throwIfStopRequested(ctx)
        ctx.historyMutex.withLock {
            throwIfStopRequested(ctx)
            results.forEach { (toolCall, msg) ->
                historyKeeper.add(
                    protocol.encodeToolResult(
                        callId = toolCall.callId,
                        toolName = toolCall.toolName,
                        resultJson = msg.contentJson
                    )
                )
                ctx.committedTurnCount += 1
            }
            markSafeCheckpoint(ctx)
            closeSegment(ctx)
        }
        throwIfStopRequested(ctx)
        doRound(ctx, null)
    }

    private fun finalReasonFor(ctx: RoundContext): SessionEvent.FinishReason {
        return if (ctx.hasError) {
            SessionEvent.FinishReason.Error
        } else {
            SessionEvent.FinishReason.Completed
        }
    }

    private fun ensureConfigValid(snapshot: SessionSnapshot) {
        if (!snapshot.endpoint.startsWith("http://") && !snapshot.endpoint.startsWith("https://")) {
            throw ConfigInvalidException()
        }
        if (snapshot.model.isBlank()) {
            throw ConfigInvalidException()
        }
    }

    private fun mergeHeadersWithCustomOverride(
        authHeaders: Map<String, String>,
        customHeaders: Map<String, String>
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val customKeysLower = customHeaders.keys.mapTo(HashSet()) { it.lowercase() }
        for ((key, value) in authHeaders) {
            if (key.lowercase() !in customKeysLower) {
                result[key] = value
            }
        }
        result.putAll(customHeaders)
        return result
    }

    private fun classifyStage(t: Throwable): SessionEvent.Stage {
        return when (t) {
            is ConfigInvalidException -> SessionEvent.Stage.Session
            is IllegalArgumentException -> SessionEvent.Stage.Session
            is IllegalStateException -> SessionEvent.Stage.Parse
            else -> SessionEvent.Stage.Transport
        }
    }

    private fun openSegment(ctx: RoundContext) {
        ctx.openSegmentTextAccumulator.clear()
        ctx.openSegmentHasToolCalls = false
    }

    private fun closeSegment(ctx: RoundContext) {
        ctx.openSegmentTextAccumulator.clear()
        ctx.openSegmentHasToolCalls = false
    }

    private fun markSafeCheckpoint(ctx: RoundContext) {
        ctx.safeCheckpointTurnCount = ctx.committedTurnCount
        ctx.safeCheckpointCommittedInitialUser = ctx.committedInitialUser
    }

    private fun throwIfStopRequested(ctx: RoundContext) {
        val request = ctx.activeStopRequest ?: return
        throw RoundStopException(request)
    }
}

private fun HttpFrame.payloadOrNull(): String? = when (this) {
    is HttpFrame.SseData -> value
    is HttpFrame.Text -> value.takeIf { it.isNotBlank() }
}

private class RoundContext(
    val snapshot: SessionSnapshot,
    val onEvent: suspend (SessionEvent) -> Unit,
    val initialInput: String,
    val roundToken: Any,
    val textAccumulator: StringBuilder = StringBuilder(),
    val openSegmentTextAccumulator: StringBuilder = StringBuilder(),
    var hasStarted: Boolean = false,
    var hasCompleted: Boolean = false,
    var hasError: Boolean = false,
    var committedInitialUser: Boolean = false,
    var committedTurnCount: Int = 0,
    var safeCheckpointTurnCount: Int = 0,
    var safeCheckpointCommittedInitialUser: Boolean = false,
    var stopPartialCommitted: Boolean = false,
    var openSegmentHasToolCalls: Boolean = false,
    var activeStopRequest: StopRequest? = null,
    var terminalFailure: Throwable? = null,
    var idleWatcher: Job? = null,
    var collectorJob: Job? = null,
    val historyMutex: Mutex = Mutex(),
    val eventMutex: Mutex = Mutex()
)

private data class StopRequest(
    val keepCurrentTurn: Boolean,
    val reason: SessionEvent.FinishReason
)

private data class PendingStopRequest(
    val roundToken: Any,
    val request: StopRequest
)

private class RoundStopException(
    val request: StopRequest
) : CancellationException("Round stopped")

private class RoundEventCallbackException(
    val original: Throwable
) : RuntimeException(original)
