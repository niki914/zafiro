package com.niki914.permission

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PermissionEngineTest {

    private val perm = Permission.OVERLAY

    private fun engine(vararg handlers: ChannelHandler): PermissionEngine =
        PermissionEngine(
            currentApi = 34,
            handlers = handlers.associateBy { it.channel },
        )

    private fun request(e: PermissionEngine, vararg channels: Channel): PermissionResult =
        runBlocking { e.request(perm, channels.toList()) }

    @Test
    fun `success short-circuits chain`() {
        val e = engine(
            FakeChannelHandler(Channel.ROOT_SHELL, requestStatus = PermissionState.DENIED_BY_USER),
            FakeChannelHandler(Channel.SHIZUKU, requestStatus = PermissionState.GRANTED),
            FakeChannelHandler(Channel.JUMP_SETTINGS, requestStatus = PermissionState.GRANTED),
        )
        val r = request(e, Channel.ROOT_SHELL, Channel.SHIZUKU, Channel.JUMP_SETTINGS)
        assertEquals(PermissionState.GRANTED, r.finalState)
        assertEquals(2, r.attempts.size)
        assertEquals(PermissionState.DENIED_BY_USER, r.attempts[0].state)
        assertEquals(PermissionState.GRANTED, r.attempts[1].state)
    }

    @Test
    fun `UNAVAILABLE degrades to next channel`() {
        val e = engine(
            FakeChannelHandler(Channel.ROOT_SHELL, requestStatus = PermissionState.UNAVAILABLE),
            FakeChannelHandler(Channel.JUMP_SETTINGS, requestStatus = PermissionState.GRANTED),
        )
        val r = request(e, Channel.ROOT_SHELL, Channel.JUMP_SETTINGS)
        assertEquals(PermissionState.GRANTED, r.finalState)
        assertEquals(2, r.attempts.size)
    }

    @Test
    fun `DENIED_BY_USER continues to next channel`() {
        val e = engine(
            FakeChannelHandler(Channel.ROOT_SHELL, requestStatus = PermissionState.DENIED_BY_USER),
            FakeChannelHandler(Channel.SHIZUKU, requestStatus = PermissionState.DENIED_BY_USER),
            FakeChannelHandler(Channel.JUMP_SETTINGS, requestStatus = PermissionState.GRANTED),
        )
        val r = request(e, Channel.ROOT_SHELL, Channel.SHIZUKU, Channel.JUMP_SETTINGS)
        assertEquals(PermissionState.GRANTED, r.finalState)
        assertEquals(3, r.attempts.size)
        assertTrue(r.attempts.take(2).all { it.state == PermissionState.DENIED_BY_USER })
    }

    @Test
    fun `chain exhaust returns final state of last attempt`() {
        val e = engine(
            FakeChannelHandler(Channel.ROOT_SHELL, requestStatus = PermissionState.DENIED_BY_USER),
            FakeChannelHandler(Channel.SHIZUKU, requestStatus = PermissionState.UNAVAILABLE),
        )
        val r = request(e, Channel.ROOT_SHELL, Channel.SHIZUKU)
        assertEquals(PermissionState.UNAVAILABLE, r.finalState)
        assertEquals(2, r.attempts.size)
    }

    @Test
    fun `minSdk gate skips unsupported channel with UNAVAILABLE`() {
        val e = engine(
            FakeChannelHandler(
                Channel.SYSTEM_DIALOG, minSdk = MinSdk(33),
                requestStatus = PermissionState.GRANTED,
            ),
            FakeChannelHandler(Channel.JUMP_SETTINGS, requestStatus = PermissionState.GRANTED),
        )
        // currentApi=34 >= 33, so SYSTEM_DIALOG should be attempted and succeed
        val r = request(e, Channel.SYSTEM_DIALOG, Channel.JUMP_SETTINGS)
        assertEquals(PermissionState.GRANTED, r.finalState)
        assertEquals(1, r.attempts.size)
        assertEquals(Channel.SYSTEM_DIALOG, r.attempts[0].channel)
    }

    @Test
    fun `minSdk gate blocks channel when device below requirement`() {
        val e = PermissionEngine(
            currentApi = 30,
            handlers = mapOf(
                Channel.SYSTEM_DIALOG to FakeChannelHandler(
                    Channel.SYSTEM_DIALOG, minSdk = MinSdk(33),
                    requestStatus = PermissionState.GRANTED,
                ),
                Channel.JUMP_SETTINGS to FakeChannelHandler(
                    Channel.JUMP_SETTINGS, requestStatus = PermissionState.GRANTED,
                ),
            ),
        )
        val r = runBlocking { e.request(perm, listOf(Channel.SYSTEM_DIALOG, Channel.JUMP_SETTINGS)) }
        assertEquals(PermissionState.GRANTED, r.finalState)
        assertEquals(PermissionState.UNAVAILABLE, r.attempts[0].state)
        assertTrue(r.attempts[0].detail?.contains("API 33") == true)
        assertEquals(2, r.attempts.size)
    }

    @Test
    fun `no registered handler yields UNAVAILABLE`() {
        val e = engine(/* empty */)
        val r = request(e, Channel.ROOT_SHELL)
        assertEquals(PermissionState.UNAVAILABLE, r.finalState)
        assertEquals("handler not registered", r.attempts[0].detail)
    }

    @Test
    fun `status returns GRANTED if any handler grants`() {
        val e = engine(
            FakeChannelHandler(Channel.ROOT_SHELL, fixedStatus = PermissionState.UNAVAILABLE),
            FakeChannelHandler(Channel.SHIZUKU, fixedStatus = PermissionState.GRANTED),
        )
        assertEquals(PermissionState.GRANTED, e.status(perm))
    }

    @Test
    fun `status returns UNAVAILABLE if no handler grants`() {
        val e = engine(
            FakeChannelHandler(Channel.ROOT_SHELL, fixedStatus = PermissionState.UNAVAILABLE),
        )
        assertEquals(PermissionState.UNAVAILABLE, e.status(perm))
    }

    @Test
    fun `status prefers real state over UNKNOWN`() {
        val e = engine(
            FakeChannelHandler(Channel.ROOT_SHELL, fixedStatus = PermissionState.UNKNOWN),
            FakeChannelHandler(Channel.SHIZUKU, fixedStatus = PermissionState.DENIED_BY_USER),
        )
        assertEquals(PermissionState.DENIED_BY_USER, e.status(perm))
    }

    @Test
    fun `status all UNKNOWN returns UNKNOWN`() {
        val e = engine(
            FakeChannelHandler(Channel.ROOT_SHELL, fixedStatus = PermissionState.UNKNOWN),
            FakeChannelHandler(Channel.SHIZUKU, fixedStatus = PermissionState.UNKNOWN),
        )
        assertEquals(PermissionState.UNKNOWN, e.status(perm))
    }

    @Test
    fun `UNKNOWN request continues to next channel`() {
        val e = engine(
            FakeChannelHandler(Channel.ROOT_SHELL, requestStatus = PermissionState.UNKNOWN),
            FakeChannelHandler(Channel.JUMP_SETTINGS, requestStatus = PermissionState.GRANTED),
        )
        val r = request(e, Channel.ROOT_SHELL, Channel.JUMP_SETTINGS)
        assertEquals(PermissionState.GRANTED, r.finalState)
        assertEquals(2, r.attempts.size)
        assertEquals(PermissionState.UNKNOWN, r.attempts[0].state)
    }

    @Test
    fun `chain exhaust with all UNKNOWN returns UNKNOWN`() {
        val e = engine(
            FakeChannelHandler(Channel.ROOT_SHELL, requestStatus = PermissionState.UNKNOWN),
            FakeChannelHandler(Channel.SHIZUKU, requestStatus = PermissionState.UNKNOWN),
        )
        val r = request(e, Channel.ROOT_SHELL, Channel.SHIZUKU)
        assertEquals(PermissionState.UNKNOWN, r.finalState)
    }

    @Test
    fun `request failure catches exception and records FAILED`() {
        val failing = object : ChannelHandler {
            override val channel = Channel.ROOT_SHELL
            override val minSdk = MinSdk(26)
            override fun status(permission: Permission) = PermissionState.UNAVAILABLE
            override suspend fun request(permission: Permission): PermissionState =
                throw RuntimeException("boom")
        }
        val e = engine(failing)
        val r = request(e, Channel.ROOT_SHELL)
        assertEquals(PermissionState.FAILED, r.finalState)
    }

    @Test
    fun `cancellation propagates instead of becoming FAILED`() {
        val cancelling = object : ChannelHandler {
            override val channel = Channel.ROOT_SHELL
            override val minSdk = MinSdk(26)
            override fun status(permission: Permission) = PermissionState.UNAVAILABLE
            override suspend fun request(permission: Permission): PermissionState {
                currentCoroutineContext().ensureActive()
                throw CancellationException("activity destroyed")
            }
        }
        val e = engine(cancelling)
        assertFailsWith<CancellationException> {
            request(e, Channel.ROOT_SHELL)
        }
    }

    @Test
    fun `respects exact channel order from caller`() {
        val e = engine(
            FakeChannelHandler(Channel.JUMP_SETTINGS, requestStatus = PermissionState.GRANTED),
            FakeChannelHandler(Channel.ROOT_SHELL, requestStatus = PermissionState.GRANTED),
        )
        val r = request(e, Channel.JUMP_SETTINGS, Channel.ROOT_SHELL)
        assertEquals(Channel.JUMP_SETTINGS, r.attempts[0].channel)
        assertEquals(1, r.attempts.size)
    }
}
