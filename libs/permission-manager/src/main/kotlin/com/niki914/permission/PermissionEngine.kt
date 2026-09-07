package com.niki914.permission

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * 纯 Kotlin 引擎，不依赖 Android。
 * 版本门槛判定集中在此处，是整个系统唯一的 SDK 分叉点。
 */
class PermissionEngine(
    private val currentApi: Int,
    private val handlers: Map<Channel, ChannelHandler>,
) {
    /** 静默查询：任一 handler 报 GRANTED 即 GRANTED，否则 UNAVAILABLE */
    fun status(permission: Permission): PermissionState {
        for ((_, handler) in handlers) {
            val s = handler.status(permission)
            if (s == PermissionState.GRANTED) return PermissionState.GRANTED
        }
        return PermissionState.UNAVAILABLE
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
        }
        val final = attempts.lastOrNull()?.state ?: PermissionState.UNAVAILABLE
        return PermissionResult(permission, final, attempts)
    }

    fun requestBlocking(permission: Permission, channels: List<Channel>): PermissionResult =
        runBlocking { request(permission, channels) }
}
