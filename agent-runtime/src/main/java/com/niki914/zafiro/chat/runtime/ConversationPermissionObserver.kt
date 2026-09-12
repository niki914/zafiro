package com.niki914.zafiro.chat.runtime

import com.niki914.permission.Attempt
import com.niki914.permission.Channel
import com.niki914.permission.PermissionObservation
import com.niki914.permission.PermissionObservationEvent
import com.niki914.permission.PermissionState
import com.niki914.zafiro.chat.agentic.accessibility.ScreenControlConsent
import com.niki914.zafiro.chat.agentic.shell.AuthorizationContext
import com.niki914.zafiro.chat.agentic.shell.TOOL_CONFIRM_CHANNEL_BACKGROUND
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator

/**
 * 授权观察与响应转发（T-05，AC6/AC9）。
 *
 * 只登记捕获的 TurnKey/requestId 及该请求的原 resolver，Runtime 校验目标后转发；
 * 不作任何授权决定，不读全局“当前回合”，无独立 Job、无 reducer、无 UI。
 *
 * - [contextFor]：executor 每次执行创建 [AuthorizationContext] 并随协程上下文下发；
 *   上下文汇点跟踪工具确认/屏控事实的请求身份（Requested 登记、终态移除），全部事实转发给下游。
 * - 系统权限链事件经上下文自带的 [PermissionObservation] 转为 Requested/Processing/
 *   终态事实（携带创建时回合身份，attempts 防御性复制）；系统权限响应一律 [CommandOutcome.UnsupportedAction]。
 * - [respond]：仅当绑定回合一致且请求挂起时转发给原等待器（前台工具确认、屏控、
 *   App 注册的后台实际等待器）；重复/已结束/过期请求返回 [CommandOutcome.IgnoredStaleTarget]，
 *   永不完成新请求。未完成不删绑定：原 resolver false 只说明当时无等待器（可能尚未安装），
 *   终态只由生命周期事实决定。
 * - [clearTurn]：按显式 [TurnKey] 原子关闭该回合生命周期并清理绑定，迟到回调凭捕获的
 *   生命周期对象围栏（不重建条目），不碰其他回合；只保留活跃回合，无全局历史。
 */
