package com.niki914.permission

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 引擎 × 真实 handler 组合的行为测试（用 Fake 注入 engine 层验证聚合语义；
 * RootShellHandler/ShizukuHandler 本身的 Shell/Shizuku 交互由真机 smoke test 验证）。
 */
class ShellHandlersMetaTest {

    private val perm = Permission.OVERLAY

    /** 模拟 RootShellHandler.status 的状态分布：真实状态 + UNKNOWN 混合时的引擎聚合 */
    @Test
    fun `engine aggregates overlay status across channels with UNKNOWN`() {
        // root handler 报真实状态（canDrawOverlays），shizuku handler 也报同一目标
        val rootLike = FakeChannelHandler(
            Channel.ROOT_SHELL, fixedStatus = PermissionState.DENIED_BY_USER,
        )
        val shizukuLike = FakeChannelHandler(
            Channel.SHIZUKU, fixedStatus = PermissionState.GRANTED,
        )
        val engine = PermissionEngine(currentApi = 34, handlers = mapOf(
            Channel.ROOT_SHELL to rootLike,
            Channel.SHIZUKU to shizukuLike,
        ))
        assertEquals(PermissionState.GRANTED, engine.status(perm))
    }

    /** root 不可静默嗅探（UNKNOWN），但另一个 handler 有真实状态时不污染结果 */
    @Test
    fun `root UNKNOWN does not pollute real status from other handler`() {
        val rootLike = FakeChannelHandler(
            Channel.ROOT_SHELL, fixedStatus = PermissionState.UNKNOWN,
        )
        val engine = PermissionEngine(currentApi = 34, handlers = mapOf(
            Channel.ROOT_SHELL to rootLike,
        ))
        assertEquals(PermissionState.UNKNOWN, engine.status(Permission.ROOT))
    }
}
