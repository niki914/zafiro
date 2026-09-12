package com.niki914.permission

import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * 纯 Kotlin 引擎，不依赖 Android。
 * 版本门槛判定集中在此处，是整个系统唯一的 SDK 分叉点。
 */
class PermissionEngine(
    private val currentApi: Int,
    private val handlers: Map<Channel, ChannelHandler>,
) {
    /**
     * 静默查询聚合：任一 handler 报 GRANTED → GRANTED；
     * 否则取任一真实状态（DENIED_BY_USER/UNAVAILABLE/FAILED）；全为 UNKNOWN → UNKNOWN。
     */
    fun status(permission: Permission): PermissionState {
        var fallback: PermissionState = PermissionState.UNKNOWN
        for ((_, handler) in handlers) {
            when (val s = handler.status(permission)) {
                PermissionState.GRANTED -> return PermissionState.GRANTED
                PermissionState.UNKNOWN -> Unit
                else -> if (fallback == PermissionState.UNKNOWN) fallback = s
            }
        }
        return fallback
    }

    suspend fun request(
        permission: Permission,
        channels: List<Channel>,
        observation: PermissionObservation? = null,
    ): PermissionResult {
        val requestId = UUID.randomUUID().toString()
        emit(observation, PermissionObservationEvent.RequestStarted(requestId, permission, channels.toList()))
        val attempts = ArrayList<Attempt>(channels.size)
        for (channel in channels) {
            val handler = handlers[channel]
            if (handler == null) {
                val attempt = Attempt(permission, channel, PermissionState.UNAVAILABLE,
                    detail = "handler not registered")
                attempts += attempt
                emit(observation, PermissionObservationEvent.ChannelAttempted(requestId, attempt))
                continue
            }
            if (currentApi < handler.minSdk.api) {
                val attempt = Attempt(permission, channel, PermissionState.UNAVAILABLE,
                    detail = "requires API ${handler.minSdk.api} (device: $currentApi)")
                attempts += attempt
                emit(observation, PermissionObservationEvent.ChannelAttempted(requestId, attempt))
                continue
            }
            emit(observation, PermissionObservationEvent.ChannelStarted(requestId, permission, channel))
            val state = try {
                handler.request(permission)
            } catch (e: CancellationException) {
                emit(observation, PermissionObservationEvent.RequestCancelled(requestId, permission))
                throw e
            } catch (e: Throwable) {
                emit(observation, PermissionObservationEvent.HandlerThrew(requestId, permission, channel, e))
                PermissionState.FAILED
            }
            val attempt = Attempt(permission, channel, state)
            attempts += attempt
            emit(observation, PermissionObservationEvent.ChannelAttempted(requestId, attempt))
            if (state == PermissionState.GRANTED) {
                val result = PermissionResult(permission, PermissionState.GRANTED, attempts)
                emit(observation, PermissionObservationEvent.RequestFinished(requestId, result))
                return result
            }
            // UNKNOWN/DENIED/UNAVAILABLE/FAILED 均视为未成功，继续下一环
        }
        val final = attempts.lastOrNull()?.state ?: PermissionState.UNAVAILABLE
        val result = PermissionResult(permission, final, attempts)
        emit(observation, PermissionObservationEvent.RequestFinished(requestId, result))
        return result
    }

    /**
     * 观察隔离：观察者抛任何异常（含 CancellationException）都不影响业务链与返回值；
     * 真正的协程取消只来自 handler.request 的挂起处，由上游 catch 原样传播。
     */
    private fun emit(observation: PermissionObservation?, event: PermissionObservationEvent) {
        runCatching { observation?.onEvent(event) }
    }
}