class ConversationPermissionObserver(
    private val downstream: RuntimeFactSink,
) {
    private val lock = Any()
    /** requestId -> 绑定；终态/完成/清理只移除回合一致的条目。 */
    private val bindings = mutableMapOf<String, Binding>()
    /** 系统链尝试累积（requestId -> 快照源），终态与清理时移除；迟到回调凭生命周期围栏不重建。 */
    private val systemAttempts = mutableMapOf<String, MutableList<Attempt>>()
    /** 活跃回合生命周期（仅活跃回合驻留）；关闭只由 clearTurn 在锁内原子执行。 */
    private val lifecycles = mutableMapOf<TurnKey, TurnLifecycle>()
    /** 系统链 handler 原始异常（requestId -> channel -> 异常）；只注记观察侧 Attempt.detail 副本。 */
    private val handlerThrows = mutableMapOf<String, MutableMap<Channel, Throwable>>()

    /** 全经 lock 访问，保证注册/解除比较-清除原子。 */
    private var backgroundResponder: ((requestId: String, allowed: Boolean) -> Boolean)? = null

    /** 单回合生命周期：contextFor 创建、汇点与系统观察共同捕获，clearTurn 关闭。 */
    private class TurnLifecycle {
        @Volatile
        var closed: Boolean = false
    }

    private data class Binding(
        val turn: TurnKey,
        val kind: PermissionKind,
        val channel: String?,
    )

    /**
     * executor 装配缝：为一次执行创建授权上下文，用 `withContext` 包住执行冷流。
     * toolCallId 无证据时保持 null（unknown，不猜测）。汇点与系统观察共享同一
     * 生命周期对象；clearTurn 后迟到回调不再写任何映射，但事实照常发出由 reducer 归属。
     */
    fun contextFor(turn: TurnKey): AuthorizationContext {
        val lifecycle = synchronized(lock) { lifecycles.getOrPut(turn) { TurnLifecycle() } }
        val sink = RuntimeFactSink { fact ->
            if (fact is RuntimeFact.PermissionReported) {
                track(fact.observation, lifecycle)
            } else {
                downstream.accept(fact)
            }
        }
        return AuthorizationContext(
            turn = turn,
            sink = sink,
            permissionObservation = TurnSystemObservation(turn, lifecycle),
        )
    }

    /**
     * 统一响应转发：身份一致才完成同一个原等待器，沿原 finally 清理。
     * 系统权限无 resolver，返回 UnsupportedAction。
     */
    fun respond(turn: TurnKey, requestId: String, allowed: Boolean): CommandOutcome {
        // 绑定与后台接缝一次原子快照；未完成不删绑定（等待器可能尚未安装）。
        val snapshot = synchronized(lock) {
            val binding = bindings[requestId] ?: return@synchronized null
            binding to backgroundResponder
        } ?: return CommandOutcome.IgnoredStaleTarget
        val (binding, background) = snapshot
        if (binding.turn != turn) return CommandOutcome.IgnoredStaleTarget
        if (binding.kind == PermissionKind.SystemPermission) return CommandOutcome.UnsupportedAction
        val completed = when (binding.kind) {
            PermissionKind.ScreenConsent -> ScreenControlConsent.respond(requestId, allowed)
            else ->
                if (binding.channel == TOOL_CONFIRM_CHANNEL_BACKGROUND) {
                    background?.invoke(requestId, allowed) == true
                } else {
                    ToolPermissionCoordinator.respond(requestId, allowed)
                }
        }
        if (!completed) return CommandOutcome.IgnoredStaleTarget
        return CommandOutcome.Applied
    }

    /**
     * App 装配后台实际等待器响应接缝（overlay 真实显示的请求身份）。
     * 解除按实例比较，未注册或已换新时不误删。
     */
    fun registerBackgroundResponder(responder: (requestId: String, allowed: Boolean) -> Boolean) {
        synchronized(lock) { backgroundResponder = responder }
    }

    fun unregisterBackgroundResponder(responder: (requestId: String, allowed: Boolean) -> Boolean) {
        synchronized(lock) { if (backgroundResponder === responder) backgroundResponder = null }
    }

    /** 生命周期清理：原子关闭并移除该回合生命周期，迟到回调凭捕获对象围栏；只删本回合条目。 */
    fun clearTurn(turn: TurnKey) {
        synchronized(lock) {
            lifecycles.remove(turn)?.closed = true
            val ids = bindings.filterValues { it.turn == turn }.keys.toList()
            ids.forEach {
                bindings.remove(it)
                systemAttempts.remove(it)
                handlerThrows.remove(it)
            }
        }
    }

    private fun track(observation: PermissionRequestObservation, lifecycle: TurnLifecycle) {
        synchronized(lock) {
            // 已关闭回合的迟到事实：不写任何映射，只转发由 reducer 按身份归属。
            if (!lifecycle.closed) {
                when {
                    observation.phase == PermissionPhase.Requested ->
                        bindings[observation.requestId] =
                            Binding(observation.turn, observation.kind, observation.channel)
                    observation.phase.isTerminal -> {
                        if (bindings[observation.requestId]?.turn == observation.turn) {
                            bindings.remove(observation.requestId)
                            systemAttempts.remove(observation.requestId)
                            handlerThrows.remove(observation.requestId)
                        }
                    }
                    // Requested 时 channel 为空，Processing 补实际渠道（前台/后台决定响应路由）。
                    observation.phase == PermissionPhase.Processing -> {
                        val current = bindings[observation.requestId]
                        if (current != null && current.turn == observation.turn && current.channel == null) {
                            bindings[observation.requestId] = current.copy(channel = observation.channel)
                        }
                    }
                }
            }
        }
        downstream.accept(RuntimeFact.PermissionReported(observation))
    }

    private fun attemptsSnapshot(requestId: String): List<Attempt> = synchronized(lock) {
        systemAttempts[requestId]?.toList().orEmpty()
    }

    /**
     * 观察侧失败注记：同一请求同一 channel 发生过 handler 抛异常时，把异常摘要写入
     * Attempt.detail 副本；原 Attempt 与引擎 PermissionResult 永不改动。
     * 仅在持有 lock 时调用。
     */
    private fun annotateLocked(requestId: String, attempt: Attempt): Attempt {
        val error = handlerThrows[requestId]?.get(attempt.channel) ?: return attempt
        val note = "handlerThrew=${error.javaClass.simpleName}: ${error.message}"
        val detail = if (attempt.detail == null) note else "${attempt.detail}; $note"
        return attempt.copy(detail = detail)
    }

    /** 按回合绑定的系统权限链观察：事件转事实，身份为创建时捕获的回合；映射写操作凭生命周期围栏。 */
    private inner class TurnSystemObservation(
        val turn: TurnKey,
        val lifecycle: TurnLifecycle,
    ) : PermissionObservation {
        override fun onEvent(event: PermissionObservationEvent) {
            val observation = when (event) {
                is PermissionObservationEvent.RequestStarted -> {
                    synchronized(lock) {
                        // 已关闭回合不再重建条目；事实照常发出，由 reducer 归属旧回合。
                        if (!lifecycle.closed) {
                            bindings[event.requestId] =
                                Binding(turn, PermissionKind.SystemPermission, null)
                            systemAttempts[event.requestId] = mutableListOf()
                        }
                    }
                    PermissionRequestObservation(
                        requestId = event.requestId,
                        turn = turn,
                        toolCallId = null,
                        kind = PermissionKind.SystemPermission,
                        target = event.permission.name,
                        channel = null,
                        phase = PermissionPhase.Requested,
                        reason = null,
                    )
                }
                is PermissionObservationEvent.ChannelStarted -> PermissionRequestObservation(
                    requestId = event.requestId,
                    turn = turn,
                    toolCallId = null,
                    kind = PermissionKind.SystemPermission,
                    target = event.permission.name,
                    channel = event.channel.name,
                    phase = PermissionPhase.Processing,
                    attempts = attemptsSnapshot(event.requestId),
                    reason = null,
                )
                // Handler 异常不改变引擎结果：记失败诊断（终态/清理时移除），并发出带
                // 当前渠道与结构化原因的 Processing 事实，诊断随请求保留至终态。
                is PermissionObservationEvent.HandlerThrew -> {
                    val attempts = synchronized(lock) {
                        if (lifecycle.closed) return
                        if (bindings[event.requestId]?.turn == turn) {
                            handlerThrows.getOrPut(event.requestId) { mutableMapOf() }[event.channel] =
                                event.error
                        }
                        systemAttempts[event.requestId]?.toList().orEmpty()
                    }
                    downstream.accept(
                        RuntimeFact.PermissionReported(
                            PermissionRequestObservation(
                                requestId = event.requestId,
                                turn = turn,
                                toolCallId = null,
                                kind = PermissionKind.SystemPermission,
                                target = event.permission.name,
                                channel = event.channel.name,
                                phase = PermissionPhase.Processing,
                                attempts = attempts,
                                reason = Reason(
                                    "HANDLER_THREW", "PermissionEngine", event.error.message,
                                ),
                            ),
                        ),
                    )
                    return
                }
                is PermissionObservationEvent.ChannelAttempted -> {
                    // 无累积条目（已终态/已清理/迟到）不重建，只带本次 attempt 注记副本，不泄漏。
                    val attempts = synchronized(lock) {
                        val annotated = annotateLocked(event.requestId, event.attempt)
                        val accumulated = systemAttempts[event.requestId]
                        if (accumulated != null && bindings[event.requestId]?.turn == turn &&
                            !lifecycle.closed
                        ) {
                            accumulated += annotated
                            accumulated.toList()
                        } else {
                            listOf(annotated)
                        }
                    }
                    PermissionRequestObservation(
                        requestId = event.requestId,
                        turn = turn,
                        toolCallId = null,
                        kind = PermissionKind.SystemPermission,
                        target = event.attempt.permission.name,
                        channel = event.attempt.channel.name,
                        phase = PermissionPhase.Processing,
                        attempts = attempts,
                        reason = null,
                    )
                }
                is PermissionObservationEvent.RequestFinished -> {
                    val state = event.result.finalState
                    // 终态 attempts 全部走观察侧注记：早前失败渠道的诊断保留在副本 detail 中，
                    // 即使后续渠道成功；原 PermissionResult 不改动。
                    val attempts = synchronized(lock) {
                        val annotated =
                            event.result.attempts.map { annotateLocked(event.requestId, it) }
                        if (bindings[event.requestId]?.turn == turn) {
                            bindings.remove(event.requestId)
                            systemAttempts.remove(event.requestId)
                            handlerThrows.remove(event.requestId)
                        }
                        annotated
                    }
                    PermissionRequestObservation(
                        requestId = event.requestId,
                        turn = turn,
                        toolCallId = null,
                        kind = PermissionKind.SystemPermission,
                        target = event.result.permission.name,
                        channel = event.result.attempts.lastOrNull()?.channel?.name,
                        phase = state.toTerminalPhase(),
                        attempts = attempts,
                        reason = if (state == PermissionState.GRANTED) {
                            null
                        } else {
                            Reason(state.name, "PermissionEngine", null)
                        },
                    )
                }
                is PermissionObservationEvent.RequestCancelled -> {
                    val observation = PermissionRequestObservation(
                        requestId = event.requestId,
                        turn = turn,
                        toolCallId = null,
                        kind = PermissionKind.SystemPermission,
                        target = event.permission.name,
                        channel = null,
                        phase = PermissionPhase.Cancelled,
                        attempts = attemptsSnapshot(event.requestId),
                        reason = Reason("CANCELLED", "PermissionEngine", null),
                    )
                    synchronized(lock) {
                        if (bindings[event.requestId]?.turn == turn) {
                            bindings.remove(event.requestId)
                            systemAttempts.remove(event.requestId)
                            handlerThrows.remove(event.requestId)
                        }
                    }
                    observation
                }
            }
            downstream.accept(RuntimeFact.PermissionReported(observation))
        }
    }
}

private val PermissionPhase.isTerminal: Boolean
    get() = when (this) {
        PermissionPhase.Allowed,
        PermissionPhase.Denied,
        PermissionPhase.Unavailable,
        PermissionPhase.Failed,
        PermissionPhase.Cancelled,
        -> true
        PermissionPhase.Requested,
        PermissionPhase.Processing,
        -> false
    }

private fun PermissionState.toTerminalPhase(): PermissionPhase = when (this) {
    PermissionState.GRANTED -> PermissionPhase.Allowed
    PermissionState.DENIED_BY_USER -> PermissionPhase.Denied
    PermissionState.UNAVAILABLE -> PermissionPhase.Unavailable
    PermissionState.FAILED -> PermissionPhase.Failed
    // 静默不可知：非成功非失败，按不可用归终态，原因保留 UNKNOWN。
    PermissionState.UNKNOWN -> PermissionPhase.Unavailable
}
