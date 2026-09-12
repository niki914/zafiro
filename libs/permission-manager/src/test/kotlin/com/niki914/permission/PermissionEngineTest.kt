package com.niki914.permission

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
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

    // ── T-28 观察接缝（Group11 AC6） ───────────────────────────────────────

    @Test
    fun `default chain is observed in exact execution order`() = runBlocking {
        val e = engine(
            FakeChannelHandler(Channel.ROOT_SHELL, requestStatus = PermissionState.DENIED_BY_USER),
            FakeChannelHandler(Channel.SHIZUKU, requestStatus = PermissionState.DENIED_BY_USER),
            FakeChannelHandler(Channel.JUMP_SETTINGS, requestStatus = PermissionState.GRANTED),
        )
        val observation = RecordingObservation()

        val result = e.request(perm, PermissionManager.defaultChain(perm), observation)

        assertEquals(PermissionState.GRANTED, result.finalState)
        assertEquals(
            PermissionManager.defaultChain(perm),
            observation.events.filterIsInstance<PermissionObservationEvent.RequestStarted>()
                .single().channels,
        )
        // 每环在 handler 挂起前先 ChannelStarted，attempt 后到；顺序即链顺序。
        assertEquals(
            listOf(Channel.ROOT_SHELL, Channel.SHIZUKU, Channel.JUMP_SETTINGS),
            observation.startedChannels(),
        )
        assertEquals(
            listOf(Channel.ROOT_SHELL, Channel.SHIZUKU, Channel.JUMP_SETTINGS),
            observation.attemptedChannels(),
        )
        assertEquals(
            listOf(
                PermissionState.DENIED_BY_USER,
                PermissionState.DENIED_BY_USER,
                PermissionState.GRANTED,
            ),
            observation.attemptedStates(),
        )
        // 首个事件是 RequestStarted，末个事件是 RequestFinished，且终态携带原 result 实例。
        assertTrue(observation.events.first() is PermissionObservationEvent.RequestStarted)
        val finished = observation.events.last()
        assertTrue(finished is PermissionObservationEvent.RequestFinished)
        assertSame(result, (finished as PermissionObservationEvent.RequestFinished).result)
    }

    @Test
    fun `custom chain is observed in caller order with version gate attempt`() = runBlocking {
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
        val observation = RecordingObservation()

        val result = e.request(
            perm,
            listOf(Channel.SYSTEM_DIALOG, Channel.JUMP_SETTINGS),
            observation,
        )

        assertEquals(PermissionState.GRANTED, result.finalState)
        assertEquals(
            listOf(Channel.SYSTEM_DIALOG, Channel.JUMP_SETTINGS),
            observation.attemptedChannels(),
        )
        // 版本门槛未达：只 attempt，不进入等待渠道。
        assertEquals(listOf(Channel.JUMP_SETTINGS), observation.startedChannels())
        assertEquals(
            listOf(PermissionState.UNAVAILABLE, PermissionState.GRANTED),
            observation.attemptedStates(),
        )
    }

    @Test
    fun `unregistered handler is observed as attempt without channel start`() = runBlocking {
        val e = engine(FakeChannelHandler(Channel.JUMP_SETTINGS, requestStatus = PermissionState.GRANTED))
        val observation = RecordingObservation()

        e.request(perm, listOf(Channel.ROOT_SHELL, Channel.JUMP_SETTINGS), observation)

        assertEquals(listOf(Channel.ROOT_SHELL, Channel.JUMP_SETTINGS), observation.attemptedChannels())
        assertEquals(listOf(Channel.JUMP_SETTINGS), observation.startedChannels())
        assertEquals(
            "handler not registered",
            observation.events.filterIsInstance<PermissionObservationEvent.ChannelAttempted>()
                .first().attempt.detail,
        )
    }

    @Test
    fun `channel start is observed before the handler suspends`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val blocking = object : ChannelHandler {
            override val channel = Channel.ROOT_SHELL
            override val minSdk = MinSdk(26)
            override fun status(permission: Permission) = PermissionState.UNAVAILABLE
            override suspend fun request(permission: Permission): PermissionState {
                entered.complete(Unit)
                gate.await()
                return PermissionState.GRANTED
            }
        }
        val e = engine(blocking)
        val observation = RecordingObservation()

        val job = launch { e.request(perm, listOf(Channel.ROOT_SHELL), observation) }
        // runBlocking 主协程挂起时子协程运行到 handler 内挂起点。
        entered.await()

        assertEquals(listOf(Channel.ROOT_SHELL), observation.startedChannels())
        assertTrue(observation.events.none { it is PermissionObservationEvent.ChannelAttempted })

        gate.complete(Unit)
        job.join()
        assertEquals(listOf(Channel.ROOT_SHELL), observation.attemptedChannels())
        assertEquals(
            PermissionState.GRANTED,
            observation.events.filterIsInstance<PermissionObservationEvent.RequestFinished>()
                .single().result.finalState,
        )
    }

    @Test
    fun `handler failure is observed and result equals the unobserved baseline`() {
        val e = engine(ThrowingChannelHandler(IllegalStateException("boom")))
        val baseline = runBlocking { e.request(perm, listOf(Channel.ROOT_SHELL)) }
        val observation = RecordingObservation()

        val observed = runBlocking { e.request(perm, listOf(Channel.ROOT_SHELL), observation) }

        assertEquals(PermissionState.FAILED, observed.finalState)
        assertEquals(baseline, observed)
        assertEquals(1, observation.handlerThrewCount())
        assertEquals(listOf(Channel.ROOT_SHELL), observation.startedChannels())
        assertEquals(listOf(PermissionState.FAILED), observation.attemptedStates())
        assertEquals(
            PermissionState.FAILED,
            observation.events.filterIsInstance<PermissionObservationEvent.RequestFinished>()
                .single().result.finalState,
        )
    }

    @Test
    fun `throwing observation does not change chain order or result`() {
        val e = engine(
            FakeChannelHandler(Channel.ROOT_SHELL, requestStatus = PermissionState.DENIED_BY_USER),
            FakeChannelHandler(Channel.SHIZUKU, requestStatus = PermissionState.GRANTED),
        )
        val chain = listOf(Channel.ROOT_SHELL, Channel.SHIZUKU)
        val baseline = runBlocking { e.request(perm, chain) }

        val observed = runBlocking { e.request(perm, chain, ThrowingObservation()) }

        assertEquals(baseline, observed)
        assertEquals(PermissionState.GRANTED, observed.finalState)
        assertEquals(2, observed.attempts.size)
    }

    @Test
    fun `observation thrown cancellation does not cancel the request`() {
        val e = engine(FakeChannelHandler(Channel.ROOT_SHELL, requestStatus = PermissionState.GRANTED))

        val observed = runBlocking {
            e.request(perm, listOf(Channel.ROOT_SHELL), CancellationThrowingObservation())
        }

        assertEquals(PermissionState.GRANTED, observed.finalState)
    }

    @Test
    fun `handler cancellation propagates and is observed even with a hostile observation`() {
        val e = engine(CancellingChannelHandler())
        val observation = RecordingObservation()

        assertFailsWith<CancellationException> {
            runBlocking { e.request(perm, listOf(Channel.ROOT_SHELL), observation) }
        }
        assertTrue(observation.events.any { it is PermissionObservationEvent.RequestCancelled })
        assertTrue(observation.events.none { it is PermissionObservationEvent.RequestFinished })

        assertFailsWith<CancellationException> {
            runBlocking {
                e.request(perm, listOf(Channel.ROOT_SHELL), ThrowingObservation())
            }
        }
    }

    @Test
    fun `observation throws on every event and chain still returns last attempt state`() {
        val e = engine(
            FakeChannelHandler(Channel.ROOT_SHELL, requestStatus = PermissionState.DENIED_BY_USER),
            FakeChannelHandler(Channel.SHIZUKU, requestStatus = PermissionState.UNAVAILABLE),
        )
        val observation = object : PermissionObservation {
            override fun onEvent(event: PermissionObservationEvent) {
                throw CancellationException("observer hostile")
            }
        }

        val observed = runBlocking {
            e.request(perm, listOf(Channel.ROOT_SHELL, Channel.SHIZUKU), observation)
        }

        assertEquals(PermissionState.UNAVAILABLE, observed.finalState)
        assertEquals(2, observed.attempts.size)
    }
}

