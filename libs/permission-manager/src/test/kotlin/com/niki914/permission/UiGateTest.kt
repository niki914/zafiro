package com.niki914.permission

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** UiGate 语义：resume 代数、通知结果路由、unbind 取消。 */
class UiGateTest {

    @Test
    fun `awaitResumeAfter returns immediately when generation already advanced`() = runBlocking {
        val ui = UiGate()
        val gen = ui.currentGeneration()
        ui.onActivityResumed()
        ui.awaitResumeAfter(gen) // 不挂起，直接返回
    }

    @Test
    fun `awaitResumeAfter suspends until next resume`() = runBlocking {
        val ui = UiGate()
        val gen = ui.currentGeneration()
        val job = async { ui.awaitResumeAfter(gen) }
        delay(50)
        assertTrue(job.isActive)
        ui.onActivityResumed()
        job.await() // 被唤醒
    }

    @Test
    fun `notification result routes to waiter`() = runBlocking {
        val ui = UiGate()
        val job = async { ui.awaitNotificationResult() }
        delay(50)
        assertTrue(job.isActive)
        ui.onNotificationResult(true)
        assertEquals(true, job.await())
    }

    @Test
    fun `unbind cancels resume waiter`() = runBlocking {
        val ui = UiGate()
        val gen = ui.currentGeneration()
        val job = launch { ui.awaitResumeAfter(gen) }
        delay(50)
        ui.unbind()
        // CancellationException 原样上抛：join 不抛，用 isCompleted 断言被取消唤醒
        job.join()
        assertTrue(job.isCompleted)
    }
}

/** 默认链：PRD 通道与默认链对照表。 */
class DefaultChainTest {

    @Test
    fun `overlay and accessibility default to root-shizuku-jump`() {
        val expected = listOf(Channel.ROOT_SHELL, Channel.SHIZUKU, Channel.JUMP_SETTINGS)
        assertEquals(expected, PermissionManager.defaultChain(Permission.OVERLAY))
        assertEquals(expected, PermissionManager.defaultChain(Permission.ACCESSIBILITY))
    }

    @Test
    fun `notification defaults to dialog-jump`() {
        assertEquals(
            listOf(Channel.SYSTEM_DIALOG, Channel.JUMP_SETTINGS),
            PermissionManager.defaultChain(Permission.NOTIFICATION),
        )
    }

    @Test
    fun `capability permissions default to own channel`() {
        assertEquals(listOf(Channel.ROOT_SHELL), PermissionManager.defaultChain(Permission.ROOT))
        assertEquals(listOf(Channel.SHIZUKU), PermissionManager.defaultChain(Permission.SHIZUKU))
    }
}
// JumpSettings 复查语义（startActivity → resume → 复查 status）需 Activity，真机冒烟覆盖，
// 不进纯 JVM 单测。
