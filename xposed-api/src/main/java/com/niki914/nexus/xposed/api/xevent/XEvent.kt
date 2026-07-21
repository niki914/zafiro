package com.niki914.nexus.xposed.api.xevent

import android.app.Application
import android.os.Build
import com.niki914.nexus.xposed.api.util.ContextProvider
import com.niki914.nexus.xposed.api.util.xTry
import com.niki914.nexus.xposed.api.util.xtlog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext as coroutineWithContext

object XEvent {

    private class ContextElement(
        val eventContext: XEventContext?
    ) : CoroutineContext.Element {
        override val key: CoroutineContext.Key<*> = Key

        companion object Key : CoroutineContext.Key<ContextElement>
    }

    private val currentContext = AtomicReference<XEventContext?>(null)
    private val eventScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> }
    )

    fun setContext(context: XEventContext?) {
        currentContext.set(context)
    }

    fun updateContext(
        roomId: String? = null,
        turnId: Long? = null,
        fields: Map<String, Any?> = emptyMap()
    ) {
        val previous = currentContext.get()
        currentContext.set(
            XEventContext(
                roomId = roomId ?: previous?.roomId,
                turnId = turnId ?: previous?.turnId,
                fields = previous?.fields.orEmpty() + fields
            )
        )
    }

    fun snapshotContext(): XEventContext? = currentContext.get()

    fun asCoroutineContext(context: XEventContext?): CoroutineContext = ContextElement(context)

    suspend fun <T> withContext(context: XEventContext?, block: suspend () -> T): T {
        return coroutineWithContext(asCoroutineContext(context)) {
            block()
        }
    }

    fun clearContext() {
        setContext(null)
    }

    suspend fun inputCaptured(
        vararg fields: Pair<String, Any?>
    ) = emit(XEventType.INPUT_CAPTURED, mapOf(*fields))

    suspend fun inputCaptured(
        fields: Map<String, Any?>
    ) = emit(XEventType.INPUT_CAPTURED, fields)

    suspend fun turnDecided(
        vararg fields: Pair<String, Any?>
    ) = emit(XEventType.TURN_DECIDED, mapOf(*fields))

    suspend fun turnDecided(
        fields: Map<String, Any?>
    ) = emit(XEventType.TURN_DECIDED, fields)

    suspend fun llmRoundStarted(
        vararg fields: Pair<String, Any?>
    ) = emit(XEventType.LLM_ROUND_STARTED, mapOf(*fields))

    suspend fun llmRoundStarted(
        fields: Map<String, Any?>
    ) = emit(XEventType.LLM_ROUND_STARTED, fields)

    suspend fun llmTextDelta(
        vararg fields: Pair<String, Any?>
    ) = emit(XEventType.LLM_TEXT_DELTA, mapOf(*fields))

    suspend fun llmTextDelta(
        fields: Map<String, Any?>
    ) = emit(XEventType.LLM_TEXT_DELTA, fields)

    suspend fun llmRoundCompleted(
        vararg fields: Pair<String, Any?>
    ) = emit(XEventType.LLM_ROUND_COMPLETED, mapOf(*fields))

    suspend fun llmRoundCompleted(
        fields: Map<String, Any?>
    ) = emit(XEventType.LLM_ROUND_COMPLETED, fields)

    suspend fun llmError(
        vararg fields: Pair<String, Any?>
    ) = emit(XEventType.LLM_ERROR, mapOf(*fields))

    suspend fun llmError(
        fields: Map<String, Any?>
    ) = emit(XEventType.LLM_ERROR, fields)

    fun hookFailed(
        name: String,
        throwable: Throwable?
    ) {
        eventScope.launch {
            emit(
                XEventType.HOOK_FAILED,
                fields = mapOf(
                    "name" to name,
                    "errorType" to throwable?.javaClass?.name,
                    "message" to throwable?.message
                )
            )
        }
    }

    suspend fun nativeResponseBlocked(
        vararg fields: Pair<String, Any?>
    ) = emit(XEventType.NATIVE_RESPONSE_BLOCKED, mapOf(*fields))

    suspend fun nativeResponseBlocked(
        fields: Map<String, Any?>
    ) = emit(XEventType.NATIVE_RESPONSE_BLOCKED, fields)

    suspend fun renderTargetMissing(
        vararg fields: Pair<String, Any?>
    ) = emit(XEventType.RENDER_TARGET_MISSING, mapOf(*fields))

    suspend fun renderTargetMissing(
        fields: Map<String, Any?>
    ) = emit(XEventType.RENDER_TARGET_MISSING, fields)

    suspend fun renderFirstChunkInjected(
        vararg fields: Pair<String, Any?>
    ) = emit(XEventType.RENDER_FIRST_CHUNK_INJECTED, mapOf(*fields))

    suspend fun renderFirstChunkInjected(
        fields: Map<String, Any?>
    ) = emit(XEventType.RENDER_FIRST_CHUNK_INJECTED, fields)

    suspend fun renderFinalized(
        vararg fields: Pair<String, Any?>
    ) = emit(XEventType.RENDER_FINALIZED, mapOf(*fields))

    suspend fun renderFinalized(
        fields: Map<String, Any?>
    ) = emit(XEventType.RENDER_FINALIZED, fields)

    suspend fun emit(
        type: XEventType,
        fields: Map<String, Any?> = emptyMap()
    ) {
        return // TODO
        val eventContext =
            currentCoroutineContext()[ContextElement]?.eventContext ?: snapshotContext()
        val mergedFields = eventContext?.fields.orEmpty() + fields
        val context = ContextProvider.await()
        val packageName = context.packageName
        val processName = xTry("XEvent#resolveProcessName") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentProcessNameMethod =
                    activityThreadClass.getDeclaredMethod("currentProcessName")
                currentProcessNameMethod.invoke(null) as String
            }
        } ?: packageName

        val envelope = XEventEnvelope(
            ts = System.currentTimeMillis(),
            type = type,
            packageName = packageName,
            processName = processName,
            roomId = eventContext?.roomId,
            turnId = eventContext?.turnId,
            fields = mergedFields.toJsonObject()
        )

        val jsonString = Json.encodeToString(envelope)

        xtlog("XEvent", jsonString) // TODO P1 py server --> db --> script check
    }
}