/** 记录型观察者：断言真实引擎的事件顺序与内容。 */
private class RecordingObservation : PermissionObservation {
    val events = mutableListOf<PermissionObservationEvent>()

    override fun onEvent(event: PermissionObservationEvent) {
        events += event
    }

    fun startedChannels(): List<Channel> = events.filterIsInstance<PermissionObservationEvent.ChannelStarted>()
        .map { it.channel }

    fun attemptedChannels(): List<Channel> = events.filterIsInstance<PermissionObservationEvent.ChannelAttempted>()
        .map { it.attempt.channel }

    fun attemptedStates(): List<PermissionState> =
        events.filterIsInstance<PermissionObservationEvent.ChannelAttempted>().map { it.attempt.state }

    fun handlerThrewCount(): Int =
        events.count { it is PermissionObservationEvent.HandlerThrew }
}

/** 每个事件都抛非取消异常：不得改变业务链或返回值。 */
private class ThrowingObservation : PermissionObservation {
    override fun onEvent(event: PermissionObservationEvent) {
        throw IllegalStateException("observer failed")
    }
}

/** 每个事件都抛 CancellationException：不得被误认为真实协程取消。 */
private class CancellationThrowingObservation : PermissionObservation {
    override fun onEvent(event: PermissionObservationEvent) {
        throw CancellationException("observer cancelled")
    }
}

/** handler 抛非取消异常：引擎映射 FAILED 继续降级。 */
private class ThrowingChannelHandler(
    private val error: Throwable,
) : ChannelHandler {
    override val channel = Channel.ROOT_SHELL
    override val minSdk = MinSdk(26)

    override fun status(permission: Permission) = PermissionState.UNAVAILABLE

    override suspend fun request(permission: Permission): PermissionState = throw error
}

/** handler 抛 CancellationException：原取消必须继续向上传播。 */
private class CancellingChannelHandler : ChannelHandler {
    override val channel = Channel.ROOT_SHELL
    override val minSdk = MinSdk(26)

    override fun status(permission: Permission) = PermissionState.UNAVAILABLE

    override suspend fun request(permission: Permission): PermissionState {
        currentCoroutineContext().ensureActive()
        throw CancellationException("activity destroyed")
    }
}

