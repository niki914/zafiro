package com.niki914.zafiro.business.permission

import kotlin.test.Test
import kotlin.test.assertEquals

/** 默认链：PRD 通道与默认链对照表。纯 JVM，不碰 Android 框架。 */
class DefaultChainTest {

    @Test
    fun `overlay and accessibility default to root-shizuku-jump`() {
        val expected = listOf(Channel.ROOT_SHELL, Channel.SHIZUKU, Channel.JUMP_SETTINGS)
        assertEquals(expected, PermissionManagerImpl.defaultChain(Permission.OVERLAY))
        assertEquals(expected, PermissionManagerImpl.defaultChain(Permission.ACCESSIBILITY))
    }

    @Test
    fun `notification tries shell channels before dialog-jump`() {
        // 有 root / Shizuku 时先静默授权（handler 内部走 pm grant / appops），
        // 不可用时降级到系统弹窗与跳设置页。
        assertEquals(
            listOf(Channel.ROOT_SHELL, Channel.SHIZUKU, Channel.SYSTEM_DIALOG, Channel.JUMP_SETTINGS),
            PermissionManagerImpl.defaultChain(Permission.NOTIFICATION),
        )
    }

    @Test
    fun `capability permissions default to own channel`() {
        assertEquals(listOf(Channel.ROOT_SHELL), PermissionManagerImpl.defaultChain(Permission.ROOT))
        assertEquals(listOf(Channel.SHIZUKU), PermissionManagerImpl.defaultChain(Permission.SHIZUKU))
    }
}
// JumpSettings 复查语义（startActivity → resume → 复查 status）需 Activity，真机冒烟覆盖，
// 不进纯 JVM 单测。
