package com.niki914.permission

/**
 * 测试用：可编程的通道 handler。
 * - [fixedStatus]：status() 固定返回
 * - [requestStatus]：request() 固定返回
 */
class FakeChannelHandler(
    override val channel: Channel,
    override val minSdk: MinSdk = MinSdk(26),
    private val fixedStatus: PermissionState = PermissionState.UNAVAILABLE,
    private val requestStatus: PermissionState = PermissionState.UNAVAILABLE,
) : ChannelHandler {
    override fun status(permission: Permission): PermissionState = fixedStatus
    override suspend fun request(permission: Permission): PermissionState = requestStatus
}
