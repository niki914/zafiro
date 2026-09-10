package com.niki914.permission

import kotlinx.coroutines.CancellationException

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

    suspend fun request(permission: Permission, channels: List<Channel>): PermissionResult {
        val attempts = ArrayList<Attempt>(channels.size)
        for (channel in channels) {
            val handler = handlers[channel]
            if (handler == null) {
                attempts += Attempt(permission, channel, PermissionState.UNAVAILABLE,
                    detail = "handler not registered")
                continue
            }
            if (currentApi < handler.minSdk.api) {
                attempts += Attempt(permission, channel, PermissionState.UNAVAILABLE,
                    detail = "requires API ${handler.minSdk.api} (device: $currentApi)")
                continue
            }
            val state = try {
                handler.request(permission)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                PermissionState.FAILED
            }
            attempts += Attempt(permission, channel, state)
            if (state == PermissionState.GRANTED) {
                return PermissionResult(permission, PermissionState.GRANTED, attempts)
            }
            // UNKNOWN/DENIED/UNAVAILABLE/FAILED 均视为未成功，继续下一环
        }
        val final = attempts.lastOrNull()?.state ?: PermissionState.UNAVAILABLE
        return PermissionResult(permission, final, attempts)
    }
}
